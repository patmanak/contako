package com.patmanak.contako.data.sync

import android.accounts.Account
import android.content.Context
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.android.account.AccountRemovalCoordinator
import com.patmanak.contako.android.account.AndroidAccountRemovalCoordinator
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.android.account.AccountSignOutCoordinator
import com.patmanak.contako.android.account.AndroidAccountProvisioningCoordinator
import com.patmanak.contako.android.account.RoomAndroidAccountBindingStore
import com.patmanak.contako.data.android.provider.FrameworkAndroidAccountPlatform
import com.patmanak.contako.android.account.BoundAndroidAccountReader
import com.patmanak.contako.android.account.FrameworkAccountPlatformCleanup
import com.patmanak.contako.android.account.PendingMutationReader
import com.patmanak.contako.android.account.InAppAccountRemoval
import com.patmanak.contako.android.account.SharedPreferencesAccountRemovalCheckpointStore
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
            gateD.existence,
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
    )
    val mutationOrchestrator = DurableMutationOrchestrator(
        RoomMutationExecutionStore(database, dependencies.existence),
        RoomBackedMutationPreparationGateway(account, database, dependencies.membershipReader,
            dependencies.verifiedCards, dependencies.existence),
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
    val executor = ContakoSyncPassExecutor(
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
            existenceGateway = dependencies.existence,
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
        ),
        statusPublisher = RoomSyncPassStatusPublisher(
            account.value,
            database,
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
        androidPreflight = AndroidRuntimeAccountPreflight(
            RoomAndroidDurableAccountContextReader(database),
            AndroidContactsRuntimeProbe(applicationContext),
        ),
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
    )
    val runner = AccountSyncRunner(runnerScope, executor)
    runnerScope.launch {
        if (fullRepairStore.load(account) != null) {
            runner.request(
                com.patmanak.contako.domain.sync.SyncTrigger.MANUAL,
                com.patmanak.contako.domain.sync.SyncScope.FULL_REPAIR,
            )
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
            SharedPreferencesAccountRemovalCheckpointStore(applicationContext),
        )
    return ProductionSharedSyncRuntime(
        runner,
        resolver,
        removal,
        accountProvisioningCoordinator = AndroidAccountProvisioningCoordinator(
            RoomAndroidAccountBindingStore(RoomAndroidProjectionLedger(database)),
            FrameworkAndroidAccountPlatform(applicationContext),
        ),
        accountSignOutCoordinator = AccountSignOutCoordinator(
            account,
            PendingMutationReader { scope ->
                withContext(Dispatchers.IO) {
                    val binding = RoomAndroidDurableAccountContextReader(database).load(scope)
                    check(binding != null && !binding.hasCrossAccountBinding)
                    val name = checkNotNull(binding.androidAccountName)
                    database.accountRemovalDao().countPending(scope.value) +
                        FrameworkAndroidAccountProjectionCleaner(applicationContext.contentResolver)
                            .pendingChanges(name, ContakoAndroidAccountContract.ACCOUNT_TYPE)
                }
            },
            BoundAndroidAccountReader { scope ->
                database.androidProjectionLedgerDao().getAccount(scope.value)?.androidAccountName?.let {
                    Account(it, ContakoAndroidAccountContract.ACCOUNT_TYPE)
                }
            },
            runner,
            InAppAccountRemoval(removal::removeFromApp),
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
