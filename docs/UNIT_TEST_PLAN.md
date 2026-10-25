# Unit test plan

## Actionable issue presentation

Issue presentation MUST cover every closed `SyncProblem` and scope, explicit
durability, permission absence, offline eligibility, queued conflict choices and
unavailable comparison. An authentication banner MUST NOT replace another known
item cause. Current-issue aggregation MUST preserve independent groups, cards and
Android failures; ordinary pending work and healed historical events MUST NOT
become errors. Deleted items remain named without offering a destructive action.

## Generated compatibility sequences

Run the [deterministic compatibility bench](../app/qa/compatibility/README.md)
alongside the hand-written regressions. The same generator version, profile and
seed MUST reproduce inputs and fault points. Independent expected fields MUST
include repeated email occurrences, per-occurrence groups, Unicode, dates and
opaque properties. Mutation interruptions MUST pass through save, claim and the
external-effect boundary before simulated restart. Verify retained intent,
foreign partitions and identities after each step, then two no-change drains.
A budget interruption MUST be reported as NOT RUN, never PASS. A reduced failure
MUST preserve its causal signature; a deliberately injected defect MUST be
detected and reduced to demonstrate the bench's sensitivity.

Unit tests exercise deterministic rules in app/src/test without an account, phone,
network or wall-clock sleep. Run the affected classes first; run the full suite
when preparing a release candidate or changing shared policies. A passing unit
test does not establish interoperability with a real Proton account or OEM.

From app/, use the verified JDK and wrapper:
```powershell
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=debug :testDebugUnitTest --tests '*RegressionDatasetTest'
```
Add --offline only when the verified dependency cache is complete. To run the complete
JVM suite, remove --tests. XML/HTML results stay under app/build/, never in Git.

## Coverage

| Case | Rule and expected result | Existing executable anchors |
| --- | --- | --- |
| UT-01 | Save/delete validation, names, empty/whitespace and field limits. Blank display name derives from explicit names; aliases remain. | ContactPoliciesTest, ContactValidationCoverageTest, ContactsViewModelTest |
| UT-02 | Search, accents, alphabet sections, tab/detail scroll restoration and compact actions. | ContactSearchCoverageTest, DirectorySectionIndexTest, NavigationStateStoreTest, ContactActionPolicyTest |
| UT-03 | vCard decode/edit/encode preserves unknown occurrences, parameters, hidden N components and per-email groups. Clear public provenance upgrades safely. | ProtonVCardMappingContractTest, ProtonPendingPreservationTest, RegressionDatasetTest |
| UT-04 | Equivalent repeated PREF/VALUE is accepted; conflicting declarations, forged provenance and malformed envelopes are rejected. | ProtonVCardMappingContractTest, ContactCryptoSecurityTest, RegressionDatasetTest |
| UT-05 | Dates: full/yearless/leap/text imports, dd/MM/yyyy presentation, cancel, invalid February 30, no invented year. | AndroidEventDateTest, ContactDateInputTest, ContactDatePresentationTest |
| UT-06 | New-image bounds/orientation sizing; imported PHOTO/LOGO preservation, inline binary conversion, no arbitrary URL fetch. | SelectedContactImageSizeTest, CanonicalPhotoBinaryLoaderTest, ProtonLegacyInlineImageTest |
| UT-07 | Android name equivalences, own-row ownership, arbitrary opaque MIME partition, supported edits beside unknown text/BLOB/claims, strict recognized payloads, primary/hidden values, stale bindings and photo proof. | CanonicalAndroidContactMapperTest, AndroidProviderMimeRouterTest, ProductionAndroidContactObservationCoordinatorTest, AndroidProjectionBindingRecoveryPolicyTest, AndroidInterruptedPhotoProjectionTest |
| UT-08 | Serialized runner, durable intent, retry/coalescing, cancellation, lost acknowledgement and bounded context refresh. | AccountSyncRunnerTest, DurableMutationOrchestratorTest, ContakoSyncPassExecutorTest, SyncAdapterBlockingTest |
| UT-09 | Timing evidence, unknown timestamps and expiry/clock jumps never authorize a conflict winner; snapshot encoding preserves complete rich versions and rejects malformed data. | ConflictPolicyTest, VerifiedServerClockTest, ContactConflictSnapshotCodecTest |
| UT-10 | Sign-out counts native intent and refuses unsafe cleanup; fresh dialog attempt forgets obsolete warnings. | AccountSignOutCoordinatorTest, ContactsViewModelTest |
| UT-11 | Closed diagnostics and error-body exclusion; timed bounded journal, retained cause, disk validation/rotation/restart/clear, observer isolation, version suffixes and contact-count/list consistency. | LocalDiagnosticReportTest, SyncDiagnosticJournalTest, SanitizedDiagnosticEventTest, AccountSyncRunnerTest, ProtonGateCAdaptersTest, ContactsViewModelTest |
| UT-12 | Auth generations/password lifetime, permissions, capability failure classes, route/backup/build boundaries. | PendingLoginPasswordTest, AuthenticationViewModelTest, GroupCapabilityCoordinatorTest, ProjectInvariantTest |

