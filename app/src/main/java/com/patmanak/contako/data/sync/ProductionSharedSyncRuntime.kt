package com.patmanak.contako.data.sync

import android.accounts.Account
import android.content.Context
import androidx.room.withTransaction
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.android.account.AccountRemovalCoordinator
import com.patmanak.contako.android.account.AndroidAccountRemovalCoordinator
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.android.account.AccountSignOutCoordinator
import com.patmanak.contako.android.account.AccountLifecycleAdmission
import com.patmanak.contako.android.account.AndroidAccountProvisioningCoordinator
import com.patmanak.contako.android.account.RoomAndroidAccountBindingStore
import com.patmanak.contako.data.android.provider.FrameworkAndroidAccountPlatform
import com.patmanak.contako.android.account.BoundAndroidAccountReader
import com.patmanak.contako.android.account.FrameworkAccountPlatformCleanup
import com.patmanak.contako.android.account.PendingMutationReader
import com.patmanak.contako.android.account.InAppAccountRemoval
import com.patmanak.contako.android.account.SharedPreferencesAccountRemovalCheckpointStore
import com.patmanak.contako.android.account.SharedPreferencesUnprovisionedRemovalCheckpointStore
import com.patmanak.contako.data.local.hasAccountScopedData
import com.patmanak.contako.android.sync.AndroidAccountSyncRunnerResolver
import com.patmanak.contako.data.android.AndroidRuntimeAccountPreflight
import com.patmanak.contako.data.android.RoomAndroidDurableAccountContextReader
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.provider.AndroidContactsProviderReader
import com.patmanak.contako.data.android.provider.AndroidContactsRuntimeProbe
import com.patmanak.contako.data.android.provider.AndroidGroupsProviderReader
import com.patmanak.contako.data.android.provider.AndroidProviderProjectionReplanObserver
import com.patmanak.contako.data.android.provider.productionAndroidProjectionCoordinator
import com.patmanak.contako.data.android.provider.productionGroupObservationCoordinator
import com.patmanak.contako.data.android.provider.CanonicalPhotoBinaryLoader
import com.patmanak.contako.data.android.provider.FrameworkAndroidAccountProjectionCleaner
import com.patmanak.contako.data.android.provider.FrameworkAndroidProviderEpochProofWriter
import com.patmanak.contako.data.gateway.ProtonLocalSessionCleanupGateway
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonContactMutationGateway
import com.patmanak.contako.data.gateway.ProtonSessionGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactInventoryCheckpointStore
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomMutationExecutionStore
import com.patmanak.contako.data.local.RoomAndroidGroupCommitRepairObserver
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitRepairObserver
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitReplanObserver
import com.patmanak.contako.data.local.RoomAndroidMembershipLedgerStaleObserver
import com.patmanak.contako.data.local.AndroidCanonicalContactRejectionObserver
import com.patmanak.contako.data.local.RoomRemoteCanonicalReconciliationStore
import com.patmanak.contako.data.local.RoomRemoteGroupReconciliationStore
import com.patmanak.contako.data.proton.ContactInventoryCheckpointStore
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.domain.sync.AccountSyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProductionSharedSyncRuntime(
    val runner: AccountSyncRunner,
    val androidAccountSyncRunnerResolver: AndroidAccountSyncRunnerResolver,
    val accountRemovalCoordinator: AccountRemovalCoordinator,
    val accountSignOutCoordinator: AccountSignOutCoordinator,
    val accountProvisioningCoordinator: AndroidAccountProvisioningCoordinator,
    val accountLifecycleAdmission: AccountLifecycleAdmission,
)

internal data class ProductionSyncDependencies(
    val account: AccountScope,
    val session: ProtonSessionGateway,
    val inventory: ProtonContactInventoryGateway,
    val verifiedCards: ProtonVerifiedContactCardGateway,
    val contactMutations: ProtonContactMutationGateway,
    val groups: ProtonContactGroupGateway,
    val emailLabels: ProtonContactEmailLabelGateway,
    val membershipReader: ProtonEmailGroupMembershipReader,
    val localSessionCleanup: ProtonLocalSessionCleanupGateway,
    val existence: com.patmanak.contako.data.gateway.ProtonContactExistenceGateway =
        com.patmanak.contako.data.gateway.ProtonContactExistenceGateway { _, _ -> GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN) },
    val events: com.patmanak.contako.data.proton.ProtonContactEventsGateway? = null,
    val releaseOwnerAfterLocalDataCleared: suspend () -> Unit = {},
    val invalidateGroupCapabilities: () -> Unit = {},
    val canCleanOwnedScope: suspend () -> Boolean = { false },
    val localOwnerIdentity: suspend () -> String? = { null },
    val hasNoCoreAccount: suspend () -> Boolean = { false },
    val isAccountReady: suspend () -> Boolean = { false },
    val capabilityInvalidationGeneration: () -> Long = { 0 },
)

