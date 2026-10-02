package com.patmanak.contako.domain.sync

import com.patmanak.contako.domain.model.CanonicalContact

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
) {
    override fun toString() = "ContactConflictDetail(REDACTED)"
}
