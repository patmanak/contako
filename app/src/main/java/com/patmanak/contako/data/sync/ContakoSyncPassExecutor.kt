package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.SyncPassExecutor
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncScope
import kotlinx.coroutines.CancellationException

enum class SyncPrerequisiteState {
    READY,
    OFFLINE,
    AUTHENTICATION_REQUIRED,
    INTERACTIVE_ACTION_REQUIRED,
}

fun interface SyncPrerequisiteChecker {
    suspend fun check(): SyncPrerequisiteState
}

/**
 * Payload-free production pass boundaries. Observers receive only this fixed enumeration: no
 * account, contact, provider, remote response, exception, or stable identifier can cross it.
 */
internal enum class SyncPassStage {
    REQUEST_GATE,
    FULL_REPAIR_SETUP,
    ANDROID_PREFLIGHT,
    ANDROID_REPAIR,
    ANDROID_INGEST,
    ANDROID_INITIAL_PROJECTION,
    PREREQUISITES,
    REMOTE_GROUPS,
    REMOTE_CONTACTS,
    OUTBOX_DRAIN,
    ANDROID_FINAL_PROJECTION,
    STATUS_PUBLICATION,
    FULL_REPAIR_FINALIZATION,
    COMPLETE,
    TARGETED_RECOVERY,
    REMOTE_DELETION_RECOVERY,
}

internal fun interface SyncPassStageObserver {
    fun onStage(stage: SyncPassStage)
    fun onException(category: SyncPassExceptionCategory) {}
    fun onOutcome(outcome: SyncPassOutcome) {}
    fun onImportPhase(phase: RemoteImportPhase) {}
}

/** MUST NOT retain exception messages, class names, causes or stack traces. */
internal enum class SyncPassExceptionCategory {
    PERMISSION, IO, INVALID_ARGUMENT, INVALID_STATE, SQLITE_ROW_TOO_LARGE, DATABASE, OUT_OF_MEMORY, UNEXPECTED,
}

internal fun syncPassExceptionCategory(error: Throwable): SyncPassExceptionCategory = when (error) {
    is android.database.sqlite.SQLiteBlobTooBigException -> SyncPassExceptionCategory.SQLITE_ROW_TOO_LARGE
    is android.database.sqlite.SQLiteException -> SyncPassExceptionCategory.DATABASE
    is OutOfMemoryError -> SyncPassExceptionCategory.OUT_OF_MEMORY
    is SecurityException -> SyncPassExceptionCategory.PERMISSION
    is java.io.IOException -> SyncPassExceptionCategory.IO
    is IllegalArgumentException -> SyncPassExceptionCategory.INVALID_ARGUMENT
    is IllegalStateException -> SyncPassExceptionCategory.INVALID_STATE
    else -> SyncPassExceptionCategory.UNEXPECTED
}

/** Fixed-result companion to [SyncPassStageObserver] for Android ingest/projection boundaries. */
internal fun interface AndroidSyncStageResultObserver {
    fun onResult(stage: SyncPassStage, result: AndroidInteroperabilityStageResult)
}

/**
 * One ordered production pass for remote reconciliation, durable uploads and Android
 * ingestion/projection; every trigger uses the same account-scoped runner.
 */
