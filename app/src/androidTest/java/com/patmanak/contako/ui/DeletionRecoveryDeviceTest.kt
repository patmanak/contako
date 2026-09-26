package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.ContactRepository
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.ui.theme.ContakoTheme
import java.io.IOException
import kotlinx.coroutines.flow.first
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

    private companion object { const val DATABASE = "qa26-deletion-recovery.db" }
}
