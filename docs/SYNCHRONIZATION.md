# Synchronization contract

The app-private Room store is canonical locally. Proton is the remote peer;
Android ContactsProvider is an editable interoperability projection.
Every foreground/background/system entry point MUST use one account-scoped engine.

## Ordered work

An ordinary pass checks prerequisites and session generation, ingests owned Android changes,
reads a bounded remote index, reconciles remote changes and durable local intent,
drains eligible uploads, then converges Android projection and records outcomes.
Account/provider context MUST be revalidated after ingestion and reconciliation.
See [State contracts](SYNC_STATES.md) and [Architecture](ARCHITECTURE.md).

Local and Android-originated mutations MUST be committed to canonical state and
outbox before network writes. Lost acknowledgements, retries and process restarts
MUST NOT create duplicate intent or discard pending edits.
Both ordinary and repair passes MUST durably recover interrupted in-flight
intent before remote hydration can match an unbound creation. Recovering only
when draining uploads is too late: the remote create may already be present.
A resumed repair MUST perform a fresh incremental remote check and revisit
guarded projection obligations even after those phases were checkpointed.
Completed phases MUST NOT trigger another forced hydration of unchanged cards
or move checkpoints backward; new deferred cards MUST keep the repair open.

Group assignment reconciliation MUST wait for pending contact writes in the same
account, including blocked conflicts. Contact writes can restore email identities
and labels; desired memberships alone cannot identify removed-edge dependencies.
This conservative ordering retains group intent without consuming retry attempts.

## Targeted recovery

A user MAY request **Recheck this Proton card** for a durable rejected-card
obligation, or **Retry this Android copy** for a known native/copy obligation.
Preview MUST read local state only. Queueing MUST revalidate the actual owned
obligation, source generation, canonical revision and provider epoch, and MUST
persist the request before scheduling the existing account runner. An arbitrary
UI identifier MUST NOT authorize a read, adoption or provider write.

A targeted-only pass MUST NOT enumerate the remote directory, consume the event
cursor, drain unrelated uploads or project the whole Android directory. The
Proton action MUST use one bounded verified complete-card read and the normal
canonical reconciliation policy. A generic 404 MUST NOT become a deletion.
Existing native changes MUST be captured durably before reconciling an already
projected contact; required common ingestion is separate prerequisite work.
The Android action MUST ingest the exact owned source, then plan from the newly
committed canonical revision and use the normal writer/receipt guards. It MUST
NOT call Proton. A bounded Android group catalog MAY be needed as a shared
prerequisite; it MUST NOT cause whole-directory contact projection.

Missing ownership, epoch, identity, permission or group context MUST defer or
block the request with a closed reason, never start an implicit full repair or
provider reset. Repeated confirmation, internal replanning and process restart
MUST preserve retry eligibility and ambiguity receipts. A resolved request MUST
qualify only its scoped operation, retain independent problems and MUST NOT
advance the last successful whole-account synchronization timestamp. Ordinary
work already scheduled alongside the request remains ordinary work and MUST
not be suppressed or described as the cost of an isolated targeted pass.

A canonical deletion MUST NOT by itself resolve an Android recovery request.
Its owned copy's deletion MUST retain the current owner/epoch/source and
convergence proofs; an obsolete request without those proofs MUST remain
unresolved or stale. The latest targeted failure category and available closed
hydration detail MUST survive restart independently of the original import
failure. They MUST NOT replace unrelated account health or reset retry delays.

## Ordinary triggers and cost

A rejected individual full card MUST NOT block verified independent cards.
Before acknowledging its event cursor, the engine MUST persist either the
verified canonical card or an account-scoped hydration obligation. Ordinary
retries MUST include outstanding obligations even when public metadata is
unchanged. Failed verification MUST NOT produce a canonical card, deletion or
upload permission. An interrupted obligation absent from the next inventory
MUST still receive targeted absence verification. Session, ownership and local
durability failures remain pass-wide boundaries.

An unavailable group catalog MUST NOT be treated as an empty catalog. Retain its
existing groups and defer dependent group writes while independent contact work
continues. A resumed repair MUST refresh catalog availability before draining
group intent. Authentication failures MUST stop subsequent remote writes.
When a catalog becomes available, missing known per-email memberships MUST
trigger targeted verified hydration. Unknown labels, ambiguous group identities
and pending local intent MUST NOT authorize reconstruction from stale metadata.
After a new authenticated/capability generation, previously denied writes MAY
become eligible for fresh verification at their exact retained revision. This
MUST NOT acknowledge them or reopen validation, cryptographic or conflict failures.
Completion MUST account for durable blocked and pending work even when no new
command was eligible during the pass.

When Android changes the preferred email, contact intent, membership context and
the observed baseline MUST commit atomically. Preserve assignments of surviving
email occurrences; only the proven native membership delta applies to the new
preferred occurrence. A changed preference alone MUST NOT transfer all groups
from the previous preferred email.

Durable mutations and connectivity return schedule best-effort foreground work
and an Android background fallback. Respect master/account automatic-sync switches,
debounce, rate spacing and engine backoff. The periodic request is approximately
hourly; stale app-open refresh uses a 15-minute interval. Android may delay work;
a forced scheduler dispatch is not proof of natural execution timing.

An authenticated foreground return MUST retry a missing Android account even
when its durable binding was committed before a failed platform creation. Reuse
the bound name and revalidate ownership; a changed display address MUST NOT
silently rebind the account. This projection recovery MUST NOT restart a full
Proton import or recreate an already present bound account.

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
canonical reconciliations or explicit hydration obligations are durable. Drain
all event pages before advancing it.
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
The explicit sign-out operation MUST survive removal of its initiating screen.
Its own session-clear notification MUST NOT cancel the remaining cleanup or
hide a cleanup failure. Owner release remains conditional on committed local
deletion, with partial-cleanup checkpoints retained for retry. This recovery
MUST also survive process restart when no Android account was provisioned and
the Proton session was already cleared. Recovery MUST retain the original owner
fence, block new synchronization/login admission and expose an explicit retry.
A finalization checkpoint after owner release MUST NOT authorize another data
or session deletion.
Refusal before destructive cleanup, with no remaining checkpoint, MUST NOT force
exclusive cleanup recovery. UI mutations MUST share account lifecycle admission:
cleanup invalidates queued draft saves and waits for admitted writes before purge.
Drafts and cached contact views MUST NOT survive into a different owner's session.

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
CLEAN-baseline recovery remains name-only. Historical PHOTO relocation MUST use
its separate committed stream journal; batch recovery MUST NOT bypass that
boundary. New synchronous photo writes retain a distinct durable representation
receipt under the ownership, version and readback guards in Contacts.
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
