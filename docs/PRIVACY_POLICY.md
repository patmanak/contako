# Contako Privacy Policy

Effective date: 2 October 2026.

This policy applies to **Contako**, the Android contact-management and
synchronization application (`com.patmanak.contako`), developed and maintained
by **Patmanak**. Contako is an independent open-source project; it is not
affiliated with, endorsed by or sponsored by Proton AG.

## What Contako accesses and why

Contako connects an existing Proton account to an Android contact account. It
processes the following data to provide that functionality:

- **Contact and group data:** names, email addresses, telephone numbers, postal
  addresses, notes, dates, photos, organization details, group assignments and
  other fields present in your contacts. Contako downloads your Proton contacts,
  keeps a local copy for offline use, and synchronizes supported edits and
  deletions between Contako, Proton and its Android contact account. Additional
  unsupported contact fields may be retained to preserve existing data.
- **Authentication and session data:** your Proton account identifier, the
  credentials or verification codes you enter, authentication proofs, session
  tokens and cryptographic key material needed to sign in and access your
  contacts. Contako uses Proton's authentication and cryptographic libraries.
  Authentication involves Proton's services, including its human-verification
  service when required. The developer does not receive your credentials.
- **Android contact and synchronization state:** records, identifiers and version
  information needed to identify Contako-owned contacts, detect changes and
  avoid overwriting another account's records. Synchronization targets the
  Contako contact account; it does not automatically import your Google or
  other account's address book. Android may combine records from several
  accounts into an aggregate contact.
- **Images you select:** the Android image picker gives Contako access to the
  selected image for a contact photo or logo. Newly selected images are resized
  and their metadata is removed before use. The app does not request access to
  your entire photo library or use the camera.
- **App preferences and operational state:** language, appearance, permission
  state, synchronization progress, pending edits and errors. These support the
  interface and reliable synchronization, including recovery after interruption.

Synchronization can run in the background while you are not using the app,
subject to your Android synchronization settings, connectivity and battery
restrictions. This uses the same contact data and Proton connection as foreground
synchronization.

## Permissions

Contako requests Android contact read and write permissions to read changes to
its system contact account and create, update or remove its system copies.
Without contact permission, Android contact synchronization is unavailable.
You can revoke permissions in Android settings.

Internet and network-state access support authentication and synchronization.
Synchronization-settings access supports the Android contact sync adapter.
Optional notification permission enables synchronization alerts; alerts do not
include contact values or account details. Denying notifications does not stop
synchronization.

Contako does not request location, microphone, call-log or SMS-reading permissions.
Opening a dialer, messaging app or email app from a contact passes the selected
number or address to that app; it does not let Contako read your calls or messages.

## Where data goes and who can access it

- **Proton:** authentication requests and contact synchronization go directly
  from your device to Proton over HTTPS. Contact protection follows Proton's
  format: some fields, including names and email addresses, are available to
  Proton in unencrypted form, while protected fields use Proton contact
  encryption. Do not assume every contact field is end-to-end encrypted.
  Proton also receives connection information such as your IP address and
  requests to its services. Proton's handling of data is described in its
  [privacy policy](https://proton.me/legal/privacy).
- **Android and contact-enabled apps:** system copies are stored in Android's
  ContactsProvider. System contact apps and other apps with authorized contact
  access may read them. These copies are decrypted data; Proton encryption does
  not protect them from applications with that access. Android and your device
  vendor govern aggregation and their own system services.
- **Apps and services you choose:** contact actions can open another app or a
  contact website. Exporting a diagnostic report writes it to the document
  provider you select, which may be local or cloud-based. These destinations
  apply their own privacy policies.
- **Project website and support:** following the GitHub links opens GitHub, which
  receives normal web connection information under
  [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
  Information you voluntarily submit in a support request is received by the
  maintainers and hosted by GitHub. Public issues are visible to others.

Contako has no developer-operated contact backend, advertising, usage analytics
or automatic remote crash reporting. It does not sell your personal data or
send your contacts or credentials to the developer. Included Proton libraries
provide authentication, networking and cryptography, not advertising tracking.

## Local storage and security

Contact data, pending changes and synchronization records are stored in the
Android app's private storage. The contact database is **not separately encrypted
by Contako**; it relies on Android's application sandbox and device security.
Session and key storage use the maintained Proton/Android protection mechanisms.

Contako disables Android backup and device-to-device transfer of its app-private
data. This exclusion does not govern copies held by Android's contact provider,
Proton, another app, or files you export yourself. Ordinary release builds disable
diagnostic logging of contact payloads and credentials. These measures reduce
exposure but cannot guarantee security on a compromised device.

## Retention, deletion and your choices

Local contacts and pending changes are retained while your Contako session is
in use. Signing out removes the scoped local data and Contako's Android copies.
Normal sign-out checks for pending edits; explicitly choosing to abandon them
discards unsynchronized local work. Removing the Contako Android account also
removes its scoped local data.

Signing out **does not delete your Proton contacts or your Proton account**.
Contact or group deletions made in Contako are synchronized to Proton when
delivery succeeds. You can also manage or delete contacts directly in Proton.
Contako does not create a separate online user account or offer deletion of your
Proton account; that account remains governed by Proton's own controls.

Android's clear-storage operation removes app-private data, including pending
edits and session data. Uninstalling removes app-private storage. Separately held
system records and exported files are governed by Android or the app/provider
holding them; review those locations if you want to remove every copy. Removing
local data does not remove remote Proton data.

Diagnostic summaries are generated only when requested. They contain the app
version, broad Android platform and count categories, permission state and overall
sync state, rather than contact values, account identifiers or credentials.
You choose whether and where to export them. Dismissing an on-screen report does
not delete a file already exported; delete it through its destination provider.
Contako does not automatically send reports to the developer.

## Contact and policy changes

For privacy questions, contact **Patmanak**, the Contako maintainer, through the
[project's GitHub issue tracker](https://github.com/patmanak/contako/issues).
Ask for a private channel before providing personal information; do not post
contacts, credentials, raw logs or other sensitive data in public issues.
Suspected security vulnerabilities follow the
[private reporting instructions](../SECURITY.md).

This policy will be updated when the application's data practices change.
The effective date above identifies the current policy; updates are published
on this page. Contako's About page links here; the same public URL is intended
for the application's Play Store privacy-policy field.
