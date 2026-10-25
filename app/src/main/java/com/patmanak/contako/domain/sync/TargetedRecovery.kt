package com.patmanak.contako.domain.sync

enum class TargetedRecoveryKind { PROTON_CARD, ANDROID_COPY }
enum class TargetedRecoveryState { REQUESTED, RUNNING, DEFERRED, RESOLVED, STALE, ACTION_REQUIRED }
enum class TargetedRecoveryPrerequisite {
    NO_OBLIGATION, AUTHENTICATION, ANDROID_PERMISSION, ANDROID_CONTEXT,
    GROUP_CONTEXT, CONTACT_CHANGED, REMOTE_IDENTITY, UNAVAILABLE,
}
enum class TargetedRecoveryQueueResult { QUEUED, ALREADY_PENDING, STALE, UNAVAILABLE }

/** A private local preview, revalidated on queue and execution; never a write capability. */
data class TargetedRecoveryPreview(
    val kind: TargetedRecoveryKind,
    val targetId: String,
    val obligationGeneration: String,
    val canonicalRevision: Long?,
    val providerEpoch: Long?,
    val prerequisite: TargetedRecoveryPrerequisite? = null,
    val displayName: String? = null,
    val isDeleted: Boolean = false,
) {
    val canQueue: Boolean get() = prerequisite == null
    override fun toString() = "TargetedRecoveryPreview(REDACTED, kind=$kind, prerequisite=$prerequisite)"
}

/** Result of one scoped operation. RESOLVED does not qualify whole-account convergence. */
data class TargetedRecoveryStatus(
    val kind: TargetedRecoveryKind,
    val targetId: String,
    val state: TargetedRecoveryState,
    val nextEligibleAtEpochMillis: Long? = null,
    val prerequisite: TargetedRecoveryPrerequisite? = null,
    val displayName: String? = null,
) {
    override fun toString() = "TargetedRecoveryStatus(REDACTED, kind=$kind, state=$state)"
}
