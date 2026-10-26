package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.ContactGroupAssignment

internal data class RecreationGroup(val id: String, val name: String, val remoteLabelId: String?)

/** Rebuild through the maintained codec; old wire cards are provenance, never upload payloads. */
internal fun recontextualizeContactForRecreation(
    source: CanonicalContact,
    destinationId: String,
    groups: List<RecreationGroup>,
    assignments: Set<ContactGroupAssignment>,
): CanonicalContact {
    require(destinationId.isNotBlank() && destinationId != source.id && destinationId != source.remoteVCardUid && destinationId != source.remoteContactId)
    val emails = source.valuesOf(ContactValueKind.EMAIL)
    require(assignments.all { edge -> groups.count { it.id == edge.groupId } == 1 && emails.any { it.id == edge.emailValueId } })
    // The service may collapse repeated addresses into one email identity. Until a distinct
    // occurrence identity can be proved, divergent group sets cannot safely be recreated.
    emails.groupBy { it.value.trim().lowercase(java.util.Locale.ROOT) }.values.filter { it.size > 1 }.forEach { repeated ->
        require(repeated.map { email -> assignments.filter { it.emailValueId == email.id }.map { it.groupId }.toSet() }.distinct().size <= 1)
    }
    // Clear CATEGORIES are label names attached to an exact signed EMAIL group. Unknown or
    // unbound categories cannot safely become group assignments on another contact.
    source.valuesOf(ContactValueKind.CATEGORY).forEach { category ->
        val categoryLine = source.sourceLine(category)
        require(categoryLine.first.startsWith("proton-card-0-"))
        val categoryGroup = categoryLine.second.substringBefore(':').substringBefore(';').substringBeforeLast('.', "")
        require(categoryGroup.isNotBlank())
        val matchingEmails = emails.filter { email ->
            val line = source.sourceLine(email).second
            line.substringBefore(':').substringBefore(';').substringBeforeLast('.', "").equals(categoryGroup, true)
        }
        require(matchingEmails.size == 1)
        val matches = groups.filter { it.name == category.value && it.remoteLabelId != null }
        require(matches.size == 1 && ContactGroupAssignment(matches.single().id, matchingEmails.single().id) in assignments)
    }
    // Values with positional provenance retain their local IDs. They are contact-scoped, and
    // changing them would detach preserved parameters, duplicate dates and unknown properties.
    return source.copy(
        id = destinationId, revision = 0, updatedAtEpochMillis = 0,
        remoteContactId = null, remoteVCardUid = null, remoteVersion = null,
        actionRequiredReasons = emptySet(), pendingMutationRevision = null, conflictState = null, isDeleted = false,
        values = source.values.filterNot { it.kind == ContactValueKind.CATEGORY }.map { value ->
            value.copy(metadata = value.metadata - PROTON_EMAIL_ID_KEY - PROTON_GROUP_IDS_KEY)
        },
    ).also { ProtonContactVCardCodec().encode(it) }
}

private fun CanonicalContact.sourceLine(value: ContactValue): Pair<String, String> {
    val key = requireNotNull(value.preservationKey)
    val cardKey = key.substringBeforeLast("#line=", "")
    val index = key.substringAfterLast("#line=", "").substringBefore("#value=").toIntOrNull()
    require(cardKey.isNotBlank() && index != null && index >= 0)
    val card = requireNotNull(preservationEnvelope?.valueSourceCards()?.get(cardKey))
    return cardKey to requireNotNull(unfoldBoundedVCard(card).lines.getOrNull(index))
}
