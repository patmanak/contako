package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.patmanak.contako.R
import com.patmanak.contako.qa.saveSyntheticUiEvidence
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
        val model = ViewModelProvider(owner, ContactsViewModel.factory(ReadOnlyRepository, recovery,
            navigationState = SavedStateHandle()))[ContactsViewModel::class.java]
        compose.setContent { ContakoTheme { ContakoApp(model) } }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_context)).assertIsDisplayed()
        val pending = compose.activity.getString(R.string.initial_import_pending)
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_synced, pending)).assertIsDisplayed()
        compose.runOnIdle {
            recovery.status.value = recovery.status.value!!.copy(androidAvailability = AndroidAvailability.UNAVAILABLE)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_unavailable)).assertIsDisplayed()
        compose.runOnIdle {
            recovery.status.value = recovery.status.value!!.copy(androidAvailability = AndroidAvailability.UNKNOWN,
                initialImport = InitialImportSnapshot(false, 0, null, 0, 0))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_synced, pending)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_completed)).assertDoesNotExist()
        compose.runOnIdle {
            recovery.status.value = recovery.status.value!!.copy(androidAvailability = AndroidAvailability.READY,
                initialImport = InitialImportSnapshot(false, 1, null, 0, 1, 0))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_synced, pending)).assertIsDisplayed()
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
        val model = ViewModelProvider(owner, ContactsViewModel.factory(ReadOnlyRepository, recovery,
            navigationState = SavedStateHandle()))[ContactsViewModel::class.java]
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

    @Test fun compactSummaryShowsUnknownThenExactCountsAndOnlyRelevantErrors() {
        val progress = mutableStateOf(InitialImportSnapshot(false, 0, null, 0, 0))
        val activity = mutableStateOf(SyncActivity.RUNNING)
        val errors = mutableStateOf(0)
        compose.setContent { ContakoTheme { Surface(Modifier.fillMaxSize()) {
            InitialImportDialog(progress.value, activity.value, AndroidAvailability.READY,
                errors.value > 0, {}, {}, {}, androidErrorContactCount = errors.value)
        } } }
        val pending = compose.activity.getString(R.string.initial_import_pending)
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_title)).assertIsDisplayed()
        listOf(R.string.initial_import_inventory, R.string.initial_import_available,
            R.string.initial_import_android_synced).forEach {
            compose.onNodeWithText(compose.activity.getString(it, pending)).assertIsDisplayed()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_view_problems)).assertDoesNotExist()
        compose.waitForIdle()
        saveSyntheticUiEvidence("first-sync-pending")
        compose.runOnIdle {
            progress.value = InitialImportSnapshot(true, 12, 15, 3, 2, 10)
            errors.value = 2
            activity.value = SyncActivity.IDLE
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_completed_errors)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_inventory, "15")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_available, "12")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_synced, "10")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_deferred, 3)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_errors, 2)).assertIsDisplayed()
        compose.waitForIdle()
        saveSyntheticUiEvidence("first-sync-errors")
        compose.runOnIdle {
            progress.value = InitialImportSnapshot(true, 0, 0, 0, 0, 0)
            errors.value = 0
        }
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_completed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_android_synced, "0")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.initial_import_view_problems)).assertDoesNotExist()
        compose.waitForIdle()
        saveSyntheticUiEvidence("first-sync-complete")
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
