# Reporting a security vulnerability

Use [GitHub private vulnerability reporting](https://github.com/patmanak/contako/security/advisories/new)
for suspected vulnerabilities in Contako. The repository's Security tab provides
the same route through "Report a vulnerability". Reports are reviewed privately
with the maintainers before agreeing on a fix and public disclosure.

If the private reporting option is unavailable, do not post vulnerability details
publicly. Open an issue requesting that private reporting be enabled, without
describing the exploit, affected private data or reproduction details. Wait for
a private channel before sharing them.

## What to include

- The exact Contako version or source commit, installation source, and relevant
  Android version/device family.
- A concise description of the issue, required conditions and potential impact.
- Reproduction steps using a dedicated account and synthetic contacts.
- A minimal proof of concept when safe, with expected and observed results.

Reports MUST NOT contain passwords, OTP/recovery values, session tokens, private
keys, real contact payloads, database exports or raw device logs. Do not access
other people's data, disrupt Proton's services or change account security settings
to demonstrate a problem. A weakness in Proton itself should be reported through
Proton's own security reporting process.

Fixes are developed on the current main branch. Include the affected version so
maintainers can assess its scope; no response deadline or backport schedule is
promised. Ordinary bugs and feature requests follow [Contributing](CONTRIBUTING.md).

For the application's protection model and documented limits, see
[Security design](docs/SECURITY.md) and [Known limitations](docs/KNOWN_LIMITATIONS.md).
