package com.patmanak.contako.domain.sync

/** Private UI scope only. This model never includes names, contact identities or raw errors. */
enum class SyncIssueScope { ACCOUNT, CONTACT, GROUP, PROTON_IMPORT, ANDROID_COPY }

enum class SyncIssueCause {
    AUTHENTICATION, NETWORK, CONFLICT, VALIDATION, CRYPTOGRAPHY,
    GROUP_CAPABILITY, REMOTE_PERMISSION, ANDROID, UNKNOWN,
}

enum class SyncIssueAction {
    CONTACTS_PERMISSION, SIGN_IN, RETRY_SYNC, COMPARE_VERSIONS, REVIEW_ITEM, VIEW_DIAGNOSTICS, WAIT,
    PREVIEW_TARGETED_RECOVERY,
}

/** Only field families established by canonical validation, never inferred from a remote refusal. */
enum class SyncValidationIssue { MISSING_NAME, INVALID_EMAIL }

data class SyncIssueAdvice(
    val cause: SyncIssueCause,
    val scope: SyncIssueScope,
    val action: SyncIssueAction,
    /** True only when the caller supplies evidence of the relevant durable local intent. */
    val localIntentSaved: Boolean,
    val validationIssues: Set<SyncValidationIssue> = emptySet(),
)

/**
 * Maps current closed domain facts to one primary action. It performs no recovery, acknowledgement
 * or side effect, and MUST NOT be fed historical diagnostic failures as current problems.
 *
 * RETRY_SYNC requests the existing serialized engine; it does not override its admission,
 * eligibility, backoff or account-generation checks. Opening an item or returning from settings
 * is not proof that the issue has been resolved. A queued conflict choice is pending work, not
 * another invitation to choose or a promise that its write has already completed.
 */
object SyncIssuePresentationPolicy {
    fun present(
        problem: SyncProblem?,
        scope: SyncIssueScope,
        state: SyncDashboardState = SyncDashboardState.BLOCKED,
        hasDurableIntent: Boolean = false,
        canCompare: Boolean = false,
        canReview: Boolean = false,
        contactsPermissionMissing: Boolean = false,
        conflictChoiceQueued: Boolean = false,
        canTarget: Boolean = false,
        knownValidationIssues: Set<SyncValidationIssue> = emptySet(),
    ): SyncIssueAdvice {
        fun advice(cause: SyncIssueCause, action: SyncIssueAction) = SyncIssueAdvice(cause, scope,
            if (action == SyncIssueAction.VIEW_DIAGNOSTICS && canTarget &&
                scope in setOf(SyncIssueScope.PROTON_IMPORT, SyncIssueScope.ANDROID_COPY))
                SyncIssueAction.PREVIEW_TARGETED_RECOVERY else action, hasDurableIntent,
            if (cause == SyncIssueCause.VALIDATION && scope == SyncIssueScope.CONTACT)
                knownValidationIssues else emptySet())

        if (scope == SyncIssueScope.ANDROID_COPY && contactsPermissionMissing) {
            return advice(SyncIssueCause.ANDROID, SyncIssueAction.CONTACTS_PERMISSION)
        }
        // An account prerequisite does not replace the independently known cause of an item.
        // The global authentication issue is presented separately; state is a fallback only
        // when no closed item problem is available.
        if ((problem == null && state == SyncDashboardState.AUTHENTICATION_REQUIRED) ||
            problem == SyncProblem.AUTHENTICATION_REQUIRED ||
            problem == SyncProblem.INTERACTIVE_AUTHENTICATION_REQUIRED) {
            return advice(SyncIssueCause.AUTHENTICATION, SyncIssueAction.SIGN_IN)
        }
        return when (problem) {
            SyncProblem.AUTHENTICATION_REQUIRED, SyncProblem.INTERACTIVE_AUTHENTICATION_REQUIRED ->
                advice(SyncIssueCause.AUTHENTICATION, SyncIssueAction.SIGN_IN)
            SyncProblem.CONFLICT_RECOVERY_REQUIRED -> advice(SyncIssueCause.CONFLICT, when {
                conflictChoiceQueued -> SyncIssueAction.WAIT
                canCompare -> SyncIssueAction.COMPARE_VERSIONS
                // A generic item link is not evidence that two retained versions can be chosen.
                else -> SyncIssueAction.VIEW_DIAGNOSTICS
            })
            SyncProblem.VALIDATION_REJECTED -> advice(SyncIssueCause.VALIDATION,
                if (canReview) SyncIssueAction.REVIEW_ITEM else SyncIssueAction.VIEW_DIAGNOSTICS)
            SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED ->
                advice(SyncIssueCause.CRYPTOGRAPHY, SyncIssueAction.VIEW_DIAGNOSTICS)
            SyncProblem.GROUP_CAPABILITY_REQUIRED ->
                advice(SyncIssueCause.GROUP_CAPABILITY, SyncIssueAction.RETRY_SYNC)
            SyncProblem.REMOTE_PERMISSION_REQUIRED -> advice(SyncIssueCause.REMOTE_PERMISSION,
                if (canReview) SyncIssueAction.REVIEW_ITEM else SyncIssueAction.VIEW_DIAGNOSTICS)
            SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED, SyncProblem.ANDROID_CONTACTS_PARTIALLY_PROJECTED ->
                advice(SyncIssueCause.ANDROID, SyncIssueAction.VIEW_DIAGNOSTICS)
            SyncProblem.INTERNAL_FAILURE -> advice(SyncIssueCause.UNKNOWN, SyncIssueAction.VIEW_DIAGNOSTICS)
            null -> if (state == SyncDashboardState.OFFLINE) {
                advice(SyncIssueCause.NETWORK, SyncIssueAction.RETRY_SYNC)
            } else advice(SyncIssueCause.UNKNOWN, SyncIssueAction.VIEW_DIAGNOSTICS)
        }
    }

