# Shared regression dataset

Version 1. All values are invented for this repository: no exported personal
contact, private identifier, credential, key or personal photo is included.
Emails use example.test, URLs use reserved .test hosts, and phone examples use
the fictional +1-202-555-01xx range. Do not call/message these fixtures.

The source of truth is [catalog.json](catalog.json), indexing exactly three
multi-contact JSON datasets: [nominal](nominal.json),
[compatibility](compatibility.json) and [boundaries](boundaries.json).
Each embeds its UTF-8 plaintext vCard fragments, so no file is needed per contact.
The datasets supply independent expected names/emails/notes and selected values,
explicit email-to-group memberships, case IDs and transport-layout recipes.
These are test data, not cryptographically signed or encrypted files: card kinds
describe the input to the codec/maintained crypto fixture. A Web import may
normalize syntax and sign cards; it does not prove the requested crypto layout.

| Profile | Purpose |
| --- | --- |
| C01 | Minimal creation, offline edits, deletion/restart |
| C02 | Display-only name and Android-generated components |
| C03 | Distinct alias, five name components, two notes, two emails with different groups |
| C04 | Rich preservation: accented compound name, unknown property/parameter, organization/title/role, three phones, two seven-part addresses, URL/relation, full leap dates, gender, language/time zone and image |
| C05 | Legacy clear EMAIL/custom parameter/group provenance plus encrypted note |
| C06 | Unsigned encrypted private card; invalid-signature tests remain separate |
| C07 | Equivalent repeated PREF/VALUE parameters |
| C08 | Yearless leap birthday/anniversary; known Web limitation, with C04 full-date control |
| C09 | Textual imported date, unchanged through unrelated edit |
| C10 | Explicit legacy binary PHOTO/LOGO and retained image parameter |
| C11 | Remote image references that MUST NOT be fetched automatically |
| C12 | Long multiline note, accents (including decomposed forms), Japanese/Arabic/Greek, emoji, apostrophes, escaped punctuation/backslash, custom labels, address and Unicode URL |
| C13 | Named contact without email; group assignment stays disabled |
| C14 | Overlap with C03 from a separate owned Android source; excluded from ordinary Proton import |
| N01/N02/N03 | Conflicting VALUE, conflicting PREF, nested/malformed envelope: isolated rejection tests only |

G01/G02 belong to C03's primary email, G03 to its secondary email. Android MUST
project only G01/G02 for C03. C04 has a different membership distribution; G04 is
empty. The catalog is authoritative for all edges. Remove G01 during the native
phone-edit regression; restore it only as an explicit setup operation. Renaming,
duplicate group names, deletion and palette variation are actions, not silently
merged seed groups. Default #8080FF is a supported Proton group color.

## Prepare a local import pack

From app/, use Python 3.11+ (standard library):

```text
python qa/dataset/prepare.py --check
python qa/dataset/prepare.py --profile core --output build/qa-dataset/core-run-1
python qa/dataset/prepare.py --profile load-300 --output build/qa-dataset/load-run-1
python qa/dataset/prepare.py --profile load-5000 --output build/qa-dataset/load-large-run-1
```

The tool writes only to a new/empty directory below app/build and never contacts
the phone or Proton. Core emits two multi-contact import files (nominal.vcf: six
contacts; compatibility.vcf: seven contacts), a native-only.vcf (C14, excluded from
Proton import), a groups.json membership oracle, and generated nonuniform PNG picker images
A/B/C, small/panorama and transparent-logo variants. Tiny inline PNGs in source
cards are wire-format fixtures, not the full-photo/thumbnail acceptance images.
Use the generated large images for that journey. EXIF-rotated JPEG and special
decoder formats require their own controlled fixture; PNG selection is not EXIF
qualification. Load profiles create exactly 300/20 or 5000/200 contacts/groups,
with deterministic emails and memberships in ONE contacts.vcf each. Every fifth
load contact also includes two emails, organization, full birthday,
address and a long accented/multilingual note. The first 100 rich contacts also
have distinct fictional phone numbers; shared phones MUST NOT accidentally turn
the volume case into one huge Android aggregate. These are optional load tests;
5000 contacts do not create 5000 files. Malformed N01–N03 stay in boundaries.json
for isolated rejection checks, never in generated imports.

The JVM RegressionDatasetTest consumes these same three datasets and verifies
independent expected values, exact Unicode/newline preservation, compatible edit
round trips, per-email groups and negative rejection. This qualifies the codec,
not the behavior of Proton Web, encryption/signatures or an Android OEM.

Known isolated finding: public FN before private N leaves canonical firstName/
lastName empty although the structured-name value retains both. The executable
canonicalNamesMustNotDependOnPublicPrivateCardOrder case is explicitly ignored
until a separate production fix; it is NOT a pass. The active checks compare
the independently authored structured components and their preservation.
Actual phone/Web impact remains unverified; do not reorder inputs to hide it.

## Install, observe and clean up

1. Confirm the dedicated account independently in Contako and Web and identify
   the physical target. Never seed a personal account based only on package name.
2. Use a fresh owned fixture scope. If the prefix already exists, stop and inspect
   the local run ledger; do not import again or delete by prefix alone. Record
   pre-existing counts only as context, never as deletion authority.
3. Create C01/C02/C03 through the actual UI for creation-origin cases. For imported
   cases, import the generated selected .vcf through Proton Web. Bulk core import
   is setup only, not proof of Contako/native creation. Negative files are never
   included. Avoid importing C14 into Proton.
4. Create the four groups through the real UI and apply each exact email edge in
   groups.json; the pack deliberately omits grouped CATEGORIES during import.
   Do not assume that a vCard category creates a Proton contact group. Keep actual
   remote/provider IDs only in the ignored local ledger. Read back per-email
   membership in Web and preferred-email membership in Android.
5. Record initial field values in each applicable peer. Run one case at a time,
   comparing both the intended change and fields that MUST remain unchanged.
   Verify card provenance separately for C05/C06/C07; record NOT RUN if the deployed
   import path cannot retain it. Never forge a signature to obtain a live fixture.
6. Delete only run-owned contacts/groups by individually recorded identities,
   verify absence in all peers, then no-change sync/restart. Preserve unrelated
   accounts. Document intentional retained fixtures instead of claiming cleanup.

RCS/aggregation, interrupted photo journals, stale bindings, deleted-group receipts,
lost acknowledgements and genuine native edits need the setup in the
[software](../../../docs/SOFTWARE_TEST_PLAN.md) and
[target](../../../docs/FUNCTIONAL_TEST_PLAN.md) plans. A static snapshot cannot
replace those sequences. See [regression mapping](../../../docs/REGRESSION_MATRIX.md).

Original fixture recipes and tooling follow the repository GPL-3.0-or-later
license. Generated checker images are original procedural test patterns.
