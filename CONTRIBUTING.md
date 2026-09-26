# Contributing to Contako

Contako manages Proton contacts and synchronizes them with Android system
contacts. Contributions should follow the [target specification](docs/SPECIFICATION.md)
and preserve existing contact data. Discuss substantial feature or architecture
changes in an issue before starting a large pull request.

## Report a bug or suggest an improvement

Check [known limitations](docs/KNOWN_LIMITATIONS.md) and existing issues first.
For an ordinary bug, include:

- Contako version and installation source, Android version and phone model.
- The application where the action began: Contako, Proton Web or the system editor.
- Steps to reproduce, expected behavior and actual behavior.
- The type of field changed and whether it was added, replaced or deleted.
- Whether the problem reproduces with synthetic contacts in a dedicated test account.

Reports MUST NOT include real contact values, credentials, tokens, private keys,
account addresses, device serials, database exports or raw device logs. Screenshots
MUST exclude personal information. An anonymized example should be a new synthetic
contact, not a lightly redacted export of somebody's address book. Do not delete
contacts, reset an account or reinstall the application just to prepare a report.

Suspected vulnerabilities MUST use the private route in [Security](SECURITY.md),
not a public issue or pull request containing exploit details.

## Prepare a change

Use [the build guide](app/README.md) and [development workflow](docs/DEVELOPMENT.md).
Keep changes small and focused. Public documentation and source are English;
user-facing text belongs in the existing localized resources.

Room is the local canonical store, Proton the remote peer, and Android an editable
projection. Changes MUST preserve durable pending edits, account isolation,
unknown contact fields, provider ownership and the shared serialized sync engine.
Preserve Room migration history, dependency checksums and full third-party notices.
Do not add real account data, signing keys, build outputs or temporary work logs.

## Validate and submit

Follow [QA](docs/QA.md): run checks appropriate to the changed scope and reuse
the shared datasets and regression cases. Functional sync or UI changes need
physical-phone and independent Proton/system observations when available.
A JVM pass or APK build MUST NOT be described as a successful real-account journey.

A pull request should state the problem, resulting behavior, relevant validation
and any checks that could not be run. Update the applicable user documentation
and regression case when behavior changes. Keep raw evidence local; include only
sanitized results needed to assess the change. Documentation-only changes need
link and consistency checks rather than a new device campaign.

Original contributions use the project's GPL-3.0-or-later license. Retain the
license and attribution of reused code and artwork; see [Licensing](docs/LICENSING.md).
