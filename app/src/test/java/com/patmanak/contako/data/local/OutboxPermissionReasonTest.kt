package com.patmanak.contako.data.local

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.SyncActionReason
import com.patmanak.contako.data.sync.outboxActionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboxPermissionReasonTest {
    @Test
    fun deniedContactOperationsDoNotClaimAGroupFailure() {
        MutationOperation.entries.forEach { operation ->
            val mutation = mutation(AggregateType.CONTACT, operation)
            assertEquals("REMOTE_PERMISSION_REQUIRED", mutation.blockedReasonFor(DENIED))
            assertEquals(
                SyncActionReason.REMOTE_PERMISSION_REQUIRED,
                listOf(mutation.copy(blockedReason = mutation.blockedReasonFor(DENIED))).outboxActionReason(),
            )
        }
    }

    @Test
    fun deniedGroupWritesIncludingAssignmentsRetainTheGroupCause() {
        MutationOperation.entries.forEach { operation ->
            val mutation = mutation(AggregateType.GROUP, operation)
            assertEquals("GROUP_CAPABILITY_REQUIRED", mutation.blockedReasonFor(DENIED))
            assertEquals(
                SyncActionReason.GROUP_CAPABILITY_REQUIRED,
                listOf(mutation.copy(blockedReason = mutation.blockedReasonFor(DENIED))).outboxActionReason(),
            )
        }
    }

    @Test
    fun historicalMislabelIsCorrectedWithoutChangingTheBlockedMutation() {
        val historical = mutation(AggregateType.CONTACT, MutationOperation.UPSERT).copy(
            errorCategory = DENIED.name,
            blockedReason = "GROUP_CAPABILITY_REQUIRED",
            state = DurableMutationState.ACTION_REQUIRED.name,
        )
        assertEquals(SyncActionReason.REMOTE_PERMISSION_REQUIRED, listOf(historical).outboxActionReason())
        assertEquals("GROUP_CAPABILITY_REQUIRED", historical.blockedReason)
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, historical.state)
        val group = historical.copy(aggregateType = AggregateType.GROUP.name)
        assertEquals(SyncActionReason.GROUP_CAPABILITY_REQUIRED, listOf(group).outboxActionReason())
    }

    @Test
    fun otherGatewayCausesAndTheirPriorityRemainDistinct() {
        val mutation = mutation(AggregateType.CONTACT, MutationOperation.DELETE)
        val expected = mapOf(
            GatewayFailureCategory.AUTHENTICATION_REQUIRED to "AUTHENTICATION_REQUIRED",
            GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED to "INTERACTIVE_AUTHENTICATION_REQUIRED",
            GatewayFailureCategory.VALIDATION_REJECTED to "VALIDATION_REJECTED",
            GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED to "CRYPTOGRAPHIC_VERIFICATION_FAILED",
            GatewayFailureCategory.CONFLICT to "CONFLICT_RECOVERY_REQUIRED",
            GatewayFailureCategory.NOT_FOUND to "CONFLICT_RECOVERY_REQUIRED",
            GatewayFailureCategory.UNKNOWN to "REMOTE_ACTION_REQUIRED",
        )
        expected.forEach { (category, reason) -> assertEquals(reason, mutation.blockedReasonFor(category)) }
        assertNull(emptyList<OutboxMutationEntity>().outboxActionReason())
        val permission = mutation.copy(blockedReason = mutation.blockedReasonFor(DENIED))
        val authentication = mutation.copy(blockedReason = "AUTHENTICATION_REQUIRED")
        assertEquals(
            SyncActionReason.AUTHENTICATION_REQUIRED,
            listOf(permission, authentication).outboxActionReason(),
        )
    }

    private fun mutation(type: AggregateType, operation: MutationOperation) = OutboxMutationEntity(
        accountId = "fixture-account",
        aggregateType = type.name,
        aggregateId = "fixture-aggregate",
        operation = operation.name,
        revision = 1,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
        idempotencyKey = "fixture-key",
    )

    private companion object {
        val DENIED = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
    }
}
