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
