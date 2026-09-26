# Architecture

## Source-of-truth model

The canonical data model belongs to Contako.

| Representation | Responsibility | Authority |
| --- | --- | --- |
| Contako local database | Complete offline model, mutation intent, baselines, sync state | Canonical local source |
| Proton | Encrypted remote persistence and cross-device peer | Remote source |
| Android `ContactsProvider` | System interoperability and controlled local edits | Editable projection |

Android rows and raw Proton vCard fragments are boundary formats. Neither may
become the normal UI model.

## Required architectural boundaries

The implementation SHOULD use modules or equivalent strict packages for:

- `domain`: canonical contact/group models and pure policies;
- `local`: database, transactions, migrations, outbox, and reactive queries;
- `proton`: public Proton adapters, card crypto, API capability isolation;
- `android-contacts`: account, schema, provider reads/writes, and delta mapping;
- `sync`: one state machine and per-account single-flight runner;
- `features`: Compose screens and use cases;
- `testing`: fixtures, fake peers, fault injection, and live-test harnesses.

Dependencies MUST point inward toward the domain. UI, Android provider, and
Proton DTOs MUST NOT leak into the canonical model.

## Canonical aggregates

### Contact

A canonical contact requires:

- a stable local identifier independent from Proton and Android identifiers;
- optional Proton contact identifier and vCard UID;
- structured and display names;
- typed, ordered, repeatable values where the source formats allow them;
- photos and mutation intent;
- Proton labels/groups, including per-email assignments;
- raw preservation envelopes for unknown supported card content;
- remote baseline/version metadata;
- pending mutation and conflict metadata.

### Contact group

A group requires:

- stable local identifier;
- optional Proton label identifier;
- name, color, order/visibility metadata where available;
- membership semantics separated from Android's contact-level projection;
- durable mutation lifecycle.

### Sync account

An account requires:

- stable local account identifier;
- Proton user identity and Android account identity;
- session state;
- last successful incremental and full refresh checkpoints;
- sync lease/single-flight state;
- user-visible blocking condition without secret-bearing diagnostics.

Contako MUST expose only one active Proton account. Account identity MUST still be
present in canonical ownership, outbox, sync checkpoints, and Android account
mapping to keep later multi-account evolution possible.

## Local persistence

The database MUST:

- be private to the application;
- remain unencrypted at application level by explicit product decision;
- use atomic transactions for canonical data and outbox intent;
- support schema migrations without destructive fallback in release builds;
- expose reactive local reads so startup never waits for remote data;
- avoid loading all large vCard/photo payloads into one cursor or object graph;
- retain a baseline sufficient for deterministic three-way reconciliation.

Photos and preserved raw card payloads SHOULD be stored or loaded separately
from list-view summaries.

The inventory checkpoint model is normalized and keyed by account.
Its header generation, contact entries, contact-group memberships, and
email-group memberships MUST be replaced in one transaction guarded by an exact
generation compare-and-set. The schema MUST retain an email membership even
when its group set is empty, because absence of the membership row is not an
equivalent state. Entity diagnostics MUST redact account, contact, email, group,
display-name, and remote-version values.

## Mutation model

The outbox is a durable command log, not merely a boolean dirty flag.

Each mutation MUST contain:

- account and local aggregate identity;
- operation (`create`, `update`, `delete`, plus group/assignment operations);
- monotonic local revision;
- canonical payload or deterministic reference to a committed snapshot;
- creation time, attempt count, next eligible retry, and error category;
- remote identity/version if known;
- an idempotency or reconciliation strategy.

Multiple consecutive edits SHOULD coalesce without losing the latest canonical
state. Deletion MUST dominate older unsent updates.

### Durable synchronization

The mutation model persists account sync status and full-repair progress. The
single row per account/aggregate is a compacted command whose revision is used
for every compare-and-set transition. It stores the operation, retry state,
remote baseline, reconciliation requirement, last attempt, and the complete
`D-024` local clock evidence. A newer local revision replaces an older pending
or in-flight command; an acknowledgement for the older revision therefore
cannot clean the newer canonical edit.

`PENDING`, `IN_FLIGHT`, `ACKNOWLEDGED`, and `ACTION_REQUIRED` are durable Room
states. Startup recovery changes an interrupted `IN_FLIGHT` command to
`PENDING` with mandatory reconciliation before replay. A multi-step group
create/update plus email-label change advances the same command durably to an
assignment operation instead of hiding multiple writes in one gateway call.
Canonical acknowledgement and outbox removal commit in one Room transaction.

