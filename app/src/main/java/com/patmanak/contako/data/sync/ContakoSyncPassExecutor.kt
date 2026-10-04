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
) : SyncPassExecutor {
    override suspend fun execute(request: com.patmanak.contako.domain.sync.SyncPassRequest): SyncPassOutcome {
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

    private fun observe(stage: SyncPassStage) {
        // Diagnostics must never be able to change synchronization behavior.
        runCatching { stageObserver.onStage(stage) }
    }

    private fun observeException(error: Throwable) {
        runCatching { stageObserver.onException(syncPassExceptionCategory(error)) }
    }

    private fun observeAndroidResult(
        stage: SyncPassStage,
        result: AndroidInteroperabilityStageResult,
    ): AndroidInteroperabilityStageResult = result.also {
        runCatching { androidStageResultObserver.onResult(stage, result) }
    }

    private suspend fun executeFullRepair(
        request: com.patmanak.contako.domain.sync.SyncPassRequest,
        repair: FullRepairExecution,
    ): SyncPassOutcome {
        fun cancelled() = request.isCancellationRequested() || repair.isCancellationRequested()
        if (cancelled()) return SyncPassOutcome.CANCELLED

        observe(SyncPassStage.ANDROID_PREFLIGHT)
        val preflight = androidPreflight.check(account, ::cancelled)
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
            AndroidInteroperabilityPreflightResult.PermissionDenied -> return SyncPassOutcome.ACTION_REQUIRED
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
        when (prerequisites.check()) {
            SyncPrerequisiteState.READY -> Unit
            SyncPrerequisiteState.OFFLINE -> return SyncPassOutcome.RETRY_WAITING
            SyncPrerequisiteState.AUTHENTICATION_REQUIRED,
            SyncPrerequisiteState.INTERACTIVE_ACTION_REQUIRED,
            -> return SyncPassOutcome.ACTION_REQUIRED
        }

        if (!repair.progress.completed(FullRepairPhase.CANONICAL_RECONCILIATION)) {
            observe(SyncPassStage.REMOTE_GROUPS)
            when (val groups = groupStage?.run(account, ::cancelled)) {
                null, is RemoteGroupStageResult.Success, RemoteGroupStageResult.Unavailable -> Unit
                is RemoteGroupStageResult.RetryWaiting -> return SyncPassOutcome.RETRY_WAITING
                RemoteGroupStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
                RemoteGroupStageResult.ActionRequired -> return SyncPassOutcome.ACTION_REQUIRED
                RemoteGroupStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
            observe(SyncPassStage.REMOTE_CONTACTS)
            when (remoteStage.run(account, forceHydration = true, isCancellationRequested = ::cancelled)) {
                is RemoteContactStageResult.Success -> Unit
                is RemoteContactStageResult.RetryWaiting, RemoteContactStageResult.StalePlan ->
                    return SyncPassOutcome.RETRY_WAITING
                RemoteContactStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
                RemoteContactStageResult.ActionRequired -> return SyncPassOutcome.ACTION_REQUIRED
                RemoteContactStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
            }
            if (cancelled()) return SyncPassOutcome.CANCELLED
            if (!repair.checkpoint(FullRepairPhase.REMOTE_ENUMERATION, 1, 1) ||
                !repair.checkpoint(FullRepairPhase.CANONICAL_RECONCILIATION, 0, 1) ||
                !repair.checkpoint(FullRepairPhase.CANONICAL_RECONCILIATION, 1, 1)
            ) return if (cancelled()) SyncPassOutcome.CANCELLED else SyncPassOutcome.FAILED
        }

        if (!repair.progress.completed(FullRepairPhase.ANDROID_PROJECTION)) {
            // Ingestion/forced hydration may advance the account revision. Project against
            // a fresh guarded context, just as the incremental pass does after remote work.
            observe(SyncPassStage.ANDROID_PREFLIGHT)
            val projectionContext = when (val refreshed = androidPreflight.check(account, ::cancelled)) {
                is AndroidInteroperabilityPreflightResult.Ready -> refreshed.context
                is AndroidInteroperabilityPreflightResult.RepairRequired -> {
                    onAndroidDegraded(refreshed.reason)
                    return SyncPassOutcome.ACTION_REQUIRED
                }
                AndroidInteroperabilityPreflightResult.PermissionDenied -> {
                    onAndroidDegraded(null)
                    return SyncPassOutcome.ACTION_REQUIRED
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
            if (!repair.checkpoint(FullRepairPhase.ANDROID_PROJECTION, 0, 1) ||
                !repair.checkpoint(FullRepairPhase.ANDROID_PROJECTION, 1, 1)
            ) return if (cancelled()) SyncPassOutcome.CANCELLED else SyncPassOutcome.FAILED
        }

        if (cancelled()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.OUTBOX_DRAIN)
        val drained = drainImmediateProgress(::cancelled)
        val outcome = when {
            drained.cancelled -> SyncPassOutcome.CANCELLED
            drained.actionRequired > 0 -> SyncPassOutcome.ACTION_REQUIRED
            drained.retryWaiting > 0 || drained.progressPending > 0 -> SyncPassOutcome.RETRY_WAITING
            else -> SyncPassOutcome.SUCCESS
        }
        if (outcome != SyncPassOutcome.SUCCESS) return outcome
        if (!repair.progress.completed(FullRepairPhase.PUBLISHING) &&
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
            )
        }
        return result
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
        var preflight = androidPreflight.check(account, request::isAccountCancellationRequested)
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
                    preflight = androidPreflight.check(
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
                onAndroidDegraded(null)
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
            androidContext = when (val refreshed = androidPreflight.check(
                account,
                request::isAccountCancellationRequested,
            )) {
                is AndroidInteroperabilityPreflightResult.Ready -> refreshed.context
                AndroidInteroperabilityPreflightResult.PermissionDenied,
                is AndroidInteroperabilityPreflightResult.RepairRequired,
                -> {
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
        when (prerequisites.check()) {
            SyncPrerequisiteState.READY -> Unit
            SyncPrerequisiteState.OFFLINE -> return withDeferredAndroid(SyncPassOutcome.RETRY_WAITING)
            SyncPrerequisiteState.AUTHENTICATION_REQUIRED,
            SyncPrerequisiteState.INTERACTIVE_ACTION_REQUIRED,
            -> return withDeferredAndroid(SyncPassOutcome.ACTION_REQUIRED)
        }
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.REMOTE_GROUPS)
        when (groupStage?.run(account, request::isAccountCancellationRequested)) {
            null, is RemoteGroupStageResult.Success, RemoteGroupStageResult.Unavailable -> Unit
            is RemoteGroupStageResult.RetryWaiting ->
                return withDeferredAndroid(SyncPassOutcome.RETRY_WAITING)
            RemoteGroupStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
            RemoteGroupStageResult.ActionRequired ->
                return withDeferredAndroid(SyncPassOutcome.ACTION_REQUIRED)
            RemoteGroupStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
        }
        observe(SyncPassStage.REMOTE_CONTACTS)
        val remote = remoteStage.run(
            account,
            isCancellationRequested = request::isAccountCancellationRequested,
        )
        when (remote) {
            is RemoteContactStageResult.Success -> Unit
            is RemoteContactStageResult.RetryWaiting,
            RemoteContactStageResult.StalePlan,
            -> return withDeferredAndroid(SyncPassOutcome.RETRY_WAITING)
            RemoteContactStageResult.Cancelled -> return SyncPassOutcome.CANCELLED
            RemoteContactStageResult.ActionRequired ->
                return withDeferredAndroid(SyncPassOutcome.ACTION_REQUIRED)
            RemoteContactStageResult.LocalPersistenceFailure -> return SyncPassOutcome.FAILED
        }
        if (request.isAccountCancellationRequested()) return SyncPassOutcome.CANCELLED
        observe(SyncPassStage.OUTBOX_DRAIN)
        val drained = drainImmediateProgress(request::isAccountCancellationRequested)
        val drainOutcome = when {
            drained.cancelled -> SyncPassOutcome.CANCELLED
            drained.actionRequired > 0 -> SyncPassOutcome.ACTION_REQUIRED
            drained.retryWaiting > 0 || drained.progressPending > 0 -> SyncPassOutcome.RETRY_WAITING
            else -> SyncPassOutcome.SUCCESS
        }
        if (drainOutcome == SyncPassOutcome.CANCELLED || request.isAccountCancellationRequested()) {
            return SyncPassOutcome.CANCELLED
        }
        androidContext?.let {
            // Remote reconciliation and the durable outbox acknowledgement can advance the
            // account revision after the post-ingest refresh. Reusing that earlier context makes
            // the final projection report a false stale replan after a successful Proton create.
            observe(SyncPassStage.ANDROID_PREFLIGHT)
            val finalContext = when (val refreshed = androidPreflight.check(
                account,
                request::isAccountCancellationRequested,
            )) {
                is AndroidInteroperabilityPreflightResult.Ready -> refreshed.context
                AndroidInteroperabilityPreflightResult.PermissionDenied,
                is AndroidInteroperabilityPreflightResult.RepairRequired,
                -> {
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
                currentContext = when (val replanned = androidPreflight.check(
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
