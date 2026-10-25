package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.*
import com.patmanak.contako.domain.sync.SyncProblem
import com.patmanak.contako.ui.theme.ContakoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI-only evidence. No Android account, provider, credentials or remote service. */
class SyncIssueNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun knownLocalValidationRendersBothCorrectionsInsteadOfUnknownFieldAdvice() {
        val contact = CanonicalContact("synthetic-owner", "invalid", actionRequiredReasons = setOf("MISSING_NAME", "INVALID_EMAIL"),
            pendingMutationRevision = 1)
        val state = ContactsUiState(actionContacts = listOf(contact))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ContactsViewModel.factory(NoWrites))[ContactsViewModel::class.java]
        val issue = currentSyncIssues(state, emptyList()).single()
        compose.setContent { ContakoTheme { SyncIssueCard(issue, state, model) { error("No sign-in from local validation") } } }
        compose.onNodeWithText(compose.activity.getString(R.string.actions_missing_name_body)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.actions_invalid_email_body)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_issue_validation)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("MISSING_NAME", "INVALID_EMAIL"), contact.actionRequiredReasons); owner.viewModelStore.clear() }
    }

    @Test fun lastTargetedAuthenticationFailureIsDistinctFromImportAndOffersSignInWithoutRetry() {
        val snapshot = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
            importProblems = listOf(com.patmanak.contako.domain.sync.SyncImportProblem("synthetic-card", null, SyncProblem.INTERNAL_FAILURE)),
            targetedRecoveries = listOf(com.patmanak.contako.domain.sync.TargetedRecoveryStatus(
                com.patmanak.contako.domain.sync.TargetedRecoveryKind.PROTON_CARD, "synthetic-card",
                com.patmanak.contako.domain.sync.TargetedRecoveryState.ACTION_REQUIRED,
                lastFailure = com.patmanak.contako.domain.sync.TargetedRecoveryFailure.AUTHENTICATION)))
        val state = ContactsUiState(syncDashboard = snapshot)
        var signIns = 0
        val recovery = object : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
            override suspend fun requestSync(accountId: String) = error("No global retry from a targeted failure")
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ContactsViewModel.factory(NoWrites, recovery))[ContactsViewModel::class.java]
        val issue = currentSyncIssues(state, emptyList()).single()
        compose.setContent { ContakoTheme { SyncIssueCard(issue, state, model) { signIns++ } } }
        compose.onNodeWithText(compose.activity.getString(R.string.sync_issue_unknown)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_last_failure,
            compose.activity.getString(R.string.sync_issue_auth))).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.auth_sign_in)).performClick()
        compose.runOnIdle {
            assertEquals(1, signIns)
            assertEquals(snapshot, state.syncDashboard)
            owner.viewModelStore.clear()
        }
    }

    @Test fun targetedCryptoFailureKeepsAReadOnlyPreviewAvailableForALaterRetry() {
        val kind = com.patmanak.contako.domain.sync.TargetedRecoveryKind.PROTON_CARD
        val snapshot = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
            importProblems = listOf(com.patmanak.contako.domain.sync.SyncImportProblem("synthetic-card", null, SyncProblem.INTERNAL_FAILURE)),
            targetedRecoveries = listOf(com.patmanak.contako.domain.sync.TargetedRecoveryStatus(kind, "synthetic-card",
                com.patmanak.contako.domain.sync.TargetedRecoveryState.ACTION_REQUIRED,
                lastFailure = com.patmanak.contako.domain.sync.TargetedRecoveryFailure.CRYPTOGRAPHY)))
        val state = ContactsUiState(syncDashboard = snapshot)
        var previews = 0
        val recovery = object : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
            override suspend fun previewTargetedRecovery(accountId: String,
                kind: com.patmanak.contako.domain.sync.TargetedRecoveryKind, targetId: String):
                com.patmanak.contako.domain.sync.TargetedRecoveryPreview {
                previews++
                return com.patmanak.contako.domain.sync.TargetedRecoveryPreview(kind, targetId, "synthetic-generation", null, null)
            }
            override suspend fun requestSync(accountId: String) = error("No global retry from a targeted preview")
            override suspend fun requestTargetedRecovery(accountId: String,
                expected: com.patmanak.contako.domain.sync.TargetedRecoveryPreview):
                com.patmanak.contako.domain.sync.TargetedRecoveryQueueResult = error("No queue before confirmation")
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ContactsViewModel.factory(NoWrites, recovery))[ContactsViewModel::class.java]
        val issue = currentSyncIssues(state, emptyList()).single()
        compose.setContent { ContakoTheme { SyncIssueCard(issue, state, model) { error("No sign-in from cryptography") } } }
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_last_failure,
            compose.activity.getString(R.string.sync_issue_crypto))).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_issue_diagnostics)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_proton_title)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, previews)
            assertEquals("synthetic-card", model.targetedRecoveryPanel.value.preview?.targetId)
            assertEquals(snapshot, state.syncDashboard)
            owner.viewModelStore.clear()
        }
    }

    @Test fun targetedRecoveryRequiresPreviewThenConfirmationAndNeverClaimsImmediateCompletion() {
        val kind = com.patmanak.contako.domain.sync.TargetedRecoveryKind.PROTON_CARD
        val snapshot = SyncDashboardSnapshot(SyncDashboardState.BLOCKED, actionRequiredCount = 1,
            importProblems = listOf(com.patmanak.contako.domain.sync.SyncImportProblem("synthetic-remote", "Synthetic card",
                SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED)))
        val recovery = object : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
            val status = MutableStateFlow<SyncDashboardSnapshot?>(snapshot)
            var queued = 0
            override fun observeStatus(accountId: String) = status
            override suspend fun previewTargetedRecovery(accountId: String,
                kind: com.patmanak.contako.domain.sync.TargetedRecoveryKind, targetId: String) =
                com.patmanak.contako.domain.sync.TargetedRecoveryPreview(kind, targetId, "synthetic-generation", null, null,
                    displayName = "Synthetic card")
            override suspend fun requestTargetedRecovery(accountId: String,
                expected: com.patmanak.contako.domain.sync.TargetedRecoveryPreview): com.patmanak.contako.domain.sync.TargetedRecoveryQueueResult {
                queued++
                status.value = snapshot.copy(targetedRecoveries = listOf(com.patmanak.contako.domain.sync.TargetedRecoveryStatus(
                    kind, expected.targetId, com.patmanak.contako.domain.sync.TargetedRecoveryState.REQUESTED)))
                return com.patmanak.contako.domain.sync.TargetedRecoveryQueueResult.QUEUED
            }
            override suspend fun requestSync(accountId: String) = error("No ordinary pass from a preview")
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ContactsViewModel.factory(NoWrites, recovery))[ContactsViewModel::class.java]
        model.selectDestination(RootDestination.SYNC)
        compose.setContent { ContakoTheme { ContakoApp(model) } }
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_proton_title)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_proton_body)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, recovery.queued) }
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, recovery.queued) }
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_proton_title)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_confirm)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.targeted_queued)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, recovery.queued)
            assertEquals(snapshot.importProblems, recovery.status.value?.importProblems)
            owner.viewModelStore.clear()
        }
    }

    @Test fun unknownIssueOpensDiagnosticsWithoutRetryGenerationOrAcknowledgement() {
        val snapshot = SyncDashboardSnapshot(SyncDashboardState.BLOCKED, actionRequiredCount = 1,
            problem = SyncProblem.INTERNAL_FAILURE)
        val recovery = object : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
            val status = MutableStateFlow<SyncDashboardSnapshot?>(snapshot)
            var requests = 0
            override fun observeStatus(accountId: String) = status
            override suspend fun requestSync(accountId: String) { requests++ }
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ContactsViewModel.factory(NoWrites, recovery))[ContactsViewModel::class.java]
        model.selectDestination(RootDestination.SYNC)
        compose.setContent { ContakoTheme { ContakoApp(model) } }
        compose.onNodeWithText(compose.activity.getString(R.string.sync_issue_diagnostics)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.diagnostics_generate)).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("contako_diagnostic_schema=", substring = true).fetchSemanticsNodes().size)
        compose.runOnIdle {
            assertEquals(snapshot, recovery.status.value)
            assertEquals(0, recovery.requests)
            model.handleBack()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.sync_issue_unknown)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, recovery.requests); owner.viewModelStore.clear() }
    }

    private object NoWrites : ContactRepository {
        override fun observeContacts(accountId: String) = flowOf(emptyList<CanonicalContact>())
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? = null
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> = error("Unexpected write")
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact,
            assignments: Set<ContactGroupAssignment>, managedGroupIds: Set<String>, baseline: ContactEditBaseline?
        ): SaveResult<CanonicalContact> = error("Unexpected write")
        override suspend fun deleteContact(accountId: String, contactId: String) = error("Unexpected write")
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = error("Unexpected write")
        override suspend fun deleteGroup(accountId: String, groupId: String) = error("Unexpected write")
    }
}
