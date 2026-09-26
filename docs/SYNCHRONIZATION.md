# Synchronization contract

The app-private Room store is canonical locally. Proton is the remote peer;
Android ContactsProvider is an editable interoperability projection.
Every foreground/background/system entry point MUST use one account-scoped engine.

## Ordered work

A pass checks prerequisites and session generation, ingests owned Android changes,
reads a bounded remote index, reconciles remote changes and durable local intent,
drains eligible uploads, then converges Android projection and records outcomes.
Account/provider context MUST be revalidated after ingestion and reconciliation.
See [State contracts](SYNC_STATES.md) and [Architecture](ARCHITECTURE.md).

Local and Android-originated mutations MUST be committed to canonical state and
outbox before network writes. Lost acknowledgements, retries and process restarts
MUST NOT create duplicate intent or discard pending edits.

## Triggers and cost

Durable mutations and connectivity return schedule best-effort foreground work
and an Android background fallback. Respect master/account automatic-sync switches,
debounce, rate spacing and engine backoff. The periodic request is approximately
hourly; stale app-open refresh uses a 15-minute interval. Android may delay work;
a forced scheduler dispatch is not proof of natural execution timing.

On foreground return, an empty Room outbox MUST NOT establish absence of native
intent. For the eligible current account, probe at most one owned RawContacts
DIRTY flag, including dirty tombstones, and schedule ANDROID_UPLOAD through the
same engine and platform fallback when present. This hint MUST NOT read contact
payloads/identities, clear DIRTY, bypass automatic-sync eligibility or acknowledge
an edit. A failed probe MUST preserve intent for a later lifecycle/system retry.
An unchanged probe alone MUST NOT request a new sync. Background-only timing is
subject to Android scheduling and requires separate physical evidence.

Ordinary no-change sync MUST use a lightweight complete paged inventory and
MUST NOT request full cards once per unchanged contact. Hydrate new, changed or
ambiguous items only. A page or inventory failure cannot establish remote deletion,
including when a response appears empty.

Read-only hydration uses at most ten concurrent requests and durable canonical
commits of at most 25 contacts. Independent Android projections use at most
16 slots; account-wide changes and group operations remain exclusive. These caps
MUST NOT be raised without measured physical memory/provider evidence.
Writes to Proton remain serialized.

## Conflicts and deletion

Use the approved deterministic last-writer evidence for comparable update/update
conflicts. If clock uncertainty overlaps, pending local intent wins. Comparable
edit/delete timestamps may resolve automatically; an incomparable edit/delete
conflict requires a user choice. Do not infer a remote deletion timestamp.

Current production limitation (D-096): the public contact directory supplies a
local index fingerprint, not a remote modification timestamp or full-card size.
`modifiedAtEpochSeconds` and `sizeBytes` are null. Pending local updates therefore
win incomparable update/update conflicts even if the Web edit actually occurred
later. Server-clock calibration alone cannot provide latest-edit-wins behavior.
The public-field fingerprint also MUST NOT be described as a full-card revision;
private-only change detection needs its own verification. Full repair hydrates
all cards, but MUST NOT be used to declare ordinary incremental sync qualified.

Local write evidence uses the process's verified primary-host HTTPS Date sample,
with measured request round-trip uncertainty and Android elapsed realtime for both
calibration and writes. Samples expire after 15 minutes; clock jumps, absent or
invalid Date, cached responses and excessive request duration MUST NOT establish
ordering. Small wall-clock adjustments MUST widen the uncertainty interval.
Unknown remote modification times and already queued incomparable writes retain
the defined fallback; ingestion time MUST NOT be described as the original time
of a native edit.

Non-discard sign-out MUST include unconsumed dirty Android contacts/groups and
tombstones, not only Room outbox rows. Unavailable observation blocks cleanup.
The final clean provider assertion and deletion share a non-yielding batch; Room
intent is rechecked with local writes excluded. No session clearance precedes a
failed clean guard. Partial cleanup is checkpointed, and a retry MUST NOT imply
consent to discard newly created native intent. Explicit discard and Android
Settings account removal retain scoped local cleanup without remote deletion.

