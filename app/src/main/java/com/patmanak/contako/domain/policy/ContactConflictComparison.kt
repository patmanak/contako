package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind

enum class ConflictDifference { SAME, LOCAL_ONLY, PROTON_ONLY, DIFFERENT, UNAVAILABLE }

enum class ConflictField { DISPLAY_NAME, FIRST_NAME, LAST_NAME, DELETION, VALUE, PRESERVATION }

/** Private presentation data only: neither values nor matching identities belong in diagnostics. */
data class ConflictComparedRow(
    val field: ConflictField,
    val kind: ContactValueKind? = null,
    val localOrdinal: Int? = null,
    val protonOrdinal: Int? = null,
    val localText: String? = null,
    val protonText: String? = null,
    val localValue: ContactValue? = null,
    val protonValue: ContactValue? = null,
    val localGroups: List<String>? = null,
    val protonGroups: List<String>? = null,
    val difference: ConflictDifference,
    val ambiguous: Boolean = false,
) {
    override fun toString() = "ConflictComparedRow(REDACTED, field=$field, difference=$difference)"
}

/**
 * Compares stored snapshots without attributing edits to an author or fetching either peer.
 * Positional vCard keys are deliberately not occurrence identities. Repeated occurrences without
 * a unique service identity stay separate, including when their text happens to be identical.
 */
object ContactConflictComparison {
    fun compare(
        local: CanonicalContact,
        proton: CanonicalContact,
        groups: List<ContactGroup> = emptyList(),
    ): List<ConflictComparedRow> {
        require(local.accountId == proton.accountId) { "Comparison requires one owner" }
        val catalog = groups.filter { it.accountId == local.accountId && !it.isDeleted }
        val rows = mutableListOf<ConflictComparedRow>()
        rows += scalar(ConflictField.DISPLAY_NAME, local.displayName, proton.displayName)
        rows += scalar(ConflictField.FIRST_NAME, local.firstName, proton.firstName)
        rows += scalar(ConflictField.LAST_NAME, local.lastName, proton.lastName)
        rows += scalar(ConflictField.DELETION, local.isDeleted.toString(), proton.isDeleted.toString())
        ContactValueKind.entries.forEach { kind ->
            val left = local.valuesOf(kind)
            val right = proton.valuesOf(kind)
            val usedRight = mutableSetOf<Int>()
            val leftTokens = uniqueEmailTokens(left)
            val rightTokens = uniqueEmailTokens(right)
            left.forEachIndexed { index, value ->
                val counterpart = when {
                    left.size == 1 && right.size == 1 -> 0
                    kind == ContactValueKind.EMAIL -> value.metadata[EMAIL_ID]
                        ?.takeIf { leftTokens[it] == index }
                        ?.let { rightTokens[it] }
                    else -> null
                }?.takeUnless { it in usedRight }
                if (counterpart != null) usedRight += counterpart
                rows += valueRow(local, kind, index, value, counterpart, counterpart?.let(right::get),
                    catalog, ambiguous = counterpart == null && right.isNotEmpty())
            }
            right.forEachIndexed { index, value ->
                if (index !in usedRight) rows += valueRow(local, kind, null, null, index, value,
                    catalog, ambiguous = left.isNotEmpty())
            }
        }
        // These envelopes contain complete source cards, not individually editable properties.
        // Exact raw equality is not semantic field equality and MUST NOT expose the cards here.
        if (local.preservationEnvelope != null || proton.preservationEnvelope != null) {
            rows += ConflictComparedRow(ConflictField.PRESERVATION, difference = ConflictDifference.UNAVAILABLE)
        }
        return rows.sortedBy { it.difference == ConflictDifference.SAME }
    }

    private fun scalar(field: ConflictField, local: String, proton: String): ConflictComparedRow =
        ConflictComparedRow(field, localText = local.takeUnless(String::isEmpty),
            protonText = proton.takeUnless(String::isEmpty), difference = when {
                local == proton -> ConflictDifference.SAME
                local.isEmpty() -> ConflictDifference.PROTON_ONLY
                proton.isEmpty() -> ConflictDifference.LOCAL_ONLY
                else -> ConflictDifference.DIFFERENT
            })

