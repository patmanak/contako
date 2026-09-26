package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.GatewayFailureCategory

enum class RemoteMutationOperation { CREATE, UPDATE, DELETE, ASSIGNMENTS }

sealed interface RetryDecision {
    data class RetryAt(
        val attemptCount: Int,
        val nextEligibleEpochMillis: Long,
        val reconcileBeforeReplay: Boolean,
    ) : RetryDecision

    data object ActionRequired : RetryDecision
    data object Cancelled : RetryDecision
    data object DeleteConverged : RetryDecision
}

/** Sanitized, deterministic and overflow-safe retry policy for durable mutations. */
class MutationRetryPolicy(
    private val baseDelayMillis: Long = 5_000L,
    private val maximumDelayMillis: Long = 6 * 60 * 60 * 1_000L,
    private val maximumAutomaticAttempts: Int = 8,
    private val jitterFraction: (attempt: Int) -> Double = { 0.0 },
) {
    init {
        require(baseDelayMillis > 0)
        require(maximumDelayMillis >= baseDelayMillis)
        require(maximumAutomaticAttempts > 0)
    }

    fun decide(
        category: GatewayFailureCategory,
        operation: RemoteMutationOperation,
        previousAttemptCount: Int,
        nowEpochMillis: Long,
        retryAfterMillis: Long? = null,
    ): RetryDecision {
        require(previousAttemptCount >= 0)
        require(retryAfterMillis == null || retryAfterMillis >= 0)
        if (category == GatewayFailureCategory.CANCELLED) return RetryDecision.Cancelled
        if (category == GatewayFailureCategory.NOT_FOUND && operation == RemoteMutationOperation.DELETE) {
            return RetryDecision.DeleteConverged
        }
        if (category !in RETRYABLE) return RetryDecision.ActionRequired

        if (previousAttemptCount >= maximumAutomaticAttempts) return RetryDecision.ActionRequired
        val attempt = previousAttemptCount + 1
        val exponent = (attempt - 1).coerceAtMost(62)
        val exponential = saturatingShift(baseDelayMillis, exponent).coerceAtMost(maximumDelayMillis)
        // The local cap bounds our exponential backoff, never a server's minimum delay.
        val serverDelay = retryAfterMillis ?: 0L
        val unjittered = maxOf(exponential, serverDelay)
        val fraction = jitterFraction(attempt).coerceIn(0.0, 1.0)
        val jitterRoom = (maximumDelayMillis - unjittered).coerceAtLeast(0L)
        val jitter = (unjittered.toDouble() * fraction).toLong().coerceAtMost(jitterRoom)
        val delay = saturatingAdd(unjittered, jitter)
        return RetryDecision.RetryAt(
            attemptCount = attempt,
            nextEligibleEpochMillis = saturatingAdd(nowEpochMillis, delay),
            reconcileBeforeReplay = category in AMBIGUOUS_DELIVERY,
        )
    }

    private companion object {
        val RETRYABLE = setOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.RATE_LIMITED,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            GatewayFailureCategory.UNKNOWN,
        )
        val AMBIGUOUS_DELIVERY = setOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            GatewayFailureCategory.UNKNOWN,
        )
    }
}

private fun saturatingShift(value: Long, exponent: Int): Long {
    if (exponent == 0) return value
    if (value > (Long.MAX_VALUE shr exponent)) return Long.MAX_VALUE
    return value shl exponent
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
