package com.patmanak.contako.data.local

import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.domain.model.ContactValue
import java.util.Locale

/** Preserve pending occurrence-level intent when hydration replaces vCard value IDs. */
internal fun repeatedEmailReplacement(old: ContactValue, previous: List<ContactValue>, current: List<ContactValue>): ContactValue? {
    fun key(value: ContactValue) = value.value.trim().lowercase(Locale.ROOT)
    current.singleOrNull { it.id == old.id && key(it) == key(old) }?.let { return it }
    val candidates = current.filter { key(it) == key(old) }
    val siblings = previous.filter { key(it) == key(old) }.sortedBy { it.order }
    val ordered = candidates.sortedBy { it.order }
    // A uniquely retained service identity remains proof when another occurrence disappears.
    old.metadata[PROTON_EMAIL_ID_KEY]?.let { id ->
        if (siblings.count { it.metadata[PROTON_EMAIL_ID_KEY] == id } == 1) {
            ordered.singleOrNull { it.metadata[PROTON_EMAIL_ID_KEY] == id }?.let { return it }
        }
    }
    if (siblings.size == 1 && candidates.size == 1) return candidates.single()
    if (siblings.size != ordered.size || siblings.isEmpty() ||
        siblings.map { it.order }.distinct().size != siblings.size ||
        ordered.map { it.order }.distinct().size != ordered.size ||
        siblings.map { it.label } != ordered.map { it.label }) return null
    val index = siblings.indexOfFirst { it.id == old.id }
    return ordered.getOrNull(index)
}

internal fun OutboxMutationEntity.isRepeatedEmailRecoveryCandidate(revision: Long, pendingRevision: Long?): Boolean =
    aggregateType == AggregateType.GROUP.name && operation == MutationOperation.ASSIGNMENTS.name &&
        state == DurableMutationState.ACTION_REQUIRED.name && errorCategory == "CONFLICT" &&
        blockedReason == "CONFLICT_RECOVERY_REQUIRED" && this.revision == revision && pendingRevision == revision