    private fun uniqueEmailTokens(values: List<ContactValue>): Map<String, Int> = values.withIndex()
        .filter { it.value.kind == ContactValueKind.EMAIL && !it.value.metadata[EMAIL_ID].isNullOrBlank() }
        .groupBy { requireNotNull(it.value.metadata[EMAIL_ID]) }
        .mapNotNull { (token, occurrences) -> occurrences.singleOrNull()?.let { token to it.index } }.toMap()

    private fun valueRow(
        owner: CanonicalContact,
        kind: ContactValueKind,
        leftIndex: Int?,
        left: ContactValue?,
        rightIndex: Int?,
        right: ContactValue?,
        catalog: List<ContactGroup>,
        ambiguous: Boolean,
    ): ConflictComparedRow {
        val localGroups = if (kind == ContactValueKind.EMAIL && left != null) localGroups(owner, left, catalog) else null
        val protonGroups = if (kind == ContactValueKind.EMAIL && right != null) protonGroups(right, catalog) else null
        val difference = when {
            ambiguous -> ConflictDifference.UNAVAILABLE
            left == null -> ConflictDifference.PROTON_ONLY
            right == null -> ConflictDifference.LOCAL_ONLY
            kind == ContactValueKind.EMAIL && (localGroups == null || protonGroups == null) -> ConflictDifference.UNAVAILABLE
            kind in MEDIA && (!left.hasInlineMedia() || !right.hasInlineMedia()) -> ConflictDifference.UNAVAILABLE
            !sameFields(left, right) -> ConflictDifference.DIFFERENT
            kind == ContactValueKind.EMAIL && localGroups?.keys != protonGroups?.keys -> ConflictDifference.DIFFERENT
            else -> ConflictDifference.SAME
        }
        return ConflictComparedRow(ConflictField.VALUE, kind, leftIndex?.plus(1), rightIndex?.plus(1),
            localValue = left, protonValue = right, localGroups = localGroups?.names,
            protonGroups = protonGroups?.names, difference = difference, ambiguous = ambiguous)
    }

    private fun sameFields(left: ContactValue, right: ContactValue): Boolean =
        left.kind == right.kind && left.value == right.value && left.label == right.label &&
            left.order == right.order && left.isPrimary == right.isPrimary && left.components == right.components &&
            left.metadata.filterKeys { it !in BINDING_METADATA } == right.metadata.filterKeys { it !in BINDING_METADATA } &&
            left.binaryReference == right.binaryReference

    /** Equality is stored inline representation equality, never thumbnail or remote-URL equality. */
    private fun ContactValue.hasInlineMedia(): Boolean =
        (binaryReference ?: value).let { it.startsWith("data:image/", ignoreCase = true) && it.contains(";base64,", ignoreCase = true) }

    private data class ResolvedGroups(val keys: Set<String>, val names: List<String>) {
        override fun toString() = "ResolvedGroups(REDACTED)"
    }

    private fun localGroups(owner: CanonicalContact, email: ContactValue, catalog: List<ContactGroup>): ResolvedGroups? {
        val selected = catalog.filter { group ->
            group.memberships.any { it.contactId == owner.id && it.emailValueId == email.id }
        }
        if (selected.map { it.id }.distinct().size != selected.size) return null
        return ResolvedGroups(selected.mapTo(linkedSetOf()) { "local:${it.id}" }, selected.map { it.name }.sorted())
    }

    private fun protonGroups(email: ContactValue, catalog: List<ContactGroup>): ResolvedGroups? {
        val encoded = email.metadata[GROUP_IDS] ?: return null
        val remoteIds = if (encoded.isEmpty()) emptyList() else encoded.split(',')
        if (remoteIds.any(String::isBlank) || remoteIds.distinct().size != remoteIds.size) return null
        val selected = remoteIds.map { remoteId ->
            catalog.singleOrNull { it.remoteLabelId == remoteId } ?: return null
        }
        return ResolvedGroups(selected.mapTo(linkedSetOf()) { "local:${it.id}" }, selected.map { it.name }.sorted())
    }

    private const val EMAIL_ID = "protonEmailId"
    private const val GROUP_IDS = "protonGroupIds"
    private val MEDIA = setOf(ContactValueKind.PHOTO, ContactValueKind.LOGO)
    private val BINDING_METADATA = setOf(EMAIL_ID, GROUP_IDS, "contakoLocalOnlyLabel", "syncDisposition", "duplicateStandardDate")
}
