package com.patmanak.contako.ui

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.sync.*
import com.patmanak.contako.R
import org.junit.Assert.*
import org.junit.Test

class SyncIssueItemsTest {
    private val contact = CanonicalContact("synthetic-owner", "one", displayName = "Chloë Groß")

    @Test fun visibleUnverifiedMatchHasItsOwnCauseAndRetryWithoutCompareOrCreateAnyway() {
        val retry = SyncMutationRetry(8, SyncMutationRetryReason.MATCH_VERIFICATION)
        val blocked = SyncBlockedItem(contact.id, SyncBlockedItemType.CONTACT, contact.resolvedDisplayName,
            false, SyncProblem.INTERNAL_FAILURE, retry = retry)
        val state = ContactsUiState(actionContacts = listOf(contact), syncDashboard = SyncDashboardSnapshot(
            SyncDashboardState.BLOCKED, blockedItems = listOf(blocked)))
        val issue = currentSyncIssues(state, emptyList()).single()
        assertEquals(SyncIssueCause.MATCH_VERIFICATION, issue.advice.cause)
        assertEquals(SyncIssueAction.RETRY_ITEM, issue.advice.action)
        assertEquals(listOf(R.string.sync_retry_match_unverified), syncIssueBodyResources(issue.advice))
        assertEquals(retry, issue.retry)
        assertNull(issue.createAnywayRevision)
        assertEquals(contact.id, issue.targetId)
    }

    @Test fun retainedPartialGroupDoesNotHideThePartialContactTitle() {
        val partial = SyncDashboardSnapshot(SyncDashboardState.PENDING, pendingMutationCount = 0,
            partialContactIds = setOf(contact.id))
        assertEquals(R.string.sync_email_partial_title, syncStateTitle(partial))
        assertEquals(R.string.sync_state_pending, syncStateTitle(partial.copy(pendingMutationCount = 1)))
        assertEquals(R.string.sync_state_blocked, syncStateTitle(partial.copy(state = SyncDashboardState.BLOCKED)))
    }

    @Test fun ambiguousCreateChoiceIsScopedToTheBlockedContactRevision() {
        val blocked = SyncBlockedItem(contact.id, SyncBlockedItemType.CONTACT, contact.resolvedDisplayName,
            false, SyncProblem.INTERNAL_FAILURE, createAnywayRevision = 17L)
        val state = ContactsUiState(actionContacts = listOf(contact), syncDashboard = SyncDashboardSnapshot(
            SyncDashboardState.BLOCKED, blockedItems = listOf(blocked)))
        val issue = currentSyncIssues(state, emptyList()).single()
        assertEquals(contact.id, issue.targetId)
        assertEquals(17L, issue.createAnywayRevision)
        val changed = state.copy(syncDashboard = state.syncDashboard.copy(blockedItems = listOf(blocked.copy(createAnywayRevision = null))))
        assertNull(currentSyncIssues(changed, emptyList()).single().createAnywayRevision)
    }

