# User guide and FAQ

## Filter the contact directory

Use **Filter** in Contacts, choose criteria and press **Apply**. Closing
the sheet cancels its draft. The directory shows a result count and removable
criteria. **Clear filters** keeps search text; clearing search is separate.
Search and different criteria intersect. Company names use ordinary search,
ignoring case and accents without changing stored names.

**Without phone/email** considers every nonblank occurrence, including secondary
and invalid values. **Local-only fields** finds custom dates, phonetic names
and established labels that Proton cannot represent. Pending delivery alone does
not make data local-only.

**Recently changed** offers a **Days** field, set to seven by default. It uses local observation time of contact
field changes. It does not claim when a field was edited on Proton. First imports
and migrated contacts without a known observation date are excluded. An unknown
historical remote baseline is first verified without inventing a change date. Unchanged
reads, acknowledgements, retries and group-only changes do not renew the date.
Future dates are excluded. Results update on new data and periodically while
this criterion is active.

Filters work offline and survive navigation and rotation. A new application
session or owner starts without them. A detail stays open if an edit makes it
stop matching; returning shows the current results. Sync keeps its complete
issues and work. A local read error offers a local retry and does not look like
an empty address book. Filtering never starts synchronization.

## Recover edits after a Proton deletion

When a Proton contact has been deleted while local edits are waiting, Sync keeps
the local work and offers **Recover local changes**. Opening the recovery view
does not contact a peer or apply a choice. Use the explicit verification action
if the source's absence has not yet been confirmed.

Choose **Keep a local draft**, **Create a new contact**, or **Discard local
changes**, then confirm the named action. Keeping a draft does not back it up on
Proton: it remains on this installation and can be edited from Sync without
uploading it. Recreating makes a distinct contact, with a new identity, through
the ordinary synchronization queue. It does not undo the original deletion.
Discarding abandons the retained local edits; it does not delete another Proton
contact.

The old Contako-owned Android copy is removed only after safe preservation and
checks. A changed native source, unavailable Android access or opaque data that
cannot be retained safely can leave cleanup blocked. Work stays available for
review; other account sources of a linked system contact are not removed.
Unresolved group mappings remain explicit rather than being guessed.

Retained drafts prevent normal sign-out from treating an empty upload queue as
fully backed up. Review/recreate them or separately confirm discarding local
work. Uninstalling, clearing application data or explicitly removing the Android
account still follows the application's destructive local cleanup policy.

## Inspect one contact's synchronization

Open a saved contact, then choose **Synchronization details** in its overflow
menu. Identified contact and Android-copy issues in Sync also offer this action,
including a retained summary for a pending deletion. Opening or closing the
panel reads local records only; it does not contact Proton or the system provider.

The panel separates what is saved in Contako, the last known Proton confirmation
and Android's last verified copy. A confirmation identifies the local version
it covers, its source and its time when known. An earlier version or Android
context is historical. Existing records without sufficient evidence show an
unknown confirmation rather than a date inferred from editing or account sync.
A last confirmation does not rule out newer changes on Proton or in Android.

Proton confirmation covers supported contact fields, not independent email-group
assignments. Android verification covers its representable subset and supported
normalizations, not an identical copy of every Contako field. Fields retained
only locally are not synchronization errors. **Refresh synchronization** explicitly
requests an ordinary pass through the existing engine; viewing the panel never
starts that work implicitly.

## Getting your contacts ready

The first import opens an overview that can be closed at any time. **View my
contacts** opens the contacts already saved on this device; it does not cancel
the import. Sync keeps the same availability summary afterwards.

**First synchronization** shows **In progress**, **Completed** or **Completed
with errors**, followed by three counts: Proton contacts, contacts imported into
Contako and system contacts synced. Unknown totals show **Pending**; a confirmed
empty inventory shows zero. Errors add a short summary and **View problems**.

After restart the summary is rebuilt from saved records. The system count covers
acknowledged active copies, not a fresh comparison of every field. Only current
activity animates the indicator. Refusing Contacts permission leaves Contako and
Proton available while system synchronization waits.

## Understanding a synchronization issue

Sync and **Items needing attention** use the same issue cards. Each card names
its scope: account, contact, group, Proton import or Android copy. Independent
problems remain visible even while a more urgent account issue heads the page.
Normal pending copies and edits are waiting work, rather than errors by themselves.

The main action follows the known cause: allow Contacts access, sign in to the
same account, compare retained versions, review an item, retry through the
normal engine or open local diagnostics. A saved-operation message appears only
for known durable intent. The app does not guess which field caused a generic
validation failure, and an unidentified cause stays unidentified.

Opening an issue, viewing a contact or returning from Android settings does not
resolve the issue. It stays until the relevant operation is verified. Retries
keep the existing eligibility and backoff rules. **View diagnostics** opens a
local page; generating, exporting and clearing history remain explicit actions.
Historical failed attempts in a report may have recovered since then and are
not themselves current alerts.

