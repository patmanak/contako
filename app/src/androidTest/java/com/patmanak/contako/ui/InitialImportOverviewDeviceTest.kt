package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.patmanak.contako.R
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.*
import com.patmanak.contako.domain.sync.InitialImportSnapshot
import com.patmanak.contako.domain.sync.AndroidAvailability
import com.patmanak.contako.ui.theme.ContakoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic read-only presentation; never creates an Android account or calls Proton. */
class InitialImportOverviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun grantedPermissionDoesNotHideAnUnavailableAndroidContextOrStartMoreWork() {
        val recovery = object : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
            val status = MutableStateFlow<SyncDashboardSnapshot?>(SyncDashboardSnapshot(SyncDashboardState.PENDING,
                initialImport = InitialImportSnapshot(false, 1, null, 0, 1),
                androidAvailability = AndroidAvailability.CONTEXT_NOT_ESTABLISHED))
            var requests = 0
            override fun observeStatus(accountId: String) = status
            override suspend fun requestSync(accountId: String) { requests++ }
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ContactsViewModel.factory(ReadOnlyRepository, recovery))[ContactsViewModel::class.java]
        compose.setContent { ContakoTheme { ContakoApp(model) } }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_context)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_pending, 1)).assertDoesNotExist()
        compose.runOnIdle {
            recovery.status.value = recovery.status.value!!.copy(androidAvailability = AndroidAvailability.UNAVAILABLE)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_unavailable)).assertIsDisplayed()
        compose.runOnIdle {
            recovery.status.value = recovery.status.value!!.copy(androidAvailability = AndroidAvailability.UNKNOWN,
                initialImport = InitialImportSnapshot(false, 0, null, 0, 0))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_unknown)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_no_pending)).assertDoesNotExist()
        compose.runOnIdle {
            recovery.status.value = recovery.status.value!!.copy(androidAvailability = AndroidAvailability.READY,
                initialImport = InitialImportSnapshot(false, 1, null, 0, 1))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_pending, 1)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_view_contacts)).performClick()
        compose.onNodeWithText("Chloë Groß").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, recovery.requests); owner.viewModelStore.clear() }
    }

    @Test fun dismissAndRestoreKeepPartialContactsUsableWithoutStartingAnotherImport() {
        val recovery = object : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
            val status = MutableStateFlow<SyncDashboardSnapshot?>(SyncDashboardSnapshot(SyncDashboardState.PENDING,
                initialImport = InitialImportSnapshot(false, 1, null, 0, 1)))
            var requests = 0
            override fun observeStatus(accountId: String) = status
            override suspend fun requestSync(accountId: String) { requests++ }
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ContactsViewModel.factory(ReadOnlyRepository, recovery))[ContactsViewModel::class.java]
        val restore = StateRestorationTester(compose)
        val address = mutableStateOf<String?>(null)
        restore.setContent { ContakoTheme { ContakoApp(model, accountAddress = address.value) } }
        compose.onNodeWithTag("initial_import_overview").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_view_contacts)).performClick()
        compose.onNodeWithTag("initial_import_overview").assertDoesNotExist()
        compose.onNodeWithText("Chloë Groß").assertIsDisplayed()
        compose.runOnIdle { address.value = "synthetic@example.test" }
        compose.onNodeWithTag("initial_import_overview").assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("initial_import_overview").assertDoesNotExist()
        compose.runOnIdle {
            recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.PENDING,
                initialImport = InitialImportSnapshot(true, 1, 2, 1, 1))
            assertEquals(0, recovery.requests)
            model.selectDestination(RootDestination.SYNC)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_deferred, 1)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, recovery.requests); owner.viewModelStore.clear() }
    }

    private object ReadOnlyRepository : ContactRepository {
        val contact = CanonicalContact(LOCAL_ACCOUNT_ID, "synthetic-contact", displayName = "Chloë Groß")
        override fun observeContacts(accountId: String) = flowOf(listOf(contact))
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String) = contact.takeIf { it.id == contactId }
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> = error("Unexpected write")
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact,
            assignments: Set<ContactGroupAssignment>, managedGroupIds: Set<String>, baseline: ContactEditBaseline?
        ): SaveResult<CanonicalContact> = error("Unexpected write")
        override suspend fun deleteContact(accountId: String, contactId: String) = error("Unexpected write")
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = error("Unexpected write")
        override suspend fun deleteGroup(accountId: String, groupId: String) = error("Unexpected write")
    }
}
