package com.patmanak.contako.data.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest

/** One process-owned waiter per account; only the existing runner may perform the actual work.
 * Changes of account admission or durable eligibility cancel the previous wait. There is no
 * timer-driven HTTP call and no immediate loop when a pass cannot make progress. */
internal suspend fun watchTargetedRecoveryWakeups(
    nextEligible: Flow<Long?>,
    invalidated: Flow<Boolean>,
    clock: () -> Long = System::currentTimeMillis,
    requestPass: suspend () -> Unit,
) {
    nextEligible.combine(invalidated) { next, stopped -> if (stopped) null else next }
        .collectLatest { deadline ->
            if (deadline == null) return@collectLatest
            while (clock() < deadline) {
                // Periodic clock rechecks handle wall-clock changes without retrying early.
                delay((deadline - clock()).coerceAtLeast(1).coerceAtMost(60_000))
            }
            requestPass()
        }
}
