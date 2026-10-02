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

Existing meaningful JVM/instrumentation tests and versioned wire-format fixtures
remain: historical names do not by themselves make executable coverage obsolete.
Room schema history, legal notices and dependency verification MUST remain.

Shared performance helpers live in `app/src/testShared/java`, compiled into JVM
and instrumentation tests only. Their synthetic load profiles measure capacity
and bounded work; the three datasets under `app/qa/dataset` define rich contact
journeys and regression acceptance. Neither helper code nor generated load data
belongs in application source sets.

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
