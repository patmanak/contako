# Contact field mapping

## Provider-local Android metadata

The exact MIME `vnd.android.cursor.item/rcs_data`, observed on Samsung, is opaque
provider-local data. While the raw contact is retained, ingestion and ordinary
projection MUST preserve these rows in Android without interpreting, uploading,
rebinding or replacing their values. They MUST NOT enter canonical contact/group
snapshots or projection write plans. Their presence alone MUST NOT block existing
contact synchronization. Account scope, complete-observation row bounds and raw-
contact version guards still apply, including concurrent metadata changes.
Other unsupported MIME types MUST retain their explicit unsupported handling;
this is not a general vendor-field allowlist. Native edits to supported contact
fields MUST still be durably ingested before acknowledging the observed version.

## Field contracts

Field and loss-policy contract. Implementation limits are listed in
[Known limitations](KNOWN_LIMITATIONS.md).

## Mapping policy

Compatible edits MUST preserve the association between imported clear-card
`ITEMn.CATEGORIES` metadata and the matching signed `ITEMn.EMAIL`.
When email order changes, both prefixes MUST be remapped together. This does not
prove arbitrary standalone CATEGORY persistence or resolve the broader D-124
compatibility gap; it preserves an observed imported association.

Every field family MUST define:

1. its canonical Contako representation;
2. Proton card or metadata representation;
3. Android projection;
4. whether Android edits are accepted as compatible deltas;
5. cardinality and ordering;
6. loss and preservation behavior;
7. deterministic round-trip fixtures.

When representations disagree, the canonical Contako value and a valid pending
local mutation win over an Android echo or stale raw card.

## Status legend

- **Delta:** Android may update the canonical family through controlled mapping.
- **Partial:** Android may update representable components; richer data remains
  preserved.
- **Projection:** Android receives data but is not trusted to rebuild it.
- **None:** no Android-native mapping.

## Canonical mapping matrix

| Field family | Canonical cardinality | Proton representation | Android representation | Android edit | Required rule |
| --- | ---: | --- | --- | --- | --- |
| Local identity | 1 | Contact ID / vCard UID | `SOURCE_ID` + private sync metadata | None | IDs are stable and never inferred from names |
| Display name | 1 | `FN` | structured-name display | Delta | Canonical display policy is deterministic |
| Structured name | 1 | `N` | `StructuredName` | Partial | Preserve prefix, middle, suffix, phonetics where supported |
| Email | 0..n | grouped `ITEMn.EMAIL` in the signed card | `Email` rows | Delta | Preserve order, type, preference, group identity, and Proton label IDs |
| Phone | 0..n | `TEL` | `Phone` rows | Delta | Normalize types without rewriting the number semantically |
| Postal address | 0..n | `ADR` | `StructuredPostal` | Partial | Preserve components Android omits or flattens |
| Organization | 0..n | `ORG`, `TITLE`, `ROLE` | `Organization` (often effectively 0..1 in editors) | Partial | Never delete extra Proton organizations from one Android row |
| Photo | 0..n | ordered `PHOTO`; Proton Web assigns `PREF` | one primary provider photo | Partial | Keep the full canonical gallery; project only the preferred photo |
| Logo | 0..n | `LOGO` when present | no reliable native contact-logo field | None | Preserve all values; show the preferred logo as a Contako badge |
| Note | 0..n | `NOTE` | commonly one `Note` row/editor | Partial | Project and edit only the canonical primary note; preserve all others |
| Nickname | 0..n | `NICKNAME` | commonly one `Nickname` row | Partial | Preserve unprojected values |
| Birthday | 0..1 | `BDAY` | repeatable event row with birthday type | Delta | Support full and yearless dates; preserve invalid duplicate imports outside the canonical value |
| Anniversary | 0..1 | `ANNIVERSARY` | repeatable event row with anniversary type | Delta | Support full and yearless dates; preserve invalid duplicate imports outside the canonical value |
| Other/custom date | 0..n | none generated; unknown imported extensions preserved | other/custom event rows with labels | Partial | Preserve labels/order and visibly mark as not synchronized with Proton under `D-059` |
| URL | 0..n | `URL` | `Website` rows | Delta | Preserve remote type tokens; unsupported/custom labels remain visibly local-only |
| Relationship | 0..n | `RELATED` / compatibility extension | `Relation` rows | Delta | Type mapping table required |
| Language | 0..n | `LANG` | none | None | Proton/local only |
| Time zone | 0..n | `TZ` | none | None | Proton/local only |
| Gender | 0..1 | `GENDER` | none | None | Structured standardized component plus optional identity text |
| Member | 0..n | Proton-compatible `MEMBER`; RFC group-card URI preserved | none | None | Advanced Proton compatibility field; never a Proton label/group assignment |
| Category | 0..n | `CATEGORIES` and item metadata | no exact native field | None | Do not confuse with Proton groups |
| Public key | 0..n | `KEY` | no safe standard Android contact field | None | Editable in Contako; accept public-key representations only, preserve preference, and reject private-key material |
| Proton group/label | 0..n per email | label metadata and email IDs | contact-level group membership | Partial | Approved projection policy required (`D-007`) |
| Unknown vCard property | 0..n | raw preserved card content | none/private metadata only | None | Must survive compatible edits |

