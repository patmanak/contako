package com.patmanak.contako.ui

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.sync.*
import org.junit.Assert.*
import org.junit.Test

class SyncIssueItemsTest {
    private val contact = CanonicalContact("synthetic-owner", "one", displayName = "Chloë Groß")

    @Test fun reviewingAnAffectedGroupDoesNotDependOnTheDirectorySearch() {
        val group = com.patmanak.contako.domain.model.ContactGroup("synthetic-owner", "group", "Synthetic group")
        val state = ContactsUiState(groups = emptyList(), actionGroups = listOf(group),
            syncDashboard = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
                blockedItems = listOf(SyncBlockedItem(group.id, SyncBlockedItemType.GROUP, group.name, false,
                    SyncProblem.VALIDATION_REJECTED))))
        val issue = currentSyncIssues(state, emptyList()).single()
        assertTrue(issue.canReview)
        assertEquals(SyncIssueAction.REVIEW_ITEM, issue.advice.action)
    }

    @Test fun independentIssuesSurviveAnAccountAuthenticationBanner() {
        val state = ContactsUiState(actionContacts = listOf(contact), syncDashboard = SyncDashboardSnapshot(
            SyncDashboardState.AUTHENTICATION_REQUIRED, problem = SyncProblem.AUTHENTICATION_REQUIRED,
            importProblems = listOf(SyncImportProblem("remote", null, SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED)),
            androidContactIssues = listOf(SyncAndroidContactIssue(contact.id, SyncAndroidContactIssueKind.ANDROID_INGEST)),
            blockedItems = listOf(SyncBlockedItem("group", SyncBlockedItemType.GROUP, "Synthetic group", false,
                SyncProblem.GROUP_CAPABILITY_REQUIRED))))
        val issues = currentSyncIssues(state, emptyList())
        assertEquals(4, issues.size)
        assertEquals(setOf(SyncIssueCause.AUTHENTICATION, SyncIssueCause.CRYPTOGRAPHY,
            SyncIssueCause.ANDROID, SyncIssueCause.GROUP_CAPABILITY), issues.map { it.advice.cause }.toSet())
        assertFalse(issues.single { it.advice.scope == SyncIssueScope.ANDROID_COPY }.advice.localIntentSaved)
    }

    @Test fun resolvingOneIssueKeepsTheOtherAndDoesNotImportHistoricalFailures() {
        val snapshot = SyncDashboardSnapshot(SyncDashboardState.ANDROID_PARTIAL,
            androidContactIssues = listOf(SyncAndroidContactIssue(contact.id, SyncAndroidContactIssueKind.ANDROID_PROJECTION)))
        val state = ContactsUiState(actionContacts = listOf(contact), syncDashboard = snapshot)
        val conflict = ContactConflictSummary(contact.id, "generation", 3, "version", null)
        assertEquals(2, currentSyncIssues(state, listOf(conflict)).size)
        val remaining = currentSyncIssues(state, emptyList())
        assertEquals(listOf(SyncIssueScope.ANDROID_COPY), remaining.map { it.advice.scope })
        assertTrue(currentSyncIssues(ContactsUiState(), emptyList()).isEmpty())
    }

    @Test fun normalAndroidBacklogAndOrdinaryPendingEditsAreNotInventedErrors() {
        val state = ContactsUiState(pendingContacts = listOf(contact), androidPendingContacts = listOf(contact),
            syncDashboard = SyncDashboardSnapshot(SyncDashboardState.PENDING, pendingMutationCount = 1,
                androidPendingContactIds = setOf(contact.id)))
        assertTrue(currentSyncIssues(state, emptyList()).isEmpty())
    }

    @Test fun permissionAndUnknownProblemsOfferDifferentActionsWithoutSavedClaims() {
        val state = ContactsUiState(contactsPermissionGranted = false,
            syncDashboard = SyncDashboardSnapshot(SyncDashboardState.BLOCKED, actionRequiredCount = 1,
                problem = SyncProblem.INTERNAL_FAILURE))
        val issues = currentSyncIssues(state, emptyList())
        assertEquals(setOf(SyncIssueAction.CONTACTS_PERMISSION, SyncIssueAction.VIEW_DIAGNOSTICS),
            issues.map { it.advice.action }.toSet())
        assertTrue(issues.none { it.advice.localIntentSaved })
    }

    @Test fun blockedDeletionRemainsNamedButHasNoItemReviewOrDestructiveAction() {
        val state = ContactsUiState(syncDashboard = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
            blockedItems = listOf(SyncBlockedItem("deleted", SyncBlockedItemType.CONTACT, "Synthetic deleted", true,
                SyncProblem.VALIDATION_REJECTED))))
        val issue = currentSyncIssues(state, emptyList()).single()
        assertEquals("Synthetic deleted", issue.name)
        assertTrue(issue.isDeleted)
        assertFalse(issue.canReview)
        assertEquals(SyncIssueAction.VIEW_DIAGNOSTICS, issue.advice.action)
        assertTrue(issue.advice.localIntentSaved)
        assertFalse(issue.toString().contains("Synthetic deleted"))
    }

    @Test fun remoteDeletionDoesNotAdvertiseAnUnavailableVersionChoice() {
        val conflict = ContactConflictSummary(contact.id, "generation", 1, "version", null, remoteDeleted = true)
        val issues = currentSyncIssues(ContactsUiState(actionContacts = listOf(contact)), listOf(conflict))
        assertEquals(SyncIssueAction.VIEW_DIAGNOSTICS, issues.single().advice.action)
    }
}