## Recovering one contact operation

An import problem can offer **Recheck this Proton card**. An Android issue or
pending copy can offer **Retry this Android copy**. Open the preview to see its
scope and prerequisites, then explicitly queue the operation. Opening or closing
the preview makes no network/provider request and does not acknowledge an error.

Rechecking reads and verifies the selected Proton card and reconciles it with
the saved local state. Retrying an Android copy first captures native edits,
then verifies and updates only that contact using the saved local version. If
the contact already has pending deletion intent, the preview explicitly says
that the existing Android-copy removal will be retried. Other linked sources
are retained. Missing remote identity or provider context requires the stated
prerequisite; there is no silent global repair or reset.

The result distinguishes queued, running, deferred, verified, changed context
and attention required. Retry eligibility is the earliest allowed time, not an
Android execution promise. Repeated taps do not reset the delay or attempt
budget. Requests survive restart and stay with their owner. A verified operation
can leave other synchronization work pending; it is not a new full-account
success. Normal synchronization already scheduled may also process other work.

Contako runs on Android 12 or later, with one Proton account at a time.
See [Known limitations](KNOWN_LIMITATIONS.md) for compatibility and implementation limits.
This guide is repository documentation; it is not yet an in-app FAQ screen.

## Start and find your contacts

Contako checks contact permission at startup. Grant it to make Proton contacts
available in the system address book. Without it, system contact synchronization
is unavailable; the Sync screen provides the permission recovery action.
After sign-in and key unlock, the app schedules synchronization. The first import
can take longer than later passes, especially with contact photos.

Contacts and Groups have a search icon beside the account menu. Tap it to search;
the cross clears the query and closes search. Matching ignores letter case and
accents. The alphabet rail jumps through the list. Returning from a contact keeps
your previous list position; tapping the already selected tab returns to the top.
Cached contacts remain readable offline, and saved edits wait for synchronization.

## Edit contacts and groups

- Start with the name, email and phone. **Add a field** opens the other field
  types; existing values are shown when editing. Tap the avatar to add a photo.
- **Field options** reveals labels, preferred values and ordering. **Manage email
  groups** opens assignments for each address, including secondary addresses.
- **Information** at the bottom of the contact shows unsupported fields in read-only form; their
  text can be selected and copied. They remain preserved during compatible edits.
- Save commits the edit locally before upload. A pending indicator does not mean
  that Save lost the edit. Reopen the contact to check the saved values.
- A blank display name is filled from the entered first and family names. An
  explicit alias is kept, with structured names shown separately in contact detail.
- Groups belong to individual email addresses in Proton. Edit each email's group
  assignments independently. Android projects the preferred email's memberships.
  Deleting a group removes its assignments, not its member contacts.
- Photos and logos are separate fields. Newly picked images are reduced; an
  unrelated edit does not deliberately resize imported images. A single image
  opens from the header; multiple images have a gallery.
- Date forms use `dd/MM/yyyy`, or `dd/MM` without a year, with a calendar picker.
  Custom date events can stay local to Contako/Android. Yearless dates have a known
  Proton Web editing limitation; see [field compatibility](CONTACTS.md).

In a system contacts app, choose the Contako account when creating a contact.
Edits to a Google or device-only contact are not automatically imports into
Contako. Android may display several account entries as one person, or leave
them as duplicates. Contako does not merge or take ownership of other accounts.
An Android editor may expose fewer fields than Contako; supported hidden fields
are preserved during compatible edits.

## Understand synchronization

| What you see | What it means / next action |
| --- | --- |
| Pending changes | Local changes still need a confirmed Proton result. Retain the session and inspect the affected entries. |
| Pending Android copies | Android projection or its verification is unfinished. A visible native contact is not proof that every field was delivered. |
| Attention required | Open the entry from Sync and follow the available correction, authentication, permission or conflict action. Some reasons concern the account or provider rather than one contact. |
| Incomplete sync with zero changes | The outbox can be empty while another stage is waiting or failed. Zero pending mutations alone does not prove synchronization succeeded. |
| Offline | Cached browsing and durable local edits remain available; delivery requires connectivity and an eligible sync run. |

Use ordinary **Sync** first when investigating a stale result. **Repair** is a
separate, more expensive full re-read and Android reconciliation; it preserves
pending intent rather than replacing local data blindly. Repair exposes progress
and safe cancellation; a retained interrupted checkpoint can be resumed.

Android controls background execution. Automatic-sync switches, connectivity and
battery restrictions can delay work. Contako does not promise an exact background
deadline. Use **Configure synchronization alerts** in Sync to allow notifications
or open Android notification settings. Notifications contain no contact or account
details; denied permission does not prevent synchronization.

