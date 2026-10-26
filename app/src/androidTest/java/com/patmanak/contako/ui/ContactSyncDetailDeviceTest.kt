package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.*
import com.patmanak.contako.domain.sync.*
import com.patmanak.contako.ui.theme.ContakoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Physical Compose rendering with local controlled observations; no accounts or peer operations. */
class ContactSyncDetailDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val canonical = CanonicalContact("synthetic-owner", "synthetic-card", displayName = "Chloë Groß", revision = 7)
    private val unknown = ContactPeerSyncDetail(ContactPeerSyncState.UNKNOWN)
    private val original = ContactSyncDetail(canonical.id, canonical.displayName, 7, false, unknown, unknown)

    @Test fun overflowOpensThreeDistinctLocalSectionsAndRestorationDoesNotRequestWork() {
        val recovery = Details(original)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = model(owner, recovery)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ContakoTheme { ContakoApp(model) } }
        compose.runOnIdle { model.selectContact(canonical) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.contact_more_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_title)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_local_version, 7)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_proton)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_android)).performScrollTo().assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText(compose.activity.getString(R.string.contact_sync_unknown)).fetchSemanticsNodes().size)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_title)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, recovery.requests); assertEquals(1, recovery.observations) }
        compose.onNodeWithText(compose.activity.getString(R.string.action_close)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, recovery.requests); owner.viewModelStore.clear() }
    }

    @Test fun newerIntentKeepsOldEvidenceHistoricalUntilAnExplicitRefreshActuallyChangesTheReadModel() {
        val recovery = Details(original.copy(proton = ContactPeerSyncDetail(ContactPeerSyncState.PENDING,
            ContactSyncEvidence(6, 1_000, ContactSyncEvidenceKind.VERIFIED_READ))))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = model(owner, recovery)
        compose.setContent { ContakoTheme { ContakoApp(model) } }
        compose.runOnIdle { model.openContactSyncDetail(canonical.id) }
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_previous_version, 6)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_refresh)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, recovery.requests); assertEquals(6L, model.contactSyncPanel.value.detail?.proton?.evidence?.localRevision) }
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_previous_version, 6)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            recovery.detail.value = original.copy(localRevision = 8, isDeleted = true)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_deleted_local)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_local_version, 8)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, recovery.requests); assertEquals(1, recovery.observations); owner.viewModelStore.clear() }
    }

    @Test fun retainedDeletedIssueOffersInspectionWithoutRecreatingOrSynchronizingTheContact() {
        val recovery = Details(original.copy(isDeleted = true))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = model(owner, recovery)
        val issue = SyncIssueItem("synthetic-deletion", canonical.id, canonical.displayName, SyncProblem.INTERNAL_FAILURE,
            SyncIssueAdvice(cause = SyncIssueCause.UNKNOWN, scope = SyncIssueScope.CONTACT,
                action = SyncIssueAction.VIEW_DIAGNOSTICS, localIntentSaved = true), isDeleted = true)
        compose.setContent { ContakoTheme {
            androidx.compose.foundation.layout.Column {
                SyncIssueCard(issue, ContactsUiState(), model) { error("No sign-in from inspection") }
                val panel = model.contactSyncPanel.collectAsState().value
                if (panel.contactId != null) ContactSyncDetailDialog(panel, ContactsUiState(), model)
            }
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_title)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.contact_sync_deleted_local)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, recovery.requests); assertTrue(model.contactSyncPanel.value.detail?.isDeleted == true); owner.viewModelStore.clear() }
    }

    private fun model(owner: ViewModelStoreOwner, recovery: Details) =
        ViewModelProvider(owner, ContactsViewModel.factory(NoWrites(canonical), recovery,
            navigationState = SavedStateHandle(), accountId = canonical.accountId))[ContactsViewModel::class.java]

    private class Details(initial: ContactSyncDetail) : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
        val detail = MutableStateFlow<ContactSyncDetail?>(initial)
        var requests = 0
        var observations = 0
        override fun observeContactSyncDetail(accountId: String, contactId: String) = detail.also { observations++ }
        override suspend fun requestSync(accountId: String) { requests++ }
    }

    private class NoWrites(private val contact: CanonicalContact) : ContactRepository {
        override fun observeContacts(accountId: String) = flowOf(listOf(contact))
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? = error("No lookup")
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> = error("No write")
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact, assignments: Set<ContactGroupAssignment>,
            managedGroupIds: Set<String>, baseline: ContactEditBaseline?): SaveResult<CanonicalContact> = error("No write")
        override suspend fun deleteContact(accountId: String, contactId: String) = error("No write")
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = error("No write")
        override suspend fun deleteGroup(accountId: String, groupId: String) = error("No write")
    }
}
