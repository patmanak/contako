package com.patmanak.contako.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.patmanak.contako.android.account.AccountLifecycleAdmission
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.*
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactSyncDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val original = ContactSyncDetail("synthetic-contact", "Chloë Groß", 7, false,
        ContactPeerSyncDetail(ContactPeerSyncState.CONFIRMED,
            ContactSyncEvidence(7, 1_000, ContactSyncEvidenceKind.VERIFIED_READ)),
        ContactPeerSyncDetail(ContactPeerSyncState.UNKNOWN))

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owner.viewModelStore.clear(); Dispatchers.resetMain() }

    @Test fun repeatedInspectionIsLocalAndRefreshIsExplicitAndAccountScoped() = runTest(dispatcher) {
        val recovery = ReadOnlyDetails(original)
        val model = model(recovery)
        runCurrent()
        repeat(10) {
            model.openContactSyncDetail(original.contactId)
            runCurrent()
            assertEquals(original, model.contactSyncPanel.value.detail)
            assertEquals(1, recovery.activeObservers)
            model.dismissContactSyncDetail()
            runCurrent()
            assertNull(model.contactSyncPanel.value.contactId)
            assertEquals(0, recovery.activeObservers)
        }
        assertEquals(10, recovery.observations)
        assertTrue(recovery.syncAccounts.isEmpty())
        model.openContactSyncDetail(original.contactId)
        runCurrent()
        model.syncNow()
        runCurrent()
        assertEquals(listOf("synthetic-owner"), recovery.syncAccounts)
        assertEquals(original, model.contactSyncPanel.value.detail)
    }

    @Test fun newerLocalIntentAndRetainedDeletionUpdateTheOpenPanelWithoutAnotherRequest() = runTest(dispatcher) {
        val recovery = ReadOnlyDetails(original)
        val model = model(recovery)
        model.openContactSyncDetail(original.contactId)
        runCurrent()
        val changed = original.copy(localRevision = 8,
            proton = original.proton.copy(state = ContactPeerSyncState.PENDING))
        recovery.detail.value = changed
        runCurrent()
        assertEquals(changed, model.contactSyncPanel.value.detail)
        assertEquals(7L, model.contactSyncPanel.value.detail?.proton?.evidence?.localRevision)
        recovery.detail.value = changed.copy(isDeleted = true)
        runCurrent()
        assertTrue(model.contactSyncPanel.value.detail?.isDeleted == true)
        assertTrue(recovery.syncAccounts.isEmpty())
        assertEquals(1, recovery.observations)
    }

    @Test fun cleanupCancelsTheObserverAndRejectsPriorOwnerUpdatesAndReopening() = runTest(dispatcher) {
        val admission = AccountLifecycleAdmission()
        val recovery = ReadOnlyDetails(original)
        val model = model(recovery, admission)
        model.openContactSyncDetail(original.contactId)
        runCurrent()
        assertEquals(1, recovery.activeObservers)
        admission.cleanup { }
        runCurrent()
        assertEquals(0, recovery.activeObservers)
        assertNull(model.contactSyncPanel.value.contactId)
        recovery.detail.value = original.copy(displayName = "Prior owner data")
        model.openContactSyncDetail(original.contactId)
        runCurrent()
        assertNull(model.contactSyncPanel.value.detail)
        assertEquals(1, recovery.observations)
    }

    @Test fun aWrongTargetOrMissingRecordCannotExposeAnotherContact() = runTest(dispatcher) {
        val recovery = ReadOnlyDetails(original.copy(contactId = "another-contact"))
        val model = model(recovery)
        model.openContactSyncDetail(original.contactId)
        runCurrent()
        assertNull(model.contactSyncPanel.value.detail)
        assertTrue(model.contactSyncPanel.value.failed)
        recovery.detail.value = null
        runCurrent()
        assertNull(model.contactSyncPanel.value.detail)
        assertFalse(model.contactSyncPanel.value.loading)
        assertTrue(recovery.syncAccounts.isEmpty())
    }

    @Test fun privateDetailAndProofMetadataNeverAppearInStringRepresentations() {
        val hidden = original.copy(contactId = "private-id-canary", displayName = "Private name canary")
        assertEquals("ContactSyncDetail(REDACTED)", hidden.toString())
        assertFalse(ContactSyncPanelState(hidden.contactId, hidden).toString().contains("canary"))
        assertFalse(hidden.proton.evidence.toString().contains("1000"))
    }

    private fun model(recovery: SyncRecoveryDataSource,
        admission: ContactMutationAdmission = UnrestrictedContactMutationAdmission) =
        ViewModelProvider(owner, ContactsViewModel.factory(NoWrites, recovery, accountId = "synthetic-owner",
            mutationAdmission = admission, navigationState = SavedStateHandle()))[ContactsViewModel::class.java]

    private class ReadOnlyDetails(initial: ContactSyncDetail?) : SyncRecoveryDataSource by EmptySyncRecoveryDataSource {
        val detail = MutableStateFlow(initial)
        var activeObservers = 0
        var observations = 0
        val syncAccounts = mutableListOf<String>()
        override fun observeContactSyncDetail(accountId: String, contactId: String) = flow {
            assertEquals("synthetic-owner", accountId)
            observations++; activeObservers++
            try { emitAll(detail) } finally { activeObservers-- }
        }
        override suspend fun requestSync(accountId: String) { syncAccounts += accountId }
    }

    private object NoWrites : ContactRepository {
        override fun observeContacts(accountId: String) = flowOf(emptyList<CanonicalContact>())
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? = error("No peer/repository lookup")
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> = error("No write")
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact, assignments: Set<ContactGroupAssignment>,
            managedGroupIds: Set<String>, baseline: ContactEditBaseline?): SaveResult<CanonicalContact> = error("No write")
        override suspend fun deleteContact(accountId: String, contactId: String) = error("No write")
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = error("No write")
        override suspend fun deleteGroup(accountId: String, groupId: String) = error("No write")
    }
}
