package com.patmanak.contako.domain.sync

import org.junit.Assert.*
import org.junit.Test

class SyncIssuePresentationTest {
    @org.junit.Test fun scopedRecoveryOnlyOffersAPreviewWhenAnExactTargetIsAvailable() {
        for (scope in SyncIssueScope.entries) {
            val advice = SyncIssuePresentationPolicy.present(SyncProblem.INTERNAL_FAILURE, scope, canTarget = true)
            org.junit.Assert.assertEquals(if (scope in setOf(SyncIssueScope.PROTON_IMPORT, SyncIssueScope.ANDROID_COPY))
                SyncIssueAction.PREVIEW_TARGETED_RECOVERY else SyncIssueAction.VIEW_DIAGNOSTICS, advice.action)
            org.junit.Assert.assertFalse(advice.localIntentSaved)
        }
        org.junit.Assert.assertEquals(SyncIssueAction.CONTACTS_PERMISSION,
            SyncIssuePresentationPolicy.present(SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED,
                SyncIssueScope.ANDROID_COPY, contactsPermissionMissing = true, canTarget = true).action)
    }
    private val policy = SyncIssuePresentationPolicy

    @Test fun everyCurrentProblemHasAnExplicitConservativeMappingInEveryScope() {
        val expected = mapOf(
            SyncProblem.AUTHENTICATION_REQUIRED to (SyncIssueCause.AUTHENTICATION to SyncIssueAction.SIGN_IN),
            SyncProblem.INTERACTIVE_AUTHENTICATION_REQUIRED to (SyncIssueCause.AUTHENTICATION to SyncIssueAction.SIGN_IN),
            SyncProblem.VALIDATION_REJECTED to (SyncIssueCause.VALIDATION to SyncIssueAction.VIEW_DIAGNOSTICS),
            SyncProblem.CONFLICT_RECOVERY_REQUIRED to (SyncIssueCause.CONFLICT to SyncIssueAction.VIEW_DIAGNOSTICS),
            SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED to (SyncIssueCause.CRYPTOGRAPHY to SyncIssueAction.VIEW_DIAGNOSTICS),
            SyncProblem.GROUP_CAPABILITY_REQUIRED to (SyncIssueCause.GROUP_CAPABILITY to SyncIssueAction.RETRY_SYNC),
            SyncProblem.REMOTE_PERMISSION_REQUIRED to (SyncIssueCause.REMOTE_PERMISSION to SyncIssueAction.VIEW_DIAGNOSTICS),
            SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED to (SyncIssueCause.ANDROID to SyncIssueAction.VIEW_DIAGNOSTICS),
            SyncProblem.ANDROID_CONTACTS_PARTIALLY_PROJECTED to (SyncIssueCause.ANDROID to SyncIssueAction.VIEW_DIAGNOSTICS),
            SyncProblem.INTERNAL_FAILURE to (SyncIssueCause.UNKNOWN to SyncIssueAction.VIEW_DIAGNOSTICS),
        )
        assertEquals(SyncProblem.entries.toSet(), expected.keys)
        SyncIssueScope.entries.forEach { scope ->
            expected.forEach { (problem, mapping) ->
                val actual = policy.present(problem, scope)
                assertEquals("$problem/$scope", SyncIssueAdvice(mapping.first, scope, mapping.second, false), actual)
            }
        }
    }

    @Test fun permissionAdviceRequiresAConfirmedAndroidPrerequisiteAndCannotLeakIntoRemoteScopes() {
        SyncIssueScope.entries.forEach { scope ->
            val advice = policy.present(SyncProblem.ANDROID_CONTACTS_PARTIALLY_PROJECTED, scope,
                contactsPermissionMissing = true)
            assertEquals(if (scope == SyncIssueScope.ANDROID_COPY) SyncIssueAction.CONTACTS_PERMISSION
                else SyncIssueAction.VIEW_DIAGNOSTICS, advice.action)
            assertEquals(SyncIssueCause.ANDROID, advice.cause)
        }
        assertEquals(SyncIssueAction.CONTACTS_PERMISSION, policy.present(null, SyncIssueScope.ANDROID_COPY,
            contactsPermissionMissing = true).action)
        assertEquals(SyncIssueAction.VIEW_DIAGNOSTICS, policy.present(
            SyncProblem.ANDROID_CONTACTS_PARTIALLY_PROJECTED, SyncIssueScope.ANDROID_COPY).action)
    }

    @Test fun signInIsNeverGuessedFromAndroidValidationCryptographyOrUnknownFailures() {
        listOf(null, SyncProblem.INTERNAL_FAILURE, SyncProblem.VALIDATION_REJECTED,
            SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED, SyncProblem.REMOTE_PERMISSION_REQUIRED,
            SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED).forEach { problem ->
            SyncIssueScope.entries.forEach { scope ->
                assertNotEquals(SyncIssueAction.SIGN_IN, policy.present(problem, scope,
                    canCompare = true, canReview = true).action)
            }
        }
        assertEquals(SyncIssueAction.SIGN_IN, policy.present(null, SyncIssueScope.ACCOUNT,
            state = SyncDashboardState.AUTHENTICATION_REQUIRED).action)
    }