/** Production composition only wires dormant gateways; I/O starts when the shared runner executes. */
internal fun composeProductionSharedSyncRuntime(
    context: Context,
    database: ContakoDatabase,
    proton: ProtonGateCRuntime,
    runnerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    stopScheduling: () -> Unit = {},
    passStageObserver: SyncPassStageObserver = SyncPassStageObserver { },
    androidStageResultObserver: AndroidSyncStageResultObserver =
        AndroidSyncStageResultObserver { _, _ -> },
    projectionReplanObserver: AndroidProjectionReplanObserver = AndroidProjectionReplanObserver { },
    providerProjectionReplanObserver: AndroidProviderProjectionReplanObserver =
        AndroidProviderProjectionReplanObserver { },
    remoteContactActionRequiredObserver: RemoteContactActionRequiredObserver =
        RemoteContactActionRequiredObserver { _, _, _ -> },
    mutationActionRequiredObserver: MutationActionRequiredObserver =
        MutationActionRequiredObserver { },
    androidIngestActionRequiredObserver: AndroidIngestActionRequiredObserver =
        AndroidIngestActionRequiredObserver { },
    androidIngestReplanObserver: AndroidIngestReplanObserver = AndroidIngestReplanObserver { },
    androidGroupCommitRepairObserver: RoomAndroidGroupCommitRepairObserver =
        RoomAndroidGroupCommitRepairObserver { },
    existingContactPlanRepairObserver: AndroidExistingContactPlanRepairObserver =
        AndroidExistingContactPlanRepairObserver { },
    existingContactCommitRepairObserver: RoomAndroidUnifiedCommitRepairObserver =
        RoomAndroidUnifiedCommitRepairObserver { },
    existingContactCommitReplanObserver: RoomAndroidUnifiedCommitReplanObserver =
        RoomAndroidUnifiedCommitReplanObserver { },
    membershipLedgerStaleObserver: RoomAndroidMembershipLedgerStaleObserver =
        RoomAndroidMembershipLedgerStaleObserver { },
    canonicalContactRejectionObserver: AndroidCanonicalContactRejectionObserver =
        AndroidCanonicalContactRejectionObserver { },
    projectionRepairObserver: AndroidProjectionRepairObserver = AndroidProjectionRepairObserver { },
    syncRunnerObserver: com.patmanak.contako.domain.sync.SyncRunnerObserver = object : com.patmanak.contako.domain.sync.SyncRunnerObserver {},
    remoteGroupFailureObserver: (GatewayFailureCategory) -> Unit = {},
): ProductionSharedSyncRuntime {
    val gateD = proton.gateD
    return composeProductionSharedSyncRuntime(
        context,
        database,
        ProductionSyncDependencies(
            proton.accountScope,
            proton.session,
            gateD.inventory,
            gateD.verifiedCards,
            gateD.contactMutations,
            gateD.groups,
            gateD.emailLabels,
            gateD.membershipReader,
            proton.session as ProtonLocalSessionCleanupGateway,
            events = gateD.events,
            existence = gateD.existence,
            releaseOwnerAfterLocalDataCleared = proton.releaseOwnerAfterLocalDataCleared,
            invalidateGroupCapabilities = { proton.invalidateGroupCapabilities(
                com.patmanak.contako.data.proton.ContactGroupCapabilityInvalidationReason.MANUAL_REFRESH) },
            canCleanOwnedScope = proton.canCleanOwnedScope,
            localOwnerIdentity = proton.localOwnerIdentity,
            hasNoCoreAccount = proton.hasNoCoreAccount,
            isAccountReady = { proton.readyOwnerUserId() != null },
            capabilityInvalidationGeneration = proton.capabilityInvalidationGeneration,
        ),
        runnerScope,
        stopScheduling,
        passStageObserver,
        androidStageResultObserver,
        projectionReplanObserver,
        providerProjectionReplanObserver,
        remoteContactActionRequiredObserver,
        mutationActionRequiredObserver,
        androidIngestActionRequiredObserver,
        androidIngestReplanObserver,
        androidGroupCommitRepairObserver,
        existingContactPlanRepairObserver,
        existingContactCommitRepairObserver,
        existingContactCommitReplanObserver,
        membershipLedgerStaleObserver,
        canonicalContactRejectionObserver,
        projectionRepairObserver,
        syncRunnerObserver,
        remoteGroupFailureObserver,
    )
}