internal class ContakoSyncPassExecutor(
    private val account: AccountScope,
    private val prerequisites: SyncPrerequisiteChecker,
    private val remoteStage: IncrementalRemoteContactStage,
    private val mutationOrchestrator: DurableMutationOrchestrator,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val groupStage: IncrementalRemoteGroupStage? = null,
    private val statusPublisher: SyncPassStatusPublisher = SyncPassStatusPublisher { },
    private val androidPreflight: AndroidInteroperabilityPreflight,
    private val androidStage: AndroidInteroperabilityStage,
    private val androidRepair: AndroidInteroperabilityRepairCoordinator =
        AndroidInteroperabilityRepairCoordinator { _, _ -> AndroidInteroperabilityStageResult.ActionRequired },
    /**
     * Reports why the Android boundary degraded, so the published reason is not an opaque
     * internal failure. Called at most once per pass, before the outcome is published.
     */
    private val onAndroidDegraded: (AndroidInteroperabilityRepairReason?) -> Unit = {},
    private val stageObserver: SyncPassStageObserver = SyncPassStageObserver { },
    private val androidStageResultObserver: AndroidSyncStageResultObserver =
        AndroidSyncStageResultObserver { _, _ -> },
    private val fullRepairCoordinator: FullRepairExecutionCoordinator =
        MissingFullRepairExecutionCoordinator,
    private val onPassStarted: (com.patmanak.contako.domain.sync.SyncPassRequest) -> Unit = {},
    private val onGroupCatalogAvailability: (Boolean) -> Unit = {},
    private val targetedRecovery: TargetedContactRecovery? = null,
    private val deletedContactRecovery: DeletedContactRecoveryPhase? = null,
) : SyncPassExecutor, com.patmanak.contako.domain.sync.SyncPassCompletionDetailsSource {
    override var completionCause: com.patmanak.contako.domain.sync.SyncCompletionCause? = null
        private set
    private var independentOutcome = SyncPassOutcome.SUCCESS
    var hasUnscopedFailure: Boolean = false
        private set

    override suspend fun execute(request: com.patmanak.contako.domain.sync.SyncPassRequest): SyncPassOutcome {
        completionCause = null
        independentOutcome = SyncPassOutcome.SUCCESS
        hasUnscopedFailure = false
        onPassStarted(request)
        onGroupCatalogAvailability(false)
        return try {
            executePass(request).also { outcome -> runCatching { stageObserver.onOutcome(outcome) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Publication/finalization exceptions still propagate exactly as before.
            observeException(error)
            throw error
        }
    }

    private suspend fun executePass(request: com.patmanak.contako.domain.sync.SyncPassRequest): SyncPassOutcome {
        observe(SyncPassStage.REQUEST_GATE)
        if (request.scope == SyncScope.INCREMENTAL && request.triggers ==
            setOf(com.patmanak.contako.domain.sync.SyncTrigger.TARGETED_RECOVERY)) {
            return try { executeTargeted(request) } catch (_: CancellationException) {
                SyncPassOutcome.CANCELLED
            } catch (error: Throwable) {
                observeException(error)
                SyncPassOutcome.FAILED
            }
        }
        var fullRepair: FullRepairExecution? = null
        var outcome = try {
            if (request.scope == SyncScope.FULL_REPAIR) {
                observe(SyncPassStage.FULL_REPAIR_SETUP)
                fullRepair = fullRepairCoordinator.resume(account)
                    ?: return SyncPassOutcome.FAILED.also { statusPublisher.publish(it) }
                executeFullRepair(request, requireNotNull(fullRepair))
            } else {
                executeOrdered(request)
            }
        } catch (_: CancellationException) {
            SyncPassOutcome.CANCELLED
        } catch (error: Throwable) {
            // Boundary exceptions are deliberately collapsed before publication. Remote messages,
            // identifiers, payloads, and stack details must never enter durable user-visible state.
            observeException(error)
            SyncPassOutcome.FAILED
        }
        if (outcome in setOf(SyncPassOutcome.RETRY_WAITING, SyncPassOutcome.CANCELLED, SyncPassOutcome.FAILED))
            hasUnscopedFailure = true
        outcome = combineTerminalOutcomes(outcome, independentOutcome)
        targetedRecovery?.settleAfterOrdinaryPass(account, request::isAccountCancellationRequested)
        if (fullRepair != null) {
            if (outcome == SyncPassOutcome.SUCCESS &&
                !fullRepair.checkpoint(FullRepairPhase.PUBLISHING, 1, 1)
            ) {
                outcome = SyncPassOutcome.FAILED
            }
        }
        observe(SyncPassStage.STATUS_PUBLICATION)
        statusPublisher.publish(outcome)
        if (fullRepair != null) {
            observe(SyncPassStage.FULL_REPAIR_FINALIZATION)
            if (outcome == SyncPassOutcome.CANCELLED && fullRepair.isCancellationRequested()) {
                fullRepair.clearCancellation()
            } else if (outcome == SyncPassOutcome.SUCCESS && !fullRepair.clearAfterPublished()) {
                return SyncPassOutcome.FAILED
            }
        }
        observe(SyncPassStage.COMPLETE)
        return outcome
    }

    /** A scoped result never overwrites whole-account health or last-success timestamps. */
    private suspend fun executeTargeted(request: com.patmanak.contako.domain.sync.SyncPassRequest): SyncPassOutcome {
        val recovery = targetedRecovery ?: return SyncPassOutcome.FAILED
        if (request.isCancellationRequested()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.TARGETED_RECOVERY)
        return recovery.run(account, isCancelled = request::isCancellationRequested,
            remotePrerequisites = { observe(SyncPassStage.PREREQUISITES); checkRemotePrerequisites() },
            beforeRemote = { mutationOrchestrator.recoverInterrupted(account.value, wallClock()) })
            .also { observe(SyncPassStage.COMPLETE) }
    }

    private fun observe(stage: SyncPassStage) {
        // Diagnostics must never be able to change synchronization behavior.
        runCatching { stageObserver.onStage(stage) }
    }

    private suspend fun recoverDeletedContacts(isCancelled: () -> Boolean): SyncPassOutcome? {
        val recovery = deletedContactRecovery ?: return null
        if (isCancelled()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.REMOTE_DELETION_RECOVERY)
        return when (val result = recovery.run(account, isCancelled)) {
            SyncPassOutcome.SUCCESS -> null
            SyncPassOutcome.ACTION_REQUIRED, SyncPassOutcome.RETRY_WAITING -> {
                independentOutcome = combineTerminalOutcomes(independentOutcome, result)
                if (result == SyncPassOutcome.ACTION_REQUIRED && completionCause == null)
                    completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.CONFLICT
                null
            }
            else -> result
        }
    }

    private suspend fun checkAndroidPreflight(account: AccountScope, cancelled: () -> Boolean): AndroidInteroperabilityPreflightResult =
        androidPreflight.check(account, cancelled).also {
            if (it !is AndroidInteroperabilityPreflightResult.Ready && it != AndroidInteroperabilityPreflightResult.Cancelled)
                hasUnscopedFailure = true
        }

    private suspend fun checkRemotePrerequisites(): SyncPrerequisiteState = prerequisites.check().also {
        if (it != SyncPrerequisiteState.READY) hasUnscopedFailure = true
    }

    private fun observeException(error: Throwable) {
        hasUnscopedFailure = true
        completionCause = when (syncPassExceptionCategory(error)) {
            SyncPassExceptionCategory.DATABASE, SyncPassExceptionCategory.SQLITE_ROW_TOO_LARGE ->
                com.patmanak.contako.domain.sync.SyncCompletionCause.STORAGE
            SyncPassExceptionCategory.IO -> com.patmanak.contako.domain.sync.SyncCompletionCause.TRANSIENT_IO
            else -> com.patmanak.contako.domain.sync.SyncCompletionCause.UNKNOWN
        }
        runCatching { stageObserver.onException(syncPassExceptionCategory(error)) }
    }

    private fun observeAndroidResult(
        stage: SyncPassStage,
        result: AndroidInteroperabilityStageResult,
    ): AndroidInteroperabilityStageResult = result.also {
        if (result == AndroidInteroperabilityStageResult.ActionRequired && completionCause == null)
            completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.PROJECTION
        if (result in setOf(AndroidInteroperabilityStageResult.ActionRequired,
                AndroidInteroperabilityStageResult.RetryWaiting, AndroidInteroperabilityStageResult.LocalPersistenceFailure)) {
            val contactScopeProved = stage in setOf(SyncPassStage.ANDROID_INGEST,
                SyncPassStage.ANDROID_INITIAL_PROJECTION, SyncPassStage.ANDROID_FINAL_PROJECTION) &&
                (androidStage as? AndroidScopedFailureProofSource)?.lastFailureIsFullyScoped == true
            if (!contactScopeProved) hasUnscopedFailure = true
        }
        runCatching { androidStageResultObserver.onResult(stage, result) }
    }

    private fun authenticationRequired(): SyncPassOutcome {
        hasUnscopedFailure = true
        completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.AUTHENTICATION
        return SyncPassOutcome.ACTION_REQUIRED
    }

    private fun androidPermissionRequired(): SyncPassOutcome {
        hasUnscopedFailure = true
        completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.ANDROID_PERMISSION
        onAndroidDegraded(null)
        return SyncPassOutcome.ACTION_REQUIRED
    }

    private fun persistenceFailure(category: SyncPassExceptionCategory?): SyncPassOutcome {
        hasUnscopedFailure = true
        completionCause = when (category) {
            SyncPassExceptionCategory.DATABASE, SyncPassExceptionCategory.SQLITE_ROW_TOO_LARGE ->
                com.patmanak.contako.domain.sync.SyncCompletionCause.STORAGE
            SyncPassExceptionCategory.IO -> com.patmanak.contako.domain.sync.SyncCompletionCause.TRANSIENT_IO
            else -> com.patmanak.contako.domain.sync.SyncCompletionCause.UNKNOWN
        }
        return SyncPassOutcome.FAILED
    }

    private fun remoteActionRequired(): SyncPassOutcome {
        hasUnscopedFailure = true
        return when (remoteStage.lastFailureCategory) {
        com.patmanak.contako.data.gateway.GatewayFailureCategory.AUTHENTICATION_REQUIRED,
        com.patmanak.contako.data.gateway.GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED,
        com.patmanak.contako.data.gateway.GatewayFailureCategory.UNSUPPORTED_AUTHENTICATION,
        com.patmanak.contako.data.gateway.GatewayFailureCategory.CLIENT_IDENTITY_REJECTED -> authenticationRequired()
        else -> {
            completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.VALIDATION
            SyncPassOutcome.ACTION_REQUIRED
        }
        }
    }

    /** Catalog unavailability never means an empty catalog. Dependent writes remain deferred. */
    private fun acceptGroups(result: RemoteGroupStageResult?): SyncPassOutcome? = when (result) {
        null, is RemoteGroupStageResult.Success -> { onGroupCatalogAvailability(true); null }
        RemoteGroupStageResult.Unavailable -> null
        RemoteGroupStageResult.Cancelled -> SyncPassOutcome.CANCELLED
        RemoteGroupStageResult.LocalPersistenceFailure -> persistenceFailure(groupStage?.lastExceptionCategory)
        is RemoteGroupStageResult.RetryWaiting -> {
            hasUnscopedFailure = true
            independentOutcome = combineTerminalOutcomes(independentOutcome, SyncPassOutcome.RETRY_WAITING)
            null
        }
        RemoteGroupStageResult.ActionRequired -> {
            hasUnscopedFailure = true
            if (groupStage?.lastFailureCategory in setOf(
                    com.patmanak.contako.data.gateway.GatewayFailureCategory.AUTHENTICATION_REQUIRED,
                    com.patmanak.contako.data.gateway.GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED,
                    com.patmanak.contako.data.gateway.GatewayFailureCategory.CLIENT_IDENTITY_REJECTED,
                    com.patmanak.contako.data.gateway.GatewayFailureCategory.UNSUPPORTED_AUTHENTICATION)) authenticationRequired()
            else {
                independentOutcome = SyncPassOutcome.ACTION_REQUIRED
                completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.VALIDATION
                null
            }
        }
    }

    private suspend fun executeFullRepair(
        request: com.patmanak.contako.domain.sync.SyncPassRequest,
        repair: FullRepairExecution,
    ): SyncPassOutcome {
        fun cancelled() = request.isCancellationRequested() || repair.isCancellationRequested()
        var remoteCardsPending = false
        if (cancelled()) return SyncPassOutcome.CANCELLED

        observe(SyncPassStage.ANDROID_PREFLIGHT)
        val preflight = checkAndroidPreflight(account, ::cancelled)
        var repairedProvider = false
        val androidContext = when (preflight) {
            is AndroidInteroperabilityPreflightResult.Ready -> preflight.context
            is AndroidInteroperabilityPreflightResult.RepairRequired -> {
                val context = preflight.context ?: return SyncPassOutcome.FAILED
                observe(SyncPassStage.ANDROID_REPAIR)
                when (observeAndroidResult(
                    SyncPassStage.ANDROID_REPAIR,
                    androidRepair.repair(context, ::cancelled),
                )) {
                    AndroidInteroperabilityStageResult.Success -> context.also { repairedProvider = true }
                    AndroidInteroperabilityStageResult.RetryWaiting -> return SyncPassOutcome.RETRY_WAITING
                    AndroidInteroperabilityStageResult.ActionRequired -> return SyncPassOutcome.ACTION_REQUIRED
                    AndroidInteroperabilityStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
                    AndroidInteroperabilityStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
                }
            }
            AndroidInteroperabilityPreflightResult.PermissionDenied -> return androidPermissionRequired()
            AndroidInteroperabilityPreflightResult.ProviderUnavailable -> return SyncPassOutcome.RETRY_WAITING
            AndroidInteroperabilityPreflightResult.Cancelled -> return SyncPassOutcome.CANCELLED
            AndroidInteroperabilityPreflightResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
        }

        if (!repairedProvider && !repair.progress.completed(FullRepairPhase.CANONICAL_RECONCILIATION)) {
            observe(SyncPassStage.ANDROID_INGEST)
            when (observeAndroidResult(
                SyncPassStage.ANDROID_INGEST,
                androidStage.ingest(androidContext, ::cancelled),
            )) {
                AndroidInteroperabilityStageResult.Success -> Unit
                AndroidInteroperabilityStageResult.RetryWaiting -> return SyncPassOutcome.RETRY_WAITING
                AndroidInteroperabilityStageResult.ActionRequired -> return SyncPassOutcome.ACTION_REQUIRED
                AndroidInteroperabilityStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
                AndroidInteroperabilityStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
        }

        observe(SyncPassStage.PREREQUISITES)
        when (checkRemotePrerequisites()) {
            SyncPrerequisiteState.READY -> Unit
            SyncPrerequisiteState.OFFLINE -> return SyncPassOutcome.RETRY_WAITING
            SyncPrerequisiteState.AUTHENTICATION_REQUIRED,
            SyncPrerequisiteState.INTERACTIVE_ACTION_REQUIRED,
            -> return authenticationRequired()
        }

        // A resumed repair still needs a current catalog before draining group intent.
        observe(SyncPassStage.REMOTE_GROUPS)
        acceptGroups(groupStage?.run(account, ::cancelled))?.let { return it }
        if (cancelled()) return SyncPassOutcome.CANCELLED
        mutationOrchestrator.recoverInterrupted(account.value, wallClock())
        if (cancelled()) return SyncPassOutcome.CANCELLED
        // Uploads happen after the canonical/projection checkpoints. A resumed repair MUST
        // inspect current remote state even when those phases completed: its last create may
        // have reached Proton without a local acknowledgement. An earlier failed resume may
        // already have recovered the intent, so the recovery count is not a freshness proof.
        observe(SyncPassStage.REMOTE_CONTACTS)
        when (remoteStage.run(account,
            forceHydration = !repair.progress.completed(FullRepairPhase.REMOTE_ENUMERATION),
            isCancellationRequested = ::cancelled)) {
            is RemoteContactStageResult.Success -> Unit
            is RemoteContactStageResult.Partial -> {
                independentOutcome = SyncPassOutcome.ACTION_REQUIRED
                completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.VALIDATION
                remoteCardsPending = true
            }
            is RemoteContactStageResult.RetryWaiting, RemoteContactStageResult.StalePlan -> {
                hasUnscopedFailure = true
                return SyncPassOutcome.RETRY_WAITING
            }
            RemoteContactStageResult.Cancelled -> { hasUnscopedFailure = true; return SyncPassOutcome.CANCELLED }
            RemoteContactStageResult.ActionRequired -> return remoteActionRequired()
            RemoteContactStageResult.LocalPersistenceFailure -> return persistenceFailure(remoteStage.lastExceptionCategory)
        }
        if (cancelled()) return SyncPassOutcome.CANCELLED
        recoverDeletedContacts(::cancelled)?.let { return it }
        if (!repair.progress.completed(FullRepairPhase.REMOTE_ENUMERATION) &&
            !repair.checkpoint(FullRepairPhase.REMOTE_ENUMERATION, 1, 1)
        ) return if (cancelled()) SyncPassOutcome.CANCELLED else SyncPassOutcome.FAILED
        if (!remoteCardsPending && !repair.progress.completed(FullRepairPhase.CANONICAL_RECONCILIATION)) {
            if ((repair.progress.phase.ordinal < FullRepairPhase.CANONICAL_RECONCILIATION.ordinal &&
                !repair.checkpoint(FullRepairPhase.CANONICAL_RECONCILIATION, 0, 1)) ||
                !repair.checkpoint(FullRepairPhase.CANONICAL_RECONCILIATION, 1, 1)
            ) return if (cancelled()) SyncPassOutcome.CANCELLED else SyncPassOutcome.FAILED
        }

        run {
            // Ingestion/forced hydration may advance the account revision. Project against
            // a fresh guarded context, just as the incremental pass does after remote work.
            // Always revisit durable projection obligations: a previous resumed pass may
            // have committed remote changes and failed here after the original checkpoint.
            observe(SyncPassStage.ANDROID_PREFLIGHT)
            val projectionContext = when (val refreshed = checkAndroidPreflight(account, ::cancelled)) {
                is AndroidInteroperabilityPreflightResult.Ready -> refreshed.context
                is AndroidInteroperabilityPreflightResult.RepairRequired -> {
                    onAndroidDegraded(refreshed.reason)
                    return SyncPassOutcome.ACTION_REQUIRED
                }
                AndroidInteroperabilityPreflightResult.PermissionDenied -> {
                    return androidPermissionRequired()
                }
                AndroidInteroperabilityPreflightResult.ProviderUnavailable -> return SyncPassOutcome.RETRY_WAITING
                AndroidInteroperabilityPreflightResult.Cancelled -> return SyncPassOutcome.CANCELLED
                AndroidInteroperabilityPreflightResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
            observe(SyncPassStage.ANDROID_FINAL_PROJECTION)
            when (observeAndroidResult(
                SyncPassStage.ANDROID_FINAL_PROJECTION,
                androidStage.project(projectionContext, ::cancelled),
            )) {
                AndroidInteroperabilityStageResult.Success -> Unit
                AndroidInteroperabilityStageResult.RetryWaiting -> return SyncPassOutcome.RETRY_WAITING
                AndroidInteroperabilityStageResult.ActionRequired -> return SyncPassOutcome.ACTION_REQUIRED
                AndroidInteroperabilityStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
                AndroidInteroperabilityStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
            // Project healthy contacts without advancing past deferred cards. Historical
            // checkpoints remain monotone; a partial result keeps even a resumed repair open.
            if (!remoteCardsPending && !repair.progress.completed(FullRepairPhase.ANDROID_PROJECTION)) {
                if ((repair.progress.phase.ordinal < FullRepairPhase.ANDROID_PROJECTION.ordinal &&
                    !repair.checkpoint(FullRepairPhase.ANDROID_PROJECTION, 0, 1)) ||
                    !repair.checkpoint(FullRepairPhase.ANDROID_PROJECTION, 1, 1)
                ) return if (cancelled()) SyncPassOutcome.CANCELLED else SyncPassOutcome.FAILED
            }
        }

        if (cancelled()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.OUTBOX_DRAIN)
        val drained = drainImmediateProgress(::cancelled)
        val outcome = drainOutcome(drained)
        if (outcome != SyncPassOutcome.SUCCESS) return outcome
        if (!remoteCardsPending && !repair.progress.completed(FullRepairPhase.PUBLISHING) &&
            !repair.checkpoint(FullRepairPhase.PUBLISHING, 0, 1)
        ) return if (cancelled()) SyncPassOutcome.CANCELLED else SyncPassOutcome.FAILED
        return SyncPassOutcome.SUCCESS
    }

    /** Continue successful multi-step intent without WorkManager's failure backoff. */
    private suspend fun drainImmediateProgress(isCancelled: () -> Boolean): MutationDrainResult {
        var result = mutationOrchestrator.drain(account.value, wallClock(), isCancelled)
        // At most three bounded drains: group metadata, assignment, removal. Larger changes
        // remain durable for scheduling. Never retry a failure or bypass a server retry delay.
        repeat(2) {
            if (result.cancelled || isCancelled()) return result.copy(cancelled = true)
            // A completed contact acknowledgement can make previously blocked dependent
            // assignments eligible. Re-read the durable queue after successful uploads too.
            if (result.actionRequired > 0 || result.retryWaiting > 0 ||
                (result.progressPending == 0 && result.uploaded == 0)) return result
            val next = mutationOrchestrator.drain(account.value, wallClock(), isCancelled)
            result = MutationDrainResult(
                examined = result.examined + next.examined,
                uploaded = result.uploaded + next.uploaded,
                reconciledWithoutUpload = result.reconciledWithoutUpload + next.reconciledWithoutUpload,
                retryWaiting = next.retryWaiting,
                actionRequired = next.actionRequired,
                progressPending = next.progressPending,
                cancelled = next.cancelled,
                failureCategories = result.failureCategories + next.failureCategories,
            )
        }
        return result
    }

    private fun drainOutcome(result: MutationDrainResult): SyncPassOutcome {
        if (result.cancelled || result.retryWaiting > 0 || result.progressPending > 0) hasUnscopedFailure = true
        if (result.failureCategories.any { it in setOf(
                com.patmanak.contako.data.gateway.GatewayFailureCategory.AUTHENTICATION_REQUIRED,
                com.patmanak.contako.data.gateway.GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED,
                com.patmanak.contako.data.gateway.GatewayFailureCategory.UNSUPPORTED_AUTHENTICATION,
                com.patmanak.contako.data.gateway.GatewayFailureCategory.CLIENT_IDENTITY_REJECTED,
                com.patmanak.contako.data.gateway.GatewayFailureCategory.ACCOUNT_ALREADY_CONNECTED) })
            return authenticationRequired()
        if (result.actionRequired > 0 && completionCause == null)
            completionCause = if (com.patmanak.contako.data.gateway.GatewayFailureCategory.CONFLICT in result.failureCategories)
                com.patmanak.contako.domain.sync.SyncCompletionCause.CONFLICT
            else com.patmanak.contako.domain.sync.SyncCompletionCause.VALIDATION
        return when {
            result.cancelled -> SyncPassOutcome.CANCELLED
            result.actionRequired > 0 -> SyncPassOutcome.ACTION_REQUIRED
            result.retryWaiting > 0 || result.progressPending > 0 -> SyncPassOutcome.RETRY_WAITING
            else -> SyncPassOutcome.SUCCESS
        }
    }

    private suspend fun executeOrdered(
        request: com.patmanak.contako.domain.sync.SyncPassRequest,
    ): SyncPassOutcome {
        var deferredAndroidOutcome = SyncPassOutcome.SUCCESS
        var deferredInitialProjection = SyncPassOutcome.SUCCESS
        fun acceptAndroid(result: AndroidInteroperabilityStageResult): SyncPassOutcome? = when (result) {
            AndroidInteroperabilityStageResult.Success -> null
            AndroidInteroperabilityStageResult.RetryWaiting -> {
                deferredAndroidOutcome = combineTerminalOutcomes(
                    deferredAndroidOutcome,
                    SyncPassOutcome.RETRY_WAITING,
                )
                null
            }
            AndroidInteroperabilityStageResult.ActionRequired -> {
                if (completionCause == null) completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.PROJECTION
                // Ingestion and projection can also degrade after a Ready preflight, so the
                // reason must be reported here too; otherwise it falls back to INTERNAL_FAILURE.
                onAndroidDegraded(null)
                deferredAndroidOutcome = combineTerminalOutcomes(
                    deferredAndroidOutcome,
                    SyncPassOutcome.ACTION_REQUIRED,
                )
                null
            }
            AndroidInteroperabilityStageResult.Cancelled -> SyncPassOutcome.CANCELLED
            AndroidInteroperabilityStageResult.LocalPersistenceFailure -> SyncPassOutcome.FAILED
        }
        fun withDeferredAndroid(outcome: SyncPassOutcome): SyncPassOutcome =
            combineTerminalOutcomes(combineTerminalOutcomes(deferredAndroidOutcome, deferredInitialProjection), outcome)

        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.ANDROID_PREFLIGHT)
        var preflight = checkAndroidPreflight(account, request::isAccountCancellationRequested)
        var skipAndroidStages = false
        if (preflight is AndroidInteroperabilityPreflightResult.RepairRequired &&
            preflight.reason == AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED
        ) {
            val repairContext = preflight.context
                ?: return SyncPassOutcome.FAILED
            observe(SyncPassStage.ANDROID_REPAIR)
            when (val repair = observeAndroidResult(
                SyncPassStage.ANDROID_REPAIR,
                androidRepair.repair(
                    repairContext,
                    request::isAccountCancellationRequested,
                ),
            )) {
                AndroidInteroperabilityStageResult.Success -> {
                    if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
                    preflight = checkAndroidPreflight(
                        account,
                        request::isAccountCancellationRequested,
                    )
                }
                AndroidInteroperabilityStageResult.RetryWaiting,
                AndroidInteroperabilityStageResult.ActionRequired,
                -> {
                    acceptAndroid(repair)
                    skipAndroidStages = true
                }
                AndroidInteroperabilityStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
                AndroidInteroperabilityStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
        }
        var androidContext = if (skipAndroidStages) null else when (preflight) {
            is AndroidInteroperabilityPreflightResult.Ready -> preflight.context
            AndroidInteroperabilityPreflightResult.PermissionDenied -> {
                androidPermissionRequired()
                acceptAndroid(AndroidInteroperabilityStageResult.ActionRequired)
                null
            }
            AndroidInteroperabilityPreflightResult.ProviderUnavailable -> {
                acceptAndroid(AndroidInteroperabilityStageResult.RetryWaiting)
                null
            }
            is AndroidInteroperabilityPreflightResult.RepairRequired -> {
                // Android cannot proceed, but Proton and outbox work still can under D-062.
                onAndroidDegraded((preflight as AndroidInteroperabilityPreflightResult.RepairRequired).reason)
                acceptAndroid(AndroidInteroperabilityStageResult.ActionRequired)
                null
            }
            AndroidInteroperabilityPreflightResult.Cancelled -> return SyncPassOutcome.CANCELLED
            AndroidInteroperabilityPreflightResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
        }
        if (androidContext != null) {
            observe(SyncPassStage.ANDROID_INGEST)
            acceptAndroid(observeAndroidResult(
                SyncPassStage.ANDROID_INGEST,
                androidStage.ingest(
                    androidContext,
                    request::isAccountCancellationRequested,
                ),
            ))?.let { return it }
            if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
            // Android ingestion can advance the durable account revision while committing a
            // Google-created contact or group observation. Reusing the pre-ingest context makes
            // the immediately following projection report a false stale replan.
            observe(SyncPassStage.ANDROID_PREFLIGHT)
            androidContext = when (val refreshed = checkAndroidPreflight(
                account,
                request::isAccountCancellationRequested,
            )) {
                is AndroidInteroperabilityPreflightResult.Ready -> refreshed.context
                AndroidInteroperabilityPreflightResult.PermissionDenied,
                is AndroidInteroperabilityPreflightResult.RepairRequired,
                -> {
                    if (refreshed == AndroidInteroperabilityPreflightResult.PermissionDenied) androidPermissionRequired()
                    onAndroidDegraded(
                        (refreshed as? AndroidInteroperabilityPreflightResult.RepairRequired)?.reason,
                    )
                    acceptAndroid(AndroidInteroperabilityStageResult.ActionRequired)
                    null
                }
                AndroidInteroperabilityPreflightResult.ProviderUnavailable -> {
                    acceptAndroid(AndroidInteroperabilityStageResult.RetryWaiting)
                    null
                }
                AndroidInteroperabilityPreflightResult.Cancelled -> return SyncPassOutcome.CANCELLED
                AndroidInteroperabilityPreflightResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
            androidContext?.let { refreshedContext ->
                observe(SyncPassStage.ANDROID_INITIAL_PROJECTION)
                val initialProjection = observeAndroidResult(
                    SyncPassStage.ANDROID_INITIAL_PROJECTION,
                    androidStage.project(
                        refreshedContext,
                        request::isAccountCancellationRequested,
                    ),
                )
                if (initialProjection == AndroidInteroperabilityStageResult.RetryWaiting) {
                    deferredInitialProjection = SyncPassOutcome.RETRY_WAITING
                } else {
                    acceptAndroid(initialProjection)?.let { return it }
                }
            }
        }
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.PREREQUISITES)
        when (checkRemotePrerequisites()) {
            SyncPrerequisiteState.READY -> Unit
            SyncPrerequisiteState.OFFLINE -> return withDeferredAndroid(SyncPassOutcome.RETRY_WAITING)
            SyncPrerequisiteState.AUTHENTICATION_REQUIRED,
            SyncPrerequisiteState.INTERACTIVE_ACTION_REQUIRED,
            -> return withDeferredAndroid(authenticationRequired())
        }
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.REMOTE_GROUPS)
        acceptGroups(groupStage?.run(account, request::isAccountCancellationRequested))?.let {
            return withDeferredAndroid(it)
        }
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        mutationOrchestrator.recoverInterrupted(account.value, wallClock())
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.REMOTE_CONTACTS)
        val remote = remoteStage.run(
            account,
            isCancellationRequested = request::isAccountCancellationRequested,
        )
        when (remote) {
            is RemoteContactStageResult.Success -> Unit
            is RemoteContactStageResult.Partial -> {
                independentOutcome = SyncPassOutcome.ACTION_REQUIRED
                completionCause = com.patmanak.contako.domain.sync.SyncCompletionCause.VALIDATION
            }
            is RemoteContactStageResult.RetryWaiting,
            RemoteContactStageResult.StalePlan,
            -> {
                hasUnscopedFailure = true
                return withDeferredAndroid(SyncPassOutcome.RETRY_WAITING)
            }
            RemoteContactStageResult.Cancelled -> { hasUnscopedFailure = true; return SyncPassOutcome.CANCELLED }
            RemoteContactStageResult.ActionRequired ->
                return withDeferredAndroid(remoteActionRequired())
            RemoteContactStageResult.LocalPersistenceFailure -> return persistenceFailure(remoteStage.lastExceptionCategory)
        }
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        recoverDeletedContacts(request::isAccountCancellationRequested)?.let { return it }
        observe(SyncPassStage.OUTBOX_DRAIN)
        val drained = drainImmediateProgress(request::isAccountCancellationRequested)
        val drainOutcome = drainOutcome(drained)
        if (drainOutcome == SyncPassOutcome.CANCELLED || request.isAccountCancellationRequested()) {
            return SyncPassOutcome.CANCELLED
        }
        androidContext?.let {
            // Remote reconciliation and the durable outbox acknowledgement can advance the
            // account revision after the post-ingest refresh. Reusing that earlier context makes
            // the final projection report a false stale replan after a successful Proton create.
            observe(SyncPassStage.ANDROID_PREFLIGHT)
            val finalContext = when (val refreshed = checkAndroidPreflight(
                account,
                request::isAccountCancellationRequested,
            )) {
                is AndroidInteroperabilityPreflightResult.Ready -> refreshed.context
                AndroidInteroperabilityPreflightResult.PermissionDenied,
                is AndroidInteroperabilityPreflightResult.RepairRequired,
                -> {
                    if (refreshed == AndroidInteroperabilityPreflightResult.PermissionDenied) androidPermissionRequired()
                    onAndroidDegraded(
                        (refreshed as? AndroidInteroperabilityPreflightResult.RepairRequired)?.reason,
                    )
                    acceptAndroid(AndroidInteroperabilityStageResult.ActionRequired)
                    null
                }
                AndroidInteroperabilityPreflightResult.ProviderUnavailable -> {
                    acceptAndroid(AndroidInteroperabilityStageResult.RetryWaiting)
                    null
                }
                AndroidInteroperabilityPreflightResult.Cancelled -> return SyncPassOutcome.CANCELLED
                AndroidInteroperabilityPreflightResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
            var currentContext = finalContext
            var localReplans = 0
            while (currentContext != null) {
                observe(SyncPassStage.ANDROID_FINAL_PROJECTION)
                val projection = observeAndroidResult(
                    SyncPassStage.ANDROID_FINAL_PROJECTION,
                    androidStage.project(
                        currentContext,
                        request::isAccountCancellationRequested,
                    ),
                )
                if (projection != AndroidInteroperabilityStageResult.RetryWaiting) {
                    // A completed final traversal resolves an earlier projection replan.
                    // Ingestion, repair and outbox failures remain independently deferred.
                    if (projection == AndroidInteroperabilityStageResult.Success) {
                        deferredInitialProjection = SyncPassOutcome.SUCCESS
                    }
                    acceptAndroid(projection)?.let { return it }
                    break
                }
                if (++localReplans > MAX_FINAL_ANDROID_REPLANS) {
                    acceptAndroid(projection)
                    break
                }
                if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
                observe(SyncPassStage.ANDROID_PREFLIGHT)
                val previousContext = currentContext
                currentContext = when (val replanned = checkAndroidPreflight(
                    account,
                    request::isAccountCancellationRequested,
                )) {
                    is AndroidInteroperabilityPreflightResult.Ready -> {
                        // Retry only when the durable context proves that the projection itself
                        // advanced state. An unchanged context is a real retry-waiting condition,
                        // not authority to spin on a provider failure.
                        replanned.context.takeUnless { it == previousContext }.also {
                            if (it == null) acceptAndroid(projection)
                        }
                    }
                    AndroidInteroperabilityPreflightResult.PermissionDenied,
                    is AndroidInteroperabilityPreflightResult.RepairRequired,
                    -> {
                        if (replanned == AndroidInteroperabilityPreflightResult.PermissionDenied) androidPermissionRequired()
                        onAndroidDegraded(
                            (replanned as? AndroidInteroperabilityPreflightResult.RepairRequired)?.reason,
                        )
                        acceptAndroid(AndroidInteroperabilityStageResult.ActionRequired)
                        null
                    }
                    AndroidInteroperabilityPreflightResult.ProviderUnavailable -> {
                        acceptAndroid(AndroidInteroperabilityStageResult.RetryWaiting)
                        null
                    }
                    AndroidInteroperabilityPreflightResult.Cancelled -> return SyncPassOutcome.CANCELLED
                    AndroidInteroperabilityPreflightResult.LocalPersistenceFailure ->
                        return SyncPassOutcome.FAILED
                }
            }
        }
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        return withDeferredAndroid(drainOutcome)
    }

    /** A later projection MUST NOT hide a more severe durable-outbox outcome. */
    private fun combineTerminalOutcomes(
        first: SyncPassOutcome,
        second: SyncPassOutcome,
    ): SyncPassOutcome = listOf(first, second).maxBy(::outcomePriority)

    private fun outcomePriority(outcome: SyncPassOutcome): Int = when (outcome) {
        SyncPassOutcome.SUCCESS -> 0
        SyncPassOutcome.RETRY_WAITING -> 1
        SyncPassOutcome.ACTION_REQUIRED -> 2
        SyncPassOutcome.FAILED -> 3
        SyncPassOutcome.CANCELLED -> 4
    }

    private companion object {
        const val MAX_FINAL_ANDROID_REPLANS = 4
    }
}

private fun FullRepairProgress.completed(target: FullRepairPhase): Boolean =
    phase.ordinal > target.ordinal ||
        (phase == target && totalUnits != null && completedUnits == totalUnits)