Named deletion confirmation precedes a durable delete. A sync failure MUST retain
recoverable intent; cancellation is not success. Provider deletion/acknowledgement
must not resurrect a contact or replace an unrelated row. Contact and group
identities are stable identifiers, not display names.

## Android and rich data

Project only Contako-owned rows. Primary email determines Android group membership.
Android changes affect that email, not every email in a contact. A contact without
email cannot acquire a fabricated address to support a group.

Canonical revision, provider epoch, ownership, bindings and write receipts guard
against stale feedback and cross-account writes. Repeated or singleton-limited
Android fields MUST preserve hidden canonical occurrences and unknown vCard data.
See [Field mapping](CONTACTS.md).

Unified Android contact/membership ingestion MUST validate the planned canonical
group revision/deletion vector before mutating either aggregate. A contact write
may queue assignment reconciliation and advance group revisions itself. Within
the same Room transaction, membership validation MUST use that committed vector;
it MUST NOT mistake those own writes for concurrent changes. A stale initial
vector or later membership failure MUST still roll back the entire observation,
preserving Android DIRTY intent for retry and preventing premature acknowledgement.

For a display-only name, projection comparison MAY accept provider-generated
name parts only when their reconstruction matches the unchanged display value
and canonical name parts were empty. The actual provider snapshot MUST remain
the ingestion baseline; normalization MUST NOT mask native changes or replace
unconsumed DIRTY observations. This compatibility rule is deliberately narrower
than accepting arbitrary provider differences.

A reinserted Android structured-name row MAY regain its existing durable identity
only when the complete decoded projection matches the current pending write or
the integrity-checked last completed CLEAN baseline. Matching DATA_SYNC claims
alone MUST NOT authorize relocation. Account/source/epoch/revision, absence of the
old locator, vacancy of the new locator, exact primary/phonetic bindings and a
rechecked non-DIRTY observation MUST hold. All linked bindings MUST move atomically;
no canonical value or mutation intent is rewritten. Other non-photo rows, including
dates and linked organization/title/role bindings, MAY relocate only against the
matching pending whole-projection receipt, with the same guards and atomicity.
CLEAN-baseline recovery remains name-only. PHOTO relocation MUST use its separate
stream journal; this batch recovery MUST NOT bypass that boundary.
Baseline recovery MUST NOT skip normal planning or photo byte verification:
a decoded projection fingerprint alone is not proof of the current image bytes.

A verified completed group deletion receipt is not an active group binding.
Contact ingestion and projection MUST both exclude it only after account,
identity, provider epoch, converged tombstone, absent locator and canonical
deletion checks pass, with no pending mutation/conflict. Missing active bindings
MUST still require recovery. Retained completed receipts MUST NOT block unrelated
native contact edits.

## Recovery and capabilities

Expected offline state is pending work, not an alarming failure. Retryable errors
use bounded backoff; authentication, permission, malformed-data and incomparable
conflict states provide specific user actions. Full repair is explicit and
cancellable at safe boundaries; it keeps cached browsing available. Starting
repair off Wi-Fi requires the existing per-pass confirmation.

Group capability is unknown/available/unavailable based on maintained signals or
classified API outcomes. Unsupported groups MUST NOT stop contacts. A repaired
session or permission grant should make recoverable work eligible again.

Performance targets remain 5 seconds for an ordinary no-change pass, 15 seconds
for a ten-contact delta and 90 seconds for a 300-contact initial import.
A completed import without a valid start timestamp is not a timing pass.
Current implementation limits are in [Known limitations](KNOWN_LIMITATIONS.md);
acceptance is defined by [the functional plan](FUNCTIONAL_TEST_PLAN.md).
