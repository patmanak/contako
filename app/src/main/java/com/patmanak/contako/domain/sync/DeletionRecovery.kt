package com.patmanak.contako.domain.sync

import com.patmanak.contako.domain.model.CanonicalContact

enum class DeletionRecoveryChoice { KEEP_DRAFT, RECREATE, DISCARD }
enum class DeletionRecoveryState { NEEDS_VERIFICATION, READY, QUEUED, CLEANUP_PENDING, DRAFT, RECREATED, DISCARDED, STALE, BLOCKED }
enum class DeletionRecoveryBlocker { NONE, REMOTE_PRESENT, LOCAL_CHANGED, ANDROID_UNAVAILABLE, NATIVE_CHANGES,
    OPAQUE_ANDROID_DATA, GROUPS_UNRESOLVED, CONTENT_NOT_TRANSFERABLE, REMOTE_UNAVAILABLE }

/** Private local locator and consent version. Never include in diagnostics or notifications. */
data class DeletionRecoverySummary(
    val contactId: String,
    val generation: String,
    val localRevision: Long,
    val draftRevision: Long,
    val displayName: String,
    val state: DeletionRecoveryState,
    val choice: DeletionRecoveryChoice? = null,
    val blocker: DeletionRecoveryBlocker = DeletionRecoveryBlocker.NONE,
    val hasDraft: Boolean = false,
    val absenceVerified: Boolean = false,
    val destinationContactId: String? = null,
    val destinationRevision: Long? = null,
) {
    override fun toString() = "DeletionRecoverySummary(REDACTED, state=$state, blocker=$blocker)"
}

data class DeletionRecoveryDetail(val summary: DeletionRecoverySummary, val contact: CanonicalContact,
    val retainedGroupAssignmentCount: Int = 0) {
    override fun toString() = "DeletionRecoveryDetail(REDACTED)"
}
