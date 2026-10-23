package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patmanak.contako.android.account.AccountLifecycleAdmission
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.ContactEditBaseline
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.ui.theme.ContakoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Compose account-lifetime disposal with synthetic peers; no Android account/provider work. */
class AccountDraftCompositionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cleanupHidesTheEditorAndNextOwnerGetsANewModelWithoutRecreatingTheActivity() {
        val gate = AccountLifecycleAdmission()
        val stores = AccountContactsViewModelStore()
        lateinit var current: ContactsViewModel
        compose.setContent {
            val access by gate.access.collectAsState()
            val scope = stores.forGeneration(access.generation)
            current = viewModel(viewModelStoreOwner = scope, factory = ContactsViewModel.factory(
                EmptyRepository, mutationAdmission = gate, navigationState = scope.navigationState,
            ))
            key(access.generation) {
                ContakoTheme { ContakoApp(current, accountMutationsBlocked = access.blocked) }
            }
        }
        val release = CompletableDeferred<Unit>()
        lateinit var old: ContactsViewModel
        compose.runOnIdle {
            old = current
            old.editContact(CanonicalContact(LOCAL_ACCOUNT_ID, "fixture-a", displayName = "Private draft A"))
        }
        compose.onNodeWithText("Private draft A").assertIsDisplayed()
        compose.runOnIdle {
            compose.activity.lifecycleScope.launch { gate.cleanup { release.await() } }
        }
        compose.waitUntil { gate.access.value.blocked }
        compose.onNodeWithText("Private draft A").assertDoesNotExist()
        compose.runOnIdle {
            old.editContact(CanonicalContact(LOCAL_ACCOUNT_ID, "late-a", displayName = "Late draft A"))
            old.saveContact()
            release.complete(Unit)
        }
        compose.waitUntil { !gate.access.value.blocked }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotSame(old, current)
            assertNull(current.uiState.value.contactEditor)
        }
        compose.onNodeWithText("Private draft A").assertDoesNotExist()
        compose.onNodeWithText("Late draft A").assertDoesNotExist()
        compose.runOnIdle { stores.forGeneration(gate.access.value.generation).viewModelStore.clear() }
    }

    private object EmptyRepository : ContactRepository {
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
