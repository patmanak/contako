package com.patmanak.contako.domain.sync

/** Private, local-only evidence. Never serialize these models into diagnostics or notifications. */
enum class ContactPeerSyncState { CONFIRMED, PENDING, CONFLICT, ACTION_REQUIRED, HISTORICAL, UNKNOWN, UNAVAILABLE }

enum class ContactSyncEvidenceKind { VERIFIED_READ, WRITE_ACKNOWLEDGEMENT, CONFIRMED_ABSENCE, ANDROID_READBACK, ANDROID_ABSENCE }

data class ContactSyncEvidence(
    val localRevision: Long,
    val confirmedAtEpochMillis: Long?,
    val kind: ContactSyncEvidenceKind,
) {
    override fun toString() = "ContactSyncEvidence(REDACTED, kind=$kind)"
}

data class ContactPeerSyncDetail(
    val state: ContactPeerSyncState,
    val evidence: ContactSyncEvidence? = null,
) {
    override fun toString() = "ContactPeerSyncDetail(state=$state)"
}

/** The contact-card proof excludes independently synchronized email-group assignments. */
data class ContactSyncDetail(
    val contactId: String,
    val displayName: String?,
    val localRevision: Long,
    val isDeleted: Boolean,
    val proton: ContactPeerSyncDetail,
    val android: ContactPeerSyncDetail,
    val localOnlyFieldCount: Int = 0,
    val groupChangesPending: Boolean = false,
) {
    override fun toString() = "ContactSyncDetail(REDACTED)"
}
