package com.patmanak.contako.data.local

import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.*
import org.junit.Test

class RepeatedEmailRecoveryTest {
    private val previous = listOf(
        ContactValue("old-home", ContactValueKind.EMAIL, "fixture@example.test", label = "HOME", order = 0),
        ContactValue("old-work", ContactValueKind.EMAIL, "fixture@example.test", label = "WORK", order = 1),
    )
    private val current = previous.map { it.copy(id = "new-${it.id}") }

    @Test fun `pending duplicate memberships remap to matching occurrence without collapse`() {
        assertEquals(listOf("new-old-home", "new-old-work"), previous.map {
            repeatedEmailReplacement(it, previous, current.reversed())?.id
        })
    }

    @Test fun `ambiguous occurrence replacement preserves the blocked intent`() {
        assertNull(repeatedEmailReplacement(previous[1], previous, current + current.last().copy(id = "third", order = 2)))
        assertNull(repeatedEmailReplacement(previous[1], previous, current.map { it.copy(order = 0) }))
        assertNull(repeatedEmailReplacement(previous[1], previous, current.map { it.copy(label = "OTHER") }))
        assertNull(repeatedEmailReplacement(previous[1], previous, emptyList()))
    }

    @Test fun `shared old service identity never collapses independently ordered rows`() {
        val key = com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
        val shared = previous.map { it.copy(metadata = mapOf(key to "shared")) }
        val distinct = current.mapIndexed { index, row -> row.copy(metadata = mapOf(key to if (index == 0) "shared" else "other")) }
        assertEquals(distinct.map { it.id }, shared.map { repeatedEmailReplacement(it, shared, distinct)?.id })
    }

    @Test fun `removed duplicate occurrence never transfers its pending membership to survivor`() {
        val key = com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
        val distinct = previous.mapIndexed { index, row -> row.copy(metadata = mapOf(key to "service-$index")) }
        val survivor = distinct.first().copy(id = "hydrated-home")
        assertNull(repeatedEmailReplacement(distinct.last(), distinct, listOf(survivor)))
        assertEquals(survivor, repeatedEmailReplacement(distinct.first(), distinct, listOf(survivor)))
        assertNull(repeatedEmailReplacement(previous.first(), previous, listOf(current.first())))
    }

    @Test fun `only same revision legacy assignment conflicts are recovery candidates`() {
        val intent = OutboxMutationEntity("fixture-account", "GROUP", "fixture-group", "ASSIGNMENTS", 2,
            0, 0, errorCategory = "CONFLICT", blockedReason = "CONFLICT_RECOVERY_REQUIRED",
            idempotencyKey = "fixture", state = "ACTION_REQUIRED")
        assertTrue(intent.isRepeatedEmailRecoveryCandidate(2, 2))
        assertFalse(intent.isRepeatedEmailRecoveryCandidate(3, 3))
        assertFalse(intent.isRepeatedEmailRecoveryCandidate(2, null))
        assertFalse(intent.copy(operation = "DELETE").isRepeatedEmailRecoveryCandidate(2, 2))
        assertFalse(intent.copy(aggregateType = "CONTACT").isRepeatedEmailRecoveryCandidate(2, 2))
        assertFalse(intent.copy(errorCategory = "PERMISSION_OR_PLAN_DENIED").isRepeatedEmailRecoveryCandidate(2, 2))
        assertFalse(intent.copy(blockedReason = "EDIT_DELETE_RECOVERY_REQUIRED").isRepeatedEmailRecoveryCandidate(2, 2))
    }
}