When local and Proton changes conflict, Sync offers **Compare versions**. Both
versions are kept. Differences appear first; **Show matching fields** also reveals
matching values. Structured components, labels, preference and order are included.
Repeated occurrences without a reliable match stay separate. Group names describe
current local assignments and the saved Proton version; missing information stays
unknown. Thumbnails illustrate stored media and never prove original equality.

Choose **Keep Contako version** or **Use Proton version**, inspect **With this
choice…**, then confirm the whole version. The Proton preview keeps eligible local
dates, phonetics and labels according to the normal preservation rules. A local
deletion choice is explicitly described as a deletion. The choice waits for
synchronization and is checked again against Proton. **View synchronization
status** shows the actual confirmations; saving a choice is not confirmation.
A newer edit requires another review. Pending group assignments can prevent
adopting a Proton version; review the pending group changes from the comparison.
Remote deletion links to the separate draft-recovery choices.
Avoid editing the same contact simultaneously: Proton does not provide an atomic
version check for the final write.

## Understand field compatibility

Open **Information** at the bottom of a contact or its editor. Fields are grouped
by their Proton and Android support; shared fields start collapsed. Local labels
and preserved read-only properties are shown there. Select a field for details,
or use the page's information icon for the general explanation. **Add a field**
also offers icon-only help. Closing help returns to the same contact, draft or
picker without saving or contacting a peer.

Custom dates and complete phonetic names stay local. A supported URL can still
have a local-only label. Android may project only a primary occurrence; secondary
values stay retained. Email groups belong to individual Proton email occurrences,
while Android uses the preferred email groups. Account capability can be unknown,
known available or explicitly refused; offline state is not proof of refusal.

Permission denial suspends Android copying rather than changing field support.
Unknown vCard data remains read-only; unknown Android rows stay in Android.
Media representations and contact-app visibility can differ. Use **View
synchronization status** for actual confirmations of a saved contact; compatibility
help and thumbnails do not establish delivery.

## Report a problem safely

Sync separates changes waiting for Proton, pending Android copies and identified
phone-contact problems. Items requiring attention show the affected contact or
group when its identity is known, including pending deletions. Another overall
error or a directory search does not hide these items. Opening a contact shows
its details without changing it.

In Sync, reproduce the problem, then generate the local diagnostic report, review
it with **Show technical report**, and export it only if you want to share it.
The technical text is initially collapsed. Ordinary builds include a rotating
history of up to 128 technical events: UTC times, elapsed milliseconds relative
to each process, manual/Android/local-change triggers, sync stages and known
error or field categories. The latest detailed failure is retained separately
when newer events rotate; a later generic banner or success does not erase it.
Process-start markers distinguish elapsed-time resets. UTC follows the device
clock, while elapsed times are unaffected by a wall-clock adjustment.

The report also contains broad platform/count categories, permission state,
Android-copy backlog and overall sync reason. It excludes contact values,
account identifiers, credentials, raw errors and authentication/cryptographic
traces. An unknown field remains unknown rather than being guessed. The history
survives app restarts, but abrupt termination can lose the last unflushed events.
`journal_storage=UNAVAILABLE` indicates a history-storage problem, not a sync result.
Use **Clear local history** to erase retained history; a storage failure is shown
instead of confirming a durable clear. Deleting the on-screen report or clearing
history does not delete a document already exported elsewhere.

Describe the phone model, version, source app and field/action involved, for
example “added an email in Proton Web; Android copy remains pending.” Do not send
the actual address, password, code or a screenshot containing personal contacts.
Keep the failing state available for investigation; reinstalling, clearing data
or signing out can erase evidence and unsynchronized local work.

## Disconnect, update and privacy

The menu has two separate entries: About contains the project, version,
GitHub link, credits, privacy and licenses; Usage summarizes features,
synchronization behavior and practical limitations. Each opens its own page
directly. This guide provides the fuller explanation.

Normal sign-out checks pending Contako and Android edits. Synchronize first when
possible; separately confirmed discard abandons local pending changes. Sign-out
removes the scoped local data and Android copies, not the remote Proton contacts.
Removing the Contako account through Android settings also removes scoped local
data, so it is not a harmless troubleshooting step.

Install a compatible testing APK over the existing app only when its package and
signing certificate match. There is no in-app updater or public release channel
declared yet. A version number or a `release` filename does not establish signing
compatibility or release qualification. See [build and signing](../app/README.md).

Contako is independent of Proton. It has no analytics, ads or remote crash
reporting. Local contacts use the app sandbox without separate database encryption;
system copies can be read by apps with Android contact permission. Application
backup and device transfer are disabled. A future app lock would not hide these
system copies from authorized apps. See the [Privacy Policy](PRIVACY_POLICY.md)
for data handling and deletion, and [Security](SECURITY.md) for technical safeguards.
