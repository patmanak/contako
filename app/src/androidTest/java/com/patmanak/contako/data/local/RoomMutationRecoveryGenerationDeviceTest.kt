package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room state only; does not provision Android accounts or access a remote service. */
@RunWith(AndroidJUnit4::class)
class RoomMutationRecoveryGenerationDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    @Before fun setup() {
        context.deleteDatabase(NAME)
        database = ContakoDatabase.create(context, NAME)
        repository = RoomContactRepository(database)
    }
    @After fun cleanup() { database.close(); context.deleteDatabase(NAME) }

    @Test fun sessionAndCapabilityBlocksReopenOncePerGenerationWithoutChangingIntent() = runBlocking {
        contact("auth", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED")
        contact("interactive", "HUMAN_VERIFICATION_REQUIRED", "INTERACTIVE_AUTHENTICATION_REQUIRED")
        contact("permission", "PERMISSION_OR_PLAN_DENIED", "REMOTE_PERMISSION_REQUIRED")
        contact("legacy-permission", "PERMISSION_OR_PLAN_DENIED", "GROUP_CAPABILITY_REQUIRED")
        assertTrue(repository.saveGroup(ContactGroup(ACCOUNT, "group", "Fixture group",
            remoteLabelId = "remote-group", remoteVersion = "baseline")) is SaveResult.Saved)
        block("GROUP", "group", "PERMISSION_OR_PLAN_DENIED", "GROUP_CAPABILITY_REQUIRED")
        val before = database.outboxDao().getAll(ACCOUNT).associateBy { it.aggregateId }
        var generation = 5L
        val store = RoomMutationExecutionStore(database, recoveryGeneration = { generation })
        assertEquals(5, store.recoverInterrupted(ACCOUNT, 1_000))
        database.outboxDao().getAll(ACCOUNT).forEach { intent ->
            val original = before.getValue(intent.aggregateId)
            assertEquals(original.copy(state = "PENDING", errorCategory = null, blockedReason = null,
                nextAttemptAtEpochMillis = 1_000), intent)
        }
        block("CONTACT", "auth", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED")
        val reblocked = requireNotNull(database.outboxDao().get(ACCOUNT, "CONTACT", "auth"))
        assertEquals(0, store.recoverInterrupted(ACCOUNT, 2_000))
        assertEquals(reblocked, database.outboxDao().get(ACCOUNT, "CONTACT", "auth"))
        generation++
        assertEquals(1, store.recoverInterrupted(ACCOUNT, 3_000))
        assertEquals(0, store.recoverInterrupted(ACCOUNT, 4_000))
        block("CONTACT", "auth", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED")
        // A newly created process/store permits an uncached recovery probe after READY.
        assertEquals(1, RoomMutationExecutionStore(database, recoveryGeneration = { generation })
            .recoverInterrupted(ACCOUNT, 5_000))
    }

    @Test fun nonrecoverableAndStaleIntentsRemainByteForByteUnchanged() = runBlocking {
        contact("validation", "VALIDATION_REJECTED", "REMOTE_ACTION_REQUIRED")
        contact("conflict", "CONFLICT", "REMOTE_UPDATE_CONFLICT")
        contact("crypto", "CRYPTOGRAPHIC_VERIFICATION_FAILED", "REMOTE_ACTION_REQUIRED")
        contact("wrong-revision", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED")
        contact("wrong-identity", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED")
        contact("wrong-reason", "PERMISSION_OR_PLAN_DENIED", "REMOTE_UPDATE_CONFLICT")
        val stale = requireNotNull(database.contactDao().get(ACCOUNT, "wrong-revision")).contact
        database.contactDao().upsert(stale.copy(revision = stale.revision + 1))
        val rebound = requireNotNull(database.contactDao().get(ACCOUNT, "wrong-identity")).contact
        database.contactDao().upsert(rebound.copy(remoteContactId = "different-remote"))
        val before = database.outboxDao().getAll(ACCOUNT)
        val store = RoomMutationExecutionStore(database, recoveryGeneration = { 7 })
        assertEquals(0, store.recoverInterrupted(ACCOUNT, 1_000))
        assertEquals(before, database.outboxDao().getAll(ACCOUNT))
        assertEquals(0, store.recoverInterrupted("foreign-account", 1_000))
        assertEquals(before, database.outboxDao().getAll(ACCOUNT))
    }

    @Test fun generationInvalidationRollsBackReopeningAndDoesNotConsumeTheNewProbe() = runBlocking {
        contact("auth", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED")
        val before = database.outboxDao().getAll(ACCOUNT)
        var generation = 1L
        var reads = 0
        val store = RoomMutationExecutionStore(database, recoveryGeneration = {
            if (++reads == 3) generation++
            generation
        })
        assertEquals(0, store.recoverInterrupted(ACCOUNT, 1_000))
        assertEquals(before, database.outboxDao().getAll(ACCOUNT))
        assertEquals(1, store.recoverInterrupted(ACCOUNT, 2_000))
        assertEquals(0, store.recoverInterrupted(ACCOUNT, 3_000))
    }

    private suspend fun contact(id: String, category: String, reason: String) {
        assertTrue(repository.saveContact(CanonicalContact(ACCOUNT, id, displayName = "Fixture $id",
            remoteContactId = "remote-$id", remoteVersion = "baseline")) is SaveResult.Saved)
        block("CONTACT", id, category, reason)
    }
    private suspend fun block(type: String, id: String, category: String, reason: String) {
        val intent = requireNotNull(database.outboxDao().get(ACCOUNT, type, id))
        database.outboxDao().upsert(intent.copy(state = "ACTION_REQUIRED", errorCategory = category,
            blockedReason = reason, attemptCount = 3, requiresReconciliation = true))
    }
    private companion object { const val NAME = "mutation-recovery-generation.db"; const val ACCOUNT = "fixture-account" }
}
