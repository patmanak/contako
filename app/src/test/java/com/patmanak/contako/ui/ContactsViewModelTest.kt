package com.patmanak.contako.ui

import androidx.lifecycle.SavedStateHandle
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
    @Test
    fun blankDisplayNameIsPersistedFromNamePartsOnCreateAndEdit() = runTest(dispatcher) {
        val cases = listOf(
            Triple(" Ada ", " Lovelace ", "Ada Lovelace"),
            Triple("Ada", "", "Ada"),
            Triple("", "Lovelace", "Lovelace"),
            Triple(" ", " ", ""),
        )
        for (editing in listOf(false, true)) {
            for ((given, family, expected) in cases) {
                val original = CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "contact",
                    firstName = "Previous", displayName = "Old display name").takeIf { editing }
                val repository = FakeRepository(listOfNotNull(original))
                val model = ContactsViewModel(repository)
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
                model.editContact(original)
                advanceUntilIdle()
                model.updateContactEditor(requireNotNull(model.uiState.value.contactEditor).copy(
                    firstName = given, lastName = family, displayName = "  ",
                ))
                model.saveContact()
                advanceUntilIdle()
                val saved = requireNotNull(repository.savedContact)
                assertEquals(expected, saved.displayName)
                assertEquals(given, saved.firstName)
                assertEquals(family, saved.lastName)
                model.editContact(saved)
                advanceUntilIdle()
                model.updateContactEditor(requireNotNull(model.uiState.value.contactEditor).copy(displayName = "Nickname"))
                model.saveContact()
                advanceUntilIdle()
                assertEquals("Nickname", requireNotNull(repository.savedContact).displayName)
            }
        }
    }

    @Test
    fun explicitNameEditsUpdateImportedStructuredNameWithoutLosingHiddenComponents() = runTest(dispatcher) {
        val name = ContactValue("name", ContactValueKind.STRUCTURED_NAME, ";;Middle;Dr;Jr", order = 0,
            components = mapOf("given" to "", "family" to "", "additional" to "Middle",
                "prefix" to "Dr", "suffix" to "Jr"), preservationKey = "preserved-name")
        val original = CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "contact",
            displayName = "Display only", values = listOf(name))
        val repository = FakeRepository(listOf(original))
        val model = ContactsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.editContact(original)
        advanceUntilIdle()
        model.updateContactEditor(requireNotNull(model.uiState.value.contactEditor)
            .copy(firstName = "Given", lastName = "Family"))
        model.saveContact()
        advanceUntilIdle()
        val saved = requireNotNull(repository.savedContact)
        val structured = saved.values.single()
        assertEquals("Given", saved.firstName)
        assertEquals("Family", saved.lastName)
        assertEquals(name.components + mapOf("given" to "Given", "family" to "Family"), structured.components)
        assertEquals(name.preservationKey, structured.preservationKey)
        assertEquals("Family;Given;Middle;Dr;Jr", structured.value)
        model.editContact(saved)
        advanceUntilIdle()
        model.updateContactEditor(requireNotNull(model.uiState.value.contactEditor).copy(firstName = "", lastName = ""))
        model.saveContact()
        advanceUntilIdle()
        assertEquals(name, requireNotNull(repository.savedContact).values.single())
    }

    @Test
    fun deletionFailureRetainsTargetAndRetryIsSingleFlightForContactsAndGroups() = runTest(dispatcher) {
        for (isGroup in listOf(false, true)) {
            val repository = FakeRepository(emptyList())
            repository.deleteAction = { throw java.io.IOException("synthetic private failure") }
            var committed = 0
            val model = ContactsViewModel(repository, onMutationCommitted = { committed++ })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            val contact = CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "target", firstName = "Target")
            val group = ContactGroup(LOCAL_ACCOUNT_ID, "target", "Target")
            fun request() { if (isGroup) model.requestGroupDeletion(group) else model.requestContactDeletion(contact) }
            fun confirm() { if (isGroup) model.confirmGroupDeletion() else model.confirmContactDeletion() }
            fun status() = if (isGroup) model.uiState.value.groupDeletionStatus else model.uiState.value.contactDeletionStatus
            fun target() = if (isGroup) model.uiState.value.pendingGroupDeletion?.id else model.uiState.value.pendingContactDeletion?.id
            request()
            confirm()
            advanceUntilIdle()
            assertEquals(DeletionStatus(failed = true), status())
            assertEquals("target", target())
            assertEquals(0, committed)
            val completion = CompletableDeferred<Unit>()
            repository.deleteAction = { completion.await() }
            confirm()
            confirm()
            if (isGroup) {
                model.cancelGroupDeletion()
                model.requestGroupDeletion(group.copy(id = "other"))
            } else {
                model.cancelContactDeletion()
                model.requestContactDeletion(contact.copy(id = "other"))
            }
            advanceUntilIdle()
            assertEquals(2, repository.deleteCount)
            assertEquals(DeletionStatus(inProgress = true), status())
            assertEquals("target", target())
            completion.complete(Unit)
            advanceUntilIdle()
            assertNull(target())
            assertEquals(DeletionStatus(), status())
            assertEquals(1, committed)
        }
    }

    @Test
    fun deletionCancellationDoesNotBecomeFailureOrLeaveSubmissionLocked() = runTest(dispatcher) {
        for (isGroup in listOf(false, true)) {
            val repository = FakeRepository(emptyList())
            repository.deleteAction = { throw kotlinx.coroutines.CancellationException("cancelled") }
            val model = ContactsViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            if (isGroup) model.requestGroupDeletion(ContactGroup(LOCAL_ACCOUNT_ID, "target", "Target"))
            else model.requestContactDeletion(CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "target", firstName = "Target"))
            if (isGroup) model.confirmGroupDeletion() else model.confirmContactDeletion()
            advanceUntilIdle()
            assertEquals(DeletionStatus(), if (isGroup) model.uiState.value.groupDeletionStatus else model.uiState.value.contactDeletionStatus)
            assertEquals("target", if (isGroup) model.uiState.value.pendingGroupDeletion?.id else model.uiState.value.pendingContactDeletion?.id)
            repository.deleteAction = {}
            if (isGroup) model.confirmGroupDeletion() else model.confirmContactDeletion()
            advanceUntilIdle()
            assertEquals(2, repository.deleteCount)
            assertNull(if (isGroup) model.uiState.value.pendingGroupDeletion else model.uiState.value.pendingContactDeletion)
        }
    }

    @Test
    fun dispatchFailureAfterDurableDeletionDoesNotPresentFalseDeleteFailure() = runTest(dispatcher) {
        for (isGroup in listOf(false, true)) {
            val repository = FakeRepository(emptyList())
            val model = ContactsViewModel(repository, onMutationCommitted = { throw java.io.IOException("scheduler") })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            if (isGroup) model.requestGroupDeletion(ContactGroup(LOCAL_ACCOUNT_ID, "target", "Target"))
            else model.requestContactDeletion(CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "target", firstName = "Target"))
            if (isGroup) model.confirmGroupDeletion() else model.confirmContactDeletion()
            advanceUntilIdle()
            assertNull(if (isGroup) model.uiState.value.pendingGroupDeletion else model.uiState.value.pendingContactDeletion)
            assertEquals(DeletionStatus(), if (isGroup) model.uiState.value.groupDeletionStatus else model.uiState.value.contactDeletionStatus)
            if (isGroup) model.confirmGroupDeletion() else model.confirmContactDeletion()
            advanceUntilIdle()
            assertEquals(1, repository.deleteCount)
        }
    }

    @Test
    fun groupSaveIsSingleFlightAndDoesNotNavigateOverNewDraft() = runTest(dispatcher) {
        val repository = FakeRepository(emptyList())
        val completion = CompletableDeferred<SaveResult<ContactGroup>>()
        repository.groupSave = { completion.await() }
        var committed = 0
        val model = ContactsViewModel(repository, onMutationCommitted = { committed++ })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.editGroup()
        advanceUntilIdle()
        model.updateGroupEditor(requireNotNull(model.uiState.value.groupEditor).copy(name = "First"))
        advanceUntilIdle()
        val first = requireNotNull(model.uiState.value.groupEditor)
        model.saveGroup()
        model.saveGroup()
        // A stale recomposition must not reset the synchronous submission guard.
        model.updateGroupEditor(first.copy(name = "Changed during save"))
        model.saveGroup()
        advanceUntilIdle()
        assertEquals(1, repository.groupSaveCount)
        assertTrue(requireNotNull(model.uiState.value.groupEditor).saving)
        assertEquals("First", model.uiState.value.groupEditor?.name)
        model.editGroup(ContactGroup(LOCAL_ACCOUNT_ID, "second", "Second"))
        model.selectDestination(RootDestination.CONTACTS)
        advanceUntilIdle()
        val second = requireNotNull(model.uiState.value.groupEditor)
        completion.complete(SaveResult.Saved(ContactGroup(LOCAL_ACCOUNT_ID, "first", "First")))
        advanceUntilIdle()
        assertEquals(second, model.uiState.value.groupEditor)
        assertEquals(RootDestination.CONTACTS, model.uiState.value.navigation.destination)
        assertEquals(1, committed)
    }

    @Test
    fun groupSaveFailurePreservesDraftAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository(emptyList())
        repository.groupSave = { throw java.io.IOException("synthetic storage failure") }
        val model = ContactsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.editGroup(ContactGroup(LOCAL_ACCOUNT_ID, "group", "Draft"))
        model.saveGroup()
        advanceUntilIdle()
        assertEquals(UiMessage.SAVE_FAILED, model.uiState.value.groupEditor?.validationError)
        assertFalse(requireNotNull(model.uiState.value.groupEditor).saving)
        assertEquals("Draft", model.uiState.value.groupEditor?.name)
        repository.groupSave = { SaveResult.Saved(it) }
        model.saveGroup()
        advanceUntilIdle()
        assertEquals(2, repository.groupSaveCount)
        assertNull(model.uiState.value.groupEditor)
    }

    @Test
    fun staleGroupFailureDoesNotOverwriteNewEditor() = runTest(dispatcher) {
        val repository = FakeRepository(emptyList())
        val completion = CompletableDeferred<SaveResult<ContactGroup>>()
        repository.groupSave = { completion.await() }
        val model = ContactsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.editGroup(ContactGroup(LOCAL_ACCOUNT_ID, "first", "First"))
        model.saveGroup()
        advanceUntilIdle()
        model.editGroup(ContactGroup(LOCAL_ACCOUNT_ID, "second", "Second"))
        advanceUntilIdle()
        val second = model.uiState.value.groupEditor
        completion.completeExceptionally(java.io.IOException("synthetic storage failure"))
        advanceUntilIdle()
        assertEquals(second, model.uiState.value.groupEditor)
    }

    @Test
    fun D132EmailEditPreservesUntouchedImportedValueButRejectsItsModification() = runTest(dispatcher) {
        val imported = CanonicalContact(accountId = LOCAL_ACCOUNT_ID, id = "imported", remoteContactId = "remote",
            displayName = "Imported", values = listOf(
                ContactValue("a", ContactValueKind.EMAIL, "first@example.test", order = 0),
                ContactValue("b", ContactValueKind.EMAIL, "second@example.test", order = 1),
                ContactValue("date", ContactValueKind.BIRTHDAY, "circa 1980", order = 0,
                    preservationKey = "proton-card-1-0"),
            ))
        val repository = FakeRepository(listOf(imported))
        val model = ContactsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        advanceUntilIdle()
        model.editContact(imported)
        model.deleteContactValue("a")
        model.updateContactValue("b", "updated@example.test")
        model.saveContact()
        advanceUntilIdle()
        val saved = requireNotNull(repository.savedContact)
        assertEquals(listOf("updated@example.test"), saved.valuesOf(ContactValueKind.EMAIL).map { it.value })
        assertEquals(imported.values.last(), saved.valuesOf(ContactValueKind.BIRTHDAY).single())
        assertNull(model.uiState.value.contactEditor)
        model.editContact(saved)
        model.updateContactValue("date", "still not a date")
        model.saveContact()
        advanceUntilIdle()
        assertEquals(UiMessage.INVALID_DATE, model.uiState.value.contactEditor?.fieldErrors?.get("date"))
        assertFalse(requireNotNull(model.uiState.value.contactEditor).saving)
    }

    @Test
    fun D132SyncDetailsRemainVisibleOutsideDirectorySearch() = runTest(dispatcher) {
        val pending = contact("pending", "Pending", "a", "a@example.test").copy(pendingMutationRevision = 3)
        val android = contact("android", "Android", "b", "b@example.test")
        val repository = FakeRepository(listOf(pending, android))
        val recovery = FakeSyncRecoveryDataSource()
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.ANDROID_PARTIAL,
            androidPendingContactIds = setOf(android.id))
        val model = ContactsViewModel(repository, recovery)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(pending), model.uiState.value.pendingContacts)
        model.updateQuery("no-match")
        advanceUntilIdle()
        assertTrue(model.uiState.value.contacts.isEmpty())
        assertEquals(2, model.uiState.value.totalContactCount)
        assertEquals(listOf(pending), model.uiState.value.pendingContacts)
        assertEquals(listOf(android), model.uiState.value.androidPendingContacts)
        assertEquals(listOf(android), model.uiState.value.actionContacts)
    }

    @Test
    fun D129GlobalProblemSurvivesWithNoPendingContactAndConflictsRemainVisible() = runTest(dispatcher) {
        val conflict = contact("conflict", "Conflict", "email", "synthetic@example.test")
            .copy(conflictState = "EDIT_DELETE_RECOVERY_REQUIRED")
        val repository = FakeRepository(listOf(conflict))
        val recovery = FakeSyncRecoveryDataSource()
        recovery.status.value = SyncDashboardSnapshot(
            SyncDashboardState.ANDROID_DEGRADED,
            actionRequiredCount = 1,
            problem = com.patmanak.contako.domain.sync.SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED,
        )
        val model = ContactsViewModel(repository, recovery)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(conflict), model.uiState.value.actionContacts)
        repository.contacts.value = emptyList()
        advanceUntilIdle()
        assertTrue(model.uiState.value.actionContacts.isEmpty())
        assertEquals(0, model.uiState.value.syncDashboard.pendingMutationCount)
        assertEquals(1, model.uiState.value.syncDashboard.actionRequiredCount)
        assertEquals(recovery.status.value?.problem, model.uiState.value.syncDashboard.problem)
    }

    @Test
    fun blockedProtonMutationIdentifiesContactEvenWithoutCanonicalActionReason() = runTest(dispatcher) {
        val blocked = contact("fixture-outbox-id", "Blocked fixture", "email", "blocked@example.test")
            .copy(pendingMutationRevision = 2)
        val repository = FakeRepository(listOf(blocked))
        val recovery = FakeSyncRecoveryDataSource()
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.BLOCKED,
            actionRequiredCount = 1, blockedMutationContactIds = setOf(blocked.id))
        val model = ContactsViewModel(repository, recovery)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.updateQuery("no-match")
        advanceUntilIdle()
        assertTrue(model.uiState.value.contacts.isEmpty())
        assertEquals(listOf(blocked), model.uiState.value.actionContacts)
        assertFalse(model.uiState.value.syncDashboard.toString().contains(blocked.id))
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.CURRENT)
        advanceUntilIdle()
        assertTrue(model.uiState.value.actionContacts.isEmpty())
    }

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun attentionCountIncludesEveryAffectedContactOnceAndClearsAfterRecovery() = runTest(dispatcher) {
        val first = contact("fixture-first", "First", "email-first", "first@example.test")
        val second = contact("fixture-second", "Second", "email-second", "second@example.test")
        val repository = FakeRepository(listOf(first, second))
        val recovery = FakeSyncRecoveryDataSource()
        recovery.status.value = SyncDashboardSnapshot(
            SyncDashboardState.ANDROID_PARTIAL, actionRequiredCount = 1,
            androidPendingContactIds = setOf(first.id, second.id),
            blockedMutationContactIds = setOf(first.id),
        )
        val model = ContactsViewModel(repository, recovery)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        model.updateQuery("no-matching-contact")
        advanceUntilIdle()
        assertTrue(model.uiState.value.contacts.isEmpty())
        assertEquals(listOf(first, second), model.uiState.value.actionContacts)
        assertEquals(2, model.uiState.value.syncDashboard.actionRequiredCount)
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.CURRENT)
        advanceUntilIdle()
        assertTrue(model.uiState.value.actionContacts.isEmpty())
        assertEquals(0, model.uiState.value.syncDashboard.actionRequiredCount)
    }

    @Test
    fun initialAndroidCopiesRemainPendingWithoutCreatingAttentionItems() = runTest(dispatcher) {
        val first = contact("first-copy", "First", "first-email", "first@example.test")
        val second = contact("second-copy", "Second", "second-email", "second@example.test")
        val repository = FakeRepository(listOf(first, second))
        val recovery = FakeSyncRecoveryDataSource()
        val pending = SyncDashboardSnapshot(SyncDashboardState.PENDING,
            androidPendingContactIds = setOf(first.id, second.id))
        recovery.status.value = pending
        val model = ContactsViewModel(repository, recovery)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }

        for (activity in listOf(SyncActivity.SCHEDULED, SyncActivity.RUNNING, SyncActivity.IDLE)) {
            recovery.activity.value = activity
            advanceUntilIdle()
            assertEquals(listOf(first, second), model.uiState.value.androidPendingContacts)
            assertTrue(model.uiState.value.actionContacts.isEmpty())
            assertEquals(0, model.uiState.value.syncDashboard.actionRequiredCount)
        }

        recovery.status.value = pending.copy(state = SyncDashboardState.ANDROID_PARTIAL, actionRequiredCount = 1)
        advanceUntilIdle()
        assertEquals(listOf(first, second), model.uiState.value.actionContacts)
        assertEquals(2, model.uiState.value.syncDashboard.actionRequiredCount)

        // A later successful pass followed by new pending copies is not the old failure.
        recovery.status.value = pending
        advanceUntilIdle()
        assertEquals(listOf(first, second), model.uiState.value.androidPendingContacts)
        assertTrue(model.uiState.value.actionContacts.isEmpty())
        assertEquals(0, model.uiState.value.syncDashboard.actionRequiredCount)
    }

    @Test
    fun pendingAndroidCopiesDoNotHideRealConflictsOrBlockedUploads() = runTest(dispatcher) {
        val waiting = contact("waiting-copy", "Waiting", "waiting-email", "waiting@example.test")
        val blocked = contact("blocked-copy", "Blocked", "blocked-email", "blocked@example.test")
            .copy(conflictState = "EDIT_DELETE_RECOVERY_REQUIRED")
        val repository = FakeRepository(listOf(waiting, blocked))
        val recovery = FakeSyncRecoveryDataSource()
        recovery.activity.value = SyncActivity.RUNNING
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.PENDING,
            androidPendingContactIds = setOf(waiting.id, blocked.id),
            blockedMutationContactIds = setOf(blocked.id))
        val model = ContactsViewModel(repository, recovery)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(waiting, blocked), model.uiState.value.androidPendingContacts)
        assertEquals(listOf(blocked), model.uiState.value.actionContacts)
        assertEquals(1, model.uiState.value.syncDashboard.actionRequiredCount)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun RF08QueuedLocalIntentCannotBeShownAsCurrentWhileAndroidDefersThePass() = runTest(dispatcher) {
        val repository = FakeRepository(emptyList())
        val recovery = FakeSyncRecoveryDataSource()
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.CURRENT)
        val viewModel = ContactsViewModel(repository, recovery)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        repository.pending.value = 1
        advanceUntilIdle()
        assertEquals(SyncDashboardState.PENDING, viewModel.uiState.value.syncDashboard.state)
        assertEquals(1, viewModel.uiState.value.syncDashboard.pendingMutationCount)
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.AUTHENTICATION_REQUIRED)
        advanceUntilIdle()
        assertEquals(SyncDashboardState.AUTHENTICATION_REQUIRED, viewModel.uiState.value.syncDashboard.state)
        assertEquals(1, viewModel.uiState.value.syncDashboard.pendingMutationCount)
        repository.pending.value = 0
        recovery.status.value = SyncDashboardSnapshot(SyncDashboardState.CURRENT)
        advanceUntilIdle()
        assertEquals(SyncDashboardState.CURRENT, viewModel.uiState.value.syncDashboard.state)
        assertEquals(0, viewModel.uiState.value.syncDashboard.pendingMutationCount)
    }

    @Test
    fun RF08ExternalDeletionClosesDetailWithoutDiscardingDirectoryContext() = runTest(dispatcher) {
        val selected = contact("selected", "Selected", "email", "selected@example.test")
        val group = ContactGroup(LOCAL_ACCOUNT_ID, "group", "Selected group")
        val repository = FakeRepository(listOf(selected), listOf(group))
        val viewModel = ContactsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateQuery("Selected")
        viewModel.updateScroll(12, 4)
        viewModel.selectContact(selected)
        repository.contacts.value = emptyList()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.navigation.current.selectedId)
        assertEquals("Selected", viewModel.uiState.value.query)
        assertEquals(12, viewModel.uiState.value.navigation.current.scrollIndex)
        viewModel.selectDestination(RootDestination.GROUPS)
        viewModel.selectGroup(group)
        repository.groups.value = emptyList()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.navigation.current.selectedId)

        val delayedContacts = kotlinx.coroutines.flow.MutableSharedFlow<List<CanonicalContact>>()
        val delayedRepository = object : ContactRepository by repository {
            override fun observeContacts(accountId: String): Flow<List<CanonicalContact>> = delayedContacts
        }
        val restored = ContactsViewModel(delayedRepository, savedStateHandle = SavedStateHandle(mapOf(
            "navigation.CONTACTS.selection" to selected.id,
        )))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(selected.id, restored.uiState.value.navigation.current.selectedId)
        delayedContacts.emit(listOf(selected))
        advanceUntilIdle()
        assertEquals(selected.id, restored.uiState.value.navigation.current.selectedId)
        delayedContacts.emit(emptyList())
        advanceUntilIdle()
        assertNull(restored.uiState.value.navigation.current.selectedId)
    }

    @Test
    fun RF08CreatedContactIsVisibleWithoutLosingTheDirectoryFilter() = runTest(dispatcher) {
        val alice = contact("alice", "Alice", "email", "alice@example.test")
        val group = ContactGroup(LOCAL_ACCOUNT_ID, "group", "Friends",
            memberships = listOf(GroupMembership(alice.id, "email")))
        val repository = FakeRepository(listOf(alice), listOf(group))
        val viewModel = ContactsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateQuery("Alice")
        viewModel.selectContact(alice)
        advanceUntilIdle()
        assertEquals(listOf(group), viewModel.uiState.value.groups)
        viewModel.handleBack()
        viewModel.editContact()
        advanceUntilIdle()
        viewModel.updateContactEditor(requireNotNull(viewModel.uiState.value.contactEditor).copy(displayName = "Zed"))
        viewModel.saveContact()
        advanceUntilIdle()
        val saved = requireNotNull(repository.savedContact)
        repository.contacts.value = listOf(alice, saved)
        advanceUntilIdle()
        assertEquals(saved.id, viewModel.uiState.value.navigation.current.selectedId)
        assertEquals("Alice", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.contacts.contains(saved))
        viewModel.handleBack()
        advanceUntilIdle()
        assertEquals(listOf(alice), viewModel.uiState.value.contacts)
        assertEquals("Alice", viewModel.uiState.value.query)
    }

    @Test
    fun RF02UnchangedPopulatedEditorsCloseButActualEditsKeepDiscardProtection() = runTest(dispatcher) {
        val base = contact("member", "Member", "member-email", "member@example.test")
        val member = base.copy(values = base.values + listOf(
            ContactValue("logo", ContactValueKind.LOGO, "", order = 0, binaryReference = "logo-reference"),
            ContactValue("photo-2", ContactValueKind.PHOTO, "", order = 1, binaryReference = "photo-reference-2"),
            ContactValue("photo-1", ContactValueKind.PHOTO, "", order = 0, binaryReference = "photo-reference-1"),
        ))
        val group = ContactGroup(
            accountId = LOCAL_ACCOUNT_ID,
            id = "group",
            name = "Group",
            color = "#5252CC",
            memberships = listOf(GroupMembership(member.id, "member-email")),
        )
        val viewModel = ContactsViewModel(FakeRepository(listOf(member), listOf(group)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.editGroup(group)
        viewModel.dismissGroupEditor()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.groupEditor)
        assertFalse(viewModel.uiState.value.showUnsavedConfirmation)

        viewModel.editGroup(group)
        advanceUntilIdle()
        viewModel.updateGroupEditor(requireNotNull(viewModel.uiState.value.groupEditor).copy(name = "Changed"))
        viewModel.dismissGroupEditor()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showUnsavedConfirmation)
        assertEquals("Changed", viewModel.uiState.value.groupEditor?.name)
        viewModel.discardEditor()

        viewModel.editContact(member)
        viewModel.dismissContactEditor()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.contactEditor)
        assertFalse(viewModel.uiState.value.showUnsavedConfirmation)

        viewModel.editContact(member)
        advanceUntilIdle()
        viewModel.updateContactEditor(requireNotNull(viewModel.uiState.value.contactEditor).copy(firstName = "Changed"))
        viewModel.dismissContactEditor()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showUnsavedConfirmation)
        assertEquals("Changed", viewModel.uiState.value.contactEditor?.firstName)
    }

    @Test
    fun groupEditorUsesAllCanonicalContactsWhenDirectoryIsFiltered() = runTest(dispatcher) {
        val repository = FakeRepository(
            listOf(
                contact("alice", "Alice", "alice-email", "alice@example.test"),
                contact("bob", "Bob", "bob-email", "bob@example.test"),
            ),
        )
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.updateQuery("Alice")
        advanceUntilIdle()
        assertEquals(listOf("alice"), viewModel.uiState.value.contacts.map { it.id })

        viewModel.editGroup(ContactGroup(LOCAL_ACCOUNT_ID, "friends", "Friends"))
        advanceUntilIdle()

        assertEquals(
            setOf("alice-email", "bob-email"),
            viewModel.uiState.value.groupEditor?.emailOptions?.map { it.emailValueId }?.toSet(),
        )
        collection.cancel()
    }

    @Test
    fun groupEditorOffersEveryEmailAndExplainsNoEmailContacts() = runTest(dispatcher) {
        val multiEmail = CanonicalContact(
            accountId = LOCAL_ACCOUNT_ID,
            id = "multi",
            displayName = "Multi",
            values = listOf(
                ContactValue("secondary", ContactValueKind.EMAIL, "secondary@example.test", order = 0),
                ContactValue(
                    "preferred",
                    ContactValueKind.EMAIL,
                    "preferred@example.test",
                    order = 1,
                    metadata = mapOf("vcardPref" to "1"),
                ),
            ),
        )
        val repository = FakeRepository(
            listOf(multiEmail, CanonicalContact(LOCAL_ACCOUNT_ID, "no-email", displayName = "No email")),
        )
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editGroup()
        advanceUntilIdle()

        val editor = requireNotNull(viewModel.uiState.value.groupEditor)
        assertEquals(listOf("secondary", "preferred"), editor.emailOptions.map(EmailMembershipOption::emailValueId))
        assertEquals(1, editor.contactsWithoutEmailCount)
        assertTrue(editor.selectedMemberships.isEmpty())
        collection.cancel()
    }

    @Test
    fun groupEditorPersistsTheSelectedColorWithItsMemberships() = runTest(dispatcher) {
        val member = contact("member", "Member", "member-email", "member@example.test")
        val original = ContactGroup(
            accountId = LOCAL_ACCOUNT_ID,
            id = "colored",
            name = "Colored",
            color = "#8080FF",
        )
        val repository = FakeRepository(contacts = listOf(member), groups = listOf(original))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editGroup(original)
        advanceUntilIdle()
        val option = requireNotNull(viewModel.uiState.value.groupEditor).emailOptions.single()
        viewModel.toggleGroupMembership(option)
        advanceUntilIdle()
        viewModel.updateGroupEditor(requireNotNull(viewModel.uiState.value.groupEditor).copy(color = "#5252CC"))
        viewModel.saveGroup()
        advanceUntilIdle()

        assertEquals("#5252CC", repository.savedGroup?.color)
        assertEquals(setOf(option.membership), repository.savedGroup?.memberships?.toSet())
        collection.cancel()
    }

    @Test
    fun contactEditorEditsProtonGroupsPerEmailAndSavesOneCompleteAssignmentVector() = runTest(dispatcher) {
        val editedContact = CanonicalContact(
            accountId = LOCAL_ACCOUNT_ID,
            id = "edited",
            displayName = "Edited",
            values = listOf(
                ContactValue("primary", ContactValueKind.EMAIL, "primary@example.test", order = 0),
                ContactValue("secondary", ContactValueKind.EMAIL, "secondary@example.test", order = 1),
            ),
        )
        val repository = FakeRepository(
            contacts = listOf(editedContact),
            groups = listOf(
                ContactGroup(
                    LOCAL_ACCOUNT_ID,
                    "friends",
                    "Friends",
                    memberships = listOf(GroupMembership("edited", "primary")),
                ),
                ContactGroup(LOCAL_ACCOUNT_ID, "work", "Work"),
            ),
        )
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact(editedContact)
        advanceUntilIdle()
        val opened = requireNotNull(viewModel.uiState.value.contactEditor)
        assertEquals(listOf("Friends", "Work"), opened.groupOptions.map(ContactGroupOption::name))
        assertEquals(
            setOf(ContactGroupAssignment("friends", "primary")),
            opened.selectedGroupAssignments,
        )

        viewModel.addContactValue(ContactValueKind.EMAIL)
        advanceUntilIdle()
        val blankEmailId = requireNotNull(viewModel.uiState.value.contactEditor).values
            .single { it.kind == ContactValueKind.EMAIL && it.value.isBlank() }.id
        viewModel.toggleContactGroupAssignment("work", blankEmailId)
        assertFalse(
            ContactGroupAssignment("work", blankEmailId) in
                requireNotNull(viewModel.uiState.value.contactEditor).selectedGroupAssignments,
        )
        viewModel.toggleContactGroupAssignment("friends", "primary")
        viewModel.toggleContactGroupAssignment("work", "secondary")
        viewModel.saveContact()
        advanceUntilIdle()

        assertEquals(
            setOf(ContactGroupAssignment("work", "secondary")),
            repository.savedContactGroupAssignments,
        )
        assertEquals(setOf("friends", "work"), repository.savedManagedGroupIds)
        assertEquals("edited", repository.savedContact?.id)
        assertNull(viewModel.uiState.value.contactEditor)
        collection.cancel()
    }

    @Test
    fun imageEditorReordersPrefersEditsAndDeletesPhotoWithoutTouchingLogos() = runTest(dispatcher) {
        val photoA = ContactValue("photo-a", ContactValueKind.PHOTO, "https://img.example.test/a.png", order = 0)
        val photoB = ContactValue("photo-b", ContactValueKind.PHOTO, "https://img.example.test/b.png", order = 1)
        val logo = ContactValue("logo", ContactValueKind.LOGO, "https://img.example.test/logo.png", order = 0)
        val repository = FakeRepository(listOf(contact("image", "Image", "email", "image@example.test")
            .copy(values = listOf(photoA, photoB, logo))))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact(repository.contacts.value.single())
        viewModel.moveImage(photoB.id, -1)
        viewModel.preferImage(photoB.id)
        viewModel.updateImage(photoB.id, "https://img.example.test/edited.png")
        viewModel.deleteImage(photoA.id)
        advanceUntilIdle()
        val editor = requireNotNull(viewModel.uiState.value.contactEditor)

        assertEquals(listOf(photoB.id), editor.images.filter { it.kind == ContactValueKind.PHOTO }.map { it.id })
        assertEquals("https://img.example.test/edited.png", editor.images.single { it.id == photoB.id }.value)
        assertTrue(editor.images.single { it.id == photoB.id }.isPrimary)
        assertEquals(logo.copy(isPrimary = true), editor.images.single { it.id == logo.id })
        assertFalse(editor.images.any { it.id == photoA.id })
        collection.cancel()
    }

    @Test
    fun advancedEditorCreatesEditsDeletesValidatesAndPreservesUnknownRawState() = runTest(dispatcher) {
        val unknown = ContactValue(
            "raw-canary", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "opaque", order = 0,
            preservationKey = "raw-canary",
        )
        val original = contact("advanced", "Advanced", "email", "advanced@example.test").copy(
            values = listOf(
                ContactValue("lang", ContactValueKind.LANGUAGE, "en", order = 0),
                ContactValue("gender", ContactValueKind.GENDER, "U", order = 0),
                unknown,
            ),
            preservationEnvelope = com.patmanak.contako.domain.model.PreservationEnvelope(
                rawProperties = mapOf("raw-canary" to "X-CANARY:opaque"),
            ),
        )
        val repository = FakeRepository(listOf(original))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact(original)
        viewModel.updateContactValue("lang", "fr-CA")
        viewModel.deleteContactValue("gender")
        viewModel.addContactValue(ContactValueKind.CATEGORY)
        advanceUntilIdle()
        val categoryId = requireNotNull(viewModel.uiState.value.contactEditor).values
            .single { it.kind == ContactValueKind.CATEGORY }.id
        viewModel.updateContactValue(categoryId, "Friends")
        viewModel.saveContact()
        advanceUntilIdle()

        val saved = requireNotNull(repository.savedContact)
        assertEquals("fr-CA", saved.values.single { it.kind == ContactValueKind.LANGUAGE }.value)
        assertEquals("Friends", saved.values.single { it.kind == ContactValueKind.CATEGORY }.value)
        assertFalse(saved.values.any { it.kind == ContactValueKind.GENDER })
        assertEquals(unknown, saved.values.single { it.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY })
        assertEquals(original.preservationEnvelope, saved.preservationEnvelope)
        assertNull(viewModel.uiState.value.contactEditor)
        collection.cancel()
    }

    @Test
    fun repeatableValuesSupportCustomLabelsAndReordering() = runTest(dispatcher) {
        val contact = contact("repeat", "Repeat", "email-a", "a@example.test").copy(
            values = listOf(
                ContactValue("email-a", ContactValueKind.EMAIL, "a@example.test", order = 0),
                ContactValue("email-b", ContactValueKind.EMAIL, "b@example.test", order = 1),
            ),
        )
        val repository = FakeRepository(listOf(contact))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.editContact(contact)
        viewModel.updateContactValueLabel("email-b", "Other")
        viewModel.moveContactValue("email-b", -1)
        advanceUntilIdle()
        val edited = viewModel.uiState.value.contactEditor!!.values
        assertEquals(listOf("email-b", "email-a"), edited.sortedBy(ContactValue::order).map { it.id })
        assertEquals("Other", edited.single { it.id == "email-b" }.label)

        collection.cancel()
    }

    @Test
    fun hiddenCategorySurvivesAnUnrelatedEditorSave() = runTest(dispatcher) {
        val category = ContactValue("category", ContactValueKind.CATEGORY, "Friends", order = 0)
        val original = contact("category-contact", "Before", "email", "category@example.test").copy(
            values = listOf(
                ContactValue("email", ContactValueKind.EMAIL, "category@example.test", order = 0),
                category,
            ),
        )
        val repository = FakeRepository(listOf(original))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact(original)
        advanceUntilIdle()
        val editor = requireNotNull(viewModel.uiState.value.contactEditor)
        viewModel.updateContactEditor(editor.copy(displayName = "After"))
        viewModel.saveContact()
        advanceUntilIdle()

        val saved = requireNotNull(repository.savedContact)
        assertEquals("After", saved.displayName)
        assertEquals(category, saved.values.single { it.kind == ContactValueKind.CATEGORY })
        collection.cancel()
    }

    @Test
    fun repeatableValuesCanPromoteOneExplicitPreferredValueWithoutLosingOrder() = runTest(dispatcher) {
        val contact = contact("preferred", "Preferred", "email-a", "a@example.test").copy(
            values = listOf(
                ContactValue(
                    "email-a",
                    ContactValueKind.EMAIL,
                    "a@example.test",
                    order = 0,
                    metadata = mapOf(CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA to "1"),
                ),
                ContactValue("email-b", ContactValueKind.EMAIL, "b@example.test", order = 1),
            ),
        )
        val repository = FakeRepository(listOf(contact))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact(contact)
        advanceUntilIdle()
        viewModel.preferContactValue("email-b")
        advanceUntilIdle()
        val values = requireNotNull(viewModel.uiState.value.contactEditor).values

        assertEquals(listOf("email-a", "email-b"), values.sortedBy(ContactValue::order).map(ContactValue::id))
        assertEquals("1", values.single { it.id == "email-b" }.metadata[CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA])
        assertEquals("2", values.single { it.id == "email-a" }.metadata[CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA])
        collection.cancel()
    }

    @Test
    fun postalAddressEditorDerivesDisplayValueAndPreservesUneditedComponents() = runTest(dispatcher) {
        val address = ContactValue(
            id = "address",
            kind = ContactValueKind.POSTAL_ADDRESS,
            value = "Building A, 1 Main Street, Paris, France",
            order = 0,
            components = mapOf(
                "extended" to "Building A",
                "street" to "1 Main Street",
                "locality" to "Paris",
                "country" to "France",
            ),
        )
        val original = contact("address-contact", "Address", "email", "address@example.test")
            .copy(values = listOf(address))
        val repository = FakeRepository(listOf(original))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact(original)
        viewModel.updatePostalAddressComponent("address", "locality", "Lyon")
        viewModel.saveContact()
        advanceUntilIdle()

        val saved = requireNotNull(repository.savedContact).values.single()
        assertEquals("Building A", saved.components["extended"])
        assertEquals("1 Main Street", saved.components["street"])
        assertEquals("Lyon", saved.components["locality"])
        assertEquals("France", saved.components["country"])
        assertEquals("Building A, 1 Main Street, Lyon, France", saved.value)
        collection.cancel()
    }

    @Test
    fun advancedEditorRejectsPrivateKeyWithoutSavingOrDiscardingDraft() = runTest(dispatcher) {
        val repository = FakeRepository(emptyList())
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact()
        viewModel.addContactValue(ContactValueKind.PUBLIC_KEY)
        advanceUntilIdle()
        val keyId = requireNotNull(viewModel.uiState.value.contactEditor).values.single().id
        viewModel.updateContactValue(keyId, "-----BEGIN PRIVATE KEY-----")
        viewModel.saveContact()
        advanceUntilIdle()

        val editor = requireNotNull(viewModel.uiState.value.contactEditor)
        assertEquals(UiMessage.INVALID_PUBLIC_KEY, editor.fieldErrors.getValue(keyId))
        assertEquals("-----BEGIN PRIVATE KEY-----", editor.values.single().value)
        assertNull(repository.savedContact)
        collection.cancel()
    }

    @Test
    fun privateKeyDraftIsNotPersistedInSavedStateOrProcessRecreation() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val repository = FakeRepository(emptyList())
        val viewModel = ContactsViewModel(repository, savedStateHandle = savedState)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact()
        viewModel.addContactValue(ContactValueKind.PUBLIC_KEY)
        advanceUntilIdle()
        val keyId = requireNotNull(viewModel.uiState.value.contactEditor).values.single().id
        viewModel.updateContactValue(keyId, "-----BEGIN PRIVATE KEY-----")
        viewModel.saveContact()
        advanceUntilIdle()

        assertNull(repository.savedContact)
        assertFalse(savedState.keys().any { key -> savedState.get<Any?>(key).toString().contains("PRIVATE KEY") })
        val recreated = ContactsViewModel(repository, savedStateHandle = savedState)
        val recreatedCollection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            recreated.uiState.collect {}
        }
        advanceUntilIdle()
        assertNull(recreated.uiState.value.contactEditor)
        collection.cancel()
        recreatedCollection.cancel()
    }

    @Test
    fun advancedFamilyMatrixCreatesEditsDeletesAndRetainsInvalidDrafts() = runTest(dispatcher) {
        val cases = listOf(
            AdvancedCase(ContactValueKind.LANGUAGE, "en", "fr-CA", "not_a_tag"),
            AdvancedCase(ContactValueKind.TIME_ZONE, "+01:00", "Europe/Paris", "Europe;Paris"),
            AdvancedCase(ContactValueKind.GENDER, "F", "O;nonbinary", "X"),
            AdvancedCase(ContactValueKind.PUBLIC_KEY, "https://keys.example.test/a", "https://keys.example.test/b", "http://keys.example.test/a"),
            AdvancedCase(ContactValueKind.MEMBER, "urn:uuid:00000000-0000-0000-0000-000000000001", "member-value", "bad\u202Evalue"),
            AdvancedCase(ContactValueKind.CATEGORY, "Friends", "Work", "bad\u202Evalue"),
            AdvancedCase(ContactValueKind.ROLE, "Researcher", "Reviewer", "bad\u202Evalue"),
            AdvancedCase(ContactValueKind.LOGO, "https://img.example.test/a.png", "https://img.example.test/b.png", "http://img.example.test/a.png"),
        )

        cases.forEach { case ->
            val repository = FakeRepository(emptyList())
            val viewModel = ContactsViewModel(repository)
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
            }
            advanceUntilIdle()
            viewModel.editContact()
            if (case.kind == ContactValueKind.LOGO) viewModel.addImage(case.kind) else viewModel.addContactValue(case.kind)
            advanceUntilIdle()
            val created = requireNotNull(viewModel.uiState.value.contactEditor).let { editor ->
                (editor.values + editor.images).single { it.kind == case.kind }
            }
            if (case.kind == ContactValueKind.LOGO) {
                viewModel.updateImage(created.id, case.created)
                viewModel.preferImage(created.id)
                viewModel.updateImage(created.id, case.edited)
            } else {
                viewModel.updateContactValue(created.id, case.created)
                viewModel.updateContactValue(created.id, case.edited)
            }
            viewModel.saveContact()
            advanceUntilIdle()
            assertEquals(case.edited, requireNotNull(repository.savedContact).values.single().value)

            val saved = requireNotNull(repository.savedContact)
            repository.savedContact = null
            viewModel.editContact(saved)
            if (case.kind == ContactValueKind.LOGO) {
                viewModel.updateImage(created.id, case.invalid)
            } else {
                viewModel.updateContactValue(created.id, case.invalid)
            }
            viewModel.saveContact()
            advanceUntilIdle()
            val rejected = requireNotNull(viewModel.uiState.value.contactEditor)
            assertTrue("${case.kind} must reject its invalid fixture", created.id in rejected.fieldErrors)
            assertEquals(case.invalid, (rejected.values + rejected.images).single().value)
            assertNull(repository.savedContact)

            if (case.kind == ContactValueKind.LOGO) viewModel.deleteImage(created.id) else viewModel.deleteContactValue(created.id)
            viewModel.saveContact()
            advanceUntilIdle()
            assertTrue(requireNotNull(repository.savedContact).values.none { it.kind == case.kind })
            collection.cancel()
        }
    }

    @Test
    fun delayedImageResultCannotMutateANewerContactDraft() = runTest(dispatcher) {
        val viewModel = ContactsViewModel(FakeRepository(emptyList()))
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact()
        advanceUntilIdle()
        val oldGeneration = requireNotNull(viewModel.uiState.value.contactEditor).draftGeneration
        viewModel.discardEditor()
        viewModel.editContact()
        advanceUntilIdle()
        val currentGeneration = requireNotNull(viewModel.uiState.value.contactEditor).draftGeneration

        viewModel.applySelectedImage(
            draftGeneration = oldGeneration,
            kind = ContactValueKind.PHOTO,
            imageId = null,
            value = "data:image/png;base64,AA==",
        )
        advanceUntilIdle()
        assertTrue(requireNotNull(viewModel.uiState.value.contactEditor).images.isEmpty())

        viewModel.applySelectedImage(
            draftGeneration = currentGeneration,
            kind = ContactValueKind.PHOTO,
            imageId = null,
            value = "data:image/png;base64,AA==",
        )
        advanceUntilIdle()
        assertEquals(1, requireNotNull(viewModel.uiState.value.contactEditor).images.size)
        collection.cancel()
    }

    @Test
    fun delayedReplacementCannotMutateAnotherImageAfterItsTargetWasDeleted() = runTest(dispatcher) {
        val removed = ContactValue("photo-a", ContactValueKind.PHOTO, "data:image/png;base64,AA==", order = 0)
        val retained = ContactValue("photo-b", ContactValueKind.PHOTO, "data:image/png;base64,BB==", order = 1)
        val viewModel = ContactsViewModel(FakeRepository(emptyList()))
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.editContact(
            CanonicalContact(
                accountId = LOCAL_ACCOUNT_ID,
                id = "image-target",
                values = listOf(removed, retained),
            ),
        )
        advanceUntilIdle()
        val generation = requireNotNull(viewModel.uiState.value.contactEditor).draftGeneration
        viewModel.deleteImage(removed.id)
        advanceUntilIdle()

        viewModel.applySelectedImage(
            draftGeneration = generation,
            kind = ContactValueKind.PHOTO,
            imageId = removed.id,
            value = "data:image/png;base64,CC==",
        )
        advanceUntilIdle()

        val editor = requireNotNull(viewModel.uiState.value.contactEditor)
        assertEquals(listOf(retained.id), editor.images.map(ContactValue::id))
        assertEquals(retained.value, editor.images.single().value)
        assertEquals(UiMessage.INVALID_IMAGE, editor.validationError)
        collection.cancel()
    }

    @Test
    fun newCustomDateIsMarkedLocalOnlyBeforeAndAfterSave() = runTest(dispatcher) {
        val repository = FakeRepository(emptyList())
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()
        viewModel.editContact()
        viewModel.addContactValue(ContactValueKind.CUSTOM_DATE)
        advanceUntilIdle()
        val customDate = requireNotNull(viewModel.uiState.value.contactEditor).values.single()
        assertEquals("LOCAL_ONLY", customDate.metadata["syncDisposition"])
        viewModel.updateContactValue(customDate.id, "2026-08-04")
        viewModel.saveContact()
        advanceUntilIdle()
        assertEquals("LOCAL_ONLY", requireNotNull(repository.savedContact).values.single().metadata["syncDisposition"])
        collection.cancel()
    }

    @Test
    fun dirtyEditorBackRequiresConfirmationAndPreservesDraft() = runTest(dispatcher) {
        val viewModel = ContactsViewModel(FakeRepository(emptyList()))
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()
        viewModel.editContact()
        advanceUntilIdle()
        viewModel.updateContactEditor(requireNotNull(viewModel.uiState.value.contactEditor).copy(firstName = "Draft"))
        advanceUntilIdle()

        assertTrue(viewModel.handleBack())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showUnsavedConfirmation)
        assertEquals("Draft", viewModel.uiState.value.contactEditor?.firstName)
        viewModel.keepEditing()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showUnsavedConfirmation)
        assertEquals("Draft", viewModel.uiState.value.contactEditor?.firstName)
        viewModel.requestEditorDismiss()
        viewModel.discardEditor()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.contactEditor)
        collection.cancel()
    }

    @Test
    fun syncRecoveryBoundaryPublishesDashboardAndControlsRepair() = runTest(dispatcher) {
        val recovery = FakeSyncRecoveryDataSource()
        val viewModel = ContactsViewModel(FakeRepository(emptyList()), recovery)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        SyncDashboardState.entries.forEach { dashboardState ->
            recovery.status.value = SyncDashboardSnapshot(dashboardState, pendingMutationCount = 3)
            advanceUntilIdle()
            assertEquals(dashboardState, viewModel.uiState.value.syncDashboard.state)
        }

        recovery.requireConfirmation = true
        viewModel.startRepair()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showRepairConfirmation)
        viewModel.confirmRepair()
        advanceUntilIdle()
        assertEquals(listOf(false, true), recovery.repairConfirmations)

        recovery.repair.value = RepairProgress(RepairPhase.ANDROID_PROJECTION, 2, 4, false)
        advanceUntilIdle()
        assertEquals(2L, viewModel.uiState.value.repairProgress?.completedUnits)
        viewModel.cancelRepair()
        advanceUntilIdle()
        assertEquals(1, recovery.cancelCount)
        collection.cancel()
    }

    @Test
    fun syncNowRequestsAnOrdinaryPassOnTheInjectedAccountScope() = runTest(dispatcher) {
        val recovery = FakeSyncRecoveryDataSource()
        val viewModel = ContactsViewModel(
            repository = FakeRepository(emptyList()),
            syncRecoveryDataSource = recovery,
            accountId = "engine-scope",
        )

        viewModel.syncNow()
        advanceUntilIdle()

        // A manual pass MUST reach the runner, and it MUST be scoped to the account the
        // synchronization engine actually runs on rather than the local UI alias.
        assertEquals(listOf("engine-scope"), recovery.syncRequestedAccounts)
        assertEquals(0, recovery.repairConfirmations.size)
    }

    @Test
    fun openingAContactFromAGroupSwitchesToContactsAndSelectsIt() = runTest(dispatcher) {
        val contact = contact("contact-1", "Ada", "email-1", "ada@example.test")
        val viewModel = ContactsViewModel(repository = FakeRepository(listOf(contact)))
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        viewModel.selectDestination(RootDestination.GROUPS)
        advanceUntilIdle()

        viewModel.openContactFromGroup(contact)
        advanceUntilIdle()

        // Group members are contacts, so opening one must land on the same detail screen the
        // Contacts directory uses rather than a second, divergent presentation.
        val navigation = viewModel.uiState.value.navigation
        assertEquals(RootDestination.CONTACTS, navigation.destination)
        assertEquals("contact-1", navigation.current.selectedId)
        collection.cancel()
    }

    @Test
    fun androidDegradationIsReportedSeparatelyFromPendingChanges() = runTest(dispatcher) {
        val recovery = FakeSyncRecoveryDataSource()
        val viewModel = ContactsViewModel(
            repository = FakeRepository(emptyList()),
            syncRecoveryDataSource = recovery,
        )
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        // Android degradation with an empty outbox previously surfaced as a generic blocked state
        // claiming changes needed attention, which was untrue and unactionable.
        recovery.status.value = SyncDashboardSnapshot(
            state = SyncDashboardState.ANDROID_DEGRADED,
            pendingMutationCount = 0,
        )
        advanceUntilIdle()

        val dashboard = viewModel.uiState.value.syncDashboard
        assertEquals(SyncDashboardState.ANDROID_DEGRADED, dashboard.state)
        assertEquals(0, dashboard.pendingMutationCount)
        collection.cancel()
    }

    @Test
    fun syncActivityIsSurfacedSoARequestedPassIsVisible() = runTest(dispatcher) {
        val recovery = FakeSyncRecoveryDataSource()
        val viewModel = ContactsViewModel(
            repository = FakeRepository(emptyList()),
            syncRecoveryDataSource = recovery,
        )
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        assertEquals(SyncActivity.IDLE, viewModel.uiState.value.syncActivity)

        // A pass may start and finish in the same durable state; activity is what proves it ran.
        recovery.activity.value = SyncActivity.RUNNING
        advanceUntilIdle()
        assertEquals(SyncActivity.RUNNING, viewModel.uiState.value.syncActivity)

        recovery.activity.value = SyncActivity.IDLE
        advanceUntilIdle()
        assertEquals(SyncActivity.IDLE, viewModel.uiState.value.syncActivity)
        collection.cancel()
    }

    @Test
    fun contactsPermissionRequestUsesUiBoundary() = runTest(dispatcher) {
        val permission = FakeContactsPermissionBoundary(false)
        val viewModel = ContactsViewModel(
            repository = FakeRepository(emptyList()),
            contactsPermissionBoundary = permission,
        )
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.contactsPermissionGranted)
        assertEquals(ContactsPermissionAction.REQUEST, viewModel.uiState.value.contactsPermissionAction)
        viewModel.requestContactsPermission()
        assertEquals(1, permission.requestCount)
        permission.action.value = ContactsPermissionAction.OPEN_SETTINGS
        advanceUntilIdle()
        assertEquals(ContactsPermissionAction.OPEN_SETTINGS, viewModel.uiState.value.contactsPermissionAction)
        permission.granted.value = true
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.contactsPermissionGranted)
        collection.cancel()
    }

    @Test
    fun sectionNavigationPreservesLocalQueryAndDoesNotTouchRepository() = runTest(dispatcher) {
        val repository = FakeRepository(listOf(contact("alice", "Alice", "email", "alice@example.test")))
        val viewModel = ContactsViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.updateQuery("ali")
        viewModel.updateScroll(12, 4)
        advanceUntilIdle()

        assertEquals("ali", viewModel.uiState.value.query)
        assertEquals(12, viewModel.uiState.value.navigation.current.scrollIndex)
        assertEquals(1, repository.contactsObserveCount)
        collection.cancel()
    }

    @Test
    fun confirmedDeletionClearsTheMatchingDestinationSelection() = runTest(dispatcher) {
        val contact = contact("selected-contact", "Selected", "email", "selected@example.test")
        val group = ContactGroup(
            accountId = LOCAL_ACCOUNT_ID,
            id = "selected-group",
            name = "Selected group",
        )
        val viewModel = ContactsViewModel(FakeRepository(listOf(contact), listOf(group)))
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.selectContact(contact)
        viewModel.requestContactDeletion(contact)
        viewModel.confirmContactDeletion()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.navigation.current.selectedId)

        viewModel.selectDestination(RootDestination.GROUPS)
        viewModel.selectGroup(group)
        viewModel.requestGroupDeletion(group)
        viewModel.confirmGroupDeletion()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.navigation.current.selectedId)
        collection.cancel()
    }

    private fun contact(id: String, name: String, emailId: String, email: String) = CanonicalContact(
        accountId = LOCAL_ACCOUNT_ID,
        id = id,
        firstName = name,
        values = listOf(ContactValue(emailId, ContactValueKind.EMAIL, email, order = 0)),
    )

    private data class AdvancedCase(
        val kind: ContactValueKind,
        val created: String,
        val edited: String,
        val invalid: String,
    )

    private class FakeRepository(
        contacts: List<CanonicalContact>,
        groups: List<ContactGroup> = emptyList(),
    ) : ContactRepository {
        val contacts = MutableStateFlow(contacts)
        var contactsObserveCount = 0
        var savedContact: CanonicalContact? = null
        var savedGroup: ContactGroup? = null
        var groupSaveCount = 0
        var deleteCount = 0
        var deleteAction: suspend () -> Unit = {}
        var groupSave: suspend (ContactGroup) -> SaveResult<ContactGroup> = { SaveResult.Saved(it) }
        var savedContactGroupAssignments: Set<ContactGroupAssignment>? = null
        var savedManagedGroupIds: Set<String>? = null
        val groups = MutableStateFlow(groups)
        val pending = MutableStateFlow(0)

        override fun observeContacts(accountId: String): Flow<List<CanonicalContact>> {
            contactsObserveCount++
            return contacts
        }
        override fun observeGroups(accountId: String): Flow<List<ContactGroup>> = groups
        override fun observePendingMutationCount(accountId: String): Flow<Int> = pending
        override suspend fun getContact(accountId: String, contactId: String): CanonicalContact? =
            contacts.value.firstOrNull { it.id == contactId }

        override suspend fun saveContact(contact: CanonicalContact): SaveResult<CanonicalContact> {
            savedContact = contact
            return SaveResult.Saved(contact)
        }

        override suspend fun saveContactWithGroupAssignments(
            contact: CanonicalContact,
            assignments: Set<ContactGroupAssignment>,
            managedGroupIds: Set<String>,
        ): SaveResult<CanonicalContact> {
            savedContactGroupAssignments = assignments
            savedManagedGroupIds = managedGroupIds
            return saveContact(contact)
        }

        override suspend fun deleteContact(accountId: String, contactId: String) {
            deleteCount++
            deleteAction()
        }

        override suspend fun saveGroup(group: ContactGroup): SaveResult<ContactGroup> {
            groupSaveCount++
            savedGroup = group
            return groupSave(group)
        }

        override suspend fun deleteGroup(accountId: String, groupId: String) {
            deleteCount++
            deleteAction()
        }
    }

    private class FakeSyncRecoveryDataSource : SyncRecoveryDataSource {
        val status = MutableStateFlow<SyncDashboardSnapshot?>(null)
        val repair = MutableStateFlow<RepairProgress?>(null)
        val activity = MutableStateFlow(SyncActivity.IDLE)
        val repairConfirmations = mutableListOf<Boolean>()
        var requireConfirmation = false
        var cancelCount = 0
        val syncRequestedAccounts = mutableListOf<String>()

        override fun observeStatus(accountId: String): Flow<SyncDashboardSnapshot?> = status
        override fun observeRepair(accountId: String): Flow<RepairProgress?> = repair
        override fun observeActivity(accountId: String): Flow<SyncActivity> = activity
        override suspend fun beginRepair(accountId: String, mobileDataConfirmed: Boolean): RepairStartResult {
            repairConfirmations += mobileDataConfirmed
            return if (requireConfirmation && !mobileDataConfirmed) {
                RepairStartResult.CONFIRMATION_REQUIRED
            } else {
                RepairStartResult.STARTED
            }
        }
        override suspend fun cancelRepair(accountId: String): Boolean {
            cancelCount++
            return true
        }
        override suspend fun requestSync(accountId: String) {
            syncRequestedAccounts += accountId
        }
    }

    private class FakeContactsPermissionBoundary(initiallyGranted: Boolean) : ContactsPermissionBoundary {
        override val granted = MutableStateFlow(initiallyGranted)
        override val action = MutableStateFlow(ContactsPermissionAction.REQUEST)
        var requestCount = 0
        override fun requestPermission() { requestCount++ }
    }
}
