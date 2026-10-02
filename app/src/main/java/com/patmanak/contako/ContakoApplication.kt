package com.patmanak.contako

import com.patmanak.contako.diagnostics.SanitizedDiagnosticEvent
import com.patmanak.contako.diagnostics.SanitizedDiagnosticLog
import com.patmanak.contako.diagnostics.DiagnosticAndroidResult
import com.patmanak.contako.data.sync.SyncPassStage
import com.patmanak.contako.data.sync.SyncPassExceptionCategory
import com.patmanak.contako.domain.sync.SyncPassOutcome
import kotlinx.coroutines.flow.catch
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.sync.AndroidComponentDifference
import com.patmanak.contako.data.sync.AndroidProjectionRepairObserver
import com.patmanak.contako.data.sync.AndroidProjectionRepairCategory
import com.patmanak.contako.data.android.provider.AndroidPhotoProviderRepairCategory
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure
import com.patmanak.contako.data.sync.AndroidExistingContactPlanRepairObserver
import com.patmanak.contako.data.sync.AndroidExistingContactPlanRepairReason

import android.app.Application
import com.patmanak.contako.android.sync.AndroidAccountSyncRunnerResolver
import com.patmanak.contako.android.sync.ContakoSyncAdapterRuntime
import com.patmanak.contako.android.account.AccountRemovalCoordinator
import com.patmanak.contako.android.account.ContakoAccountRemovalRuntime
import com.patmanak.contako.android.account.AccountProvisioningResult
import com.patmanak.contako.android.account.AccountSignOutCoordinator
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.data.proton.GateCInteractiveHumanVerification
import com.patmanak.contako.data.sync.ProductionSharedSyncRuntime
import com.patmanak.contako.data.sync.composeProductionSharedSyncRuntime
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.data.sync.RoomSyncRecoveryDataSource
import com.patmanak.contako.data.sync.AndroidNetworkStateProvider
import com.patmanak.contako.data.sync.AndroidSchedulingNetworkMonitor
import com.patmanak.contako.data.sync.AndroidSyncAccountEligibility
import com.patmanak.contako.data.sync.AndroidSyncWorkScheduler
import com.patmanak.contako.data.sync.LastSuccessfulSyncReader
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.SchedulingClock
import com.patmanak.contako.data.sync.SyncSchedulingPolicy
import com.patmanak.contako.data.android.provider.FrameworkAndroidAutomaticSyncState
import com.patmanak.contako.domain.sync.SyncRecoveryDataSource
import com.patmanak.contako.ui.theme.AndroidThemePreferenceStore
import com.patmanak.contako.ui.theme.ThemePreferenceBoundary
import com.patmanak.contako.ui.locale.AndroidLanguagePreferenceStore
import com.patmanak.contako.ui.locale.LanguagePreferenceBoundary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.patmanak.contako.data.proton.GateCAuthDiagnostic
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.sync.SyncPassStageObserver
import com.patmanak.contako.data.sync.RemoteContactActionRequiredObserver
import com.patmanak.contako.domain.sync.SyncTrigger

class ContakoApplication : Application(), ContakoSyncAdapterRuntime, ContakoAccountRemovalRuntime {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database: ContakoDatabase by lazy { ContakoDatabase.create(this) }

    internal fun refreshSyncNotification() {
        applicationScope.launch {
            com.patmanak.contako.android.sync.AndroidSyncActionNotifier(this@ContakoApplication, RoomSyncStatusStore(database))
                .refresh(protonGateCRuntime.accountScope.value)
        }
    }

    internal val contactRepository: ContactRepository by lazy {
        RoomContactRepository(database)
    }

    internal val themePreferences: ThemePreferenceBoundary by lazy {
        AndroidThemePreferenceStore(this)
    }

    internal val languagePreferences: LanguagePreferenceBoundary by lazy {
        AndroidLanguagePreferenceStore(this)
    }

    internal val humanVerification: GateCInteractiveHumanVerification by lazy {
        GateCInteractiveHumanVerification()
    }

