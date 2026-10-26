# Software integration test plan

## Contact confirmation details

Exercise the metadata-only one-contact read model against isolated Room with
foreign-owner canaries. Verify legacy unknown state, revision/context changes,
independent Proton conflict and Android proof, current issues, retained deletion
intent, timestamps only from proof boundaries and cleanup. Check every migration
to the current schema without inventing evidence for old contacts. Repeated
observation MUST leave contact, outbox, receipts and proof rows unchanged.

Use the production acknowledgement/readback transactions with controlled peers:
successful complete read/write/deletion records the exact revision/context;
rejection, cancellation and stale callbacks do not create current confirmation.
Include proof-insert failure rollback and a missing/excluded photo receipt.
Compose tests MUST distinguish version/time/peer sections, preserve read-only
behavior on restoration, and keep refresh explicit. Controlled provider seams
and Compose rendering do not qualify real Proton or an OEM photo transformation.

## Durable targeted recovery

Exercise request persistence/reopening, source generations, transaction rollback,
owner cleanup, stale revisions/epochs, duplicate confirmation and retry deadlines
against the real Room store. Validate the additive migration and isolation when
only request metadata remains. Test one target among 300 witnesses with a real
canonical reconciliation transaction, independently asserting changed/unchanged
fields, occurrence identities and surviving per-email group edges.

The targeted Android integration MUST call the original item executor seam and
require current contact/membership receipts before resolution. A synthetic
`Applied` without those receipts, a concurrent revision or a failed issue clear
MUST remain unresolved. Native ingestion MUST commit canonical intent/outbox
before projection; target-only page readers MUST not touch other contact rows.
These controlled seams and database cases do not qualify physical OEM photo
processing or Proton delivery.

## Issue navigation

`SyncIssueNavigationDeviceTest` checks that an unknown problem opens local
diagnostics without requesting synchronization, generating/exporting a report
or acknowledging the issue. Returning MUST still show the same current cause.

## Import availability

Exercise `InitialImportProgressDeviceTest` on an isolated local database:
committed partial batches before any inventory checkpoint, reopening without
double counting, confirmed empty inventory, deferred cards independent of
Android copies, transaction rollback and owner cleanup without touching a
foreign partition. Compare the pending-copy query with the existing ledger
predicate, including missing, clean-with-pending and unclean ledgers.
`InitialImportOverviewDeviceTest` verifies dismissal, saved-state restoration
and navigation to saved contacts without another synchronization request.
Neither test creates an Android account or qualifies Proton/OEM behavior.

This level checks several production components together: codec and preservation,
Room transactions/outbox, runner and ports, Android provider adapters, crypto and
UI. External faults MAY be controlled. These tests do not count as real Web
acceptance. Some need a physical Android runtime even without a Proton account.

## Execution

JVM integration classes use `-PcontakoTestBuildType=debug :testDebugUnitTest`
with explicit `--tests` selectors.
Compile Android tests from app/ before a selected physical run:
```powershell
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=debug -PcontakoCandidatePackageSuffix=qatest -PcontakoTargetAbi=arm64-v8a :assembleDebug :assembleDebugAndroidTest
```
Add `--offline` only when the verified dependency cache is complete. The example
selects ARM64 for a physical target; select the matching ABI for another runtime.
Inspect the selected test's setup/cleanup before installation. Use a separate
package. Crypto tests using in-memory keys, packaged-schema checks and local
WebView boundary tests do not require Android account creation. Provider/account
tests require separate authorization for their invented accounts; leave these
cases NOT RUN when account creation is excluded. Do not use the connected personal
installation. Select one class/method with the instrumentation runner, never the
whole suite blindly. Controlled software checks MAY use an emulator when local
operator restrictions permit; physical OEM/provider acceptance still requires a
physical device. Debug instrumentation does not qualify R8;
repeat affected user paths on the minified build under the functional plan.

Room's migration test helper needs the serializer ABI declared by its migration
bundle. The selected instrumentation host includes that compatible runtime because
AGP aligns shared test dependencies to the host APK. This test-host dependency
does not change the release or preview dependency graph; migration tests MUST NOT
be reported as executed when schema loading fails before the migration itself.

## Coverage

