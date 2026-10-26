# Known limitations

These are current implementation or compatibility limits, not a development
schedule or a record of completed tasks. The [target specification](SPECIFICATION.md)
defines intended behavior; [regression coverage](REGRESSION_MATRIX.md) defines
repeatable checks. An unverified case MUST NOT be presented as passed.

## Contact synchronization

- Targeted recovery retains controlled engine, Room and provider-seam regression
  cases. Execution on a physical device and real Proton, including interrupted
  photo publication and delayed scheduling, requires separate qualification;
  compilation and simulated receipts do not establish those behaviors.
  Earlier account-health records without durable item attribution remain
  conservative until a normal synchronization refreshes that attribution; a
  targeted result does not erase an independent or unidentified account failure.

| Condition | Current behavior or limitation |
| --- | --- |
| Concurrent local/Web updates | A targeted complete-card read blocks conflicting uploads and Sync offers a version choice. The GET-to-write window remains non-atomic. Both choices and stale-choice rejection have scoped physical coverage; this does not qualify every field, account or OEM combination. |
| Conflict field comparison | Choices apply to the whole contact. Repeated occurrences without reliable identity stay unpaired; missing group context and referenced-media contents stay unknown. Source cards are preserved internally rather than exposed as fields. The preview describes canonical preservation rules, not an exhaustive decoded diff of raw source cards or a current confirmation from either peer. |
| Field compatibility help | Rules describe generated-format and theoretical projection support, with current cached capability and permission conditions. They do not certify every contact editor, resolve/download media, validate arbitrary imported payloads or prove delivery. Unknown vCard data remains read-only; opaque provider data stays in Android. |
| Private-only Web changes | Public fingerprints alone cannot detect these edits. The current implementation supplements them with the contacts v6 event feed and a durable cursor. Note-only edits have scoped physical/Web coverage, including restart and an empty follow-up. Earlier distributed builds without this feed can miss private-only edits. This does not qualify every private-field variant or the non-atomic remote-write window. |
| Concurrent remote deletion | An earlier update choice is invalidated and the local draft retained. The two-version update panel does not resolve a remotely deleted contact. |
| Pending group assignments | Choosing Proton is unavailable when it removes an email needed by independent pending group assignments; those assignments must be resolved first. |
| Composite organization edits | Successive title/role edits can leave an Android copy requiring attention. Repair synchronization may recover the copy; verify the values in each peer afterward. Recovery does not establish that the underlying binding issue is fixed. A clean raw-contact flag does not establish completion; the binding guard MUST NOT be bypassed. |
| Samsung initial name baseline | An unresolved source-owned structured-name mismatch can leave an Android copy requiring attention even when the displayed contact looks correct. Similar labels or aggregation MUST NOT authorize forced baseline adoption or clearing DIRTY. |
| Provider photo transformations | New synchronous photo copies retain a durable receipt of the actual provider representation, separately from the original. A supplier with unexpected version increments, delayed display-file publication or an unverified interrupted legacy write remains pending. The new transaction boundary requires physical OEM qualification; a visually correct photo alone does not prove convergence. |
| Missing owned Android copy | Normal sync checks metadata before skipping a stored clean copy. A missing or rebound source delegates to guarded retry/repair; it does not authorize blind recreation, rebinding or adoption of a linked foreign contact. |
| Yearless birthday edited on Web | An unrelated Proton Web save has removed a yearless date. Contako MUST NOT invent a year or silently restore a remote omission. |
| Custom dates and unsupported field types | Some values remain local/Android-only with an explicit indicator. Android exposes only representable fields and selected primary occurrences; see [mapping](CONTACTS.md). |
| Local labels on unsupported field types | Remote adoption retains labels with explicit local provenance and an unambiguous unchanged value occurrence. Historical labels without that proof, or repeated identical values with ambiguous mappings, cannot safely be restored. Remote deletions and explicit remote labels remain authoritative. URL values and labels require separate checks. |

## Interface and runtime

- Remote-deletion recovery keeps local drafts on this installation; it is not a
  Proton backup or a general trash folder. Opaque Android-only payloads cannot
  be captured as canonical draft data and block removal of the old source.
  Ambiguous clear-card categories, unavailable groups and divergent assignments
  on repeated identical email addresses can block recreation rather than silently
  merge occurrences. If new canonical/group intent arrives after a draft was
  preserved, both versions remain retained for review; no automatic draft merge
  is attempted. Remote presence checks do not provide an atomic server-side
  precondition against a change between the check and a later write.

- Contact confirmation details retain per-contact proof only from explicit
  verification/acknowledgement boundaries. Earlier contacts may show unknown
  confirmation until a new qualifying observation occurs; ordinary unchanged
  sync MUST NOT fetch every full card merely to populate these dates. Proof
  times are local observation times, not server edit timestamps or live peer
  equality. Contact evidence does not confirm independent email-group writes.
  Real fault/physical accessibility qualification remains distinct from the
  controlled Room/provider and interface regressions.

- The import-availability overview has deterministic state coverage and retained
  Room/Compose integration tests. Physical first-import navigation, recreation,
  large-font and accessibility behavior still require separate qualification.
- Actionable synchronization advice uses current closed causes. Generic internal
  or validation failures may still lack a field-level cause; the UI deliberately
  leaves that detail unknown. New issue-card navigation and accessibility need
  physical qualification separately from the policy and navigation regressions.

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
- Local directory benchmarks include Compose test synchronization overhead and
  broad benchmark-only R8 keep rules. They do not establish every release latency
  target, large-photo decoding, cold-process startup, native memory peaks or
  unplugged battery performance. The legacy rendered-search oracle includes
  repeated test synchronization; its timings MUST NOT be compared directly with
  the single displayed-layout observation in protocols v4/v5. V5 verifies every
  identity against model lists captured before the clock stops. Scoped reference
  runs meet the warmed-search target with these protocols, but reopening a ViewModel over 5,000
  contacts still exceeds 500 ms. The first nonblank query prepares a lazy index
  and may exceed 100 ms; warmed searches do not qualify that preparation cost or
  a query following snapshot replacement. See the
  [measurement protocol](QA.md#performance-measurements).
- The artwork has raster masters and provenance, but no fully editable layered
  or vector master. See [licensing](LICENSING.md).

## Security and qualification

The durable local owner protects retained data after session revocation. Legacy
nonempty data without an owner marker or retained session cannot be assigned to
a fresh login safely and remains quarantined. Do not remove application data to
dismiss this state without preserving needed local changes.

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
