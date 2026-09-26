package com.patmanak.contako.domain.sync

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncTrigger {
    FIRST_IMPORT,
    STARTUP_STALE_CHECK,
    MANUAL,
    ANDROID_PERIODIC,
    ANDROID_UPLOAD,
    MUTATION_COMMITTED,
    CONNECTIVITY_RETURNED,
    RETRY,
}

enum class SyncScope { INCREMENTAL, FULL_REPAIR }

class SyncPassRequest internal constructor(
    val triggers: Set<SyncTrigger>,
    val scope: SyncScope,
    private val cancellationGeneration: Long,
    private val currentCancellationGeneration: () -> Long,
    private val repairCancellationGeneration: Long,
    private val currentRepairCancellationGeneration: () -> Long,
) {
    init {
        require(triggers.isNotEmpty())
    }

    fun isAccountCancellationRequested(): Boolean =
        currentCancellationGeneration() != cancellationGeneration

    fun isCancellationRequested(): Boolean =
        isAccountCancellationRequested() ||
            (scope == SyncScope.FULL_REPAIR &&
                currentRepairCancellationGeneration() != repairCancellationGeneration)

    override fun toString(): String = "SyncPassRequest(triggers=$triggers, scope=$scope)"
}

enum class SyncPassOutcome { SUCCESS, RETRY_WAITING, ACTION_REQUIRED, CANCELLED, FAILED }

sealed interface SyncRunState {
    data object Idle : SyncRunState
    data class Scheduled(val scope: SyncScope) : SyncRunState
    data class Running(val scope: SyncScope, val passNumber: Long) : SyncRunState
    data class Completed(val outcome: SyncPassOutcome, val passNumber: Long) : SyncRunState
}

enum class SyncRequestDisposition { STARTED, COALESCED, ACCOUNT_INVALIDATED }

fun interface SyncPassExecutor {
    suspend fun execute(request: SyncPassRequest): SyncPassOutcome
}

/**
 * One account-owned runner. Trigger submission is detached from the caller so cancellation of a UI
 * or framework caller cannot cancel an in-progress cross-system pass. Account removal uses the
 * explicit cancellation generation observed by the executor at every safe boundary.
 */
class AccountSyncRunner(
    private val scope: CoroutineScope,
    private val executor: SyncPassExecutor,
) {
    private val mutex = Mutex()
    private val cancellationGeneration = AtomicLong(0)
    private val repairCancellationGeneration = AtomicLong(0)
    private val mutableState = MutableStateFlow<SyncRunState>(SyncRunState.Idle)
    private val mutableLastCompletion = MutableStateFlow<SyncRunState.Completed?>(null)
    private val pendingTriggers = linkedSetOf<SyncTrigger>()
    private var pendingScope = SyncScope.INCREMENTAL
    private var active = false
    private var invalidated = false
    private var passNumber = 0L

    val state: StateFlow<SyncRunState> = mutableState.asStateFlow()
    val lastCompletion: StateFlow<SyncRunState.Completed?> = mutableLastCompletion.asStateFlow()

    suspend fun request(trigger: SyncTrigger, requestedScope: SyncScope = SyncScope.INCREMENTAL): SyncRequestDisposition {
        var launchDrain = false
        val disposition = mutex.withLock {
            if (invalidated) return@withLock SyncRequestDisposition.ACCOUNT_INVALIDATED
            pendingTriggers += trigger
            if (requestedScope == SyncScope.FULL_REPAIR) pendingScope = SyncScope.FULL_REPAIR
            if (active) {
                mutableState.value = SyncRunState.Scheduled(pendingScope)
                SyncRequestDisposition.COALESCED
            } else {
                active = true
                launchDrain = true
                mutableState.value = SyncRunState.Scheduled(pendingScope)
                SyncRequestDisposition.STARTED
            }
        }
        if (launchDrain) scope.launch { drain() }
        return disposition
    }

    /** Stops the current account generation before its durable and remote state is removed. */
    suspend fun cancelForAccountRemoval() {
        cancellationGeneration.incrementAndGet()
        mutex.withLock {
            invalidated = true
            pendingTriggers.clear()
            pendingScope = SyncScope.INCREMENTAL
            if (!active) mutableState.value = SyncRunState.Idle
        }
    }

    /**
     * Opens a fresh account generation only after authentication and durable provisioning succeed.
     *
     * The application runtime is process-scoped, so signing out does not construct a second runner.
     * Without this explicit transition every later request in the same process remained permanently
     * [SyncRequestDisposition.ACCOUNT_INVALIDATED].
     */
    suspend fun reactivateAfterAccountProvisioning() {
        mutex.withLock {
            if (!invalidated) return
            check(!active) { "Cannot reactivate an active account runner" }
            invalidated = false
            pendingTriggers.clear()
            pendingScope = SyncScope.INCREMENTAL
            mutableLastCompletion.value = null
            mutableState.value = SyncRunState.Idle
        }
    }

    fun cancelFullRepair() {
        repairCancellationGeneration.incrementAndGet()
    }

    suspend fun awaitIdle(): SyncRunState.Idle = state.filterIsInstance<SyncRunState.Idle>().first()

    private suspend fun drain() {
        while (true) {
            val request = mutex.withLock {
                if (invalidated || pendingTriggers.isEmpty()) {
                    active = false
                    mutableState.value = SyncRunState.Idle
                    return
                }
                val nextPass = ++passNumber
                val pass = SyncPassRequest(
                    triggers = pendingTriggers.toSet(),
                    scope = pendingScope,
                    cancellationGeneration = cancellationGeneration.get(),
                    currentCancellationGeneration = cancellationGeneration::get,
                    repairCancellationGeneration = repairCancellationGeneration.get(),
                    currentRepairCancellationGeneration = repairCancellationGeneration::get,
                )
                pendingTriggers.clear()
                pendingScope = SyncScope.INCREMENTAL
                mutableState.value = SyncRunState.Running(pass.scope, nextPass)
                pass
            }

            val outcome = try {
                executor.execute(request)
            } catch (_: CancellationException) {
                SyncPassOutcome.CANCELLED
            } catch (_: Throwable) {
                SyncPassOutcome.FAILED
            }

            val continueWithFollowUp = mutex.withLock {
                val completed = SyncRunState.Completed(outcome, passNumber)
                mutableLastCompletion.value = completed
                mutableState.value = completed
                if (invalidated || pendingTriggers.isEmpty()) {
                    active = false
                    mutableState.value = SyncRunState.Idle
                    false
                } else {
                    mutableState.value = SyncRunState.Scheduled(pendingScope)
                    true
                }
            }
            if (!continueWithFollowUp) return
        }
    }
}
