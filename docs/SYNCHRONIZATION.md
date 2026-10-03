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

Group assignment reconciliation MUST wait for pending contact writes in the same
account, including blocked conflicts. Contact writes can restore email identities
and labels; desired memberships alone cannot identify removed-edge dependencies.
This conservative ordering retains group intent without consuming retry attempts.

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
ambiguous items only. The authenticated contacts-only Proton v6 event feed MUST
supplement public-directory fingerprints, including for private-only edits.
Capture the latest event position before the first complete hydration; persist
the account-scoped cursor with the inventory checkpoint only after all planned
canonical reconciliations are durable. Drain all event pages before advancing it.
A failed read, cancellation or failed checkpoint transaction MUST retain the old
position for replay. Resolve email events through current/previous opaque email
identities in the directory checkpoint. A server-requested refresh or an unresolved
email event requires complete hydration; an ordinary empty event delta does
not. Event positions MUST NOT be treated as contact revisions or logged.
A page or inventory failure cannot establish remote deletion,
including when a response appears empty. Public pagination is not a server
snapshot: confirm each absent ID with a targeted structured Proton absence before
committing canonical deletion; a generic HTTP 404 is insufficient.

Read-only hydration uses at most ten concurrent requests and durable canonical
commits of at most 25 contacts. Independent Android projections use at most
16 slots; account-wide changes and group operations remain exclusive. These caps
MUST NOT be raised without measured physical memory/provider evidence.
Writes to Proton remain serialized.

## Conflicts and deletion

Before each existing-contact update or delete of a present remote contact, the serialized engine MUST read
one verified full card and compare its complete fingerprint with the durable
intent baseline. A divergence MUST preserve the local draft and the verified
Proton version in account-scoped Room storage and block the upload. Full repair
MUST NOT advance a conflicting upload baseline or choose a winner silently.

The Sync panel compares Contako and Proton and queues a whole-contact choice.
The choice is bound to the conflict generation, local revision and remote version.
It cannot be changed while engaged. The engine reads Proton again before applying
it; a new local or remote change invalidates the choice. Offline/cancellation
retains snapshots and intent. A local winner keeps its snapshots until confirmed
acknowledgement; a Proton winner adopts values, preservation data and projection
intent atomically with removal of only the selected local mutation.

Proton adoption MUST NOT silently drop independent group assignments to an email
absent from the selected version. Such assignments require resolution first.
A confirmed remote deletion invalidates an earlier update choice and retains the
local draft for deletion recovery; the old remote snapshot cannot authorize a
write. A pending delete converges only on confirmed absence. A delete queued after
an earlier remote deletion was reconciled MUST obtain fresh targeted absence
proof even when that ID no longer appears in the inventory checkpoint. A failed
full-card read or a retained conflict snapshot MUST NOT substitute for that proof.
Explicit local deletion MUST replace the previous edit/choice transactionally
with a new durable delete intent. It MUST NOT inherit that edit's conflict state
or acknowledge the deletion before fresh remote proof. Already blocked deletes
MAY resume after confirmed absence and revalidation of their exact local revision.

The API has no effective atomic version precondition. A remote change between
the final GET and PUT remains possible. Public-directory fingerprints alone
cannot expose private-only Web changes; ordinary reconciliation uses the contacts
event feed to identify cards requiring a fresh read.
Full repair refreshes all cards; it MUST NOT be presented as proof of ordinary
private-change detection. Server-clock calibration remains diagnostic write
evidence, not a substitute for a remote version or proof of a winning edit.

Non-discard sign-out MUST include unconsumed dirty Android contacts/groups and
tombstones, not only Room outbox rows. Unavailable observation blocks cleanup.
The final clean provider assertion and deletion share a non-yielding batch; Room
intent is rechecked with local writes excluded. No session clearance precedes a
failed clean guard. Partial cleanup is checkpointed, and a retry MUST NOT imply
consent to discard newly created native intent. Explicit discard and Android
Settings account removal retain scoped local cleanup without remote deletion.
App sign-out attempts bounded best-effort session revocation before mandatory
local session cleanup; an unavailable network MUST NOT strand the local session.

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
classified API outcomes, independently for creation, update, deletion and email
assignment. Disable only the affected operations with an explanation; unknown
or transient failure MUST NOT imply a plan restriction. Unsupported groups MUST NOT stop contacts. A repaired
session or permission grant should make recoverable work eligible again.

Notification publication is generic and contains no contact/account payload.
The Sync screen offers Android notification permission/settings. Denial MUST NOT
block synchronization or consume a notification claim. Record delivery only after
successful publication, use a stable notification ID, and cancel obsolete alerts
under the same status transaction. Authentication alerts are immediately eligible;
other sustained blocks become eligible after 24 hours and are rechecked on sync
or foreground return. Android scheduling does not guarantee an exact deadline.

Performance targets remain 5 seconds for an ordinary no-change pass, 15 seconds
for a ten-contact delta and 90 seconds for a 300-contact initial import.
A completed import without a valid start timestamp is not a timing pass.
Current implementation limits are in [Known limitations](KNOWN_LIMITATIONS.md);
acceptance is defined by [the functional plan](FUNCTIONAL_TEST_PLAN.md).