    @Test fun blankIssueNamesUseTheCanonicalGeneratedContactNameForBlockedAndAndroidIssues() {
        val unnamed = contact.copy(displayName = "", values = listOf(
            ContactValue("org", ContactValueKind.ORGANIZATION, "Atelier Café", order = 0),
            ContactValue("tel", ContactValueKind.PHONE, "+49 30 123456", order = 0)))
        for (name in listOf(null, "", "  ")) for (android in listOf(false, true)) {
            val snapshot = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
                blockedItems = if (android) emptyList() else listOf(SyncBlockedItem(unnamed.id,
                    SyncBlockedItemType.CONTACT, name, false, SyncProblem.INTERNAL_FAILURE)),
                androidContactIssues = if (!android) emptyList() else listOf(SyncAndroidContactIssue(
                    unnamed.id, SyncAndroidContactIssueKind.ANDROID_PROJECTION, displayName = name)))
            val issue = currentSyncIssues(ContactsUiState(actionContacts = listOf(unnamed), syncDashboard = snapshot), emptyList()).single()
            assertEquals("Atelier Café", issue.name)
            assertEquals(unnamed.id, issue.targetId)
            assertTrue(issue.canReview)
        }
    }

    @Test fun blankGroupIssueNamesUseTheAffectedGroupButExplicitIssueNamesRemainUnchanged() {
        val group = ContactGroup("synthetic-owner", "group", "Family")
        for (name in listOf(null, "", "  ", "Retained issue name")) {
            val state = ContactsUiState(actionGroups = listOf(group), syncDashboard = SyncDashboardSnapshot(
                SyncDashboardState.BLOCKED, blockedItems = listOf(SyncBlockedItem(group.id,
                    SyncBlockedItemType.GROUP, name, false, SyncProblem.GROUP_CAPABILITY_REQUIRED))))
            val issue = currentSyncIssues(state, emptyList()).single()
            assertEquals(name?.takeIf(String::isNotBlank) ?: group.name, issue.name)
            assertEquals(group.id, issue.targetId)
        }
    }

    @Test fun exhaustedTransientWritesOfferOneItemRetryEvenForDeletedContactsAndGroups() {
        for (type in SyncBlockedItemType.entries) {
            val retry = SyncMutationRetry(8, SyncMutationRetryReason.NETWORK, 12345)
            val blocked = SyncBlockedItem("retained", type, "Synthetic pending deletion", true, SyncProblem.INTERNAL_FAILURE, retry)
            val state = ContactsUiState(syncDashboard = SyncDashboardSnapshot(SyncDashboardState.BLOCKED, blockedItems = listOf(blocked)))
            val issue = currentSyncIssues(state, emptyList()).single()
            assertEquals(SyncIssueCause.NETWORK, issue.advice.cause)
            assertEquals(SyncIssueAction.RETRY_ITEM, issue.advice.action)
            assertEquals(retry, issue.retry)
            assertFalse(issue.canReview)
            assertTrue(issue.isDeleted)
        }
    }

    @Test fun incompleteVerificationOffersRecheckRatherThanClaimingANetworkFailureOrShowingAFakeConflictChoice() {
        val retry = SyncMutationRetry(8, SyncMutationRetryReason.VERIFICATION)
        val state = ContactsUiState(actionContacts = listOf(contact), syncDashboard = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
            blockedItems = listOf(SyncBlockedItem(contact.id, SyncBlockedItemType.CONTACT, contact.displayName, false,
                SyncProblem.INTERNAL_FAILURE, retry))))
        val conflict = ContactConflictSummary(contact.id, "generation", 8, "version", null)
        val issue = currentSyncIssues(state, listOf(conflict)).single()
        assertEquals(SyncIssueCause.VERIFICATION, issue.advice.cause)
        assertEquals(SyncIssueAction.RETRY_ITEM, issue.advice.action)
        assertEquals(listOf(R.string.sync_retry_verification), syncIssueBodyResources(issue.advice))
    }

    @Test fun transientAndIgnoredImportsAreNotAttentionItemsButBlockedSignatureKeepsItsExactGeneration() {
        val snapshot = SyncDashboardSnapshot(SyncDashboardState.PENDING, importProblems = listOf(
            SyncImportProblem("waiting", null, SyncProblem.INTERNAL_FAILURE, "waiting-generation", isTransient = true),
            SyncImportProblem("ignored", null, SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED, "ignored-generation", ignored = true),
        ))
        assertTrue(currentSyncIssues(ContactsUiState(syncDashboard = snapshot), emptyList()).isEmpty())
        val signature = SyncImportProblem("signature", null, SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED, "exact-generation")
        val issue = currentSyncIssues(ContactsUiState(syncDashboard = snapshot.copy(state = SyncDashboardState.BLOCKED,
            importProblems = snapshot.importProblems + signature)), emptyList()).single()
        assertEquals("exact-generation", issue.importGeneration)
        assertEquals(SyncIssueCause.CRYPTOGRAPHY, issue.advice.cause)
        assertFalse(issue.advice.localIntentSaved)
    }

    @Test fun knownLocalValidationFamiliesSurviveBothDirectAndBlockedIssueAggregation() {
        val expected = mapOf(
            setOf("MISSING_NAME") to listOf(R.string.actions_missing_name_body),
            setOf("INVALID_EMAIL") to listOf(R.string.actions_invalid_email_body),
            setOf("MISSING_NAME", "INVALID_EMAIL") to listOf(R.string.actions_missing_name_body, R.string.actions_invalid_email_body),
        )
        for ((reasons, messages) in expected) for (blocked in listOf(false, true)) {
            val invalid = contact.copy(actionRequiredReasons = reasons, pendingMutationRevision = 3)
            val snapshot = SyncDashboardSnapshot(SyncDashboardState.BLOCKED, blockedItems = if (blocked)
                listOf(SyncBlockedItem(contact.id, SyncBlockedItemType.CONTACT, contact.displayName, false,
                    SyncProblem.VALIDATION_REJECTED)) else emptyList())
            val state = ContactsUiState(actionContacts = listOf(invalid), syncDashboard = snapshot)
            val issue = currentSyncIssues(state, emptyList()).single()
            assertEquals(messages, syncIssueBodyResources(issue.advice))
            assertEquals(SyncIssueAction.REVIEW_ITEM, issue.advice.action)
            assertTrue(issue.advice.localIntentSaved)
            assertEquals(setOf(invalid), state.actionContacts.toSet())
            assertEquals(snapshot, state.syncDashboard)
        }
    }

    @Test fun unknownLocalReasonsAndGenericRemoteValidationNeverInventAField() {
        val opaqueReason = "unknown synthetic detail must not be displayed"
        val local = contact.copy(actionRequiredReasons = setOf(opaqueReason))
        val localIssue = currentSyncIssues(ContactsUiState(actionContacts = listOf(local)), emptyList()).single()
        assertTrue(localIssue.advice.validationIssues.isEmpty())
        assertEquals(listOf(R.string.sync_issue_validation), syncIssueBodyResources(localIssue.advice))
        assertFalse(localIssue.toString().contains(opaqueReason))
        val state = ContactsUiState(actionContacts = listOf(contact.copy(actionRequiredReasons = setOf("INVALID_EMAIL"))),
            syncDashboard = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
                importProblems = listOf(SyncImportProblem(contact.id, null, SyncProblem.VALIDATION_REJECTED))))
        val imported = currentSyncIssues(state, emptyList()).single { it.advice.scope == SyncIssueScope.PROTON_IMPORT }
        assertTrue(imported.advice.validationIssues.isEmpty())
        assertEquals(listOf(R.string.sync_issue_validation), syncIssueBodyResources(imported.advice))
    }

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
