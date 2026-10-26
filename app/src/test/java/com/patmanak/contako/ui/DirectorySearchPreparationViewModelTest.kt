package com.patmanak.contako.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.patmanak.contako.android.account.AccountLifecycleAdmission
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.repository.ContactEditBaseline
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.domain.repository.SaveResult
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectorySearchPreparationViewModelTest {
    private val main = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(main) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun emptyQueryDoesNotPrepareSearchInputsAndLaterQueriesReuseOneSnapshot() = runTest(main) {
        val original = contact("first", "Zoë", "first@example.test")
        val countedValues = CountingValues(original.values)
        val first = original.copy(values = countedValues)
        val second = contact("second", "Other", "second@example.test")
        val repository = ReadOnlyRepository(listOf(first, second))
        val worker = QueuedDispatcher()
        val store = ViewModelStore()
        val model = model(repository, worker, store)
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        try {
            untilDirectory(worker) { !model.uiState.value.directoryLoading && model.uiState.value.contacts.size == 2 }
            worker.drain()
            runCurrent()
            // No speculative normalization job runs after first useful rendering.
            assertFalse(worker.hasTasks)
            assertEquals(listOf(first, second), model.uiState.value.contacts)
            assertEquals("Équipe", model.uiState.value.organizationFacets.single().label)
            val beforeFirstQuery = countedValues.reads
            model.updateQuery("ZOE")
            runCurrent()
            assertEquals(listOf(first), model.uiState.value.directoryContacts)
            assertTrue("First nonblank query must prepare its fields", countedValues.reads > beforeFirstQuery)
            val preparedReads = countedValues.reads
            model.updateQuery("first@")
            runCurrent()
            assertEquals(listOf(first), model.uiState.value.directoryContacts)
            assertEquals("Another query must reuse its snapshot index", preparedReads, countedValues.reads)

            // Same ID, changed names and email: the next base MUST NOT use the previous index.
            val changed = first.copy(displayName = "Chloë", revision = 8, values = first.values.map {
                if (it.kind == ContactValueKind.EMAIL) it.copy(value = "fresh@example.test") else it
            })
            model.updateQuery("")
            runCurrent()
            repository.contacts.value = listOf(changed, second)
            untilDirectory(worker) { model.uiState.value.contacts.firstOrNull()?.displayName == "Chloë" }
            model.updateQuery("zoe")
            runCurrent()
            assertTrue(model.uiState.value.directoryContacts.isEmpty())
            model.updateQuery("CHLOE")
            runCurrent()
            assertEquals(listOf(changed), model.uiState.value.directoryContacts)
            worker.drain()
            runCurrent()
            assertEquals(listOf(changed), model.uiState.value.directoryContacts)
            assertEquals(listOf(changed, second), repository.contacts.value)
            assertEquals(first.preservationEnvelope, changed.preservationEnvelope)
            assertEquals(first.values.filterNot { it.kind == ContactValueKind.EMAIL },
                changed.values.filterNot { it.kind == ContactValueKind.EMAIL })
        } finally {
            subscription.cancel()
            store.clear()
            worker.drain()
            runCurrent()
        }
    }

    @Test fun rapidReplacementAndOwnerInvalidationDiscardEarlierPreparedInputs() = runTest(main) {
        val original = contact("same", "Before", "before@example.test")
        val newest = original.copy(displayName = "After", revision = 9,
            values = original.values.map { if (it.kind == ContactValueKind.EMAIL) it.copy(value = "after@example.test") else it })
        val repository = ReadOnlyRepository(listOf(original))
        val worker = QueuedDispatcher()
        val store = ViewModelStore()
        val admission = AccountLifecycleAdmission()
        val model = model(repository, worker, store, admission)
        val published = mutableListOf<List<CanonicalContact>>()
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.uiState.collect { published += it.directoryContacts }
        }
        try {
            untilDirectory(worker) { model.uiState.value.contacts.singleOrNull()?.displayName == "Before" }
            model.updateQuery("before@")
            runCurrent()
            assertEquals(listOf(original), model.uiState.value.directoryContacts)
            model.updateQuery("")
            runCurrent()
            repository.contacts.value = listOf(original.copy(displayName = "Intermediate", revision = 8))
            repository.contacts.value = listOf(newest)
            // Deliver the latest snapshot; it must own a different deferred index even with the same ID.
            worker.runLast()
            untilDirectory(worker) { model.uiState.value.contacts.singleOrNull()?.displayName == "After" }
            val afterNewBase = published.size
            model.updateQuery("after@")
            runCurrent()
            assertEquals(listOf(newest), model.uiState.value.directoryContacts)
            worker.drain()
            runCurrent()
            assertEquals(listOf(newest), model.uiState.value.directoryContacts)
            assertTrue(published.drop(afterNewBase).flatten().all { it == newest })

            admission.cleanup {}
            runCurrent()
            assertTrue(model.uiState.value.contacts.isEmpty())
            val afterInvalidation = published.size
            repository.contacts.value = listOf(original)
            worker.drain()
            runCurrent()
            assertTrue(model.uiState.value.contacts.isEmpty())
            assertTrue(model.uiState.value.directoryContacts.isEmpty())
            assertTrue(published.drop(afterInvalidation).all(List<CanonicalContact>::isEmpty))
        } finally {
            subscription.cancel()
            store.clear()
            worker.drain()
            runCurrent()
        }
    }

    private fun TestScope.untilDirectory(worker: QueuedDispatcher, ready: () -> Boolean) {
        repeat(200) {
            runCurrent()
            if (ready()) return
            check(worker.runNext()) { "Directory preparation stopped before its expected snapshot" }
        }
        error("Directory preparation did not settle")
    }

    private fun model(repository: ContactRepository, worker: CoroutineDispatcher, store: ViewModelStore,
        admission: ContactMutationAdmission = UnrestrictedContactMutationAdmission) = ViewModelProvider(store,
        ContactsViewModel.factory(repository, accountId = ACCOUNT, mutationAdmission = admission,
            navigationState = SavedStateHandle(), directoryDispatcher = worker))[ContactsViewModel::class.java]

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val hasTasks: Boolean get() = tasks.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext(): Boolean = tasks.pollFirst()?.let { it.run(); true } ?: false
        fun runLast() { tasks.pollLast()?.run() }
        fun drain() {
            repeat(1_000) { if (!runNext()) return }
            error("Directory worker did not quiesce")
        }
    }

    /** Count actual field traversal without a production normalization hook. */
    private class CountingValues(private val values: List<ContactValue>) : AbstractList<ContactValue>() {
        var reads = 0
            private set
        override val size: Int get() = values.size
        override fun get(index: Int): ContactValue = values[index].also { reads++ }
    }

    private class ReadOnlyRepository(initial: List<CanonicalContact>) : ContactRepository {
        val contacts = MutableStateFlow(initial)
        override fun observeContacts(accountId: String) = contacts
        override fun observeGroups(accountId: String) = flowOf(emptyList<ContactGroup>())
        override fun observePendingMutationCount(accountId: String) = flowOf(0)
        override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? = error("Unexpected contact read")
        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> = error("Unexpected write")
        override suspend fun saveContactWithGroupAssignments(contact: CanonicalContact,
            assignments: Set<ContactGroupAssignment>, managedGroupIds: Set<String>, baseline: ContactEditBaseline?): SaveResult<CanonicalContact> =
            error("Unexpected write")
        override suspend fun deleteContact(accountId: String, contactId: String): Unit = error("Unexpected delete")
        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> = error("Unexpected write")
        override suspend fun deleteGroup(accountId: String, groupId: String): Unit = error("Unexpected delete")
    }

    private fun contact(id: String, name: String, email: String) = CanonicalContact(ACCOUNT, id,
        displayName = name, revision = 7, values = listOf(
            ContactValue("email", ContactValueKind.EMAIL, email, order = 0),
            ContactValue("organization", ContactValueKind.ORGANIZATION, "Équipe", order = 0),
            ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "Retained canary", order = 0,
                metadata = mapOf("property" to "X-SYNTHETIC")),
        ), preservationEnvelope = PreservationEnvelope(mapOf("X-SYNTHETIC" to "Retained source")))

    companion object { private const val ACCOUNT = "directory-preparation-owner" }
}
