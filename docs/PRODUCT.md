# Product contract

Contako manages Proton contacts and makes their representable fields available
to Android applications through a writable system contact account. This is the
product target; [known limitations](KNOWN_LIMITATIONS.md) describe current gaps.

## Features

- The app MUST support one active Proton account and show only its owned contacts.
  Other Android address books MUST NOT be adopted, merged or edited by Contako.
- Cached browsing and local search MUST work offline. Creation, editing, deletion
  and per-email group assignments MUST persist intent before upload.
- Contact detail/editing MUST support repeated values, primary selection,
  structured/display names, photos/logos and the advanced fields in
  [the mapping contract](CONTACTS.md). Compatible edits MUST preserve unknown
  and unrepresentable fields.
- Proton groups belong to email addresses. Android MUST project memberships from
  the preferred email only and preserve assignments on other emails.
- Automatic synchronization, manual incremental sync and explicit full repair
  MUST share one account-scoped engine. Pending, retry and action-required states
  MUST distinguish Proton delivery from Android projection.
- Authentication MUST support login, TOTP/recovery-code proof, human verification,
  session restoration and revocation. Unsupported security-key-only authentication
  MUST provide an actionable message.
- Sign-out MUST protect pending local/native work and remove only scoped local
  and Android data, never remote Proton contacts.
- Contact permission MUST be checked at startup. Denial MUST leave local/Proton
  browsing and editing available with an explanation that Android sync is paused.
- Successful ready login MUST schedule synchronization. Background work MUST
  respect system eligibility; no exact periodic execution deadline is promised.
- Persistent actionable blocks MUST follow the notification policy in
  [Decisions](DECISIONS.md); lack of delivered notifications is a current limitation.
- Users MUST be able to generate a sanitized local diagnostic summary explicitly.
  There MUST be no analytics, advertising or remote crash reporting.

## Platform and presentation

Android 12/API 31 is the minimum. Build SDK levels are recorded in the build
configuration; supported OEM/API claims require physical evidence.
English, French, German, Spanish, Italian, Dutch, Polish and Portuguese are
supported. The app MUST offer its own light/dark/follow-system palette, readable
large text and accessible compact/expanded layouts. See [UI contracts](DESIGN.md).

Group capability restrictions MUST disable only unavailable group features, not
ordinary contact synchronization. A subscription name alone MUST NOT establish
group capability; classified API authorization and maintained explicit signals
are authoritative.

## Performance targets

Measured on a documented physical reference configuration with controlled
cache/network conditions; these are targets, not guaranteed background deadlines.

| Operation | Target |
| --- | --- |
| Cached content | 500 ms P95 |
| Local search | 100 ms P95 |
| Durable local save | 300 ms P95 |
| Ordinary no-change synchronization | 5 seconds |
| Ten-contact delta | 15 seconds |
| Initial 300-contact import | 90 seconds |
| Full 5,000-contact repair | 15 minutes |

The nominal profile has 300 contacts and 20 groups, with roughly ten contacts
per group. Robustness qualification extends to 5,000 contacts and 200 groups.
[QA](QA.md) defines measurement and correctness checks.

## Exclusions

Multi-account operation, manual vCard import/export, automatic duplicate merging,
a separate biometric/PIN app lock, FIDO login, an email client and synchronization
of unrelated address books are outside the target. Frequency/sort pickers,
Android dynamic color and tablet-exclusive features are not requirements.

Activity history, extra filters, batch operations, integrated news/help feeds and
an in-app updater are not committed target features. Their mention in an older
proposal MUST NOT be interpreted as implemented behavior or release scope.

See [the user guide](USER_GUIDE.md) for using the implementation, and
[licensing/distribution](LICENSING.md) for publication requirements.
