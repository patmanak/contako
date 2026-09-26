# Contako

Contako is an independent Android application that synchronizes Proton contacts
with Android's system contacts and lets you manage them from your phone.

## Features

- Browse and search your contacts offline, with alphabet navigation.
- Create, edit and delete contacts, including repeated fields and contact images.
- Manage Proton groups and assign them to individual email addresses.
- Use compatible system contact applications to edit the Contako contact account.
- Synchronize local changes automatically, inspect pending work and request repair.
- Sign in with a Proton account, including TOTP 2FA and CAPTCHA.
- Choose light, dark or system appearance and one of eight interface languages.
- Inspect separate Proton-upload and Android-copy queues and export a local,
  sanitized diagnostic summary from Sync.

Android 12 or later is required. One Proton account is supported at a time.
The current application is a testing candidate; see [known limitations](docs/KNOWN_LIMITATIONS.md)
for known limitations. The internal version is not a declaration of a public release.

## Using Contako

Start with the [user guide and FAQ](docs/USER_GUIDE.md): first synchronization,
editing in Android, email-specific groups, pending work and safe troubleshooting.
The [target specification](docs/SPECIFICATION.md) defines product scope and contracts.
The [changelog](CHANGELOG.md) summarizes features added or improved by version.

Important limits: Android exposes only the fields its contact provider supports;
Proton groups are assigned per email; automatic sync timing depends on Android.
An unresolved Samsung name-baseline case and yearless-date loss during Proton Web
edits remain documented in [Known limitations](docs/KNOWN_LIMITATIONS.md).
Physical qualification is scoped by device and scenario; see [QA](docs/QA.md).

## Build and test

Use the checked-in Gradle wrapper and Android SDK. Start with the
[build guide](app/README.md), then the
[QA guide](docs/QA.md): unit tests, software integration and physical phone/Proton
tests have separate plans and shared synthetic regression datasets.

For project work, read the [development guide](docs/DEVELOPMENT.md).
[Product scope](docs/PRODUCT.md), [architecture](docs/ARCHITECTURE.md),
[synchronization](docs/SYNCHRONIZATION.md) and
[field mapping](docs/CONTACTS.md) describe the current contracts.

## Contributing and reporting problems

See [Contributing](CONTRIBUTING.md) for bug reports, improvements and pull requests.
Report suspected vulnerabilities privately using [the security policy](SECURITY.md).

## Privacy and licensing

Contako has no analytics, advertising SDK or remote crash reporting. Contact
data is stored in the Android application sandbox and projected into Android
contacts. App-private contacts are deliberately not encrypted separately at
database level. Credentials use the protected Proton/Android storage lifecycle.
Android backup and device transfer of application data are disabled.
See [security](docs/SECURITY.md).

Contako is licensed **GPL-3.0-or-later**. See [LICENSE](LICENSE),
[licensing and provenance](docs/LICENSING.md) and
[third-party notices](app/legal/THIRD_PARTY_NOTICES.md).

Contako is not affiliated with, endorsed by, or sponsored by Proton AG.
