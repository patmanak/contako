# Known limitations

These are current implementation or compatibility limits, not a development
schedule or a record of completed tasks. The [target specification](SPECIFICATION.md)
defines intended behavior; [regression coverage](REGRESSION_MATRIX.md) defines
repeatable checks. An unverified case MUST NOT be presented as passed.

## Contact synchronization

| Condition | Current behavior or limitation |
| --- | --- |
| Concurrent local/Web updates | A targeted complete-card read blocks conflicting uploads and Sync offers a version choice. The GET-to-write window remains non-atomic. Both choices and stale-choice rejection have scoped physical coverage; this does not qualify every field, account or OEM combination. |
| Private-only Web changes | Public fingerprints alone cannot detect these edits. The current implementation supplements them with the contacts v6 event feed and a durable cursor. Note-only edits have scoped physical/Web coverage, including restart and an empty follow-up. Earlier distributed builds without this feed can miss private-only edits. This does not qualify every private-field variant or the non-atomic remote-write window. |
| Concurrent remote deletion | An earlier update choice is invalidated and the local draft retained. The two-version update panel does not resolve a remotely deleted contact. |
| Pending group assignments | Choosing Proton is unavailable when it removes an email needed by independent pending group assignments; those assignments must be resolved first. |
| Composite organization edits | Successive title/role edits can leave an Android copy requiring attention. Repair synchronization may recover the copy; verify the values in each peer afterward. Recovery does not establish that the underlying binding issue is fixed. A clean raw-contact flag does not establish completion; the binding guard MUST NOT be bypassed. |
| Samsung initial name baseline | An unresolved source-owned structured-name mismatch can leave an Android copy requiring attention even when the displayed contact looks correct. Similar labels or aggregation MUST NOT authorize forced baseline adoption or clearing DIRTY. |
| Yearless birthday edited on Web | An unrelated Proton Web save has removed a yearless date. Contako MUST NOT invent a year or silently restore a remote omission. |
| Custom dates and unsupported field types | Some values remain local/Android-only with an explicit indicator. Android exposes only representable fields and selected primary occurrences; see [mapping](CONTACTS.md). |

## Interface and runtime

- Full TalkBack traversal remains unqualified. It is a non-blocking RC
  qualification item; this MUST NOT be presented as verified screen-reader
  accessibility.
- Sync notifications use Android permission/channel settings and durable delivery
  tracking. The physical service boundary has scoped coverage for permission
  denial, generic publication, persistence and cancellation. This does not qualify
  natural 24-hour delivery or an actual notification tap in every lifecycle state.
- CAPTCHA renderer termination/recovery is not fully qualified; accepted ordinary
  login, TOTP and CAPTCHA flows do not establish that failure path.
- Background timing depends on Android. The corrected framework-interruption
  crash does not establish the cause of a reported battery warning or qualify
  sustained unplugged operation on every OEM.
- A ten-contact native delta can exceed the 15-second performance target,
  including during an online foreground start. Successful convergence does not
  establish that latency target; network, startup and subsequent serialized work
  MUST remain distinct when measuring it.
- The artwork has raster masters and provenance, but no fully editable layered
  or vector master. See [licensing](LICENSING.md).

## Security and qualification

Contact/label HTTP responses and aggregate directory acquisition are bounded.
Accumulated decrypted contact bytes are bounded by the maintained streaming
reader before normalization. This does not bound all native allocations or total
process memory. Native dependency findings remain artifact-specific
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
