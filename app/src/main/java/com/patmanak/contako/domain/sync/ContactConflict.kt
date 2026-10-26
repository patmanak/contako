package com.patmanak.contako.domain.sync

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup

enum class ContactConflictChoice { LOCAL, PROTON }

/** Opaque revision token: display data and locators MUST NOT enter diagnostics. */
data class ContactConflictSummary(
    val contactId: String,
    val generation: String,
    val localRevision: Long,
    val remoteVersion: String,
    val choice: ContactConflictChoice?,
    val remoteDeleted: Boolean = false,
) {
    override fun toString() = "ContactConflictSummary(REDACTED)"
}

data class ContactConflictDetail(
    val summary: ContactConflictSummary,
    val local: CanonicalContact,
    val proton: CanonicalContact,
    val protonChoiceAvailable: Boolean = true,
    /** Field previews only: these are neither committed revisions nor peer confirmations. */
    val localPreview: CanonicalContact? = null,
    val protonPreview: CanonicalContact? = null,
    /** Current owned catalogue and assignments, read atomically with the conflict snapshots. */
    val groups: List<ContactGroup> = emptyList(),
    val isCurrent: Boolean = true,
) {
    override fun toString() = "ContactConflictDetail(REDACTED)"
}
