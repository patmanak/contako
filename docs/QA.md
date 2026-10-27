# QA guide

Use three complementary levels. A pass at one level MUST NOT be presented as
evidence at another.

| Level | Plan | Evidence |
| --- | --- | --- |
| Unit | [Unit test plan](UNIT_TEST_PLAN.md) | Deterministic rules without account or phone |
| Software integration | [Software test plan](SOFTWARE_TEST_PLAN.md) | Components together, controlled faults, physical runtime when required |
| Physical target and Proton | [Functional test plan](FUNCTIONAL_TEST_PLAN.md) | Actual minified app, system editor and independent Web observations |

[Regression coverage](REGRESSION_MATRIX.md) connects historical defects to stable
case IDs and [three synthetic datasets](../app/qa/dataset/README.md). Fixtures are
grouped by purpose, not one file per contact. Imported data is setup, not proof
that creation through Contako or the native editor works.

## Select and run

For a change, select affected invariants and relevant regression rows, a nominal
path and no-change/cleanup checks. A release candidate SHOULD cover the complete
applicable target matrix with explicit unavailable/accepted-limitations entries.
Do not replay every historical campaign or replace independent field comparisons
with green counters. The [nominal gate](FUNCTIONAL_TEST_PLAN.md#nominal-creation-and-editing-acceptance-gate)
retains sequential cross-peer edits on the same minimal, rich and imported contacts.

Use a physical device for OEM/provider acceptance. Controlled software checks MAY
use an emulator when local operator restrictions permit; they do not qualify
physical OEM behavior. Verify the selected target and dedicated account
independently before mutation. Personal accounts MUST remain read-only unless a
specific mutation is authorized. Existing login/2FA/CAPTCHA acceptance is OWNER-VALIDATED: retest
when affected, not as routine setup. Never reset a connected session merely to
prepare a test. Keep raw evidence, private IDs and run-owned cleanup ledgers local
under ignored app/build/. No prefix-only deletion or use of historical counts as
cleanup authority.

Do not run all instrumentation blindly. Inspect selected class setup/cleanup,
package/account scope and opt-in parameters first. Use a separate installation
for isolated tests and an explicitly selected physical device. Debug test results
do not qualify minification or every OEM.

## Results

Use [the result template](testing/RESULT_TEMPLATE.md), copied to
app/build/qa-results/<local-run>/RESULT.md. Results MUST identify actual source
and dirty-tree state, APK identity when applicable, case/variant, intended changes,
unchanged-field oracle, separate observations in each peer, trigger, pending/
attention counters, cleanup and limitations. Raw evidence remains ignored.

Distinguish PASS, FAIL, NOT RUN, OWNER-VALIDATED and KNOWN LIMITATION.
An accepted limitation is not fixed; a later successful repair does not turn a
failed automatic step into a pass. Aggregate logs alone cannot prove field
preservation. A source-only finding cannot be called a reproduced device bug.

Keep durable implementation limits in [Known limitations](KNOWN_LIMITATIONS.md).
Temporary progress and agent handoff notes MUST NOT enter public documentation.
When preparing public distribution, retain a sanitized release qualification
summary with exact build identity. Do not commit every attempt, screenshot,
installation transcript or obsolete per-campaign report. Ordinary local commits
preserve cleanup checkpoints; history replacement is a separate operation.

## Retained executable support

The [compatibility bench](../app/qa/compatibility/README.md) generates bounded,
seeded synthetic variations and short interrupted sequences against production
components. Its independent expectations distinguish exact text/bytes from the
specific Android equivalences under test. Reproducible traces and bounded
reductions remain under ignored `app/build/qa-results/compatibility/`.
Controlled peers do not qualify Room persistence, cryptography, Binder, an OEM
editor or Proton; those levels require their own execution evidence.

Existing meaningful JVM/instrumentation tests and versioned wire-format fixtures
remain: historical names do not by themselves make executable coverage obsolete.
Room schema history, legal notices and dependency verification MUST remain.

Shared performance helpers live in `app/src/testShared/java`, compiled into JVM
and instrumentation tests only. Their synthetic load profiles measure capacity
and bounded work; the three datasets under `app/qa/dataset` define rich contact
journeys and regression acceptance. Neither helper code nor generated load data
belongs in application source sets.

### Performance measurements

Compare the same source-controlled protocol before and after one optimization.
Record source and dirty-tree identity, both APK hashes, fixture seed, locale,
physical API/device class, thermal state, power, cache and network conditions.
Use a monotonic clock, five warmups and thirty local samples; retain failures,
timeouts and cancellation with their elapsed time. JSON schema 3 reports median,
nearest-rank P95, maximum and failure counts. An incomplete series has no comparable
P95. A fast rejected operation MUST NOT qualify nominal convergence.

`CachedDirectoryPerformanceDeviceTest` uses isolated Room storage and the production
repository, ViewModel and Compose screen. Select `performanceProfiles=nominal`
(300 contacts/20 groups), `robustness` (5,000/200), or both separated by a comma.
The fixtures include duplicate email occurrences, per-email group edges, Unicode,
opaque preservation data and small valid PNG images. They do not qualify large
image decoding. The benchmark measures repository acquisition, a reopened
ViewModel in the same process/open database, cached useful rendering, rendered
search and durable Save at the transaction's AFTER_COMMIT checkpoint. Editor
closure and instrumentation admission are separate phases. ViewModel reopening
MUST NOT be described as a cold process or evicted OS/database cache. Rendered
timings include Compose synchronization and semantics inspection overhead.

Protocol `accepted-action-v3` measures the current `observeDirectory` snapshot;
its repository acquisition MUST NOT be compared directly with protocol-v2
`observeContacts` results as if the paths were identical. Separate diagnostics
cover Room plus DTO materialization, canonical conversion, sorting,
search/group matching and directory-section construction. Replayed
component timings are diagnostic, not a decomposition whose P95 values may be
summed or subtracted. Report aggregate DTO rows and UTF-8 string volume outside
timing; these are not SQLite cursor bytes or native peak memory. The separate
ViewModel-publication series uses an active collector with Compose detached;
its duration MUST NOT be called rendered user latency.
Rendered cases also support `renderOracle=atomic-v4` (instrumentation argument),
tagged `accepted-action-atomic-render-v4`. The default `legacy-v3` retains the
historical serial semantics/assertion barriers for replay. V4 captures the model
and all required text witnesses on Main after one semantics acquisition, requiring
attached/placed ancestors, visible host, clipped positive bounds and intersection
with the visible window. Search requires two distinct visible witnesses and the
third absent; its unfiltered precondition MUST reject the same oracle. Public
display assertions and row interaction remain mandatory postconditions: a failure
invalidates the sample even after its clock stopped. Geometry and hidden-host
controls MUST agree with the public display assertions on the pinned test library.
This proves useful displayed layout/semantics, not GPU presentation or photo
completion. V4 rendered durations MUST NOT be compared directly with V3 values
as a causal application improvement. Preserve historical failures and protocol
metadata; repository, component and ViewModel-publication timing stay V3.
The `renderOracle=atomic-v5` variant uses the same visibility requirements,
but copies the model lists on Main before stopping the clock and checks every
captured contact identity afterward. A later model replacement MUST NOT heal
that captured proof, and a failed identity check MUST invalidate the sample.
This excludes exhaustive test identity comparison from application display
latency while retaining it as a mandatory correctness oracle. V5 uses
`accepted-action-atomic-render-v5`; its rendered durations MUST NOT be compared
directly with V3 or V4. The capture-isolation regression is mandatory alongside
the visibility controls. First-query index preparation remains a separate cost.
Additional diagnostics compare the scoped directory reader with the retained
relation-based oracle, preparation of immutable search inputs and matching over
those prepared inputs. A steady-search semantics calibration repeats the same
render assertions on an already stable screen; it MUST NOT be subtracted from a
P95 or used to declare a failed rendered-latency target passed. Fixed SQL query
plans contain planner identifiers only, never bind values or contact data.
The opt-in instrumentation argument `performanceDiagnosticsOnly=true` runs
component measurements and that calibration without qualifying the UI latency
contracts; ordinary runs retain the existing contract measurements and ceilings.
Search keys are lazy per immutable snapshot. Retain and report the first-query
warmup duration separately: warm samples with an existing index do not qualify
cold preparation or a query immediately following a directory replacement.
`COLD-SEARCH-RENDER` creates a new ViewModel/store and blank navigation state for
every warmup and each of thirty measured first queries. List readiness is proved
before starting query time; the index is unprepared. Require exact identities,
values, groups and unchanged storage. Report this P95 separately from warm search:
it includes first index preparation but is not an OS/process/database cold start.
`performanceColdSearchOnly=true` selects only that distribution; it MUST NOT be
reported as qualification of the other budgets or compared with their ceilings.
Search diagnostics MUST include query preparation inside each timed evaluation;
preparing the query once MUST preserve the same contact identities, searchable
families, Unicode normalization and group-member results as individual matching.

`ContactSortPerformanceDeviceTest` compares two comparator strategies on identical
seeded permutations, alternating their order. Its nanosecond companion reports
isolate sorting, not Room, search, network or full application latency. Exact
identity order and stable ties MUST agree. `RoomContactRepositoryTest` also
checks locale changes, concurrent observers, rich values and owner isolation.

`SyncRoomPerformanceDeviceTest` measures the real import/checkpoint transactions
with controlled remote gateways for import, ten remote changes and no-change
passes. Full-card fetches and provider writes are different counters. Its gateway
durations MUST NOT be called real Proton, crypto, native-editor detection or
Android projection timings. Unexercised peer and memory counters are not proof
of zero real-world cost. Heap-before/after is a JVM proxy, not native peak memory.

Build from `app/` with the documented toolchain and verified dependencies:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=benchmark -PcontakoCandidatePackageSuffix=perfcheck -PcontakoTargetAbi=arm64-v8a :assembleBenchmark :assembleBenchmarkAndroidTest
```

Use `--offline` only with a complete verified cache. Explicitly select an
authorized physical target and review setup/cleanup before installing either APK.
Pass instrumentation arguments `sourceRevision`, `benchmarkCommand`,
`applicationApkSha256` and `testApkSha256`; command descriptions MUST exclude
private paths, device identities and credentials. Direct ADB runs MAY set
`performanceOutput=app-external` and collect only the isolated application's
`files/performance` external-files directory before removal. Otherwise output also
uses the Android test-storage service. Keep all raw JSON/CSV and run conditions
under ignored `app/build/qa-results/`.

The benchmark variant is non-debuggable and runs R8, but its broad test-only keep
rules make absolute timings indicative. Compare like-for-like benchmark variants;
compile/check the fully optimized release separately. Real Proton/provider
convergence, process-death recovery, large repair and unplugged battery behavior
require their own physical evidence and remain NOT RUN when not exercised.

| Tool under app/qa | Purpose |
| --- | --- |
| dataset/prepare.py | Validate three datasets; generate local grouped imports and original picker images |
| generate-runtime-notices.ps1 | Exact inventory/notices validation or staging; requires expected HEAD |
| gradle/runtime-artifacts.init.gradle | Runtime graph input for inventory |
| audit-runtime-security.py | Hash-locked Maven/native advisory collection; official Go tools supplied separately |
| check-release.ps1 | APK/AAB, manifest, notices, checksum and optional offline advisory inspection |
| test-release-check.ps1 | Negative checks of the release checker using built artifacts |
| check-security-boundaries.ps1 | Selected local TLS/Android checks; inspect physical prerequisites |
| fixtures/LocalTlsFixture.java | Local TLS fixture used by the security checker |
| gate-c-broker/GateCBroker.cs | Credential-transport support for retained opt-in probes; never routine login/reset |
| verify-offline-rebuild.ps1 | Existing checksum/offline rebuild utility; requires expected HEAD |

ProtonNominalRemoteFixturePreparationDeviceTest and the corresponding candidate
cleanup helper remain legacy opt-in tools with their own fixed fixture scope.
They are NOT the shared dataset importer and MUST NOT be run as automatic setup:
inspect their account/session effects and cleanup authority first. The new pack
generator never accesses a device, credentials or remote service.

A release check blocked by unsigned artifacts or unavailable advisory data MUST
remain BLOCKED. Not every maintenance script needs rerunning for documentation.
Build commands are in [app/README](../app/README.md).