| Case | Setup, stimulus and oracle | Executable anchors |
| --- | --- | --- |
| SW-01 | Commit create/edit/delete and outbox together; fail before/after commit and lose acknowledgement. Restart cannot discard or duplicate intent. | RoomOutboxStoreDeviceTest, RoomBackedMutationGatewaysDeviceTest, DurableMutationOrchestratorTest |
| SW-02 | Inventory/event pages fail/duplicate/reset or exceed budgets. A private-only event hydrates its contact despite an unchanged public index. Cursor advancement requires durable reconciliation; failed reads/transactions replay after restart. Public-directory omissions require an individual confirmed absence before deletion; errors and generic HTTP 404 responses MUST NOT prove absence. Unchanged entries avoid full hydration. | ProtonContactEventReaderTest, ContactInventoryPlannerCasTest, IncrementalRemoteContactStageTest, ProtonInventorySafetyTest, RoomContactInventoryCheckpointStoreTest |
| SW-03 | Native adoption and existing edit with per-email groups. Own contact save advances group revisions; real concurrent changes roll back the whole observation. | RoomAndroidCreatedContactCommitterDeviceTest, RoomObservedGroupMembershipTransactionDeviceTest |
| SW-04 | Decode dataset, edit note/email, encode and reread. Assert intended values, source boundaries, unknown multiplicity and groups independently. | RegressionDatasetTest, ProtonPendingPreservationTest, ProtonVCardMappingContractTest |
| SW-05 | Real maintained crypto: signed and unsigned encrypted cards, wrong key and fallback, forged signature, truncated/corrupted input, compressed plaintext at and beyond the contact budget. Read complete authenticated EOF; reject excess without truncated canonical content or clearing durable work. A native rebuild MUST verify all ABI runtime/module identities, retained Java API equality, source-lock and artifact checksums, source notices and minified physical vectors. Reject a stale source descriptor or altered AAR; never fall back to the old upstream runtime. Recheck SRP public handshake/password vectors and affected Web interoperability after runtime changes. | ProtonContactCardCryptoDeviceTest, GateCLocalIntegrationProbeTest, ContactCryptoSecurityTest; native/golib build and verifySourceBuiltCrypto |
| SW-06 | Provider owns only scoped rows. Inject arbitrary unmapped MIME text/BLOBs/claims, a standard photo, another source and aggregation. Supported creation/edit/projection MUST continue without decoding or rewriting opaque payloads. An opaque row with an exact existing ATTACHED managed binding MUST fail identity verification without dropping canonical values. Stale metadata still rejects writes; malformed recognized fields remain strict. | AndroidContactsProviderBoundaryDeviceTest, AndroidProviderRowCodecDeviceTest, ProductionAndroidContactObservationCoordinatorDeviceTest, RoomAndroidProviderIdentityResolverDeviceTest |
| SW-07 | Interrupt batch/photo journal before first baseline; resume with or without a genuine native edit. Check full/thumbnail bytes, relocated name/birthday/organization rows and atomic bindings. | RoomAndroidPhotoProviderWriteCoordinatorDeviceTest, RoomAndroidProviderIdentityResolverDeviceTest, AndroidInterruptedPhotoProjectionTest |
| SW-08 | Sign-out race, dirty contact/group/tombstone, unavailable provider, pending Room write and explicit discard. Failure preserves session/intent; cleanup is scoped. | AndroidAccountRemovalCoordinatorDeviceTest, AuthenticationScreenDeviceTest |
| SW-09 | Foreground/background/system triggers share one runner. Framework wait interruption is contained; repair refreshes context and resumes/cancels durably. | ProductionSharedSyncRuntimeDeviceTest, ContakoContactsSyncAdapterDeviceTest, ContakoSyncPassExecutorTest |
| SW-10 | Schema upgrade, provider reset, protected-state loss/session revocation. No destructive Room fallback or cross-account late callbacks. | ContakoMigrationDeviceTest, AndroidProviderResetRepairCoordinatorDeviceTest, GateCProtectedStorageLifecycleDeviceTest |
| SW-11 | UI Save/invalid-field feedback, date picker, image replacement, navigation and expandable report export with controlled state. Persist exact Android failure identities across restart, stale CLEAN status, authentication priority and deletion. Preserve blocked group/contact names outside the active/search-filtered directory; clear only the resolved origin. Migration MUST preserve existing intent. | LocalFoundationJourneyTest, ContactDateFieldDeviceTest, ContactImageStateDeviceTest, DiagnosticSettingsDeviceTest, RoomAndroidContactIssueStoreDeviceTest, RoomSyncStatusStoreDeviceTest, ContakoMigrationDeviceTest |
| SW-12 | Host/origin/TLS and bounded response handling, parser/crypto limits, release backup/logging/resource rules. | SecurityBoundaryDeviceTest, OriginBoundVerificationBridgeDeviceTest, ProtonRawResponseBoundaryTest, check-release.ps1 |
| SW-13 | A private remote change before upload retains both full snapshots and blocks the outbox. Restart retains the choice; newer local/remote state invalidates it. Selecting Proton adopts atomically without upload; selecting local requires a fresh remote check. Pending group assignments and remote deletion MUST NOT be discarded by either choice. | RoomContactConflictDeviceTest, RoomRemoteCanonicalReconciliationStoreDeviceTest, ContactConflictSnapshotCodecTest |
| SW-14 | Real Android notification service with a synthetic Room scope, without creating an Android account. Denied publication retains the delivery claim; allowed publication contains generic text and an immutable intent, survives database reopen without reposting, and recovery cancels the actual notification. | AndroidSyncActionNotifierDeviceTest |

