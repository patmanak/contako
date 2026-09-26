package com.patmanak.contako.data.sync

import com.patmanak.contako.domain.sync.SyncTrigger
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun interface SchedulingClock {
    fun nowEpochMillis(): Long
}

internal data class EligibleSyncAccount(
    val androidAccountName: String,
    val automaticSyncEnabled: Boolean,
)

internal fun interface SyncAccountEligibility {
    suspend fun current(): EligibleSyncAccount?
}

internal fun interface LastSuccessfulSyncReader {
    suspend fun lastSuccessAtEpochMillis(): Long?
}

internal interface SyncWorkScheduler {
    fun ensurePeriodic(accountName: String, intervalSeconds: Long)
    fun request(accountName: String, triggers: Set<SyncTrigger>)
}

internal interface AndroidAutomaticSyncState {
    fun ensurePeriodic(accountName: String, intervalSeconds: Long)
    fun request(accountName: String, triggers: Set<SyncTrigger>)
    fun isEnabled(accountName: String): Boolean
}

internal enum class SchedulingNetworkState { OFFLINE, ONLINE }

internal interface SchedulingNetworkMonitor {
    fun current(): SchedulingNetworkState
    fun observe(listener: (SchedulingNetworkState) -> Unit): Closeable
}

internal data class SyncSchedulingMetrics(
    val periodicWorkRequests: Int,
    val oneShotWorkRequests: Int,
    val coalescedTriggers: Int,
    val delayedRequests: Int,
    val networkTransitions: Int,
)