    @Test fun aGlobalAuthenticationProblemCannotReplaceTheKnownCauseOfAnIndependentItem() {
        SyncProblem.entries.filterNot { it in setOf(SyncProblem.AUTHENTICATION_REQUIRED,
            SyncProblem.INTERACTIVE_AUTHENTICATION_REQUIRED) }.forEach { problem ->
            SyncIssueScope.entries.forEach { scope ->
                val independent = policy.present(problem, scope)
                assertEquals(independent, policy.present(problem, scope,
                    state = SyncDashboardState.AUTHENTICATION_REQUIRED))
                assertNotEquals(SyncIssueCause.AUTHENTICATION, independent.cause)
            }
        }
        assertEquals(SyncIssueCause.AUTHENTICATION, policy.present(null, SyncIssueScope.ACCOUNT,
            state = SyncDashboardState.AUTHENTICATION_REQUIRED).cause)
    }

    @Test fun comparingRequiresAnAvailableConflictAndAQueuedChoiceOffersOnlyWaiting() {
        for (canCompare in listOf(false, true)) for (queued in listOf(false, true)) {
            val advice = policy.present(SyncProblem.CONFLICT_RECOVERY_REQUIRED, SyncIssueScope.CONTACT,
                canCompare = canCompare, canReview = true, conflictChoiceQueued = queued)
            assertEquals(when {
                queued -> SyncIssueAction.WAIT
                canCompare -> SyncIssueAction.COMPARE_VERSIONS
                else -> SyncIssueAction.VIEW_DIAGNOSTICS
            }, advice.action)
            assertEquals(SyncIssueCause.CONFLICT, advice.cause)
        }
        assertEquals(SyncIssueAction.VIEW_DIAGNOSTICS, policy.present(null, SyncIssueScope.CONTACT,
            canCompare = true, conflictChoiceQueued = true).action)
    }

    @Test fun reviewRequiresAnIdentifiedValidationOrPermissionItemRatherThanAnUnknownFieldGuess() {
        listOf(SyncProblem.VALIDATION_REJECTED, SyncProblem.REMOTE_PERMISSION_REQUIRED).forEach { problem ->
            assertEquals(SyncIssueAction.REVIEW_ITEM, policy.present(problem, SyncIssueScope.CONTACT, canReview = true).action)
            assertEquals(SyncIssueAction.VIEW_DIAGNOSTICS, policy.present(problem, SyncIssueScope.CONTACT).action)
        }
        assertEquals(SyncIssueCause.UNKNOWN, policy.present(SyncProblem.INTERNAL_FAILURE, SyncIssueScope.CONTACT,
            canReview = true).cause)
        assertEquals(SyncIssueAction.VIEW_DIAGNOSTICS, policy.present(SyncProblem.INTERNAL_FAILURE,
            SyncIssueScope.CONTACT, canReview = true).action)
    }

    @Test fun offlineWithoutADataCausePermitsExistingRetryButCannotReclassifyAKnownFailure() {
        assertEquals(SyncIssueAdvice(SyncIssueCause.NETWORK, SyncIssueScope.ACCOUNT, SyncIssueAction.RETRY_SYNC, false),
            policy.present(null, SyncIssueScope.ACCOUNT, state = SyncDashboardState.OFFLINE))
        assertEquals(SyncIssueAction.VIEW_DIAGNOSTICS, policy.present(SyncProblem.INTERNAL_FAILURE,
            SyncIssueScope.ACCOUNT, state = SyncDashboardState.OFFLINE).action)
        assertEquals(SyncIssueCause.UNKNOWN, policy.present(null, SyncIssueScope.ACCOUNT).cause)
        assertEquals(SyncIssueCause.VALIDATION, policy.present(SyncProblem.VALIDATION_REJECTED,
            SyncIssueScope.CONTACT, state = SyncDashboardState.OFFLINE).cause)
    }

    @Test fun durableIntentIsAnExplicitFactAndIsNeverInferredFromCauseOrNavigability() {
        SyncIssueScope.entries.forEach { scope ->
            (listOf<SyncProblem?>(null) + SyncProblem.entries).forEach { problem ->
                assertFalse(policy.present(problem, scope, canReview = true, canCompare = true).localIntentSaved)
                assertTrue(policy.present(problem, scope, hasDurableIntent = true).localIntentSaved)
            }
        }
    }

    @Test fun consultingOneIssueCannotClearOrReplaceAnotherCurrentIssue() {
        val android = policy.present(SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED, SyncIssueScope.ANDROID_COPY)
        val conflict = policy.present(SyncProblem.CONFLICT_RECOVERY_REQUIRED, SyncIssueScope.CONTACT, canCompare = true)
        repeat(10) {
            assertEquals(android, policy.present(SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED, SyncIssueScope.ANDROID_COPY))
            assertEquals(conflict, policy.present(SyncProblem.CONFLICT_RECOVERY_REQUIRED, SyncIssueScope.CONTACT, canCompare = true))
        }
    }
}