The shared [dataset](../app/qa/dataset/README.md) provides concrete contact inputs
and independent expected values. Small existing inline fixtures remain appropriate
for one invariant. Do not replace meaningful tests with file-existence assertions,
invented campaign totals or identical copies at every layer.

## Recovery boundary variants

- `IncrementalRemoteHydrationIsolationTest`: 51 cards with a rejected card at
  each batch boundary, durable cursor coverage, targeted retry, interruption,
  failed storage and disappearance before the first checkpoint.
- `ContakoSyncPassExecutorTest`, `SyncCompletionClassificationTest`: unavailable
  catalog, remote authentication during a pass, scoped projection/validation
  errors and fresh completion causes on the next pass. SQLite, transient I/O,
  permission and data failures MUST NOT become authentication failures merely
  because they require action; deferred work MUST remain part of completion.
- `DurableMutationOrchestratorTest` and the Room execution-store integration
  cases: existing ineligible intent remains part of completion; a new session or
  capability generation permits one guarded recheck of an earlier denial,
  preserving validation/conflict blocks, revisions and idempotency keys.
- `ProtonAccountOwnerIsolationTest`: revoke A with retained mutations, reject B,
  restart and reconnect A; legacy ownership without proof remains quarantined.
  Initial aborted reservations on empty partitions may be released; cancelling
  same-owner reauthentication MUST preserve an established owner. Delayed logout
  callbacks for an old user, session or token MUST NOT revoke its replacement.
- `AuthenticationViewModelTest`: session changes and removal of the initiating
  composition MUST NOT cancel explicit sign-out; failures retain a visible retry.
  A refusal before destructive cleanup MUST keep normal actions available when
  no checkpoint remains. Partial cleanup or unreadable checkpoint state MUST
  retain exclusive recovery, including after cancellation.
- `AccountLifecycleAdmissionTest`, `ContactsViewModelTest`: cleanup MUST fence
  queued UI mutations and clear drafts from the previous owner. An already
  admitted save MUST finish or cancel before purge; delayed saves MUST NOT write under
  a subsequent owner.
- `SyncPassActionReasonTest`: a current authentication or interactive-unlock
  requirement MUST remain visible above older blocked mutation reasons. Resolving
  authentication MUST expose the retained underlying problem again.
- `GroupCapabilityCoordinatorTest`: session change, explicit refresh and expiry
  invalidate cached denials. An older in-flight result MUST NOT restore a denial
  after invalidation; an ordinary catalog read MUST NOT create a new retry generation.
- `RetryPolicyTest`, `DurableMutationOrchestratorTest`: a generic DELETE 404 MUST
  retain reconciliation intent and use bounded retry, without reporting a
  confirmed deletion. A later targeted absence proof is a distinct boundary.
- `SyncDiagnosticJournalTest`: when no detailed cause was recorded, a terminal
  action-required/failure outcome MUST retain the last work boundary through
  rotation and restart. A generic outcome MUST NOT replace a known detailed cause.
- `AndroidAccountProvisioningCoordinatorTest`: a retained binding after platform
  refusal still permits foreground recovery under the bound name, without
  repeating first import or recreating a present account.
- `PreferredEmailTransitionTest`: first/last/preferred email changes and a new
  preferred email with distinct existing memberships.
- `ProtonVCardUnfoldingTest`, `ProtonVCardOpaquePreservationTest`,
  `LocalContactComplementsTest`: bounded linear work, opaque phonetic properties,
  custom dates, complete local phonetic components and unambiguous local-label
  preservation. Native phonetic deletion MUST NOT be resurrected by adoption.
  Exercise physical/logical-line, character and UTF-8 byte limits during
  unfolding, including many tiny continuations. These limits do not qualify
  native allocations or total process memory.
