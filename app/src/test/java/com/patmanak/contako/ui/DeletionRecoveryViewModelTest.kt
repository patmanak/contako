package com.patmanak.contako.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.patmanak.contako.android.account.AccountLifecycleAdmission
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.*
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeletionRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val contact = CanonicalContact("synthetic-owner", "deleted-card", displayName = "Chloë Groß", revision = 7,
        remoteContactId = "old-remote", preservationEnvelope = PreservationEnvelope(mapOf("unknown" to "private synthetic payload")),
        values = listOf(
            ContactValue("email", ContactValueKind.EMAIL, "fixture@example.test", order = 0),
            ContactValue("unknown", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-SYNTHETIC:preserve", order = 0,
                preservationKey = "opaque-1", metadata = mapOf("synthetic" to "preserve")),
            ContactValue("photo", ContactValueKind.PHOTO, "https://images.example.test/photo.png", order = 0, isPrimary = true,
                preservationKey = "photo-1"),
        ))
    private val summary = DeletionRecoverySummary(contact.id, "synthetic-generation", 7, 3, contact.displayName,
        DeletionRecoveryState.READY, absenceVerified = true)
    private val original get() = DeletionRecoveryDetail(summary, contact, retainedGroupAssignmentCount = 2)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun repeatedInspectionAndDismissalNeverQueueWorkOrWakeOrdinarySync() = runTest(dispatcher) {
        val port = Recovery(original)
        val repository = NoWrites()
        var wakeups = 0
        val model = model(port, repository, onCommitted = { wakeups++ })
        runCurrent()
        repeat(8) {
            model.openDeletionRecovery(contact.id)
            runCurrent()
            assertEquals(original, model.deletionRecoveryPanel.value.detail)
            model.dismissDeletionRecovery()
            runCurrent()
            assertNull(model.deletionRecoveryPanel.value.detail)
        }
        assertEquals(List(8) { contact.accountId to contact.id }, port.loads)
        assertTrue(port.choices.isEmpty())
        assertTrue(port.saves.isEmpty())
        assertTrue(port.syncAccounts.isEmpty())
        assertEquals(0, repository.writes)
        assertEquals(0, wakeups)
    }

    @Test fun eachExplicitChoiceQueuesTheExactInspectedSummaryWithoutOrdinaryMutation() = runTest(dispatcher) {
        for (choice in DeletionRecoveryChoice.entries) {
            val port = Recovery(original)
            val repository = NoWrites()
            var wakeups = 0
            val model = model(port, repository, onCommitted = { wakeups++ })
            model.openDeletionRecovery(contact.id)
            runCurrent()
            assertTrue(port.choices.isEmpty())
            model.chooseDeletionRecovery(choice)
            runCurrent()
            assertEquals(listOf(Triple(contact.accountId, summary, choice)), port.choices)
            assertEquals(2, port.loads.size)
            assertEquals(0, repository.writes)
            assertEquals(0, wakeups)
            assertTrue(port.syncAccounts.isEmpty())
        }
    }

    @Test fun editingDraftSavesOnlyThroughRecoveryAndPreservesUnknownDataAndPhoto() = runTest(dispatcher) {
        val draft = original.copy(summary = summary.copy(hasDraft = true, state = DeletionRecoveryState.DRAFT))
        val port = Recovery(draft)
        val repository = NoWrites()
        var wakeups = 0
        val model = model(port, repository, onCommitted = { wakeups++ })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.openDeletionRecovery(contact.id)
        runCurrent()
        model.editDeletionDraft()
        runCurrent()
        val editor = requireNotNull(model.uiState.value.contactEditor)
        assertEquals(draft.summary, editor.recoveryDraft)
        assertFalse(editor.assignmentsEnabled)
        assertTrue(editor.groupOptions.isEmpty())
        model.updateContactEditor(editor.copy(displayName = "Updated local draft"))
        model.saveContact()
        runCurrent()
        val saved = port.saves.single()
        assertEquals(contact.accountId, saved.first)
        assertEquals(draft.summary, saved.second)
        assertEquals("Updated local draft", saved.third.displayName)
        assertEquals(contact.preservationEnvelope, saved.third.preservationEnvelope)
        assertEquals(contact.values.single { it.id == "unknown" }, saved.third.values.single { it.id == "unknown" })
        assertEquals(contact.values.single { it.id == "photo" }, saved.third.values.single { it.id == "photo" })
        assertNull(model.uiState.value.contactEditor)
        assertEquals(0, repository.writes)
        assertEquals(0, wakeups)
        assertTrue(port.choices.isEmpty())
        assertTrue(port.syncAccounts.isEmpty())
    }

    @Test fun staleDraftSaveKeepsTheEditorAndDoesNotFallBackToOrdinarySave() = runTest(dispatcher) {
        val port = Recovery(original.copy(summary = summary.copy(hasDraft = true))).apply { saveAccepted = false }
        val repository = NoWrites()
        val model = model(port, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.openDeletionRecovery(contact.id)
        runCurrent()
        model.editDeletionDraft()
        runCurrent()
        model.saveContact()
        runCurrent()
        assertEquals(UiMessage.STALE_CONTACT_EDIT, model.uiState.value.contactEditor?.validationError)
        assertEquals(1, port.saves.size)
        assertEquals(0, repository.writes)
        assertTrue(port.choices.isEmpty())
    }

    @Test fun wrongOwnerSummaryTargetOrPayloadTargetCannotExposeOrQueueAnotherContact() = runTest(dispatcher) {
        val mismatches = listOf(
            original.copy(contact = contact.copy(accountId = "another-owner")),
            original.copy(summary = summary.copy(contactId = "another-card")),
            original.copy(contact = contact.copy(id = "another-card")),
        )
        for (detail in mismatches) {
            val port = Recovery(detail)
            val model = model(port)
            model.openDeletionRecovery(contact.id)
            runCurrent()
            assertNull(model.deletionRecoveryPanel.value.detail)
            assertTrue(model.deletionRecoveryPanel.value.failed)
            model.chooseDeletionRecovery(DeletionRecoveryChoice.DISCARD)
            model.editDeletionDraft()
            runCurrent()
            assertTrue(port.choices.isEmpty())
            assertTrue(port.saves.isEmpty())
        }
    }

    @Test fun cleanupRevokesLoadedConsentAndRejectsReopeningOrOldDraftSaves() = runTest(dispatcher) {
        val gate = AccountLifecycleAdmission()
        val port = Recovery(original.copy(summary = summary.copy(hasDraft = true)))
        val model = model(port, admission = gate)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.openDeletionRecovery(contact.id)
        runCurrent()
        model.editDeletionDraft()
        runCurrent()
        val oldEditor = requireNotNull(model.uiState.value.contactEditor)
        gate.cleanup { }
        runCurrent()
        assertNull(model.uiState.value.contactEditor)
        assertNull(model.deletionRecoveryPanel.value.contactId)
        model.updateContactEditor(oldEditor)
        model.saveContact()
        model.openDeletionRecovery(contact.id)
        model.chooseDeletionRecovery(DeletionRecoveryChoice.RECREATE)
        runCurrent()
        assertEquals(1, port.loads.size)
        assertTrue(port.saves.isEmpty())
        assertTrue(port.choices.isEmpty())
        assertNull(model.deletionRecoveryPanel.value.detail)
    }

    @Test fun changedBackendSummaryReloadsTheOpenPanelThroughTerminalState() = runTest(dispatcher) {
        val port = Recovery(original)
        val model = model(port)
        model.openDeletionRecovery(contact.id)
        runCurrent()
        for (state in listOf(DeletionRecoveryState.QUEUED, DeletionRecoveryState.CLEANUP_PENDING, DeletionRecoveryState.RECREATED)) {
            val changed = port.detail.copy(summary = port.detail.summary.copy(state = state,
                choice = DeletionRecoveryChoice.RECREATE, destinationContactId = "new-contact"))
            port.detail = changed
            // The production list hides terminal rows; the open panel must still reload its
            // retained summary instead of staying indefinitely on CLEANUP_PENDING.
            port.rows.value = if (state == DeletionRecoveryState.RECREATED) emptyList() else listOf(changed.summary)
            runCurrent()
            assertEquals(changed, model.deletionRecoveryPanel.value.detail)
        }
        assertEquals(4, port.loads.size)
        assertTrue(port.choices.isEmpty())
        assertTrue(port.syncAccounts.isEmpty())
    }

    private fun model(port: Recovery, repository: NoWrites = NoWrites(),
        admission: ContactMutationAdmission = UnrestrictedContactMutationAdmission, onCommitted: () -> Unit = {}): ContactsViewModel {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore().also(stores::add) }
        return ViewModelProvider(owner, ContactsViewModel.factory(repository, port, accountId = contact.accountId,
            mutationAdmission = admission, onMutationCommitted = onCommitted,
            navigationState = SavedStateHandle(), directoryDispatcher = dispatcher))[ContactsViewModel::class.java]
    }

    private class Recovery(var detail: DeletionRecoveryDetail) : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
        val rows = MutableStateFlow(listOf(detail.summary))
        val loads = mutableListOf<Pair<String, String>>()
        val choices = mutableListOf<Triple<String, DeletionRecoverySummary, DeletionRecoveryChoice>>()
        val saves = mutableListOf<Triple<String, DeletionRecoverySummary, CanonicalContact>>()
        val syncAccounts = mutableListOf<String>()
        var saveAccepted = true
        override fun observeDeletionRecoveries(accountId: String): Flow<List<DeletionRecoverySummary>> = rows
        override suspend fun loadDeletionRecovery(accountId: String, contactId: String): DeletionRecoveryDetail {
            loads += accountId to contactId
            return detail
        }
        override suspend fun chooseDeletionRecovery(accountId: String, expected: DeletionRecoverySummary,
            choice: DeletionRecoveryChoice): Boolean { choices += Triple(accountId, expected, choice); return true }
        override suspend fun saveDeletionDraft(accountId: String, expected: DeletionRecoverySummary,
            contact: CanonicalContact): Boolean { saves += Triple(accountId, expected, contact); return saveAccepted }
        override suspend fun requestSync(accountId: String) { syncAccounts += accountId }
    }

    private class NoWrites : ContactRepository {
        var writes = 0
        private fun forbidden(): Nothing { writes++; error("Recovery must not use ordinary repository operations") }
        override fun observeContacts(accountId: String) = flowOf(emptyList<CanonicalContact>())
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? = forbidden()
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> = forbidden()
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact, assignments: Set<ContactGroupAssignment>,
            managedGroupIds: Set<String>, baseline: ContactEditBaseline?): SaveResult<CanonicalContact> = forbidden()
        override suspend fun deleteContact(accountId: String, contactId: String) = forbidden()
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = forbidden()
        override suspend fun deleteGroup(accountId: String, groupId: String) = forbidden()
    }
}
