package com.patmanak.contako.ui

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.patmanak.contako.R
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.policy.ContactSearch
import com.patmanak.contako.domain.policy.DirectoryFilter
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.ui.theme.ContakoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real isolated Room -> repository -> ViewModel -> production UI; no peer or account runtime. */
class DirectoryFilterIntegrationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: ContakoDatabase? = null
    private var databaseName: String? = null
    private var store: ViewModelStore? = null
    private var shownModel by mutableStateOf<ContactsViewModel?>(null)
    private val targets = listOf(
        contact("alice", "Aster Alice", "École Libre", phone = false),
        contact("bruno", "Aster Bruno", "ecole libre", phone = true),
        contact("cora", "Aster Cora", "Other fixture company", phone = false),
    )
    private val foreign = contact("foreign", "Foreign fixture", "Foreign company canary", false)
        .copy(accountId = FOREIGN)
    private val group = ContactGroup(ACCOUNT, "secondary-group", "Secondary fixture group",
        memberships = listOf(GroupMembership("alice", "alice-secondary")))

    @After fun close() {
        disposeModel()
        database?.close()
        database = null
        databaseName?.let(context::deleteDatabase)
        databaseName = null
    }

    @Test fun appliedFilterAndSearchKeepDetailIndependentAndNeverMutateContacts() {
        val repository = seedAndShow()
        val db = requireNotNull(database)
        val model = requireNotNull(shownModel)
        val allBefore = runBlocking { repository.observeContacts(ACCOUNT).first() }
        val richBefore = runBlocking { requireNotNull(repository.getContact(ACCOUNT, "alice")) }
        val foreignBefore = runBlocking { repository.getContact(FOREIGN, foreign.id) }
        val groupsBefore = runBlocking { repository.observeGroups(ACCOUNT).first() }
        assertEquals(allBefore, model.uiState.value.directoryContacts)
        assertTrue(runBlocking { db.outboxDao().getAll(ACCOUNT).isEmpty() })
        assertEquals(1, model.uiState.value.organizationFacets.count { it.key == ORGANIZATION_KEY })
        assertFalse(model.uiState.value.organizationFacets.any { it.label == "Foreign company canary" })

        compose.onNodeWithContentDescription(text(R.string.directory_filter)).performClick()
        compose.onNodeWithText(text(R.string.directory_without_phone)).performClick()
        compose.onNodeWithText(text(R.string.directory_apply_filters)).performClick()
        waitForIds(model, setOf("alice", "cora"))
        targets.filter { it.id != "bruno" }.forEach {
            compose.onNodeWithText(it.displayName).assertIsDisplayed()
        }
        compose.onNodeWithText(targets[1].displayName).assertDoesNotExist()

        compose.onNodeWithContentDescription(text(R.string.directory_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Aster Alice")
        waitForIds(model, setOf("alice"))
        // Filtering and search are read-only at the real persistence boundary.
        runBlocking {
            assertEquals(allBefore, repository.observeContacts(ACCOUNT).first())
            assertEquals(richBefore, repository.getContact(ACCOUNT, "alice"))
            assertEquals(foreignBefore, repository.getContact(FOREIGN, foreign.id))
            assertEquals(groupsBefore, repository.observeGroups(ACCOUNT).first())
            assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
            assertTrue(db.contactContentChangesDao().observeActive(ACCOUNT).first().isEmpty())
        }
        compose.onNode(hasText(targets[0].displayName) and !hasSetTextAction()).performClick()
        compose.waitUntil(TIMEOUT) { model.uiState.value.selectedContact?.id == "alice" }
        val changed = richBefore.copy(values = richBefore.values +
            ContactValue("alice-new-phone", ContactValueKind.PHONE, "+33102030405", order = 4))
        assertTrue(runBlocking { repository.saveContact(changed) } is SaveResult.Saved)
        compose.waitUntil(TIMEOUT) {
            val state = model.uiState.value
            state.directoryContacts.isEmpty() && state.selectedContact?.values?.any {
                it.id == "alice-new-phone"
            } == true
        }
        assertEquals("alice", model.uiState.value.navigation.current.selectedId)
        compose.onAllNodesWithText(targets[0].displayName).onFirst().assertIsDisplayed()
        compose.runOnIdle { assertTrue(model.handleBack()) }
        compose.waitUntil(TIMEOUT) { model.uiState.value.navigation.current.selectedId == null }
        compose.onNodeWithText(text(R.string.directory_filtered_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.directory_clear_filters)).performClick()
        waitForIds(model, setOf("alice"))
        assertEquals("Aster Alice", model.uiState.value.query)
        assertFalse(model.uiState.value.directoryFilter.isActive)
        compose.onNode(hasText(targets[0].displayName) and !hasSetTextAction()).assertIsDisplayed()

        // The group search must still find membership attached only to the secondary email.
        compose.runOnIdle { model.updateQuery(group.name) }
        waitForIds(model, setOf("alice"))
        runBlocking {
            val saved = requireNotNull(repository.getContact(ACCOUNT, "alice"))
            assertEquals(richBefore.preservationEnvelope, saved.preservationEnvelope)
            assertEquals(richBefore.values.associateBy { it.id },
                saved.values.filterNot { it.id == "alice-new-phone" }.associateBy { it.id })
            assertEquals(foreignBefore, repository.getContact(FOREIGN, foreign.id))
            assertEquals(groupsBefore.associate { it.id to it.memberships },
                repository.observeGroups(ACCOUNT).first().associate { it.id to it.memberships })
            assertEquals(setOf("alice", group.id), db.outboxDao().getAll(ACCOUNT).map { it.aggregateId }.toSet())
        }
    }

    @Test fun reopeningDatabaseAndNewViewModelPreservesObservationButStartsWithoutFilters() {
        var repository = seedAndShow()
        val model = requireNotNull(shownModel)
        // Direct import fixture setup creates no invented change dates.
        compose.runOnIdle { model.setDirectoryFilter(DirectoryFilter(recentChanges = true)) }
        waitForIds(model, emptySet())
        val before = runBlocking { requireNotNull(repository.getContact(ACCOUNT, "alice")) }
        assertTrue(runBlocking {
            repository.saveContact(before.copy(values = before.values +
                ContactValue("alice-note", ContactValueKind.NOTE, "Synthetic local field edit", order = 4)))
        } is SaveResult.Saved)
        waitForIds(model, setOf("alice"))
        val observation = runBlocking { requireNotNull(database).contactContentChangesDao().get(ACCOUNT, "alice") }
        assertNotNull(observation)
        compose.runOnIdle {
            model.setDirectoryFilter(DirectoryFilter(withoutPhone = true, organizations = setOf(ORGANIZATION_KEY),
                recentChanges = true))
            model.updateQuery("Aster Alice")
        }
        waitForIds(model, setOf("alice"))
        assertTrue(model.uiState.value.directoryFilter.isActive)

        // A fresh VM and SQLite reopen are tested; this does not simulate OS process death or rotation.
        disposeModel()
        requireNotNull(database).close()
        val reopened = ContakoDatabase.create(context, requireNotNull(databaseName)).also { database = it }
        repository = RoomContactRepository(reopened, calibrationProvider = { null })
        val fresh = showModel(repository)
        waitForIds(fresh, allIds())
        assertEquals(DirectoryFilter(), fresh.uiState.value.directoryFilter)
        assertEquals(DirectoryFilter(), fresh.uiState.value.directoryFilterDraft)
        assertEquals("", fresh.uiState.value.query)
        assertFalse(fresh.uiState.value.showDirectoryFilters)
        assertFalse(fresh.uiState.value.organizationFacets.any { it.label == "Foreign company canary" })
        runBlocking {
            assertEquals(observation, reopened.contactContentChangesDao().get(ACCOUNT, "alice"))
            assertEquals(before.preservationEnvelope, repository.getContact(ACCOUNT, "alice")?.preservationEnvelope)
            assertEquals(foreign, repository.getContact(FOREIGN, foreign.id))
            assertEquals(setOf("alice", group.id), reopened.outboxDao().getAll(ACCOUNT).map { it.aggregateId }.toSet())
        }
    }

    private fun seedAndShow(): RoomContactRepository {
        val name = "directory-filter-integration-${SystemClock.elapsedRealtimeNanos()}.db"
        databaseName = name
        val db = ContakoDatabase.create(context, name).also { database = it }
        runBlocking {
            db.withTransaction {
                val contacts = targets + (3 until 300).map { index ->
                    contact("filler-$index", "Zed fixture $index", "Fixture company", phone = true)
                } + foreign
                contacts.forEach { contact ->
                    db.contactDao().upsert(contact.toEntity())
                    db.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
                    contact.preservationEnvelope?.let {
                        db.contactPayloadDao().upsert(it.toEntity(contact.accountId, contact.id))
                    }
                }
                db.contactGroupDao().upsert(group.toEntity())
                db.contactGroupDao().insertMemberships(group.memberships.map { it.toEntity(group) })
            }
        }
        compose.setContent { shownModel?.let { model -> ContakoTheme { ContakoApp(model) } } }
        return RoomContactRepository(db, calibrationProvider = { null }).also { repository ->
            waitForIds(showModel(repository), allIds())
        }
    }

    private fun showModel(repository: RoomContactRepository): ContactsViewModel = compose.runOnIdle {
        val owner = ViewModelStore().also { store = it }
        ViewModelProvider(owner, ContactsViewModel.factory(repository = repository, accountId = ACCOUNT,
            navigationState = SavedStateHandle()))[ContactsViewModel::class.java].also { shownModel = it }
    }

    private fun disposeModel() {
        compose.runOnIdle { shownModel = null }
        compose.waitForIdle()
        compose.runOnIdle { store?.clear(); store = null }
    }

    private fun waitForIds(model: ContactsViewModel, expected: Set<String>) {
        compose.waitUntil(TIMEOUT) {
            val state = model.uiState.value
            !state.directoryLoading && !state.directoryReadFailed &&
                state.directoryContacts.map { it.id }.toSet() == expected
        }
        compose.waitForIdle()
    }

    private fun allIds() = targets.map { it.id }.toSet() + (3 until 300).map { "filler-$it" }
    private fun text(id: Int) = compose.activity.getString(id)

    private fun contact(id: String, name: String, organization: String, phone: Boolean) = CanonicalContact(
        ACCOUNT, id, displayName = name, revision = 1, values = listOf(
            ContactValue("$id-org", ContactValueKind.ORGANIZATION, organization, order = 0),
            ContactValue("$id-primary", ContactValueKind.EMAIL, "$id@example.test", order = 1, isPrimary = true),
            ContactValue("$id-secondary", ContactValueKind.EMAIL, "$id-secondary@example.test", order = 2),
            ContactValue("$id-opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "synthetic opaque value", order = 3,
                metadata = mapOf("property" to "X-FIXTURE")),
        ) + if (phone) listOf(ContactValue("$id-phone", ContactValueKind.PHONE, "+33102030405", order = 4))
            else emptyList(),
        preservationEnvelope = PreservationEnvelope(mapOf("X-FIXTURE" to "Synthetic retained envelope")),
    )

    companion object {
        private const val ACCOUNT = "directory-filter-fixture-owner"
        private const val FOREIGN = "directory-filter-foreign-owner"
        private const val TIMEOUT = 15_000L
        private val ORGANIZATION_KEY = ContactSearch.normalize("École Libre")
    }
}