internal fun composeProductionSharedSyncRuntime(
    context: Context,
    database: ContakoDatabase,
    dependencies: ProductionSyncDependencies,
    runnerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    stopScheduling: () -> Unit = {},
    passStageObserver: SyncPassStageObserver = SyncPassStageObserver { },
    androidStageResultObserver: AndroidSyncStageResultObserver =
        AndroidSyncStageResultObserver { _, _ -> },
    projectionReplanObserver: AndroidProjectionReplanObserver = AndroidProjectionReplanObserver { },
    providerProjectionReplanObserver: AndroidProviderProjectionReplanObserver =
        AndroidProviderProjectionReplanObserver { },
    remoteContactActionRequiredObserver: RemoteContactActionRequiredObserver =
        RemoteContactActionRequiredObserver { _, _, _ -> },
    mutationActionRequiredObserver: MutationActionRequiredObserver =
        MutationActionRequiredObserver { },
    androidIngestActionRequiredObserver: AndroidIngestActionRequiredObserver =
        AndroidIngestActionRequiredObserver { },
    androidIngestReplanObserver: AndroidIngestReplanObserver = AndroidIngestReplanObserver { },
    androidGroupCommitRepairObserver: RoomAndroidGroupCommitRepairObserver =
        RoomAndroidGroupCommitRepairObserver { },
    existingContactPlanRepairObserver: AndroidExistingContactPlanRepairObserver =
        AndroidExistingContactPlanRepairObserver { },
    existingContactCommitRepairObserver: RoomAndroidUnifiedCommitRepairObserver =
        RoomAndroidUnifiedCommitRepairObserver { },
    existingContactCommitReplanObserver: RoomAndroidUnifiedCommitReplanObserver =
        RoomAndroidUnifiedCommitReplanObserver { },
    membershipLedgerStaleObserver: RoomAndroidMembershipLedgerStaleObserver =
        RoomAndroidMembershipLedgerStaleObserver { },
    canonicalContactRejectionObserver: AndroidCanonicalContactRejectionObserver =
        AndroidCanonicalContactRejectionObserver { },
    projectionRepairObserver: AndroidProjectionRepairObserver = AndroidProjectionRepairObserver { },
    syncRunnerObserver: com.patmanak.contako.domain.sync.SyncRunnerObserver = object : com.patmanak.contako.domain.sync.SyncRunnerObserver {},
    remoteGroupFailureObserver: (GatewayFailureCategory) -> Unit = {},
): ProductionSharedSyncRuntime {
    val applicationContext = context.applicationContext
    val account = dependencies.account
    val repository = RoomContactRepository(database)
    val checkpointStore: ContactInventoryCheckpointStore = RoomContactInventoryCheckpointStore(database)
    val contentResolver = applicationContext.contentResolver
    val contactsReader = AndroidContactsProviderReader(contentResolver)
    val groupsReader = AndroidGroupsProviderReader(contentResolver)
    val readers = BoundedAndroidInteroperabilityStage.productionReaders(contactsReader, groupsReader)
    // Counts contacts the projection had to skip during one pass, so the sync screen can report
    // them instead of silently showing an incomplete Android projection.
    val skippedProjections = java.util.concurrent.atomic.AtomicInteger(0)
    val projectionRepairs = AndroidProjectionRepairAggregate()
    val projectionRepairFanOut = object : AndroidProjectionRepairObserver {
        override fun onRepair(category: AndroidProjectionRepairCategory) {
            projectionRepairs.onRepair(category)
            runCatching { projectionRepairObserver.onRepair(category) }
        }
        override fun onComponentMismatch(kind: AndroidRowKind, component: AndroidComponent, difference: AndroidComponentDifference) {
            runCatching { projectionRepairObserver.onComponentMismatch(kind, component, difference) }
        }
        override fun onPhotoFailure(category: com.patmanak.contako.data.android.provider.AndroidPhotoProviderRepairCategory) {
            runCatching { projectionRepairObserver.onPhotoFailure(category) }
        }
        override fun onBindingRecoveryFailure(
            stage: AndroidProjectionDecodeStage,
            detail: com.patmanak.contako.data.android.provider.AndroidBindingRecoveryDiagnostic,
        ) {
            runCatching { projectionRepairObserver.onBindingRecoveryFailure(stage, detail) }
        }
        override fun onRowFailure(
            stage: AndroidProjectionDecodeStage,
            category: com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure,
            identity: com.patmanak.contako.data.android.provider.AndroidProviderIdentityFailure?,
            kind: AndroidRowKind?,
        ) {
            runCatching { projectionRepairObserver.onRowFailure(stage, category, identity, kind) }
        }
    }
    // Without a real loader every photo-bearing contact resolves to a null payload and the
    // projection item executor returns RepairRequired, excluding it from the Android provider.
    val projectionCoordinator = productionAndroidProjectionCoordinator(
        database,
        contentResolver,
        CanonicalPhotoBinaryLoader,
        skipObserver = { _, _ -> skippedProjections.incrementAndGet() },
        repairObserver = projectionRepairFanOut,
        replanObserver = projectionReplanObserver,
        providerReplanObserver = providerProjectionReplanObserver,
    )
    val fullRepairStore = RoomFullRepairProgressStore(database)
    val androidStage = BoundedAndroidInteroperabilityStage(
        contactsReader = readers.first,
        groupsReader = readers.second,
        observationCoordinator = productionGroupObservationCoordinator(
            database,
            repository,
            contentResolver,
            actionRequiredObserver = androidIngestActionRequiredObserver,
            replanObserver = androidIngestReplanObserver,
            groupCommitRepairObserver = androidGroupCommitRepairObserver,
            existingContactPlanRepairObserver = existingContactPlanRepairObserver,
            existingContactCommitRepairObserver = existingContactCommitRepairObserver,
            existingContactCommitReplanObserver = existingContactCommitReplanObserver,
            membershipLedgerStaleObserver = membershipLedgerStaleObserver,
            canonicalContactRejectionObserver = canonicalContactRejectionObserver,
        ),
        projectionCoordinator = projectionCoordinator,
        actionRequiredObserver = androidIngestActionRequiredObserver,
        replanObserver = androidIngestReplanObserver,
        exactContactsReader = AndroidExactContactReader { name, id -> contactsReader.readStableRawContact(name, id) },
    )
    val androidPreflight = AndroidRuntimeAccountPreflight(
        RoomAndroidDurableAccountContextReader(database), AndroidContactsRuntimeProbe(applicationContext))
    val targetedStore = com.patmanak.contako.data.local.RoomTargetedRecoveryStore(database)
    val targetedRecovery = TargetedContactRecovery(targetedStore, dependencies.verifiedCards,
        ProductionTargetedAndroidRecovery(database, androidPreflight, androidStage, projectionCoordinator),
        prepareProton = { command, cancelled ->
            prepareTargetedProtonRead(database, androidPreflight, androidStage, command, cancelled)
        })
    val groupCatalogAvailable = java.util.concurrent.atomic.AtomicBoolean(false)
    val mutationPreparation = RoomBackedMutationPreparationGateway(account, database, dependencies.membershipReader,
        dependencies.verifiedCards, dependencies.existence)
    val mutationOrchestrator = DurableMutationOrchestrator(
        RoomMutationExecutionStore(database, dependencies.existence,
            recoveryGeneration = dependencies.capabilityInvalidationGeneration),
        MutationPreparationGateway { command ->
            if (command.aggregateType == com.patmanak.contako.data.local.AggregateType.GROUP.name &&
                !groupCatalogAvailable.get()) GatewayOutcome.Success(MutationPreparation.WaitingForDependencies)
            else mutationPreparation.prepare(command)
        },
        RoomBackedMutationUploadGateway(
            account,
            database,
            dependencies.contactMutations,
            dependencies.groups,
            dependencies.membershipReader,
            dependencies.emailLabels,
        ),
        actionRequiredObserver = mutationActionRequiredObserver,
    )
    // Reset per pass by the publisher below, so a recovered pass stops reporting degradation.
    val androidDegradation = java.util.concurrent.atomic.AtomicReference<SyncActionReason?>(null)
    val remoteFailure = java.util.concurrent.atomic.AtomicReference<SyncActionReason?>(null)
    var completionDetails: com.patmanak.contako.domain.sync.SyncPassCompletionDetailsSource? = null
    val executor = ContakoSyncPassExecutor(
        onPassStarted = { request ->
            androidDegradation.set(null)
            remoteFailure.set(null)
            skippedProjections.set(0)
            projectionRepairs.snapshotAndReset()
            if (com.patmanak.contako.domain.sync.SyncTrigger.MANUAL in request.triggers)
                dependencies.invalidateGroupCapabilities()
        },
        onGroupCatalogAvailability = groupCatalogAvailable::set,
        account = account,
        prerequisites = SyncPrerequisiteChecker {
            when (val restored = dependencies.session.restore(account)) {
                is GatewayOutcome.Success -> when (restored.value) {
                    SessionState.READY -> SyncPrerequisiteState.READY
                    SessionState.AUTHENTICATION_REQUIRED,
                    SessionState.REVOKED,
                    -> SyncPrerequisiteState.AUTHENTICATION_REQUIRED
                    SessionState.INTERACTIVE_MAILBOX_PASSWORD_REQUIRED,
                    SessionState.INTERACTIVE_KEY_UNLOCK_REQUIRED,
                    -> SyncPrerequisiteState.INTERACTIVE_ACTION_REQUIRED
                }
                is GatewayOutcome.Failure -> when (restored.category) {
                    GatewayFailureCategory.NETWORK_UNAVAILABLE,
                    GatewayFailureCategory.TIMEOUT,
                    GatewayFailureCategory.RATE_LIMITED,
                    GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
                    -> SyncPrerequisiteState.OFFLINE
                    else -> SyncPrerequisiteState.AUTHENTICATION_REQUIRED
                }
            }
        },
        remoteStage = IncrementalRemoteContactStage(
            dependencies.inventory,
            dependencies.verifiedCards,
            PersistentContactInventoryPlanner(checkpointStore),
            // Remote image references remain canonical references. Synchronization MUST NOT make
            // automatic requests to arbitrary contact-controlled hosts.
            RoomRemoteCanonicalReconciliationStore(database),
            obligations = com.patmanak.contako.data.local.RoomRemoteHydrationObligationStore(database),
            existenceGateway = dependencies.existence,
            eventsGateway = dependencies.events,
            hydrationBatchSize = 25,
            hydrationReadConcurrency = 10,
            importObserver = passStageObserver,
            actionRequiredObserver = RemoteContactActionRequiredObserver { boundary, category, hydration ->
                remoteFailure.set(when (category) {
                    GatewayFailureCategory.AUTHENTICATION_REQUIRED -> SyncActionReason.AUTHENTICATION_REQUIRED
                    GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED -> SyncActionReason.CRYPTOGRAPHIC_VERIFICATION_FAILED
                    GatewayFailureCategory.VALIDATION_REJECTED -> SyncActionReason.VALIDATION_REJECTED
                    else -> SyncActionReason.INTERNAL_FAILURE
                })
                remoteContactActionRequiredObserver.onActionRequired(boundary, category, hydration)
            },
        ),
        mutationOrchestrator = mutationOrchestrator,
        groupStage = IncrementalRemoteGroupStage(
            dependencies.groups,
            RoomRemoteGroupReconciliationStore(database),
            { category ->
                if (category == GatewayFailureCategory.MALFORMED_RESPONSE ||
                    category == GatewayFailureCategory.VALIDATION_REJECTED)
                    remoteFailure.set(SyncActionReason.GROUP_CAPABILITY_REQUIRED)
                remoteGroupFailureObserver(category)
            },
            exceptionObserver = passStageObserver::onException,
        ),
        statusPublisher = RoomSyncPassStatusPublisher(
            account.value,
            database,
            hasUnscopedFailure = { (completionDetails as? ContakoSyncPassExecutor)?.hasUnscopedFailure != false },
            afterPublish = {
                com.patmanak.contako.android.sync.AndroidSyncActionNotifier(applicationContext, RoomSyncStatusStore(database)).refresh(account.value)
            },
            // Without this the publisher had no reason to report and fell back to
            // INTERNAL_FAILURE, which claimed pending changes needed attention even when
            // the outbox was empty and only Android interoperability was degraded.
            // getAndSet clears the flag as it is read, so the next pass starts clean and a
            // recovered Android boundary stops reporting a stale degradation.
            externalActionReason = {
                val degraded = androidDegradation.getAndSet(null)
                val remote = remoteFailure.getAndSet(null)
                val categorizedRepairs = projectionRepairs.snapshotAndReset().values.sum()
                val skipped = maxOf(skippedProjections.getAndSet(0), categorizedRepairs)
                // Skipped contacts are the more precise diagnosis: Android access works, only
                // some contacts are missing, so it takes precedence over the blanket reason.
                when {
                    completionDetails?.completionCause == com.patmanak.contako.domain.sync.SyncCompletionCause.AUTHENTICATION ->
                        SyncActionReason.AUTHENTICATION_REQUIRED
                    remote != null -> remote
                    skipped > 0 -> SyncActionReason.ANDROID_CONTACTS_PARTIALLY_PROJECTED
                    else -> degraded
                }
            },
            // The status store already distinguishes offline retry from generic pending work, but
            // production never supplied the network predicate and therefore could never publish
            // OFFLINE. This is a status-only read; it does not impose a network-type policy.
            isOffline = {
                AndroidSchedulingNetworkMonitor(applicationContext).current() == SchedulingNetworkState.OFFLINE
            },
        ),
        androidPreflight = androidPreflight,
        androidStage = androidStage,
        onAndroidDegraded = { androidDegradation.set(SyncActionReason.ANDROID_INTEROPERABILITY_DEGRADED) },
        stageObserver = passStageObserver,
        androidStageResultObserver = androidStageResultObserver,
        androidRepair = AndroidProviderResetRepairCoordinator(
            database,
            projectionCoordinator,
            FrameworkAndroidProviderEpochProofWriter(applicationContext),
        ),
        fullRepairCoordinator = RoomFullRepairExecutionCoordinator(account, fullRepairStore),
        targetedRecovery = targetedRecovery,
    )
    completionDetails = executor
    val removalCheckpoints = SharedPreferencesAccountRemovalCheckpointStore(applicationContext)
    val unprovisionedCheckpoints = SharedPreferencesUnprovisionedRemovalCheckpointStore(applicationContext)
    val hasPendingCleanup = {
        removalCheckpoints.pendingAccount(account) != null || unprovisionedCheckpoints.load(account) != null
    }
    // Close admission before exposing the runner to SyncAdapter, workers or UI callers.
    val cleanupPending = runCatching(hasPendingCleanup).getOrDefault(true)
    val lifecycleAdmission = AccountLifecycleAdmission(dependencies.isAccountReady, hasPendingCleanup)
    val runner = AccountSyncRunner(runnerScope, syncRunnerObserver, initiallyInvalidated = cleanupPending, executor = executor)
    runnerScope.launch {
        watchTargetedRecoveryWakeups(database.targetedRecoveryRequestDao().observeNextEligible(account.value),
            runner.isInvalidated) {
            runner.request(com.patmanak.contako.domain.sync.SyncTrigger.TARGETED_RECOVERY)
        }
    }
    runnerScope.launch {
        if (!cleanupPending && fullRepairStore.load(account) != null) {
            runner.request(
                com.patmanak.contako.domain.sync.SyncTrigger.MANUAL,
                com.patmanak.contako.domain.sync.SyncScope.FULL_REPAIR,
            )
        } else if (!cleanupPending && targetedStore.hasEligible(account, System.currentTimeMillis())) {
            runner.request(com.patmanak.contako.domain.sync.SyncTrigger.TARGETED_RECOVERY)
        }
    }
    val resolver = exactBoundAccountResolver(database, account, runner)
    val removal = AndroidAccountRemovalCoordinator(
            FrameworkAndroidAccountProjectionCleaner(applicationContext.contentResolver),
            database,
            account,
            dependencies.localSessionCleanup,
            runner,
            FrameworkAccountPlatformCleanup(applicationContext, stopScheduling),
            removalCheckpoints,
            releaseOwnerAfterLocalDataCleared = dependencies.releaseOwnerAfterLocalDataCleared,
        )
    val noAndroidBinding: suspend () -> Boolean = {
        database.androidProjectionLedgerDao().getAccount(account.value)?.androidAccountName == null
    }
    val unprovisionedSignOut = com.patmanak.contako.android.account.UnprovisionedAccountSignOut(
        account, runner,
        ownerIsVerified = dependencies.canCleanOwnedScope,
        hasNoAndroidBinding = noAndroidBinding,
        pendingMutations = PendingMutationReader { database.accountRemovalDao().countPending(it.value) },
        revokeSession = { dependencies.localSessionCleanup.clearAfterBestEffortRevocation(account) is GatewayOutcome.Success },
        clearLocalData = { discard ->
            database.withTransaction {
                if (!noAndroidBinding() || (!discard && database.accountRemovalDao().countPending(account.value) != 0)) false
                else {
                    database.accountRemovalDao().deleteAccount(account.value)
                    true
                }
            }
        },
        releaseOwner = dependencies.releaseOwnerAfterLocalDataCleared,
        ownerIdentity = dependencies.localOwnerIdentity,
        hasNoCoreAccount = dependencies.hasNoCoreAccount,
        isLocalDataEmpty = { !database.hasAccountScopedData() },
        checkpoints = unprovisionedCheckpoints,
        stopScheduling = stopScheduling,
    )
    return ProductionSharedSyncRuntime(
        runner,
        resolver,
        AccountRemovalCoordinator { androidAccount -> lifecycleAdmission.cleanup { removal.remove(androidAccount) } },
        accountProvisioningCoordinator = AndroidAccountProvisioningCoordinator(
            RoomAndroidAccountBindingStore(RoomAndroidProjectionLedger(database)),
            FrameworkAndroidAccountPlatform(applicationContext),
        ),
        accountLifecycleAdmission = lifecycleAdmission,
        accountSignOutCoordinator = AccountSignOutCoordinator(
            account,
            PendingMutationReader { scope ->
                withContext(Dispatchers.IO) {
                    val binding = RoomAndroidDurableAccountContextReader(database).load(scope)
                    check(binding == null || !binding.hasCrossAccountBinding)
                    val roomPending = database.accountRemovalDao().countPending(scope.value)
                    val name = binding?.androidAccountName ?: removalCheckpoints.pendingAccount(scope)?.name
                    roomPending + if (name == null) 0 else
                        FrameworkAndroidAccountProjectionCleaner(applicationContext.contentResolver)
                            .pendingChanges(name, ContakoAndroidAccountContract.ACCOUNT_TYPE)
                }
            },
            BoundAndroidAccountReader { scope ->
                database.androidProjectionLedgerDao().getAccount(scope.value)?.androidAccountName?.let {
                    Account(it, ContakoAndroidAccountContract.ACCOUNT_TYPE)
                } ?: removalCheckpoints.pendingAccount(scope)
            },
            runner,
            InAppAccountRemoval(removal::removeFromApp),
            noAndroidRemoval = unprovisionedSignOut::remove,
            hasIncompleteRemoval = { hasPendingCleanup() },
            lifecycleAdmission = lifecycleAdmission,
        ),
    )
}

private fun exactBoundAccountResolver(
    database: ContakoDatabase,
    account: AccountScope,
    runner: AccountSyncRunner,
) = AndroidAccountSyncRunnerResolver { androidAccount: Account ->
    if (androidAccount.type != ContakoAndroidAccountContract.ACCOUNT_TYPE) {
        null
    } else {
        val durable = try {
            runBlocking {
                RoomAndroidDurableAccountContextReader(database).load(
                    account,
                )
            }
        } catch (_: Throwable) {
            null
        }
        runner.takeIf {
            durable != null && !durable.hasCrossAccountBinding &&
                durable.androidAccountName == androidAccount.name
        }
    }
}
