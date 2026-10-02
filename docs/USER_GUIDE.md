# User guide and FAQ

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
- **Preserved data** shows additional unsupported fields in read-only form; their
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
versions are kept. Choose **Keep Contako version** or **Use Proton version**; the
choice waits for synchronization and is checked again against Proton. A newer
edit requires another review. Pending group assignments can prevent adopting a
Proton version that removes their email; remote deletion requires separate recovery.
Avoid editing the same contact simultaneously: Proton does not provide an atomic
version check for the final write.

## Report a problem safely

In Sync, generate the local diagnostic summary, review it and export it only if
you want to share it. It contains the app version, broad platform/count categories,
permission state and overall synchronization state. It excludes contact values,
account identifiers and credentials, and is not a detailed activity history.
Deleting the on-screen summary does not delete a file already exported elsewhere.

Known build-specific limitation: generating this summary on a `-sync-diagnostic`
build fails the current version-format validation. The exporter is implemented,
but this diagnostic variant must not be described as qualified. This was found
by source/rule inspection, not a new phone reproduction; see [Known limitations](KNOWN_LIMITATIONS.md).

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