SW-14 requires an isolated application ID ending in `.auditqa`. Run its denied
and granted methods separately, configuring `POST_NOTIFICATIONS` before each
instrumentation process starts, and restore the original permission afterwards.
Revoking a permission while instrumentation is running can terminate that process.
The synthetic clock qualifies the service boundary, not natural 24-hour delivery;
real notification taps and authenticated navigation remain FT-17 variants.

## Recovery and negative variants

Room upgrade coverage MUST include the additive hydration-obligation schema.
Seed an obligation, reopen the database, verify the retry and remove the account;
neither upgrade nor failed sign-out may discard unresolved intent.
SW-02 MUST also exercise persistence failure before cursor acknowledgement,
healthy-card commits and targeted recovery/confirmed absence. An unfinished import
MUST NOT advance full repair past canonical reconciliation. Stage/orchestration
anchors are `IncrementalRemoteHydrationIsolationTest` and
`ContakoSyncPassExecutorTest`; `RoomRemoteHydrationObligationDeviceTest` covers
database reopen, rollback and scoped purge of the obligation itself.

Remote reconciliation MUST distinguish two Proton IDs with the same vCard UID.
Test both arrival orders, unrelated edits and a targeted deletion. UID matching
may attach only a unique unbound creation with durable ambiguity at its exact
revision. A generic DELETE 404 MUST retain intent until a targeted absence check
confirms convergence.
For a lost creation acknowledgement, obtain `IN_FLIGHT` through the real outbox
claim without manually setting the reconciliation flag. Reconstruct the pass
with that creation already present remotely. Both ordinary and repair ordering
MUST retain one canonical aggregate with its original local identity and a
recoverable intent; failure to recover durable state MUST precede hydration.
Include a repair whose projection phase was already checkpointed before the
create. Fail its first resumed inventory, restart again with the intent already
recovered, and verify convergence without relying on a nonzero recovery count.

For Android preferred-email transitions, cover first-email addition, last-email
removal, preferred removal and primary selection, with and without groups. The
new preferred email may already have different groups. Verify the canonical
contact, every email membership, outbox, native observation baseline, retry after
lost acknowledgement, database reopen and a second ordinary edit. After proven
scope/epoch/ownership, an interrupted baseline/photo failure affects only that
contact; later contacts and pages MUST still progress without acknowledging the
unverified row.
Preference-switch fixtures MUST change the canonical preference selected by the
production mapper, not only a secondary boolean flag. Exercise the native
observation through `applyControlledDelta` before committing the transition.

For local phonetic complements, ingest native given/middle/family changes, adopt
a remote note-only edit and repeat through an explicit Proton conflict choice.
Verify the exact local components and identities after Room reopen, Android
projection and a no-change observation, including a family-only phonetic name.
Anchor: `RoomPhoneticContactComplementsDeviceTest`.
Repeat adoption with local custom dates and URL/relation/address labels that
Proton cannot represent. Preserve only proven local labels on unchanged,
unambiguous occurrences. Remote value deletion or an explicit remote label MUST
remain authoritative; do not guess between identical repeated values. Anchor:
`RoomRemoteCanonicalReconciliationStoreDeviceTest`.

