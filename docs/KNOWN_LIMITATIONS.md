# Known limitations

These are current implementation or compatibility limits, not a development
schedule or a record of completed tasks. The [target specification](SPECIFICATION.md)
defines intended behavior; [regression coverage](REGRESSION_MATRIX.md) defines
repeatable checks. An unverified case MUST NOT be presented as passed.

## Contact synchronization

| Condition | Current behavior or limitation |
| --- | --- |
| Concurrent local/Web updates | The public directory has no remote modification timestamp. Incomparable updates favor the pending local edit under D-024/D-096; this is not guaranteed latest-edit-wins. |
| Private-only Web changes | A public-field fingerprint is not a complete card revision. Ordinary detection of changes that leave public fields unchanged is not fully qualified; a successful full repair does not establish it. |
| Samsung initial name baseline | An unresolved source-owned structured-name mismatch can leave an Android copy requiring attention even when the displayed contact looks correct. Similar labels or aggregation MUST NOT authorize forced baseline adoption or clearing DIRTY. |
| Public FN before private N | An isolated codec reproduction retains structured-name components but leaves canonical first/family fields empty. The regression is explicitly ignored until corrected; real phone/Web impact is unverified. |
| Yearless birthday edited on Web | An unrelated Proton Web save has removed a yearless date. Contako MUST NOT invent a year or silently restore a remote omission. |
| Custom dates and unsupported field types | Some values remain local/Android-only with an explicit indicator. Android exposes only representable fields and selected primary occurrences; see [mapping](CONTACTS.md). |

## Interface and runtime

- Android blocked-work notifications are not delivered: durable notification-claim
  storage exists, but no production publisher consumes it. Consult Sync.
- The diagnostic summary's version validator rejects the multi-part
  `-sync-diagnostic` suffix. Ordinary report generation and diagnostic-build export
  MUST NOT be treated as the same qualified path.
- CAPTCHA renderer termination/recovery is not fully qualified; accepted ordinary
  login, TOTP and CAPTCHA flows do not establish that failure path.
- Background timing depends on Android. The corrected framework-interruption
  crash does not establish the cause of a reported battery warning or qualify
  sustained unplugged operation on every OEM.
- The artwork has raster masters and provenance, but no fully editable layered
  or vector master. See [licensing](LICENSING.md).

## Security and qualification

Full-card/label HTTP coverage and early plaintext/native allocation bounds are
not universally established. Native dependency findings remain artifact-specific
and are not cleared by a version update; see [Security](SECURITY.md) and
[Dependencies](DEPENDENCIES.md).

Physical coverage varies across OEMs, accessibility/font/window configurations,
account group capabilities, concurrent edit/delete and interrupted sign-out/
provider/photo recovery. Some nominal cross-peer paths have been exercised;
this is not complete qualification of every origin/profile combination or
performance target. Login, TOTP and CAPTCHA have owner validation, attributed
as such rather than as a fresh automated run.

Exact build identity, observations and unavailable cases belong in the ignored
[run report](testing/RESULT_TEMPLATE.md). A public release summary MUST identify
its artifact and tested scope; an internal version or clean Git tree is insufficient.