The account status row contains only sanitized state, counts, timestamps, and a
closed action category. A continuous block retains its original start time.
Claiming either an immediate authentication notification or the ordinary
24-hour problem notification is an atomic compare-and-set, so process restart
or repeated framework retries cannot duplicate the same notification. Resolving
the block clears the claim; a later distinct block receives a new start/claim.

Notification delivery MUST consume the durable claim without creating duplicate
notifications after process restart, and MUST respect permission and navigation
requirements. Current delivery limitations are listed in [Known limitations](KNOWN_LIMITATIONS.md).

The full-repair row is account scoped and revision guarded. It records only a
closed phase, aggregate units, confirmation/cancellation flags, and timestamps.
Phase and unit progress cannot move backwards. The row survives process restart
and can be removed only after a completed publish or explicit cancellation, so
ordinary cached contacts are never replaced merely because a repair restarts.

### Android projection ledger

Room schema revision 8 contains the account-scoped Android projection ledger. Its
durable identity is `(account_id, canonical_contact_id)`; a raw-contact row ID
is only a replaceable locator bound to an account provider epoch. A provider
reset advances that epoch and invalidates locators and Android baselines for
only the selected account without changing canonical identity or remote state.

Projection preparation, Android-created contact adoption, observation
ingestion, deletion convergence, and reset recovery use revision
compare-and-set transitions. A canonical Android-originated delta and its
outbox mutation MUST commit in the same Room transaction as the corresponding
baseline or tombstone transition. A pending projection fingerprint survives
process death and allows the next identical provider observation to reconcile
a lost acknowledgement without creating an echo mutation.

The contact-level ledger stores SHA-256 projection fingerprints plus one bounded
provider-neutral full observation baseline. The baseline excludes inline photo
bytes, is encoded deterministically, and is integrity-checked against both its
canonical contact identity and fingerprint before use. Schema v8 additionally
stores transactional provider-row bindings: a canonical value identity is owned
once per account/contact, while its provider locator is scoped by provider epoch,
raw contact, Data row, and role. Pending bindings MUST be durable before a
projection claim is trusted; first-seen Android rows without a claim receive a
fresh UUID. Provider queries and writes remain outside Room transactions.

An active observation MUST persist its complete baseline in the same Room
transaction as the ledger revision and any canonical/outbox mutation. A deletion
MUST delete that baseline atomically. Even a semantically unchanged observation
MUST verify the expected canonical revision before advancing the ledger. The
v7-to-v8 migration invalidates fingerprint-only active baselines and marks them
for controlled repair rather than pretending that the missing observation can
support a safe three-way delta.

The caller-transaction-scoped canonical mutation store uses revision-CAS
delta/delete operations that reuse the
same canonical and outbox invariants as UI mutations but deliberately do not
announce an `AFTER_COMMIT` event before the enclosing ledger transaction has
committed. An Android-created staging shell is hidden, idempotent, and has no
outbox entry; only the subsequent validated CAS delta makes it active and
uploadable. The production coordinator MUST throw on every non-applied
mutation result so the enclosing ledger transition rolls back atomically.

The Android-created contact commit boundary now combines that shell, the first
canonical aggregate, its outbox intent, ledger attachment, raw-contact locator,
and complete observation baseline in one outer Room transaction. A retry after
process death MAY recover the committed canonical identity by locator only when
the active canonical row, full baseline, non-tombstoned `BASELINED` ledger, and
any still-pending UPSERT outbox row are mutually coherent. The caller MUST then
discard its provisional identity and decoded snapshot and re-read the raw
contact under the recovered identity. Any incomplete or recycled-locator state
fails closed as a locator conflict.

## API isolation

Public Proton libraries and repositories MUST be preferred. Any operation not
covered by a public stable abstraction MUST be:

- isolated behind one narrow gateway;
- documented as an inferred/undocumented capability;
- covered by request/response contract fixtures;
- guarded against schema change;
- replaceable without changing domain or UI code;
- reviewed at every Proton dependency upgrade.

The app MUST NOT identify itself as an official Proton client without explicit
authorization.

## Dependency policy

- All Proton Core artifacts MUST use one compatible release train.
- Dependency upgrades MUST include a public API surface review and live smoke
  test.
- Crypto, authentication, session, human-verification, user/key, contact, label,
  and network modules SHOULD be reused where their public contracts cover the
  requirement.
- A dependency must not introduce a second authoritative contact store or a
  competing background orchestrator without an explicit ADR.

## Architecture acceptance criteria

- A contact can be rendered with no Android provider or network access.
- A fake Proton gateway and fake Android gateway can test the sync engine on the
  JVM.
- There is exactly one production implementation of conflict resolution,
  canonicalization, retry policy, and field mapping.
- Process death between local commit and upload leaves a recoverable mutation.
- Removing or replacing the Proton gateway does not change the domain model.
