package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.repository.ContactEditBaseline
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.sync.*
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.ui.theme.ContakoTheme
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose/Room boundary, with one injected pre-commit storage exception; no live account. */
@RunWith(AndroidJUnit4::class)
class DeletionRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: ContakoDatabase

    @Before fun setUp() {
        compose.activity.deleteDatabase(DATABASE)
        database = ContakoDatabase.create(compose.activity, DATABASE)
    }

    @After fun tearDown() {
        database.close()
        compose.activity.deleteDatabase(DATABASE)
    }

    @Test fun contactDeletionFailureIsVisibleAndRetryCommits() = verifyRecovery(false)
    @Test fun groupDeletionFailureIsVisibleAndRetryKeepsContact() = verifyRecovery(true)

    private fun verifyRecovery(isGroup: Boolean) {
        val room = RoomContactRepository(database)
        val contact = CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "owned-contact", firstName = "Owned fixture")
        val group = ContactGroup(LOCAL_ACCOUNT_ID, "owned-group", "Owned group")
        runBlocking {
            assertTrue(room.saveContact(contact) is SaveResult.Saved)
            if (isGroup) assertTrue(room.saveGroup(group) is SaveResult.Saved)
        }
        var attempts = 0
        val repository = object : ContactRepository by room {
            override suspend fun deleteContact(accountId: String, contactId: String) {
                if (++attempts == 1) throw IOException("private synthetic storage failure")
                room.deleteContact(accountId, contactId)
            }
            override suspend fun deleteGroup(accountId: String, groupId: String) {
                if (++attempts == 1) throw IOException("private synthetic storage failure")
                room.deleteGroup(accountId, groupId)
            }
        }
        val model = ContactsViewModel(repository)
        compose.setContent { ContakoTheme { ContakoApp(model) } }
        compose.runOnIdle {
            if (isGroup) model.requestGroupDeletion(group) else model.requestContactDeletion(contact)
        }
        val confirm = compose.activity.getString(if (isGroup) R.string.action_delete_group else R.string.action_delete)
        compose.onNodeWithText(confirm).performClick()
        compose.waitUntil(5_000) {
            if (isGroup) model.uiState.value.groupDeletionStatus.failed else model.uiState.value.contactDeletionStatus.failed
        }
        compose.onNodeWithText(compose.activity.getString(R.string.error_delete_failed)).assertIsDisplayed()
        runBlocking {
            assertEquals(1, room.observeContacts(LOCAL_ACCOUNT_ID).first().size)
            if (isGroup) assertEquals(1, room.observeGroups(LOCAL_ACCOUNT_ID).first().size)
        }
        compose.onNodeWithText(confirm).performClick()
        compose.waitUntil(5_000) {
            if (isGroup) model.uiState.value.pendingGroupDeletion == null else model.uiState.value.pendingContactDeletion == null
        }
        assertEquals(2, attempts)
        runBlocking {
            assertEquals(if (isGroup) 1 else 0, room.observeContacts(LOCAL_ACCOUNT_ID).first().size)
            assertTrue(room.observeGroups(LOCAL_ACCOUNT_ID).first().isEmpty())
        }
    }

    @Test fun keepingDraftRequiresNamedConfirmationAndCancelDoesNotQueue() = verifyRecoveryConsent(DeletionRecoveryChoice.KEEP_DRAFT)

    @Test fun creatingContactRequiresNamedConfirmationAndCancelDoesNotQueue() = verifyRecoveryConsent(DeletionRecoveryChoice.RECREATE)

    @Test fun discardingEditsRequiresNamedConfirmationAndCancelDoesNotQueue() = verifyRecoveryConsent(DeletionRecoveryChoice.DISCARD)

    /** A local candidate absent from the normal directory; no account or provider calls. */
    private fun verifyRecoveryConsent(choice: DeletionRecoveryChoice) {
        val port = DeletedCandidate()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = recoveryModel(owner, port)
        compose.setContent { ContakoTheme {
            val panel = model.deletionRecoveryPanel.collectAsState().value
            if (panel.contactId != null) DeletionRecoveryDialog(panel, model)
        } }
        try {
            compose.runOnIdle { model.openDeletionRecovery(port.contact.id) }
            compose.onNodeWithText(compose.activity.getString(R.string.deletion_recovery_title)).assertIsDisplayed()
            val confirmations = listOf(R.string.deletion_recovery_keep_confirm,
                R.string.deletion_recovery_create_confirm, R.string.deletion_recovery_discard_confirm)
            confirmations.forEach { compose.onNodeWithText(compose.activity.getString(it)).assertDoesNotExist() }
            DeletionRecoveryChoice.entries.forEach { option ->
                val label = compose.activity.getString(deletionRecoveryChoiceLabel(option))
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                compose.onNodeWithText(label).assertIsDisplayed()
            }
            compose.runOnIdle { assertTrue(port.choices.isEmpty()); assertEquals(0, port.unrelatedWork) }

            val label = compose.activity.getString(deletionRecoveryChoiceLabel(choice))
            val confirmation = compose.activity.getString(when (choice) {
                DeletionRecoveryChoice.KEEP_DRAFT -> R.string.deletion_recovery_keep_confirm
                DeletionRecoveryChoice.RECREATE -> R.string.deletion_recovery_create_confirm
                DeletionRecoveryChoice.DISCARD -> R.string.deletion_recovery_discard_confirm
            })
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(confirmation).assertIsDisplayed()
            // The foreground confirmation names this exact contact rather than a count.
            compose.onAllNodesWithText(port.contact.displayName).onLast().assertIsDisplayed()
            compose.runOnIdle { assertTrue(port.choices.isEmpty()) }
            compose.onNodeWithText(compose.activity.getString(R.string.action_cancel)).performClick()
            compose.onNodeWithText(confirmation).assertDoesNotExist()
            compose.runOnIdle { assertTrue(port.choices.isEmpty()) }

            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performClick()
            compose.runOnIdle {
                assertEquals(listOf(Triple(port.contact.accountId, port.summary, choice)), port.choices)
                assertEquals(0, port.unrelatedWork)
            }
        } finally { compose.runOnIdle { owner.viewModelStore.clear() } }
    }

    @Test fun largeFontsKeepEveryExplicitChoiceAndCloseReachableWithoutQueueing() {
        val port = DeletedCandidate()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = recoveryModel(owner, port)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                ContakoTheme {
                    val panel = model.deletionRecoveryPanel.collectAsState().value
                    if (panel.contactId != null) DeletionRecoveryDialog(panel, model)
                }
            }
        }
        try {
            compose.runOnIdle { model.openDeletionRecovery(port.contact.id) }
            DeletionRecoveryChoice.entries.forEach { choice ->
                val label = compose.activity.getString(deletionRecoveryChoiceLabel(choice))
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                compose.onNodeWithText(label).assertIsDisplayed().assertHasClickAction()
            }
            compose.onNodeWithText(compose.activity.getString(R.string.action_close)).assertIsDisplayed().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.deletion_recovery_title)).assertDoesNotExist()
            compose.runOnIdle { assertTrue(port.choices.isEmpty()); assertEquals(0, port.unrelatedWork) }
        } finally { compose.runOnIdle { owner.viewModelStore.clear() } }
    }

    private fun recoveryModel(owner: ViewModelStoreOwner, port: DeletedCandidate) =
        ViewModelProvider(owner, ContactsViewModel.factory(RecoveryNoWrites, port,
            accountId = port.contact.accountId, navigationState = SavedStateHandle(),
            onMutationCommitted = { error("Recovery must not wake ordinary mutation work") }))[ContactsViewModel::class.java]

    private class DeletedCandidate : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
        val contact = CanonicalContact("synthetic-owner", "deleted-candidate", displayName = "Chloë Groß recovery fixture",
            values = listOf(ContactValue("email", ContactValueKind.EMAIL, "recovery@example.test", order = 0)))
        val summary = DeletionRecoverySummary(contact.id, "synthetic-generation", 7, 0, contact.displayName,
            DeletionRecoveryState.READY, absenceVerified = true)
        private val rows = MutableStateFlow(listOf(summary))
        val choices = mutableListOf<Triple<String, DeletionRecoverySummary, DeletionRecoveryChoice>>()
        var unrelatedWork = 0
        override fun observeDeletionRecoveries(accountId: String) = rows
        override suspend fun loadDeletionRecovery(accountId: String, contactId: String): DeletionRecoveryDetail {
            assertEquals(contact.accountId, accountId)
            assertEquals(contact.id, contactId)
            return DeletionRecoveryDetail(summary, contact, retainedGroupAssignmentCount = 2)
        }
        override suspend fun chooseDeletionRecovery(accountId: String, expected: DeletionRecoverySummary,
            choice: DeletionRecoveryChoice): Boolean { choices += Triple(accountId, expected, choice); return true }
        override suspend fun requestSync(accountId: String) { unrelatedWork++; error("No implicit synchronization") }
        override suspend fun beginRepair(accountId: String, mobileDataConfirmed: Boolean): RepairStartResult {
            unrelatedWork++; error("No account-wide repair")
        }
        override suspend fun saveDeletionDraft(accountId: String, expected: DeletionRecoverySummary,
            contact: CanonicalContact): Boolean { unrelatedWork++; error("No implicit draft save") }
    }

    private object RecoveryNoWrites : ContactRepository {
        override fun observeContacts(accountId: String) = flowOf(emptyList<CanonicalContact>())
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? = error("No lookup")
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> = error("No ordinary write")
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact, assignments: Set<ContactGroupAssignment>,
            managedGroupIds: Set<String>, baseline: ContactEditBaseline?): SaveResult<CanonicalContact> = error("No ordinary write")
        override suspend fun deleteContact(accountId: String, contactId: String) = error("No ordinary deletion")
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = error("No group write")
        override suspend fun deleteGroup(accountId: String, groupId: String) = error("No group deletion")
    }

    private companion object { const val DATABASE = "qa26-deletion-recovery.db" }
}