The policy in this matrix is normative. The selected wire contracts and
conservative local-only fallbacks MUST be validated against synthetic Proton
cards and Android provider fixtures.

A text-only canonical organization with no structured component map MUST be
serialized as the first `ORG` component. An absent component map MUST NOT be
interpreted as an explicitly blank company/department pair. When structured
components are present, their exact ordered values remain authoritative and
the flattened display value MUST NOT replace them.

Under `D-112`, a postal address is edited and persisted as the seven ordered
`ADR` components: post-office box, extended address, street, locality, region,
postal code, and country. Its display value is derived from those components.
A legacy scalar-only Contako row is preserved verbatim as the street component;
Contako MUST NOT attempt to infer or split city, region, postal code, or country
from unstructured legacy text.

## Cardinality mismatch rules

For a field with many canonical values but one Android-editable occurrence:

- Contako MUST select a documented projected primary value;
- Android changing that occurrence MUST update only the mapped canonical value;
- unprojected canonical occurrences MUST survive;
- Android deleting the projected occurrence MUST follow a field-specific rule
  and MUST NOT implicitly delete hidden occurrences;
- projection fingerprints or stable item identities SHOULD prevent value-order
  changes from targeting the wrong occurrence.

Decision `D-027` applies by default: project one primary occurrence and allow
Android to change or remove only that mapped occurrence while preserving all
hidden repetitions. This limitation MUST be explained in the relevant Contako
editor/help text so users do not mistake Android's view for the complete data.

The primary occurrence is the lowest valid positive vCard `PREF` value. If no
valid preference exists, stable canonical order is used, with stable canonical
value identity as the final deterministic tie-breaker. Current Proton Web
source rewrites `FN`, `EMAIL`, `TEL`, `ADR`, `KEY`, and `PHOTO` preferences as
`1..n` when saving. Contako MUST preserve imported order for other repeatable
families and MUST NOT invent a preference change during a no-change sync.
If an imported repeatable family nevertheless carries equal internal order
values, a real compatible edit MUST compact only that family's duplicate
orders deterministically before canonical validation. Current order MUST remain
the first key and original canonical list position MUST be the stable
tie-breaker; identities, values, metadata, hidden occurrences, primary
selection, and relative order MUST survive. A no-change sync MUST NOT perform
this normalization or trigger an upload.

For photos, Contako MUST expose the ordered canonical gallery. Android receives
only the preferred photo. The Contako contact avatar uses that photo in a circle
and MAY overlay the preferred `LOGO` in a smaller bottom-right circular badge;
additional photos and logos remain available in the gallery/detail experience.
An imported remote HTTPS image reference remains canonical preserved data but,
under `D-111`, MUST NOT cause automatic background network retrieval.

For notes, Android receives only the primary note. An Android edit or deletion
changes only that note; additional notes are neither concatenated nor deleted.

### Newly selected local images

The sizing reference is the public Proton WebClients image-selection path at
revision `ab7b618446189f845060d061d670b767ce239254`:

- [Contact image modal](https://github.com/ProtonMail/WebClients/blob/ab7b618446189f845060d061d670b767ce239254/packages/components/containers/contacts/modals/ContactImageModal.tsx)
  passes both contact dimensions, JPEG, encoder quality 1 and `bigResize: true`.
- [Contact constants](https://github.com/ProtonMail/WebClients/blob/ab7b618446189f845060d061d670b767ce239254/packages/shared/lib/contacts/constants.ts)
  set the contact size to 180 pixels.
- [Image helper](https://github.com/ProtonMail/WebClients/blob/ab7b618446189f845060d061d670b767ce239254/packages/shared/lib/helpers/image.ts)
  returns the original if both dimensions already fit. Otherwise, `bigResize`
  uses the smaller reduction factor, targeting the short edge at 180 and
  retaining proportions. It is **not** a 180-by-180 bounding box or crop; when
  only the long edge exceeds 180, that branch can enlarge the image.

Contako adopts the 180-pixel short-edge target for newly selected PHOTO/LOGO
values, with a 1024-pixel long-edge cap for extreme aspect ratios. It MUST NOT
enlarge or crop the source. Dimensions retain proportions subject to pixel
rounding (minimum one pixel). For example, 2048x1024 becomes 360x180, while
8192x128 becomes 1024x16. An ordinary small image remains at its original size.

Contako retains its existing JPEG quality 90 and PNG output for decoded images
with alpha, preserving transparent logos rather than forcing JPEG. Encoded
orientation is applied, source metadata removed, and existing MIME, byte,
dimension and pixel-count limits remain enforced before draft mutation. This is
an Android adaptation of the public sizing rule, not byte-identical browser
encoding or proof of every deployed Web client version.

Only an explicit local picker selection goes through this transformation.
Imported/synchronized image bytes, existing images during unrelated edits,
remote references and separately generated Android projection copies MUST NOT
be resized by this selection policy. No new network fetch is introduced.

## Type and custom-label rules

Imported repeated singleton parameters `PREF`/`VALUE` MAY be interpreted when all
occurrences are valid and equivalent. `PREF` MUST resolve to the same integer
1..100; `VALUE` MUST resolve to the same case-insensitive token, allowing quoted
forms. Conflicting, empty, invalid or list-valued repetitions MUST remain rejected
with `VCARD_PARSE_DUPLICATE_PARAMETER`; no first/last-value winner is guessed.
The raw card remains unchanged in the preservation envelope. On an actual edit,
regenerated managed properties MUST emit one compatible VALUE declaration and
the usual single generated PREF; repeated TYPE and unknown parameters remain
preserved. Hydration/no-change sync MUST NOT enqueue a normalization upload.

Protocol rationale: at WebClients revision
`ab7b618446189f845060d061d670b767ce239254`,
[vcard.ts](https://github.com/ProtonMail/WebClients/blob/ab7b618446189f845060d061d670b767ce239254/packages/shared/lib/contacts/vcard.ts)
can reset a text date's property type and also set its VALUE parameter.
The [locked ical.js 1.5.0](https://github.com/ProtonMail/WebClients/blob/ab7b618446189f845060d061d670b767ce239254/yarn.lock)
then emits `BDAY;VALUE=text;VALUE=TEXT` on invented text-date input. This example
explains an equivalent repeated declaration; it does not identify the origin of
every imported duplicate parameter.

Proton/vCard types, Android numeric types, and user-visible labels are separate
concepts. Mapping MUST retain:

- normalized semantic type;
- original/custom label when available;
- preference/primary flag;
- stable item identity where Proton supplies one;
- unrecognized tokens for lossless rebuild.

Current maintained Proton WebClients exposes fixed standard item-type choices
and serializes a custom item label as a lowercase `x-*` `TYPE` token. Contako
MUST emit only these standard allowlists:

- email: no type, `home`, `work`, or `other`;
- telephone: no type, `home`, `work`, `other`, `cell`, `main`, `fax`, or
  `pager`;
- postal address: no type, `home`, `work`, or `other`.

Every generated email MUST have a unique `ITEMn` group, including when it has no
`TYPE`; this is property identity required by the maintained Proton save path.
For email and telephone values, a new custom label MUST be normalized to one
lowercase `x-<token>` value containing only ASCII letters, digits, and hyphens;
runs of spaces, punctuation, and unsupported characters become one hyphen,
leading/trailing separators are removed, and an empty result becomes
`x-custom`. Contako MUST NOT generate `X-ABLABEL`. Imported unknown/custom
`TYPE` tokens and historical `X-ABLABEL` lines remain unchanged in the
preservation envelope. Custom labels for unsupported field families and
unsupported standard types remain canonical/local, continue to project to
Android, and display a “not synchronized with Proton” state. Editing only the
field value MUST NOT erase preserved remote metadata.

### Structured component mapping

| Canonical component | Proton/vCard | Android | Loss policy |
| --- | --- | --- | --- |
| Display name | `FN` | structured-name display name | Canonical `FN` is shown; Android-derived display text does not overwrite an explicit richer `FN` without an actual edit |
| Prefix/given/additional/family/suffix | five ordered `N` components | prefix/given/middle/family/suffix | Component delta; empty trailing vCard components remain structurally valid |
| Phonetic given/middle/family | compatible validated extension if any | phonetic structured-name columns | Canonical/local/Android-only until Proton round-trip support is proven; never flatten into ordinary `N` |
| Organization units | ordered `ORG` components | company plus department | Preserve every vCard component even when Android exposes only company/department |
| Job title | `TITLE` | organization title | Controlled delta |
| Role/function | `ROLE` | job description when the editor exposes it | Preserve separately from title; Android must not collapse the two on rebuild |
| PO box/extended/street/locality/region/postcode/country | seven ordered `ADR` components | PO box/neighborhood/street/city/region/postcode/country | Map exact compatible components; preserve extended/unrepresented fragments and treat formatted address as derived |

### Email, postal, and website types

| Android type | Canonical/vCard type | Rule |
| --- | --- | --- |
| Email Home / Work | `HOME` / `WORK` | Exact bidirectional semantic mapping |
| Email Mobile | local Android semantic only | Proton WebClients does not expose `CELL` for email; do not emit it |
| Postal Home / Work | `HOME` / `WORK` | Exact bidirectional semantic mapping |
| Website Home / Work | `HOME` / `WORK` | Exact bidirectional semantic mapping |
| Other | Proton `OTHER` only for email/postal where maintained WebClients exposes it | Preserve original source tokens for other families |
| Custom | separate exact user label plus preserved source tokens | Email MUST use a normalized lowercase `x-*` `TYPE`; unsupported families remain local/Android-only |
| Android website Profile/Blog/FTP or another standard with no exact vCard semantic type | retain Android type plus normalized canonical meaning | Use a proven compatible Proton label only; never claim a false Home/Work meaning |

### Phone types

| Android type | Canonical/vCard tokens | Rule |
| --- | --- | --- |
| Home / Mobile / Work | `HOME` / `CELL` / `WORK` | Exact bidirectional mapping |
| Fax Home / Fax Work / Other Fax | `HOME,FAX` / `WORK,FAX` / `FAX` | Combination is preserved; Android priority is not allowed to drop `FAX` |
| Pager / TTY-TDD | `PAGER` / local Android `TEXTPHONE` | Emit only `PAGER`; preserve an imported `TEXTPHONE` but do not generate it without a maintained Proton contract |
| Work Mobile / Work Pager | `WORK,CELL` / `WORK,PAGER` | Combination is preserved even if an editor later displays a simpler type |
| Callback, Car, Company Main, ISDN, Main, Radio, Telex, Assistant, MMS | retained Android semantic plus optional validated custom label | No exact standard vCard type is assumed; apply the `D-066` label/fallback policy |
| Custom | exact user label | Apply the `D-066` label/fallback policy; blank labels degrade to Other without deleting the number |

When multiple vCard tokens map to one Android type, the canonical model MUST
retain the complete token set. Android changing only the number MUST NOT rewrite
that set. Android explicitly changing the type replaces only the mapped type
semantics and preserves unrelated unknown tokens unless they directly conflict.

### Relationship types

| Android type | Canonical/vCard `RELATED` type | Fidelity rule |
| --- | --- | --- |
| Child / Friend / Parent / Spouse | `child` / `friend` / `parent` / `spouse` | Exact semantic mapping |
| Brother / Sister | `sibling` | Preserve the original Android subtype locally so a no-change projection restores brother/sister |
| Father / Mother | `parent` | Preserve the original Android subtype locally |
| Relative | `kin` | Exact broader semantic mapping; retain the Android subtype |
| Assistant, Domestic partner, Manager, Partner, Referred by, Custom | exact Android semantic/custom label plus preserved remote tokens | No misleading RFC relationship is invented; remote label/type uses only `D-066`-validated encoding |

A remote relationship type without an exact Android constant MUST project as
Custom with a stable nonlocalized label while its original token remains in the
canonical envelope. A localized UI string MUST never become the stored wire
token.

### Date and preference metadata

Contact forms display full dates as `dd/MM/yyyy` and yearless dates as `dd/MM`
(D-140). Tapping a date field opens a calendar with manual entry and a no-year
option. Invalid dates MUST NOT be confirmed. A calendar leap-year anchor MUST NOT
be persisted as an implicit year; restoring a year requires explicit input or
selection. Cancel and unchanged confirmation MUST retain the original source
spelling. Unrecognized imported date forms remain visible verbatim until explicitly
replaced. Newly created or value-edited `BDAY`/`ANNIVERSARY` fields MUST use
the vCard 4 basic wire format (`YYYYMMDD` or `--MMDD`), as required by
[RFC 6350 section 4.3.1](https://www.rfc-editor.org/rfc/rfc6350.html#section-4.3.1).
Untouched imported date values MUST retain their original spelling and compatible
parameters, including during an unrelated edit. The form and Android projection
remain responsible for their own date presentation; no missing year is invented.

- Android Birthday maps only to `BDAY`; Anniversary maps only to
  `ANNIVERSARY`; Other/Custom follows `D-058`/`D-059`.
- Android `IS_PRIMARY`/`IS_SUPER_PRIMARY` may set canonical preference only
  when the provider signal is unambiguous. A boolean primary becomes `PREF=1`;
  remaining positive preferences are renumbered in stable canonical order only
  when a real reorder is committed.
- Android localized type labels are presentation. Canonical semantic enums,
  original source tokens, and exact custom labels are stored separately.

## Groups and labels

Android group membership is contact-level. Proton label assignment may be
email-level. Contako uses the owner-approved preferred-email projection from
`D-007`. Labels assigned only to non-preferred emails remain visible and
editable in Contako but are not projected as Android group memberships.

A contact without an email cannot receive a Proton group assignment. Contako
MUST preserve the contact, reject or remove only the impossible assignment, and
show the explanation approved by `D-056`. Distinct groups with the same display
name MUST remain distinct by stable identity under `D-057`. The unavailable
account-capability behavior is defined by `D-050` through `D-053`. Rename,
recolor, and group deletion follow `D-063`: confirmation names the group and
member count, then removes the group/assignments but never member contacts.

New contact groups MUST use the Proton contact-group palette default
`#8080FF` on the wire under `D-103`. Contako's `#6D4AFF` application-theme
primary is a separate UI token and MUST NOT be used as a contact-group wire
default. Imported Proton group colors MUST be preserved. Contako MUST submit
only a Proton-supported palette value for a deliberate recolor; a rejected or
unknown imported value MUST remain preserved and action-required rather than
being silently changed.

The shared primary-selection policy applies to
Proton serialization, Android contact rows, and Android group membership. Blank
email drafts are excluded from preferred-email selection. An unambiguous
Android primary change writes deterministic positive `PREF` ranks, while a
stale boolean flag or no-change pass MUST NOT invent a wire preference. Group
membership changes affect only `(contact identity, preferred email identity)`;
memberships attached to secondary emails and other contacts remain unchanged.
Provider work uses bounded account-scoped `Groups`/`GroupMembership` reads,
tombstones, durable locator resolution and group-inclusive fingerprints.
Acceptance is defined in [the functional plan](FUNCTIONAL_TEST_PLAN.md).

## Advanced-field editing

All known field families in the canonical matrix MUST be editable in Contako.
Android projection remains `None` for families Android cannot safely
represent; lack of Android projection MUST NOT make the Contako field read-only.

- `KEY` accepts only validated public-key/certificate URI, text, or data
  representations and MUST reject content identified as private key material.
- `LANG` uses validated language-tag syntax while preserving an imported value
  that Proton already accepts.
- `TZ` preserves the supported text, offset, or URI value form and MUST NOT
  silently convert between forms.
- `GENDER` uses structured field-aware editing and preserves optional identity
  text separately from the standardized sex component.
- `MEMBER` preserves RFC group-card URI form and also supports the maintained
  Proton WebClients repeatable string form as an explicit compatibility
  extension. It is not confused with Proton contact-group label assignment and
  is never interpreted as a navigable link without URI validation.
- `CATEGORIES`, `ROLE`, and `LOGO` remain distinct from Proton groups, job title,
  and primary contact photo respectively.
- Raw unknown properties MUST survive compatible edits but MUST NOT be offered
  through a raw-vCard editor.

Under `D-100`, verified hydrated Proton text crosses into the canonical mapper
unchanged after the bounded single-document vCard 4.0 envelope check. The
canonical mapper is the authoritative field and preservation parser: it MUST
retain compatible Proton extensions and unknown properties, and MUST apply all
of its existing structural, cardinality, size, Unicode-control, syntax, and
identity bounds. Hydration MUST NOT first round-trip remote text through a
generic serializer. Strict generic parse/serialization remains required for
newly generated upload cards, where Contako controls the syntax.

Under `D-105`, newly generated `PHOTO` and `LOGO` data-image properties MUST
declare `VALUE=uri` and the already validated raster MIME type through
`MEDIATYPE`. URI delimiters MUST remain URI syntax and MUST NOT receive vCard
text-value escaping. A compatible imported representation parameter MUST be preserved
without duplication. This rule applies to the upload vCard only; canonical
image bytes and Android's separately bounded projection copies remain governed
by the existing fidelity policy.

Imported Proton cards can retain vCard 3 binary image declarations inside a
vCard 4 envelope. Explicit base64 (`ENCODING=b` or `BASE64`) PHOTO/LOGO values
with an unambiguous supported raster media type MUST become canonical data URIs
so offline display and Android projection can use the bytes. The original wire
representation remains in the preservation envelope. Compatible uploads MUST
remove obsolete binary encoding/type declarations and emit the current URI and
media type; unrelated extensions MUST survive. Malformed, conflicting or unknown
declarations MUST remain preserved without guessing their media type. This
conversion MUST NOT fetch remote image URLs or bypass existing card/image bounds.

New/edited advanced values MUST use the `D-067` cardinalities and syntax. A
generated serialized card is limited to 10 MiB; scalar text to 16 KiB UTF-8;
URIs to 8 KiB; decoded public-key material to 1 MiB; and decoded image/logo input
to 10 MiB. Imported values beyond an editing limit remain preserved and are
read-only/action-required rather than silently truncated. A remote validation
failure MUST retain the local edit as pending/action-required and MUST NOT
silently delete or downgrade the value.

The preferred email is the lowest valid positive vCard `PREF`; stable canonical
order is the fallback. Android may change the selected primary when its editor
provides an unambiguous primary/super-primary signal. Such a change MUST reorder
preferences deterministically without changing unrelated email values or label
assignments.

## Minimal contact validation

The current `D-031` contract distinguishes identity lifecycle state:

- a new local/Android contact MUST have at least one nonblank first name, last
  name, or display name before Proton creation; organization and email do not
  satisfy this predicate, and email is optional;
- an existing Proton-identified contact MAY be nameless and MUST remain
  representable, editable, and synchronizable;
- Contako MUST NOT fabricate a name, silently delete the contact, or convert an
  existing nameless remote contact into a new-contact draft;
- if the deployed API rejects an otherwise Web-valid nameless update, the edit
  remains durable and action-required rather than being stripped.

## Edge policies

Decisions `D-054` through `D-058` define directory, group and date edge policies.
The date model distinguishes Android's repeatable event rows from vCard's
standard property cardinalities.
`D-059` selects the visible local/Android-only fallback because maintained
Proton WebClients exposes no labeled custom-date family.

| Decision | Approved behavior |
| --- | --- |
| `D-054` directory scope | Contako lists only contacts owned by its connected Proton account, not an aggregate of every Android account |
| `D-055` incomplete Android contact | Retain it as a durable action-required local draft, list it in Sync, and defer upload until it satisfies validated Proton requirements |
| `D-056` group without email | Disable the Contako action, never invent an email, remove an impossible external Android assignment during controlled reprojection, and document why in-app |
| `D-057` duplicate group names | Preserve stable-ID-distinct remote duplicates and warn before deliberate duplicate creation; never merge by name |
| `D-058` typed date events | One standard Birthday, one standard Anniversary, and repeatable labeled Other/Custom events; unsupported custom events remain local/Android-only with a visible status and are never deleted silently |

Android `ContactsProvider` stores dates as repeatable event rows and supports
Birthday, Anniversary, Other, and Custom types with a custom label. RFC 6350
defines both `BDAY` and `ANNIVERSARY` with cardinality `*1` (zero or one), and
permits yearless date values. Public Proton Mail Android models inspected for
this specification likewise expose singular Birthday and Anniversary values.
Contako MUST synchronize full and yearless standard Birthday/Anniversary values.
Public Proton Mail source models a date with optional year, month, and day and
also permits a textual date representation. Contako MUST preserve additional
Android event rows locally and project them back to Android, while displaying
that they do not synchronize with Proton. It MUST NOT generate a guessed custom-
date extension; unknown imported extensions remain in the preservation envelope.

Android event readback MUST retain nonblank date-time, partial and textual values
verbatim; it MUST NOT invent missing calendar components or coerce a timezone.
Full and yearless calendar-shaped dates still receive calendar validation, and
empty/control-bearing values and oversized rows remain rejected. This provider
read policy MUST NOT relax validation of newly authored Proton standard dates.
Unchanged imported values MUST keep their preservation metadata through unrelated
native edits.

For a display-only name, projection verification MAY ignore Android-generated
structured parts only when contact/value identities and the display name match,
all desired structured parts are empty, and the complete word multiset matches
after splitting whitespace, dots and commas. Case, accents, hyphens, apostrophes
and repeated words remain significant. Explicit canonical parts MUST NOT be
normalized away. Ingestion MUST retain and compare the real provider baseline,
so subsequent native name edits (including reordered parts) remain observable.

## Preservation envelope

Under D-137, Contako editor Save MUST populate a blank display name from the
current trimmed given/family names joined with a single space, omitting blank
parts. This is a durable canonical value before upload, not just a visual fallback.
An explicit display name MUST remain independent of structured names. If both
name parts are empty, this rule MUST NOT persist a fabricated "Unnamed contact"
value; ordinary missing-name validation still applies. Consultation/import alone
MUST NOT mass-rewrite names.

Explicit given/family-name edits MUST update the effective preserved `N`
occurrence as well as the canonical name fields. Its hidden middle/prefix/suffix
components, parameters and other occurrences MUST survive compatible edits.
An unchanged name MUST NOT be reconstructed merely because another field changes.

On a native structured-name edit, retain a distinct canonical alias when the
observed display is exactly the new nonempty prefix/given/middle/family/suffix
concatenation and the prior provider display still equals that alias. This narrow
rule handles Google Contacts' derived display replacement; it MUST NOT suppress
a display-only edit or a new non-derived display name. A display that already
matched the prior structured concatenation follows the explicit native rename.
Do not use this rule to normalize unrelated fields or bypass projection checks.

The application SHOULD patch a preserved Proton card rather than regenerate an
unrelated minimal card, provided that:

- canonical visible values remain authoritative;
- signatures/encryption are recreated using public Proton primitives;
- stale visible properties are not resurrected;
- unsupported and unknown properties survive;
- malformed or unverifiable content is quarantined rather than trusted.

## Required fixture suite per field

Imported public `EMAIL` and validated public `KEY` properties MAY retain
provenance in a clear (type 0) or signed (type 2) source card. Their edited output
MUST use the existing signed card, preserving the exact value's compatible
parameters/group decoration. Input provenance MUST NOT be confused with the
required output protection. Private-card provenance (types 1/3), a forged value
identity or a mismatched source property MUST NOT be accepted by this allowance.
Clear email-to-category associations MUST survive the upgrade and reordering;
unknown clear/private properties MUST remain in their existing partition.

Each field family needs:

1. Proton card to canonical parse;
2. canonical create to Proton card;
3. Proton edit to canonical merge;
4. Contako offline edit, restart, and upload;
5. Android edit to canonical controlled delta;
6. canonical projection back to Android;
7. second no-change sync with no mutation;
8. unknown-property preservation;
9. cardinality mismatch case;
10. deletion of the projected primary while hidden values remain.