    /** The last scoped attempt can change its advice without reclassifying the account or import. */
    fun presentTargetedFailure(status: TargetedRecoveryStatus,
        contactsPermissionMissing: Boolean = false): SyncIssueAdvice? {
        if (status.state !in setOf(TargetedRecoveryState.DEFERRED, TargetedRecoveryState.ACTION_REQUIRED)) return null
        val scope = if (status.kind == TargetedRecoveryKind.PROTON_CARD) SyncIssueScope.PROTON_IMPORT else SyncIssueScope.ANDROID_COPY
        if (contactsPermissionMissing && (scope == SyncIssueScope.ANDROID_COPY ||
                status.prerequisite == TargetedRecoveryPrerequisite.ANDROID_PERMISSION)) {
            return SyncIssueAdvice(SyncIssueCause.ANDROID, scope, SyncIssueAction.CONTACTS_PERMISSION, false)
        }
        // Native capture may be a prerequisite of a Proton read as well as an Android retry.
        // Its technical gateway category never establishes a malformed contact field.
        when (status.prerequisite) {
            TargetedRecoveryPrerequisite.ANDROID_PERMISSION, TargetedRecoveryPrerequisite.ANDROID_CONTEXT,
            TargetedRecoveryPrerequisite.GROUP_CONTEXT -> return SyncIssueAdvice(SyncIssueCause.ANDROID,
                scope, if (status.state == TargetedRecoveryState.DEFERRED) SyncIssueAction.WAIT
                    else SyncIssueAction.PREVIEW_TARGETED_RECOVERY, false)
            TargetedRecoveryPrerequisite.AUTHENTICATION -> return SyncIssueAdvice(SyncIssueCause.AUTHENTICATION,
                scope, SyncIssueAction.SIGN_IN, false)
            else -> Unit
        }
        val failure = status.lastFailure ?: return null
        val cause = when (failure) {
            TargetedRecoveryFailure.AUTHENTICATION -> SyncIssueCause.AUTHENTICATION
            TargetedRecoveryFailure.NETWORK, TargetedRecoveryFailure.RATE_LIMITED -> SyncIssueCause.NETWORK
            TargetedRecoveryFailure.VALIDATION -> SyncIssueCause.VALIDATION
            TargetedRecoveryFailure.CRYPTOGRAPHY -> SyncIssueCause.CRYPTOGRAPHY
            TargetedRecoveryFailure.REMOTE_PERMISSION -> SyncIssueCause.REMOTE_PERMISSION
            TargetedRecoveryFailure.CONFLICT -> SyncIssueCause.CONFLICT
            TargetedRecoveryFailure.REMOTE_SERVICE, TargetedRecoveryFailure.MALFORMED_RESPONSE,
            TargetedRecoveryFailure.UNKNOWN -> SyncIssueCause.UNKNOWN
        }
        val action = when {
            failure == TargetedRecoveryFailure.AUTHENTICATION -> SyncIssueAction.SIGN_IN
            status.state == TargetedRecoveryState.DEFERRED -> SyncIssueAction.WAIT
            failure in setOf(TargetedRecoveryFailure.NETWORK, TargetedRecoveryFailure.RATE_LIMITED,
                TargetedRecoveryFailure.REMOTE_SERVICE) -> SyncIssueAction.PREVIEW_TARGETED_RECOVERY
            else -> SyncIssueAction.VIEW_DIAGNOSTICS
        }
        return SyncIssueAdvice(cause, scope, action, false)
    }
}
