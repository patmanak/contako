# Synchronization state contracts

These are design contracts, not a claim that every failure scenario is qualified.
See [Synchronization](SYNCHRONIZATION.md) and [Known limitations](KNOWN_LIMITATIONS.md).

## Runner state machine

| State | Durable input/output | Allowed next states |
| --- | --- | --- |
| `Idle` | No active lease; pending triggers/mutations may exist | `Acquire` |
| `Acquire` | Atomically obtain per-account lease; coalesce trigger reasons | `Prerequisites`, `Idle` if another runner owns the lease |
| `Prerequisites` | Validate account/session/key/network and cancellation generation | `IngestAndroid`, `RetryWaiting`, `ActionRequired`, `Cancelled` |
| `IngestAndroid` | Convert owned dirty/deleted rows to canonical+outbox transactions | `FetchRemoteIndex`, `RetryWaiting`, `ActionRequired`, `Cancelled` |
| `FetchRemoteIndex` | Persist observed index/event page/checkpoint only after valid parsing | `Classify`, `RetryWaiting`, `ActionRequired`, `Cancelled` |
| `Classify` | Compare acknowledged baseline, remote versions/events, tombstones, and pending revisions | `Hydrate`, `Upload`, `Reconcile`, `ActionRequired`, `Cancelled` |
| `Hydrate` | Fetch only bodies required by classification, in bounded batches | `Classify`, `RetryWaiting`, `ActionRequired`, `Cancelled` |
| `Upload` | Send only eligible creates or remote-version-checked winning mutations | `Reconcile`, `RetryWaiting`, `ActionRequired`, `Cancelled` |
| `Reconcile` | Atomically apply acknowledgements/remote winners, then advance the safe inventory checkpoint by generation CAS | `ProjectAndroid`, `RetryWaiting`, `ActionRequired`, `Cancelled` |
| `ProjectAndroid` | Incrementally repair changed Contako-owned rows/groups | `Publish`, `RetryWaiting`, `ActionRequired`, `Cancelled` |
| `Publish` | Persist sanitized pass result, last-success time, pending/block counts | `Release` |
| `RetryWaiting` | Preserve state, category, attempt, next eligibility, and safe checkpoint | `Release`; later trigger returns to `Acquire` |
| `ActionRequired` | Preserve affected intent/evidence and expose exact user recovery | `Release`; explicit recovery returns to `Acquire` |
| `Cancelled` | Stop at safe boundary without discarding committed intent | `Release` |
| `Release` | Release lease; atomically consume triggers covered by the pass | `Acquire` for one necessary follow-up, otherwise `Idle` |

Cancellation MUST be checked before network/batch boundaries and after durable
commit boundaries. It MUST NOT interrupt an atomic database transaction or
leave an Android batch assumed successful without observing its outcome.

For the `D-032` complete-inventory boundary, `Reconcile` MUST make canonical
results durable before it presents the exact completion proof to the checkpoint
store. The account-scoped Room generation compare-and-set and complete normalized
checkpoint replacement form one transaction. A pre-commit restart repeats the
plan; a post-commit lost acknowledgement replays with a stale generation and
MUST NOT advance or rewrite the checkpoint.

## Trigger coalescing

Trigger reasons form a set, not a queue of independent passes. While a pass is
active:

- an equivalent or weaker request is covered by the active pass;
- a new durable mutation sets follow-up-needed unless it is observed before the
  active classification snapshot closes;
- a full-repair request upgrades the follow-up scope but never runs concurrently;
- sign-out increments an account cancellation generation and prevents a new
  pass from acquiring the obsolete account lease;
- repeated framework retries do not multiply notification or retry counters.

## Mutation state machine

| State | Meaning | Transition rule |
| --- | --- | --- |
| `Draft` | Locally visible but not valid for upload | Correction -> `Pending`; discard only by explicit user deletion/sign-out discard |
| `Pending` | Canonical revision and command are atomically durable | Eligibility/classification -> `InFlight`, `Superseded`, or `ActionRequired` |
| `InFlight` | One serialized request attempt is active | Valid ack -> `Acknowledged`; transient/no ack -> `Pending`; blocking result -> `ActionRequired` |
| `Acknowledged` | Remote outcome observed, awaiting canonical/baseline commit | Atomic reconcile -> `Clean`; process death -> reconciliation repeats safely |
| `Clean` | No pending local intent for the acknowledged baseline | New edit -> new `Pending` revision |
| `Superseded` | A later durable local revision contains or dominates this intent | Retained only as audit/reconciliation metadata, then compacted safely |
| `ActionRequired` | Automatic progress is unsafe or impossible | User correction/re-auth/explicit conflict choice -> new `Pending` or resolved `Clean` |

