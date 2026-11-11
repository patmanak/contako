# Synchronization state contracts

These are design contracts, not a claim that every failure scenario is qualified.
See [Synchronization](SYNCHRONIZATION.md) and [Known limitations](KNOWN_LIMITATIONS.md).

## Targeted request state

| State | Meaning | Required transition proof |
| --- | --- | --- |
| REQUESTED | Durable request, no effect promised | Current owned obligation and queue-time revision/context |
| RUNNING | Claimed by the existing account runner | Current claim token, owner admission and cancellation generation |
| DEFERRED | A transient prerequisite or retry deadline prevents completion | Durable eligibility preserved; no busy replay loop |
| RESOLVED | The scoped operation was verified or already satisfied | Current verified card reconciliation or exact Android write/readback receipts |
| STALE | Source revision, generation or context changed | No old plan may apply; a fresh preview is required |
| ACTION_REQUIRED | A conflict, deterministic refusal or exhausted automatic budget remains | Intent retained; no automatic winner, forced copy or silent reset |

Interruption MUST leave the request recoverable. An external effect without a
returned acknowledgement MUST be reconciled from the existing canonical/provider
proofs before replay. A repeat request or internal ledger transition MUST NOT
reset retry eligibility or reopen a terminal automatic failure. Owner removal
MUST close admission and remove only that owner’s request metadata. Scoped
resolution MUST NOT erase independent failures or become global success.

## Shared runner transitions

`TARGETED_RECOVERY` uses that same runner. An isolated request executes only its
durable target work; coalescing with a normal or full pass retains the normal
pass and settles the target request from its verified current state. A single
account-scoped waiter observes durable retry deadlines and account invalidation;
it only requests the runner, never performs peer I/O itself. Deterministic or
exhausted automatic failures remain action-required. An explicit cooled retry
MAY request another check without resetting the attempt count or server delay.

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
| `InFlight` | One serialized request attempt is active | Valid ack -> `Acknowledged`; delivered valid group subset with omitted local input -> `Partial`; transient/no ack -> `Pending`; blocking result -> `ActionRequired` |
| `Acknowledged` | Remote outcome observed, awaiting canonical/baseline commit | Atomic reconcile -> `Clean`; process death -> reconciliation repeats safely |
| `Partial` | Valid group links are delivered; safely omitted invalid local memberships remain durable | Correction or accepted identity change -> guarded `Pending`; explicit group Save -> new `Pending` scope; unverified legacy scope -> `ActionRequired`. Not queued network work. |
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

A newer local revision MUST retain an unbound creation's ambiguous-delivery
marker, including after deletion. A late CREATE/UPDATE acknowledgement MAY bind
its proven identity and advance the upload baseline, but MUST NOT acknowledge
the newer local values or discard their outbox intent. Identity and provider
receipt guards still apply.

Retry decisions MUST preserve an existing ambiguous-delivery marker, including
when a read-only recovery request is rate-limited. Only explicit guarded proof
may clear it; a retry policy's default is not absence evidence.

An unresolved creation MAY retry only after a fresh complete inventory, verified
card UID inspection and a second stable inventory establish no matching identity,
with the exact currently claimed local intent revalidated transactionally. A
unique verified match with complete equivalent submitted content MAY converge
without another POST or a conflict choice. Different content retains the existing
choice; multiple matches or unreadable dependencies MUST NOT authorize another
create. Positive identity proofs may cover a bounded set of pending intentions;
absence MAY clear ambiguity on a bounded set of other pending intentions only
when each complete canonical/payload/outbox snapshot was captured before the scan
and is revalidated unchanged in the same transaction. New or changed intentions
MUST NOT inherit that proof. This
exceptional reconciliation can read full cards, with one complete scan per pass;
ordinary no-change synchronization MUST NOT do so. Earlier blocked unbound
creations MAY reopen this verification once per fresh recovery generation.
If that stable scan is incomplete because of unreadable cards, each unchanged,
due and unblocked captured intent requiring incomplete verification MAY consume one
verification attempt through the same claim/retry lifecycle. The already claimed
intent MUST NOT be charged twice. Future, changed or newly created intentions
MUST remain untouched, and interrupted network/account scans MUST NOT distribute
failure credits without the second stable inventory.

