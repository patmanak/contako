# Unit test plan

Unit tests exercise deterministic rules in app/src/test without an account, phone,
network or wall-clock sleep. Run the affected classes first; run the full suite
when preparing a release candidate or changing shared policies. A passing unit
test does not establish interoperability with a real Proton account or OEM.

From app/, use the verified JDK and wrapper:
```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 -PcontakoTestBuildType=debug :testDebugUnitTest --tests '*RegressionDatasetTest'
```
Omit --offline only to resolve missing verified dependencies. To run the complete
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
| UT-07 | Android name equivalences, own-row ownership, RCS opaque partition, primary/hidden values, stale bindings and photo proof. | CanonicalAndroidContactMapperTest, AndroidProviderMimeRouterTest, AndroidProjectionBindingRecoveryPolicyTest, AndroidInterruptedPhotoProjectionTest |
| UT-08 | Serialized runner, durable intent, retry/coalescing, cancellation, lost acknowledgement and bounded context refresh. | AccountSyncRunnerTest, DurableMutationOrchestratorTest, ContakoSyncPassExecutorTest, SyncAdapterBlockingTest |
| UT-09 | Update/update and delete/edit ordering, unknown timestamps, expiry/clock jumps and local fallback. | ConflictPolicyTest, VerifiedServerClockTest |
| UT-10 | Sign-out counts native intent and refuses unsafe cleanup; fresh dialog attempt forgets obsolete warnings. | AccountSignOutCoordinatorTest, ContactsViewModelTest |
| UT-11 | Closed diagnostics and error-body exclusion; version suffixes and contact-count/list consistency. | LocalDiagnosticReportTest, SanitizedDiagnosticEventTest, ProtonGateCAdaptersTest, ContactsViewModelTest |
| UT-12 | Auth generations/password lifetime, permissions, capability failure classes, route/backup/build boundaries. | PendingLoginPasswordTest, AuthenticationViewModelTest, GroupCapabilityCoordinatorTest, ProjectInvariantTest |

The shared [dataset](../app/qa/dataset/README.md) provides concrete contact inputs
and independent expected values. Small existing inline fixtures remain appropriate
for one invariant. Do not replace meaningful tests with file-existence assertions,
invented campaign totals or identical copies at every layer.