Delete dominates older unsent edits. A later explicit restore is a new create or
update according to the validated remote identity contract; it is never the
silent removal of a tombstone.

## Three-representation classification

Let `B` be the last acknowledged canonical/remote baseline, `L` the current
canonical state plus pending local revision, and `R` the remote index/event/body
state observed at the start of the pass.

| Local relative to `B` | Remote relative to `B` | Required classification |
| --- | --- | --- |
| unchanged | unchanged | Clean; no hydration/upload/projection beyond detected repair |
| changed | unchanged | Local candidate; upload after current remote version check |
| unchanged | changed | Remote winner; hydrate if required, commit, project |
| changed | same compatible change | Converged duplicate; acknowledge without repeating mutation |
| changed | different changed | Preserve both snapshots; queue and revalidate an explicit D-024 choice |
| delete | unchanged/present | Local delete candidate after remote check |
| unchanged | deleted | Remote delete winner; canonical tombstone and projection cleanup |
| delete | changed | `D-006`: retain intent and require explicit recovery |
| changed | deleted | `D-006`: retain intent and require explicit recovery |
| create without remote ID | no matching acknowledged identity | Serialized create with idempotency/reconciliation strategy |
| create without remote ID | possible lost-ack match | Reconcile using request/stable card evidence; never create blindly twice |
| any | missing/ambiguous version or reset checkpoint | Bounded hydration/full reconciliation or action-required; never assume unchanged |

Unknown preserved properties do not count as a local replacement boundary when
the editing source could not represent them. Conflict comparison operates on
canonical field deltas plus the preservation envelope, not on Android's
flattened contact alone.

## Capability state machine

Contact groups use:

```text
unknown -> probing -> available
                   -> unavailable
available/unavailable -> stale -> probing
any -> auth-required
auth-required -> probing after restored authentication
```

Generic plan data MAY mark the state stale or prioritize a probe but MUST NOT
set `available` against an authoritative authorization failure. A transient
network/server error returns to the prior known state with a stale indicator;
it does not become `unavailable`. Upgrade/downgrade refresh MUST NOT delete
contacts, groups, assignments, or pending intent.

## Authentication and key-unlock state

```text
signed-out -> authenticating -> interactive-2FA/human-verification
           -> session-valid/key-locked -> session-valid/key-unlocked
session-valid/key-unlocked -> refresh-required -> session-valid/key-unlocked
                           -> interactive-action-required
                           -> revoked/signed-out
```

No headless transition may synthesize OTP, recovery, CAPTCHA, or security-key
input. Under `D-060`, only Proton Core's Keystore-encrypted derived user-key
passphrase may restore `key-unlocked` after process death or reboot, and only
after credential-protected app data becomes available following the device's
first unlock. A missing, corrupt, invalidated, or mismatched passphrase moves to
`interactive-action-required` without contact mutation. Sign-out/revocation
MUST clear the passphrase/context and make every old sync generation ineligible.

## Full repair state

A full repair uses the same runner and states but sets a durable repair scope:

1. obtain non-Wi-Fi confirmation when required;
2. enumerate remote data with bounded paging and full-card hydration windows of
   at most ten concurrent read-only requests;
3. reconcile canonical state and pending intent, never replace it blindly;
4. rebuild/repair Android projection in bounded batches;
5. persist progress after safe page/batch boundaries;
6. on cancellation, retain cached data and a resumable/restartable safe state;
7. clear repair scope only after successful publish or explicit cancellation.

Changing network type after confirmation does not cancel the pass. A new repair
attempt after cancellation requires a new off-Wi-Fi confirmation.

## State-machine acceptance

Model-based/fault-injection tests MUST traverse every transition, reject illegal
transitions, kill the process before and after each durable boundary, repeat
lost acknowledgements, and assert canonical/outbox/checkpoint/provider
invariants after restart. Tests MUST exercise the confirmed `D-024` interval
order and `D-032` complete-inventory rules. These contracts supplement actual
phone/Proton journeys; they do not require replaying retired campaign gates.
