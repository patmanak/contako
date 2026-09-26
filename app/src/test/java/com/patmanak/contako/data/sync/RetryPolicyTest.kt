package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    private val policy = MutationRetryPolicy(
        baseDelayMillis = 1_000,
        maximumDelayMillis = 60_000,
        maximumAutomaticAttempts = 3,
    )

    @Test
    fun `03-FAULT transient attempts back off exponentially and require ambiguous reconciliation`() {
        listOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            GatewayFailureCategory.UNKNOWN,
        ).forEach { category ->
            val first = policy.decide(category, UPDATE, 0, 10_000) as RetryDecision.RetryAt
            val second = policy.decide(category, UPDATE, 1, 10_000) as RetryDecision.RetryAt
            assertEquals(11_000, first.nextEligibleEpochMillis)
            assertEquals(12_000, second.nextEligibleEpochMillis)
            assertTrue(first.reconcileBeforeReplay)
        }
    }

    @Test
    fun `03-FAULT rate limit never shortens server guidance and is not ambiguous`() {
        val guided = policy.decide(RATE_LIMITED, CREATE, 0, 10_000, retryAfterMillis = 9_000) as RetryDecision.RetryAt
        val exponential = policy.decide(RATE_LIMITED, CREATE, 2, 10_000, retryAfterMillis = 500) as RetryDecision.RetryAt

        assertEquals(19_000, guided.nextEligibleEpochMillis)
        assertEquals(14_000, exponential.nextEligibleEpochMillis)
        assertFalse(guided.reconcileBeforeReplay)
    }

    @Test
    fun `03-FAULT cancellation preserves attempts and delete not found converges`() {
        assertEquals(RetryDecision.Cancelled, policy.decide(CANCELLED, UPDATE, 2, 10_000))
        assertEquals(RetryDecision.DeleteConverged, policy.decide(NOT_FOUND, DELETE, 2, 10_000))
        assertEquals(RetryDecision.ActionRequired, policy.decide(NOT_FOUND, UPDATE, 2, 10_000))
    }

    @Test
    fun `server guidance above the local cap is never shortened`() {
        val guided = policy.decide(RATE_LIMITED, UPDATE, 0, 10_000, 120_000) as RetryDecision.RetryAt
        assertEquals(130_000, guided.nextEligibleEpochMillis)
        val saturated = policy.decide(RATE_LIMITED, UPDATE, 0, 10_000, Long.MAX_VALUE) as RetryDecision.RetryAt
        assertEquals(Long.MAX_VALUE, saturated.nextEligibleEpochMillis)
    }

    @Test
    fun `an exhausted persisted attempt count cannot overflow into a retry`() {
        assertEquals(RetryDecision.ActionRequired, policy.decide(TIMEOUT, UPDATE, Int.MAX_VALUE, 10_000))
    }

    @Test
    fun `03-STATUS blocking failures never enter a tight retry`() {
        GatewayFailureCategory.entries
            .filterNot { it in setOf(NETWORK_UNAVAILABLE, TIMEOUT, RATE_LIMITED, REMOTE_SERVICE_FAILURE, UNKNOWN, CANCELLED) }
            .filterNot { it == NOT_FOUND }
            .forEach { category ->
                assertEquals(RetryDecision.ActionRequired, policy.decide(category, UPDATE, 0, 10_000))
            }
    }

    @Test
    fun `03-FAULT automatic attempts are bounded and deadlines saturate`() {
        assertEquals(RetryDecision.ActionRequired, policy.decide(TIMEOUT, UPDATE, 3, 10_000))
        val saturated = policy.decide(TIMEOUT, UPDATE, 0, Long.MAX_VALUE - 10) as RetryDecision.RetryAt
        assertEquals(Long.MAX_VALUE, saturated.nextEligibleEpochMillis)
    }

    private companion object {
        val CREATE = RemoteMutationOperation.CREATE
        val UPDATE = RemoteMutationOperation.UPDATE
        val DELETE = RemoteMutationOperation.DELETE
        val NETWORK_UNAVAILABLE = GatewayFailureCategory.NETWORK_UNAVAILABLE
        val TIMEOUT = GatewayFailureCategory.TIMEOUT
        val RATE_LIMITED = GatewayFailureCategory.RATE_LIMITED
        val CANCELLED = GatewayFailureCategory.CANCELLED
        val NOT_FOUND = GatewayFailureCategory.NOT_FOUND
        val REMOTE_SERVICE_FAILURE = GatewayFailureCategory.REMOTE_SERVICE_FAILURE
        val UNKNOWN = GatewayFailureCategory.UNKNOWN
    }
}
