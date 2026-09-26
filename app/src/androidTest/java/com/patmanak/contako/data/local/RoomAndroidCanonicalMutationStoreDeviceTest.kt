package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.repository.SaveValidationIssue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAndroidCanonicalMutationStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun deltaUsesCanonicalRulesAndDoesNotAnnounceAnOuterCommit() = runBlocking {
        seedContactAndGroup()
        val checkpoints = mutableListOf<LocalMutationCheckpoint>()
        val repository = repository(
            checkpointHook = LocalMutationCheckpointHook { checkpoints += it },
        )
        val store = RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)
        val baseline = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
        checkpoints.clear()

        val result = database.withTransaction {
            store.applyContactDelta(
                expectedCanonicalRevision = 1L,
                contact = baseline.copy(
                    firstName = "Android edit",
                    values = baseline.values.filterNot { it.id == REMOVED_EMAIL_ID },
                    preservationEnvelope = PreservationEnvelope(
                        rawProperties = mapOf("raw-new" to "X-CUSTOM:new"),
                        remoteBaseline = "new-baseline",
                    ),
                ),
            )
        }

        assertTrue(result is AndroidCanonicalMutationResult.Applied)
        val stored = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
        assertEquals(2L, stored.revision)
        assertEquals("Android edit", stored.firstName)
        assertEquals(listOf(KEPT_EMAIL_ID), stored.values.map(ContactValue::id))
        assertEquals("new-baseline", stored.preservationEnvelope?.remoteBaseline)
        val group = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_ID))
        assertEquals(2L, group.group.revision)
        assertTrue(group.memberships.isEmpty())
        assertEquals(
            2L,
            database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID)?.revision,
        )
        assertEquals(
            2L,
            database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP_ID)?.revision,
        )
        assertFalse(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_COMMIT in checkpoints)
    }

    @Test
    fun callerRollbackRestoresAggregateValuesPayloadAssignmentsAndOutbox() = runBlocking {
        seedContactAndGroup()
        val repository = repository(
            checkpointHook = LocalMutationCheckpointHook { checkpoint ->
                if (checkpoint == LocalMutationCheckpoint.CONTACT_SAVE_ASSIGNMENT_AFTER_OUTBOX) {
                    throw InjectedFailure
                }
            },
        )
        val store = RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)
        val baseline = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))

        try {
            database.withTransaction {
                store.applyContactDelta(
                    expectedCanonicalRevision = 1L,
                    contact = baseline.copy(
                        firstName = "Must roll back",
                        values = baseline.values.filterNot { it.id == REMOVED_EMAIL_ID },
                        preservationEnvelope = PreservationEnvelope(remoteBaseline = "must-roll-back"),
                    ),
                )
            }
            fail("Expected the injected transaction failure")
        } catch (expected: InjectedFailure) {
            // Expected: the exception aborts the caller-owned transaction.
        }

        val restored = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
        assertEquals("Baseline", restored.firstName)
        assertEquals(1L, restored.revision)
        assertEquals(setOf(KEPT_EMAIL_ID, REMOVED_EMAIL_ID), restored.values.map(ContactValue::id).toSet())
        assertEquals("old-baseline", restored.preservationEnvelope?.remoteBaseline)
        val group = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP_ID))
        assertEquals(1L, group.group.revision)
        assertEquals(listOf(REMOVED_EMAIL_ID), group.memberships.map(GroupMembershipEntity::emailValueId))
        assertEquals(
            1L,
            database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID)?.revision,
        )
        assertEquals(
            1L,
            database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP_ID)?.revision,
        )
    }

    @Test
    fun staleAndRejectedDeltasLeaveCanonicalStateUntouched() = runBlocking {
        seedContactAndGroup()
        val repository = repository()
        val store = RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)
        val baseline = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))

        val stale = database.withTransaction {
            store.applyContactDelta(0L, baseline.copy(firstName = "Stale"))
        }
        assertTrue(stale is AndroidCanonicalMutationResult.Stale)

        val duplicate = baseline.values.first().copy(id = "duplicate", order = 0)
        val rejected = database.withTransaction {
            store.applyContactDelta(
                1L,
                baseline.copy(values = listOf(duplicate, duplicate)),
            )
        }
        assertEquals(
            setOf(SaveValidationIssue.DUPLICATE_VALUE_ID, SaveValidationIssue.DUPLICATE_VALUE_ORDER),
            (rejected as AndroidCanonicalMutationResult.Rejected).issues,
        )

        val restored = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
        assertEquals("Baseline", restored.firstName)
        assertEquals(1L, restored.revision)
        assertEquals(1L, database.outboxDao().getAll(ACCOUNT.value).first { it.aggregateId == CONTACT_ID }.revision)
    }

    @Test
    fun deletionUsesRevisionCasAndRemainsInsideCallerTransaction() = runBlocking {
        seedContactAndGroup()
        val checkpoints = mutableListOf<LocalMutationCheckpoint>()
        val repository = repository(LocalMutationCheckpointHook { checkpoints += it })
        val store = RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)
        checkpoints.clear()

        val result = database.withTransaction {
            store.applyDeletion(expectedCanonicalRevision = 1L, contactId = CONTACT_ID)
        }

        assertTrue(result is AndroidCanonicalMutationResult.Applied)
        val tombstone = requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID))
        assertTrue(tombstone.isDeleted)
        assertEquals(2L, tombstone.revision)
        assertTrue(tombstone.values.isEmpty())
        assertEquals(MutationOperation.DELETE.name, database.outboxDao().getAll(ACCOUNT.value)
            .first { it.aggregateId == CONTACT_ID }.operation)
        assertFalse(LocalMutationCheckpoint.CONTACT_DELETE_AFTER_COMMIT in checkpoints)

        val stale = database.withTransaction {
            store.applyDeletion(expectedCanonicalRevision = 1L, contactId = CONTACT_ID)
        }
        assertTrue(stale is AndroidCanonicalMutationResult.Stale)
        assertEquals(2L, requireNotNull(repository.getContact(ACCOUNT.value, CONTACT_ID)).revision)
    }

    @Test
    fun stagedAndroidShellIsIdempotentHiddenAndOutboxFreeUntilFinalDelta() = runBlocking {
        val repository = repository()
        val store = RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)

        val first = database.withTransaction { store.stageAndroidCreatedShell(STAGED_ID) }
        val second = database.withTransaction { store.stageAndroidCreatedShell(STAGED_ID) }

        assertTrue(first is AndroidCanonicalMutationResult.Applied)
        assertTrue(second is AndroidCanonicalMutationResult.Applied)
        val shell = requireNotNull(repository.getContact(ACCOUNT.value, STAGED_ID))
        assertTrue(shell.isDeleted)
        assertFalse(shell.isUploadEligible)
        assertEquals(0L, shell.revision)
        assertEquals("ANDROID_INGESTION_STAGED", shell.conflictState)
        assertTrue(repository.observeContacts(ACCOUNT.value).first().isEmpty())
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())

        val finalized = database.withTransaction {
            store.applyContactDelta(
                expectedCanonicalRevision = 0L,
                contact = CanonicalContact(
                    accountId = ACCOUNT.value,
                    id = STAGED_ID,
                    firstName = "Created on Android",
                ),
            )
        }

        assertTrue(finalized is AndroidCanonicalMutationResult.Applied)
        val active = requireNotNull(repository.getContact(ACCOUNT.value, STAGED_ID))
        assertFalse(active.isDeleted)
        assertEquals(1L, active.revision)
        assertEquals("Created on Android", active.firstName)
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
    }

    @Test
    fun stagedShellDoesNotReplaceAnExistingCanonicalContact() = runBlocking {
        val repository = repository()
        repository.saveContact(contact()).requireSavedForTest()
        val store = RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)

        val result = database.withTransaction { store.stageAndroidCreatedShell(CONTACT_ID) }

        assertTrue(result is AndroidCanonicalMutationResult.Stale)
        assertEquals("Baseline", repository.getContact(ACCOUNT.value, CONTACT_ID)?.firstName)
        assertEquals(1L, repository.getContact(ACCOUNT.value, CONTACT_ID)?.revision)
    }

    private suspend fun seedContactAndGroup() {
        val repository = repository()
        repository.saveContact(contact()).requireSavedForTest()
        repository.saveGroup(
            ContactGroup(
                accountId = ACCOUNT.value,
                id = GROUP_ID,
                name = "Group",
                memberships = listOf(GroupMembership(CONTACT_ID, REMOVED_EMAIL_ID)),
            ),
        ).requireSavedForTest()
    }

    private fun repository(
        checkpointHook: LocalMutationCheckpointHook = LocalMutationCheckpointHook.NONE,
    ) = RoomContactRepository(
        database = database,
        clock = { 2_000L },
        elapsedRealtimeClock = { 2_000L },
        checkpointHook = checkpointHook,
    )

    private fun contact() = CanonicalContact(
        accountId = ACCOUNT.value,
        id = CONTACT_ID,
        firstName = "Baseline",
        values = listOf(
            ContactValue(
                id = KEPT_EMAIL_ID,
                kind = ContactValueKind.EMAIL,
                value = "kept@example.test",
                order = 0,
                isPrimary = true,
            ),
            ContactValue(
                id = REMOVED_EMAIL_ID,
                kind = ContactValueKind.EMAIL,
                value = "removed@example.test",
                order = 1,
            ),
        ),
        preservationEnvelope = PreservationEnvelope(
            rawProperties = mapOf("raw-old" to "X-CUSTOM:old"),
            remoteBaseline = "old-baseline",
        ),
    )

    private object InjectedFailure : RuntimeException()

    private companion object {
        const val DATABASE_NAME = "android-canonical-mutation-store.db"
        val ACCOUNT = AccountScope("account")
        const val CONTACT_ID = "contact"
        const val STAGED_ID = "staged"
        const val GROUP_ID = "group"
        const val KEPT_EMAIL_ID = "email-kept"
        const val REMOVED_EMAIL_ID = "email-removed"
    }
}