For authentication, cancel an initial TOTP attempt and reject an unsupported
method before signing in as another user on an empty partition. Contrast this
with cancellation of same-owner reauthentication with retained data. Reconstruct
orphan provisional reservations after interruption without releasing an
established owner. For sign-out, suspend after Core session deletion while the
session observer and composition run; `CONFIRM` and `DISCARD` MUST finish cleanup
or expose a retryable failure without losing checkpoints.
Reconstruct both provisioned and unprovisioned cleanup after session deletion,
including interruption after owner release. Only checkpoint finalization may
continue after ownership is gone; a new owner, session or local data MUST prevent
deletion. Pending cleanup MUST close runner admission before any startup request.
Contrast a refused pre-destructive cleanup with a retained partial-cleanup
checkpoint. Only the latter MUST enter exclusive recovery; the former MUST permit
dismissal, synchronization and a new sign-out attempt. `SignOutCompositionDeviceTest`
covers the corresponding screen transition.
Open contact and group drafts before cleanup and suspend a save at admission.
After logout and login as a different owner, no previous draft, cached contact,
dialog or delayed write may reappear. Recreate the activity during this journey.

`RoomSyncStatusStoreDeviceTest` MUST combine an old group/validation block with
a new authentication or interactive-unlock requirement. The publisher MUST expose
the reconnect action and immediate notification across restart while preserving
all mutations, import obligations and counters. After reconnecting, the original
unresolved problem MUST remain actionable.

`RoomMutationRecoveryGenerationDeviceTest` MUST reopen eligible session/capability
blocks once per recovery generation at the exact retained revision. Preserve
idempotency, attempt history and reconciliation intent; stale revisions,
validation, crypto and conflict failures MUST remain blocked. Invalidation during
the transaction MUST roll it back without consuming the new generation's retry.
Completion MUST still include blocked/ineligible mutations when no new write is
attempted, including their authentication category. A following successful pass
MUST NOT inherit the previous pass's failure counts or cause.

For provisioning recovery, refuse platform creation after the binding commits,
then accept it on an authenticated foreground return with unchanged permissions.
The bound name MUST be reused, projection scheduled and full import left intact.
Interleave a suspended provisioning attempt with sign-out in both orders. Account
selection and cleanup MUST observe the completed provisioning state, or reject
the queued provisioning attempt after cleanup wins. Neither a second cleanup
marker nor a late runner reactivation may be left behind.

For SW-01/03/07/09 stop before and after each relevant durable boundary, repeat the
operation and verify canonical/outbox/provider state. Different names, DIRTY
native intent, stale account/epoch/revision, occupied destination, a still-present
old row and forged primary/phonetic claims MUST NOT be accepted as benign
relocation. Photo recovery requires its own journal and actual byte proof.

SW-07 also covers atomic membership initialization and exact legacy seed recovery
with AndroidMembershipInitializationSeedTest and
RoomAndroidMembershipBaselineInitializerDeviceTest. Inject a baseline insertion
failure: neither half of a new pair may persist. Preserve completed or unmatched
states, reject stale account/contact context and verify the final projection
receipt separately from the initial marker. Photo aggregate preference comparison
MUST retain the actual raw snapshot fingerprint when completing a write.

SW-03/13 also cover repeated identical email occurrences with equal or different
group assignments. Fresh identity repair MUST reopen a proved assignment intent
even while an unrelated contact upload is pending; preparation still waits for
its dependencies. Removing one of two occurrences MUST NOT transfer its pending
assignments to the survivor without identity proof. Verify unchanged-inventory
retry after the other upload completes with RepeatedEmailRecoveryTest and
RoomRemoteCanonicalReconciliationStoreDeviceTest.

After an unavailable group catalog returns, verify that retained per-email
metadata selects only contacts with missing known memberships for hydration.
Check restored edges, untouched pending group intent and unknown references,
then a no-change pass without repeated hydration. An extra canonical edge alone
MUST NOT trigger repair from stale metadata. Anchor:
RoomRemoteCanonicalReconciliationStoreDeviceTest.

Cryptographic layout, provider RCS, aggregation, lost responses and process death
are scenario state: importing a .vcf alone cannot establish them. The dataset
catalog links those profiles to controlled setup and the live cases.