An unreadable card MUST NOT stop inspection of later cards, but MUST prevent
absence and unbound UID-uniqueness proofs. A previously bound exact remote ID MAY
still be verified. A visible unbound candidate with incomplete coverage MUST
retain its distinct match-verification cause rather than become a content
conflict, be adopted or be created again. Bounded automatic verification and an
explicit cooled item retry after exhaustion MUST remain available; the item
MUST explain the unreadable-card cause without offering comparison of invented
versions or **Create anyway**. After exhausted
incomplete verification with no visible candidate, the user MAY explicitly
confirm **Create anyway**, acknowledging duplicate risk. This permission MUST be
revision-bound, invalidated by an edit and consumed only by the serialized
preparation of that intent. It is not an automatic absence proof; retry/server
deadlines and all ownership guards still apply.
Another creation's shared verification scan MUST NOT consume, exhaust or
reclassify this confirmed choice, including after database reopen.

Per-card import failures MUST become durable obligations before the inventory/event
checkpoint advances. Transient obligations retain deadlines and Retry-After and
MUST NOT refetch before eligibility; already healthy cards remain checkpointed.
Three consecutive transport/service failures MUST stop launching new hydration
windows for that pass. Already started reads MAY finish; unread cards MUST receive
durable deadlines without consuming attempts. A single card failure or malformed
card MUST NOT trigger this circuit. A rate limit stops new windows immediately
and retains the longest observed server minimum.
An account-scoped server cooldown also survives event, inventory and deletion
read failures. Manual requests and restart MUST NOT bypass that cooldown or
consume untouched mutation attempts. Local/native work may still advance.
A known per-contact membership-remap refusal MAY be isolated after transaction
rollback, but corruption, ownership, persistence and other invariant failures
MUST stop the unsafe traversal. Cancellation MUST propagate.

An explicit revision-bound retry credit MAY reopen an exhausted transient write
once, including a pending deletion. It MUST retain idempotency, attempts, remote
baseline, ambiguous-delivery evidence and server delay. Duplicate requests are
idempotent and new requests have a durable cooling period. The severity of one
blocked item MUST NOT suppress independent eligible work or transient scheduling.
When accepted group members are verified delivered and only a safely omitted
invalid local email remains, its assignment intent MUST be retained as PARTIAL.
It MUST NOT count as a queued write, immediate progress or retryable I/O; sign-out
MUST still count the retained intent. Correction or a relevant accepted email
identity change MUST reactivate guarded preparation. Unchanged ordinary passes
MUST NOT reread the group's remote membership solely for that retained input.
After delivery, retained authority MUST cover only the deferred links, with a
durable revision-bound scope. Remote additions/removals of already delivered
members MUST remain authoritative. A later correction of a deferred link MUST
merge it additively with fresh remote memberships, not replay the old complete
snapshot. An explicit group-membership edit replaces that restricted intent with
the newly saved user choice. Retry, process death and contact identity remapping
MUST retain the distinction; missing legacy proof MUST NOT invent delivery.
Before updating a delivered contact in a deferred-only group, Contako MUST
observe current remote membership and retain only that contact's proven
restoration links. Both the outgoing card and post-acknowledgement assignment
MUST respect that observation; a stale clear category MUST NOT resurrect a
remote removal. Unknown categories and independent group intents MUST survive.
An explicitly edited category MUST NOT be erased as a stale imported value.
If its relationship cannot be reconciled safely, the PUT MUST remain blocked
with the user's values intact.
Lost acknowledgements and identity remapping MUST retain the exact restoration
scope, without reopening a permanent refusal or resetting retry history.
When legacy deferred scope cannot be verified, the issue MUST offer review of
the affected owned group. Opening it MUST NOT mutate intent; only an explicit
save MAY replace its scope with the user's current selection.
Sanitized current-issue reports MUST include PARTIAL group work independently
of the contact validation cause, without exporting group/contact identities.

Current implementation limit: a later Proton removal can be reasserted while a
positive contact-write restoration witness remains pending, including after
failure or restart. This does not satisfy general concurrent-removal protection;
see [Known limitations](KNOWN_LIMITATIONS.md).

Exhausted network, timeout, rate-limit, service and unknown transient failures
MUST remain visible while permitting at most one automatic probe approximately
daily through ordinary synchronization. The daily and manual paths MUST share
durable cooldown evidence and preserve the attempt budget and ambiguity. Server
minimum delays MAY postpone a probe further. Incomplete creation verification,
validation, cryptographic refusal, authentication and real conflicts MUST NOT
receive daily write permission. Dependency deferral MUST preserve the last real
failure category rather than manufacture a cancellation.

Rejected cryptographic imports remain outside verified canonical data and Android.
Users MAY request a guarded recheck or ignore that exact obligation generation
until its remote source changes. Ignoring MUST NOT mark the card verified, erase
the local failure history or permit editing/re-signing unverified content.

Dependency-ineligible group assignments MUST NOT consume every slot of a bounded
outbox batch ahead of the contact writes they await. Filtering those assignments
MUST retain their intent, retry history and account isolation.

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
