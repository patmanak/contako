# Software integration test plan

This level checks several production components together: codec and preservation,
Room transactions/outbox, runner and ports, Android provider adapters, crypto and
UI. External faults MAY be controlled. These tests do not count as real Web
acceptance. Some need a physical Android runtime even without a Proton account.

## Execution

JVM integration classes use :testDebugUnitTest with explicit --tests selectors.
Compile Android tests from app/ before a selected physical run:
```powershell
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=debug -PcontakoCandidatePackageSuffix=qatest -PcontakoTargetAbi=arm64-v8a :assembleDebug :assembleDebugAndroidTest
```
Add `--offline` only when the verified dependency cache is complete. The example
selects ARM64 for a physical target; select the matching ABI for another runtime.
Inspect the selected test's setup/cleanup before installation. Use a separate
package and invented provider accounts; do not use the connected personal
installation. Select one class/method with the instrumentation runner, never the
whole suite blindly. Controlled software checks MAY use an emulator when local
operator restrictions permit; physical OEM/provider acceptance still requires a
physical device. Debug instrumentation does not qualify R8;
repeat affected user paths on the minified build under the functional plan.

## Coverage

| Case | Setup, stimulus and oracle | Executable anchors |
| --- | --- | --- |
| SW-01 | Commit create/edit/delete and outbox together; fail before/after commit and lose acknowledgement. Restart cannot discard or duplicate intent. | RoomOutboxStoreDeviceTest, RoomBackedMutationGatewaysDeviceTest, DurableMutationOrchestratorTest |
| SW-02 | Inventory pages fail/duplicate/reset; only complete authoritative absence proves deletion. Unchanged entries avoid full hydration. | ContactInventoryPlannerCasTest, IncrementalRemoteContactStageTest, RoomContactInventoryCheckpointStoreTest |
| SW-03 | Native adoption and existing edit with per-email groups. Own contact save advances group revisions; real concurrent changes roll back the whole observation. | RoomAndroidCreatedContactCommitterDeviceTest, RoomObservedGroupMembershipTransactionDeviceTest |
| SW-04 | Decode dataset, edit note/email, encode and reread. Assert intended values, source boundaries, unknown multiplicity and groups independently. | RegressionDatasetTest, ProtonPendingPreservationTest, ProtonVCardMappingContractTest |
| SW-05 | Real maintained crypto: signed and unsigned encrypted cards, wrong key, forged signature, truncated/oversized input. Reject without clearing durable work. | ProtonContactCardCryptoDeviceTest, GateCLocalIntegrationProbeTest, ContactCryptoSecurityTest |
| SW-06 | Provider owns only scoped rows. Inject RCS, another source and aggregation; stale metadata must reject writes. Preserve other source and opaque data. | AndroidContactsProviderBoundaryDeviceTest, AndroidProviderRowCodecDeviceTest |
| SW-07 | Interrupt batch/photo journal before first baseline; resume with or without a genuine native edit. Check full/thumbnail bytes, relocated name/birthday/organization rows and atomic bindings. | RoomAndroidPhotoProviderWriteCoordinatorDeviceTest, RoomAndroidProviderIdentityResolverDeviceTest, AndroidInterruptedPhotoProjectionTest |
| SW-08 | Sign-out race, dirty contact/group/tombstone, unavailable provider, pending Room write and explicit discard. Failure preserves session/intent; cleanup is scoped. | AndroidAccountRemovalCoordinatorDeviceTest, AuthenticationScreenDeviceTest |
| SW-09 | Foreground/background/system triggers share one runner. Framework wait interruption is contained; repair refreshes context and resumes/cancels durably. | ProductionSharedSyncRuntimeDeviceTest, ContakoContactsSyncAdapterDeviceTest, ContakoSyncPassExecutorTest |
| SW-10 | Schema upgrade, provider reset, protected-state loss/session revocation. No destructive Room fallback or cross-account late callbacks. | ContakoMigrationDeviceTest, AndroidProviderResetRepairCoordinatorDeviceTest, GateCProtectedStorageLifecycleDeviceTest |
| SW-11 | UI Save/invalid-field feedback, date picker, image replacement, navigation and report export with controlled state. | LocalFoundationJourneyTest, ContactDateFieldDeviceTest, ContactImageStateDeviceTest, DiagnosticSettingsDeviceTest |
| SW-12 | Host/origin/TLS and bounded response handling, parser/crypto limits, release backup/logging/resource rules. | SecurityBoundaryDeviceTest, OriginBoundVerificationBridgeDeviceTest, ProtonRawResponseBoundaryTest, check-release.ps1 |

## Recovery and negative variants

For SW-01/03/07/09 stop before and after each relevant durable boundary, repeat the
operation and verify canonical/outbox/provider state. Different names, DIRTY
native intent, stale account/epoch/revision, occupied destination, a still-present
old row and forged primary/phonetic claims MUST NOT be accepted as benign
relocation. Photo recovery requires its own journal and actual byte proof.

Cryptographic layout, provider RCS, aggregation, lost responses and process death
are scenario state: importing a .vcf alone cannot establish them. The dataset
catalog links those profiles to controlled setup and the live cases.