    internal val protonGateCRuntime: ProtonGateCRuntime by lazy {
        ProtonGateCRuntime.create(this, humanVerification.hooks, if (BuildConfig.SANITIZED_DIAGNOSTICS) {
            object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) {
                    SanitizedDiagnosticLog.write(SanitizedDiagnosticEvent.AuthPhase(phase))
                }
                override fun onFailure(event: GateCAuthDiagnosticEvent) {
                    SanitizedDiagnosticLog.write(SanitizedDiagnosticEvent.AuthFailure(event))
                }
            }
        } else GateCAuthDiagnostic.Disabled, updateFailureObserver = { stage, category, encoding, field ->
            syncDiagnostic { SanitizedDiagnosticEvent.ContactUpdateFailure(stage, category, encoding, field) }
        })
    }

    private inline fun syncDiagnostic(event: () -> SanitizedDiagnosticEvent) {
        if (BuildConfig.SANITIZED_DIAGNOSTICS || BuildConfig.SYNC_DIAGNOSTICS) {
            SanitizedDiagnosticLog.write(event())
        }
    }

    private val sharedSyncRuntime: ProductionSharedSyncRuntime by lazy {
        composeProductionSharedSyncRuntime(
            this,
            database,
            protonGateCRuntime,
            stopScheduling = syncSchedulingPolicy::close,
            passStageObserver = object : SyncPassStageObserver {
                override fun onStage(stage: SyncPassStage) {
                    syncDiagnostic { SanitizedDiagnosticEvent.SyncStage(stage) }
                }
                override fun onException(category: SyncPassExceptionCategory) {
                    syncDiagnostic { SanitizedDiagnosticEvent.SyncException(category) }
                }
                override fun onOutcome(outcome: SyncPassOutcome) {
                    syncDiagnostic { SanitizedDiagnosticEvent.SyncOutcome(outcome) }
                }
            },
            androidStageResultObserver = { stage, result ->
                syncDiagnostic { SanitizedDiagnosticEvent.AndroidResult(stage, DiagnosticAndroidResult.from(result)) }
            },
            projectionRepairObserver = object : AndroidProjectionRepairObserver {
                override fun onRepair(category: AndroidProjectionRepairCategory) {
                    syncDiagnostic { SanitizedDiagnosticEvent.ProjectionRepair(category) }
                }
                override fun onComponentMismatch(kind: AndroidRowKind, component: AndroidComponent, difference: AndroidComponentDifference) {
                    syncDiagnostic { SanitizedDiagnosticEvent.ProjectionComponent(kind, component, difference) }
                }
                override fun onPhotoFailure(category: AndroidPhotoProviderRepairCategory) {
                    syncDiagnostic { SanitizedDiagnosticEvent.PhotoWriteFailure(category) }
                }
                override fun onBindingRecoveryFailure(
                    stage: com.patmanak.contako.data.sync.AndroidProjectionDecodeStage,
                    detail: com.patmanak.contako.data.android.provider.AndroidBindingRecoveryDiagnostic,
                ) {
                    syncDiagnostic { SanitizedDiagnosticEvent.ProjectionBindingRecoveryFailure(stage, detail) }
                }
                override fun onRowFailure(
                    stage: com.patmanak.contako.data.sync.AndroidProjectionDecodeStage,
                    category: AndroidProviderRowCodecFailure,
                    identity: com.patmanak.contako.data.android.provider.AndroidProviderIdentityFailure?,
                    kind: AndroidRowKind?,
                ) {
                    syncDiagnostic { SanitizedDiagnosticEvent.ProjectionRowFailure(stage, category, identity, kind) }
                }
            },
            projectionReplanObserver = { category ->
                syncDiagnostic { SanitizedDiagnosticEvent.ProjectionReplan(category) }
            },
            providerProjectionReplanObserver = { reason ->
                syncDiagnostic { SanitizedDiagnosticEvent.ProviderReplan(reason) }
            },
            androidIngestActionRequiredObserver = { reason ->
                syncDiagnostic { SanitizedDiagnosticEvent.IngestFailure(reason) }
            },
            existingContactPlanRepairObserver = object : AndroidExistingContactPlanRepairObserver {
                override fun onRepairRequired(reason: AndroidExistingContactPlanRepairReason) {
                    syncDiagnostic { SanitizedDiagnosticEvent.ExistingContactPlanFailure(reason) }
                }
                override fun onRowCodecFailure(category: AndroidProviderRowCodecFailure) {
                    syncDiagnostic { SanitizedDiagnosticEvent.IngestRowCodecFailure(category) }
                }
                override fun onBaselineMismatch(detail: com.patmanak.contako.data.sync.AndroidInitialBaselineMismatch) {
                    syncDiagnostic { SanitizedDiagnosticEvent.IngestBaselineMismatch(
                        detail.category, detail.kind, detail.component, detail.difference,
                    ) }
                }
            },
            androidIngestReplanObserver = { reason ->
                syncDiagnostic { SanitizedDiagnosticEvent.IngestReplan(reason) }
            },
            existingContactCommitReplanObserver = { reason ->
                syncDiagnostic { SanitizedDiagnosticEvent.IngestCommitReplan(reason) }
            },
            membershipLedgerStaleObserver = { reason ->
                syncDiagnostic { SanitizedDiagnosticEvent.IngestMembershipStale(reason) }
            },
            mutationActionRequiredObserver = { diagnostic ->
                syncDiagnostic { SanitizedDiagnosticEvent.MutationFailure(
                    diagnostic.source, diagnostic.operation, diagnostic.failureCategory, diagnostic.preparationReason,
                ) }
            },
            remoteContactActionRequiredObserver = RemoteContactActionRequiredObserver { boundary, category, hydration ->
                syncDiagnostic { SanitizedDiagnosticEvent.ContactFailure(boundary, category, hydration) }
            },
        )
    }

    internal val syncSchedulingPolicy: SyncSchedulingPolicy by lazy {
        val automaticSyncState = FrameworkAndroidAutomaticSyncState()
        SyncSchedulingPolicy(
            scope = applicationScope,
            clock = SchedulingClock(System::currentTimeMillis),
            accountEligibility = AndroidSyncAccountEligibility(
                this,
                database,
                protonGateCRuntime.accountScope,
                automaticSyncState,
            ),
            lastSuccessfulSyncReader = LastSuccessfulSyncReader {
                RoomSyncStatusStore(database).load(protonGateCRuntime.accountScope.value)?.lastSuccessAtEpochMillis
            },
            scheduler = AndroidSyncWorkScheduler(automaticSyncState),
            networkMonitor = AndroidSchedulingNetworkMonitor(this),
            foregroundFirstImport = { sharedSyncRuntime.runner.request(SyncTrigger.FIRST_IMPORT) },
            foregroundSync = { triggers -> triggers.forEach { sharedSyncRuntime.runner.request(it) } },
            hasPendingMutations = { database.outboxDao().observePendingCount(protonGateCRuntime.accountScope.value).first() > 0 },
            hasPendingAndroidChanges = { accountName ->
                com.patmanak.contako.data.android.provider.AndroidContactsProviderReader(contentResolver)
                    .hasDirtyRawContacts(com.patmanak.contako.data.android.provider.AndroidProviderAccountName(accountName))
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (!instrumentationStartupSuppressed) syncSchedulingPolicy.initialize()
        if (BuildConfig.SANITIZED_DIAGNOSTICS || BuildConfig.SYNC_DIAGNOSTICS) applicationScope.launch {
            RoomSyncStatusStore(database).observe(protonGateCRuntime.accountScope.value)
                .catch { /* Optional diagnostics MUST NOT terminate the application on a Room read failure. */ }
                .collect { status ->
                    if (status == null) return@collect
                    val contactCount = runCatching {
                        database.openHelper.readableDatabase.query(
                            "SELECT COUNT(*) FROM contacts WHERE account_id = ? AND is_deleted = 0",
                            arrayOf(protonGateCRuntime.accountScope.value),
                        ).use { rows -> rows.moveToFirst(); rows.getLong(0) }
                    }.getOrNull() ?: return@collect
                    SanitizedDiagnosticLog.write(SanitizedDiagnosticEvent.SyncStatus(
                        status.state, status.actionReason, status.pendingMutationCount, status.actionRequiredCount, contactCount,
                    ))
                }
        }
    }

    companion object {
        @Volatile
        private var instrumentationStartupSuppressed = false

        internal fun suppressStartupForInstrumentation() {
            instrumentationStartupSuppressed = true
        }
    }

    internal val syncRecoveryDataSource: SyncRecoveryDataSource by lazy {
        RoomSyncRecoveryDataSource(
            database,
            sharedSyncRuntime.runner,
            AndroidNetworkStateProvider(this),
            expectedAccount = protonGateCRuntime.accountScope,
        )
    }

    override val androidAccountSyncRunnerResolver: AndroidAccountSyncRunnerResolver
        get() = sharedSyncRuntime.androidAccountSyncRunnerResolver

    override val accountRemovalCoordinator: AccountRemovalCoordinator
        get() = sharedSyncRuntime.accountRemovalCoordinator

    internal val accountSignOutCoordinator: AccountSignOutCoordinator
        get() = sharedSyncRuntime.accountSignOutCoordinator

    /**
     * Creates the Android account and its durable binding for the connected Proton account.
     *
     * Without this, `AndroidRuntimeAccountPreflight` never reaches `Ready` and every pass degrades
     * to action-required. Safe to call repeatedly: provisioning is idempotent.
     */
    internal suspend fun provisionAndroidAccount(protonAccountAddress: String): AccountProvisioningResult {
        val result = sharedSyncRuntime.accountProvisioningCoordinator.provision(
            protonGateCRuntime.accountScope,
            protonAccountAddress,
        )
        if (result == AccountProvisioningResult.READY) {
            sharedSyncRuntime.runner.reactivateAfterAccountProvisioning()
        }
        return result
    }
}