/** D-033 event policy. Android owns background timing; visible edits also wake the shared runner. */
internal class SyncSchedulingPolicy(
    private val scope: CoroutineScope,
    private val clock: SchedulingClock,
    private val accountEligibility: SyncAccountEligibility,
    private val lastSuccessfulSyncReader: LastSuccessfulSyncReader,
    private val scheduler: SyncWorkScheduler,
    private val networkMonitor: SchedulingNetworkMonitor,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val foregroundFirstImport: (suspend () -> Unit)? = null,
    private val foregroundSync: (suspend (Set<SyncTrigger>) -> Unit)? = null,
    private val hasPendingMutations: suspend () -> Boolean = { false },
    private val hasPendingAndroidChanges: suspend (String) -> Boolean = { false },
) : Closeable {
    private val guard = Any()
    private val pendingTriggers = linkedSetOf<SyncTrigger>()
    private var active = false
    private var foreground = false
    private var lifecycleGeneration = 0L
    private var pendingJob: Job? = null
    private var networkState = networkMonitor.current()
    private var networkSubscription: Closeable? = null
    private var periodicAccountName: String? = null
    private var lastOneShotAt = Long.MIN_VALUE
    private var periodicWorkRequests = 0
    private var oneShotWorkRequests = 0
    private var coalescedTriggers = 0
    private var delayedRequests = 0
    private var networkTransitions = 0

    fun initialize() {
        val generation = synchronized(guard) {
            if (active) return
            active = true
            lifecycleGeneration++
            networkState = networkMonitor.current()
            lifecycleGeneration
        }
        val subscription = networkMonitor.observe { next -> onNetworkState(generation, next) }
        val discard = synchronized(guard) {
            if (!active || lifecycleGeneration != generation || networkSubscription != null) {
                true
            } else {
                networkSubscription = subscription
                false
            }
        }
        if (discard) {
            subscription.close()
        } else {
            launchScheduling { ensurePeriodic(generation) }
        }
    }

    fun onAppStartup() {
        initialize()
        val generation = activeGeneration() ?: return
        launchScheduling {
            ensurePeriodic(generation)
            val lastSuccess = lastSuccessfulSyncReader.lastSuccessAtEpochMillis()
            val now = clock.nowEpochMillis()
            if (lastSuccess == null || now < lastSuccess || now - lastSuccess > STARTUP_STALE_MILLIS) {
                enqueue(SyncTrigger.STARTUP_STALE_CHECK, 0L, generation)
            }
        }
    }

    /**
     * Re-evaluates automatic work after authentication and Android-account provisioning complete.
     *
     * Activity startup happens before an interactive sign-in can create the Android account. Any
     * startup request made at that point is correctly rejected by [SyncAccountEligibility], but it
     * must be reconsidered once the account becomes eligible. Repeated ready notifications are
     * harmless: [enqueue] coalesces the same trigger while it is pending and revalidates Android's
     * automatic-sync switch immediately before submitting work.
     */
    fun onAccountReady() {
        // Account removal closes the process-scoped network subscription. A later login reuses the
        // same application instance, so reopening the scheduling gate must restore it explicitly.
        initialize()
        val generation = activeGeneration() ?: return
        launchScheduling {
            ensurePeriodic(generation)
            val lastSuccess = lastSuccessfulSyncReader.lastSuccessAtEpochMillis()
            val now = clock.nowEpochMillis()
            when {
                lastSuccess == null -> {
                    // Initial foreground import must not depend on Android background scheduling.
                    // The account-scoped runner still owns serialization and runtime preflight.
                    if (foregroundFirstImport != null) {
                        if (activeGeneration() == generation) foregroundFirstImport.invoke()
                    } else enqueue(SyncTrigger.FIRST_IMPORT, 0L, generation)
                }
                now < lastSuccess || now - lastSuccess > STARTUP_STALE_MILLIS ->
                    enqueue(SyncTrigger.STARTUP_STALE_CHECK, 0L, generation)
            }
        }
    }

    fun onMutationCommitted() {
        val generation = activeGeneration() ?: return
        launchScheduling { enqueue(SyncTrigger.MUTATION_COMMITTED, EDIT_BURST_MILLIS, generation) }
    }

    suspend fun metrics(): SyncSchedulingMetrics = synchronized(guard) {
        SyncSchedulingMetrics(
            periodicWorkRequests,
            oneShotWorkRequests,
            coalescedTriggers,
            delayedRequests,
            networkTransitions,
        )
    }

    fun onForegroundChanged(visible: Boolean) {
        synchronized(guard) { foreground = visible }
        if (!visible) return
        val generation = activeGeneration() ?: return
        launchScheduling {
            ensurePeriodic(generation)
            val trigger = if (hasPendingMutations()) {
                SyncTrigger.MUTATION_COMMITTED
            } else {
                val account = accountEligibility.current()
                if (account?.automaticSyncEnabled == true && hasPendingAndroidChanges(account.androidAccountName)) {
                    SyncTrigger.ANDROID_UPLOAD
                } else null
            }
            if (trigger != null && synchronized(guard) { foreground }) {
                enqueue(trigger, EDIT_BURST_MILLIS, generation)
            }
        }
    }

    override fun close() {
        val (subscription, job) = synchronized(guard) {
            active = false
            lifecycleGeneration++
            val existingSubscription = networkSubscription
            val existingJob = pendingJob
            networkSubscription = null
            pendingJob = null
            pendingTriggers.clear()
            periodicAccountName = null
            lastOneShotAt = Long.MIN_VALUE
            existingSubscription to existingJob
        }
        subscription?.close()
        job?.cancel()
    }

    private fun onNetworkState(generation: Long, next: SchedulingNetworkState) {
        val enqueueConnectivityReturn = synchronized(guard) {
            if (!active || lifecycleGeneration != generation) return@synchronized false
            val previous = networkState
            networkState = next
            if (previous != next) networkTransitions++
            previous == SchedulingNetworkState.OFFLINE && next == SchedulingNetworkState.ONLINE
        }
        if (enqueueConnectivityReturn) {
            launchScheduling { enqueue(SyncTrigger.CONNECTIVITY_RETURNED, 0L, generation) }
        }
    }

    private suspend fun ensurePeriodic(generation: Long) {
        try {
            registerPeriodic(generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A subsequent ready/startup/resume retries registration. Initial foreground
            // import must remain possible even when the Android scheduler is unavailable.
        }
    }

    private suspend fun registerPeriodic(generation: Long) {
        if (!isActiveGeneration(generation)) return
        val account = accountEligibility.current()
        synchronized(guard) {
            if (!active || lifecycleGeneration != generation || account?.automaticSyncEnabled != true ||
                periodicAccountName == account.androidAccountName
            ) return@synchronized
            scheduler.ensurePeriodic(account.androidAccountName, PERIODIC_INTERVAL_SECONDS)
            periodicAccountName = account.androidAccountName
            periodicWorkRequests++
        }
    }

    private suspend fun enqueue(trigger: SyncTrigger, initialDelayMillis: Long, generation: Long) {
        synchronized(guard) {
            if (!active || lifecycleGeneration != generation) return
            if (!pendingTriggers.add(trigger) || pendingJob != null) coalescedTriggers++
            if (pendingJob != null) return
            val sinceLast = elapsedSinceLastRequest(clock.nowEpochMillis())
            val rateDelay = (MINIMUM_ONE_SHOT_SPACING_MILLIS - sinceLast).coerceAtLeast(0L)
            val wait = maxOf(initialDelayMillis, rateDelay)
            if (wait > 0) delayedRequests++
            pendingJob = launchScheduling {
                delayMillis(wait)
                submitPending(generation)
            }
        }
    }

    private suspend fun submitPending(generation: Long) {
        val triggers = synchronized(guard) {
            if (!active || lifecycleGeneration != generation) return@synchronized emptySet()
            pendingJob = null
            pendingTriggers.toSet().also { pendingTriggers.clear() }
        }
        if (triggers.isEmpty()) return
        try {
            dispatchPending(generation, triggers)
        } catch (failure: Exception) {
            synchronized(guard) {
                if (active && lifecycleGeneration == generation) pendingTriggers.addAll(triggers)
            }
            if (failure is CancellationException) throw failure
            // Retry on the next meaningful event; no unbounded immediate failure loop.
        }
    }

    private suspend fun dispatchPending(generation: Long, triggers: Set<SyncTrigger>) {
        val account = accountEligibility.current()
        val dispatchForeground = synchronized(guard) {
            if (!active || lifecycleGeneration != generation || account?.automaticSyncEnabled != true) {
                return@synchronized false
            }
            scheduler.request(account.androidAccountName, triggers)
            lastOneShotAt = clock.nowEpochMillis()
            oneShotWorkRequests++
            foreground && networkState == SchedulingNetworkState.ONLINE &&
                (SyncTrigger.MUTATION_COMMITTED in triggers || SyncTrigger.CONNECTIVITY_RETURNED in triggers ||
                    SyncTrigger.ANDROID_UPLOAD in triggers)
        }
        if (dispatchForeground && synchronized(guard) { active && lifecycleGeneration == generation && foreground }) {
            // Keep the platform request for process death/background recovery. The same runner
            // serializes both entry points and retains its own persisted retry/remote backoff.
            foregroundSync?.invoke(triggers)
        }
    }

    private fun launchScheduling(action: suspend () -> Unit): Job = scope.launch {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Durable local intent remains authoritative; a later lifecycle/network event
            // re-evaluates it. Never expose exception text or crash the application scope.
        }
    }

    private fun activeGeneration(): Long? = synchronized(guard) {
        lifecycleGeneration.takeIf { active }
    }

    private fun isActiveGeneration(generation: Long): Boolean = synchronized(guard) {
        active && lifecycleGeneration == generation
    }

    private fun elapsedSinceLastRequest(now: Long): Long = when {
        lastOneShotAt == Long.MIN_VALUE -> Long.MAX_VALUE
        now < lastOneShotAt -> 0L
        else -> now - lastOneShotAt
    }

    private companion object {
        const val PERIODIC_INTERVAL_SECONDS = 60 * 60L
        const val STARTUP_STALE_MILLIS = 15 * 60 * 1_000L
        const val EDIT_BURST_MILLIS = 2_000L
        const val MINIMUM_ONE_SHOT_SPACING_MILLIS = 5_000L
    }
}
