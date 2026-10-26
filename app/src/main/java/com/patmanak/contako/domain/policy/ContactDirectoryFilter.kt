package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind

data class DirectoryFilter(
    val withoutPhone: Boolean = false,
    val withoutEmail: Boolean = false,
    /** Facet keys produced by [ContactSearch.normalize], never remote organization identities. */
    val organizations: Set<String> = emptySet(),
    val localOnly: Boolean = false,
    val recentChanges: Boolean = false,
) {
    val isActive: Boolean
        get() = withoutPhone || withoutEmail || organizations.isNotEmpty() || localOnly || recentChanges

    override fun toString(): String = "DirectoryFilter(redacted)"
}

data class OrganizationFacet(val key: String, val label: String) {
    override fun toString(): String = "OrganizationFacet(redacted)"
}

/** Local, read-only criteria. Callers supply one observation clock for the complete result. */
object ContactDirectoryPolicy {
    fun organizationFacets(contacts: List<CanonicalContact>): List<OrganizationFacet> =
        contacts.asSequence()
            .filterNot(CanonicalContact::isDeleted)
            .flatMap { it.values.asSequence() }
            .filter { it.kind == ContactValueKind.ORGANIZATION && it.value.isNotBlank() }
            .map { it.value.trim() }
            .groupBy(ContactSearch::normalize)
            .toSortedMap()
            .map { (key, labels) -> OrganizationFacet(key, labels.minOrNull()!!) }

    fun matches(
        contact: CanonicalContact,
        filter: DirectoryFilter,
        changedAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): Boolean {
        if (contact.isDeleted) return false
        if (filter.withoutPhone && contact.values.any { it.kind == ContactValueKind.PHONE && it.value.isNotBlank() }) return false
        if (filter.withoutEmail && contact.values.any { it.kind == ContactValueKind.EMAIL && it.value.isNotBlank() }) return false
        if (filter.organizations.isNotEmpty() && contact.values.none {
                it.kind == ContactValueKind.ORGANIZATION && it.value.isNotBlank() &&
                    ContactSearch.normalize(it.value) in filter.organizations
            }) return false
        if (filter.localOnly && contact.values.none {
                ContactValueSupport.isLocalOnly(it) && (it.value.isNotBlank() || it.components.values.any(String::isNotBlank))
            }) return false
        if (filter.recentChanges) {
            val changedAt = changedAtEpochMillis ?: return false
            // Saturate the lower bound so extreme clock values cannot wrap into the future.
            val earliest = if (nowEpochMillis < Long.MIN_VALUE + RECENT_WINDOW_MILLIS) Long.MIN_VALUE
                else nowEpochMillis - RECENT_WINDOW_MILLIS
            if (changedAt < earliest || changedAt > nowEpochMillis) return false
        }
        return true
    }

    private const val RECENT_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1_000
}
