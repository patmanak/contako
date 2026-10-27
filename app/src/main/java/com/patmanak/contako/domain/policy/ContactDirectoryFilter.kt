package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind

data class DirectoryFilter(
    val withoutPhone: Boolean = false,
    val withoutEmail: Boolean = false,
    val localOnly: Boolean = false,
    val recentChanges: Boolean = false,
    val recentDays: Int = 7,
) {
    init { require(recentDays > 0) }

    val isActive: Boolean
        get() = withoutPhone || withoutEmail || localOnly || recentChanges

    override fun toString(): String = "DirectoryFilter(redacted)"
}

/** Local, read-only criteria. Callers supply one observation clock for the complete result. */
object ContactDirectoryPolicy {
    fun matches(
        contact: CanonicalContact,
        filter: DirectoryFilter,
        changedAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): Boolean {
        if (contact.isDeleted) return false
        if (filter.withoutPhone && contact.values.any { it.kind == ContactValueKind.PHONE && it.value.isNotBlank() }) return false
        if (filter.withoutEmail && contact.values.any { it.kind == ContactValueKind.EMAIL && it.value.isNotBlank() }) return false
        if (filter.localOnly && contact.values.none {
                ContactValueSupport.isLocalOnly(it) && (it.value.isNotBlank() || it.components.values.any(String::isNotBlank))
            }) return false
        if (filter.recentChanges) {
            val changedAt = changedAtEpochMillis ?: return false
            val windowMillis = filter.recentDays.toLong() * MILLIS_PER_DAY
            // Saturate the lower bound so extreme clock values cannot wrap into the future.
            val earliest = if (nowEpochMillis < Long.MIN_VALUE + windowMillis) Long.MIN_VALUE
                else nowEpochMillis - windowMillis
            if (changedAt < earliest || changedAt > nowEpochMillis) return false
        }
        return true
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
}
