package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAtomicCheckpointMatrixTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun contactCreateIsAtomicAtEveryCheckpoint() = runBlocking {
        CONTACT_SAVE_CHECKPOINTS.forEach { checkpoint ->
            runScenario("contact-create", checkpoint, mutation = { database ->
                val result = runCatching {
                    faultingRepository(database, checkpoint).saveContact(contact("contact", "Created"))
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val stored = database.contactDao().get(ACCOUNT, "contact")
                val outbox = database.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, "contact")
                if (checkpoint.isAfterCommit) {
                    assertEquals("Created", stored?.contact?.firstName)
                    assertEquals(1L, stored?.contact?.revision)
                    assertEquals("created@example.test", stored?.values?.single()?.value)
                    assertEquals("baseline-Created", payload(database, "contact")?.remoteBaseline)
                    assertEquals(1L, outbox?.revision)
                    assertEquals(MutationOperation.UPSERT.name, outbox?.operation)
                } else {
                    assertNull(stored)
                    assertNull(payload(database, "contact"))
                    assertNull(outbox)
                }
            })
        }
    }

    @Test
    fun contactUpdateIsAtomicAtEveryCheckpoint() = runBlocking {
        CONTACT_SAVE_CHECKPOINTS.forEach { checkpoint ->
            runScenario("contact-update", checkpoint, setup = { database ->
                seedContact(database, contact("contact", "Before"))
            }, mutation = { database ->
                val before = requireNotNull(database.contactDao().get(ACCOUNT, "contact")).toDomainForTest()
                val updated = before.copy(
                    firstName = "After",
                    values = before.values.map { it.copy(value = "after@example.test") },
                    preservationEnvelope = PreservationEnvelope(remoteBaseline = "baseline-After"),
                )
                val result = runCatching {
                    faultingRepository(database, checkpoint).saveContact(updated)
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val stored = requireNotNull(database.contactDao().get(ACCOUNT, "contact"))
                val outbox = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, "contact"),
                )
                if (checkpoint.isAfterCommit) {
                    assertEquals("After", stored.contact.firstName)
                    assertEquals("after@example.test", stored.values.single().value)
                    assertEquals("baseline-After", payload(database, "contact")?.remoteBaseline)
                    assertEquals(2L, stored.contact.revision)
                    assertEquals(2L, outbox.revision)
                } else {
                    assertEquals("Before", stored.contact.firstName)
                    assertEquals("created@example.test", stored.values.single().value)
                    assertEquals("baseline-Before", payload(database, "contact")?.remoteBaseline)
                    assertEquals(1L, stored.contact.revision)
                    assertEquals(1L, outbox.revision)
                }
                assertEquals(1, database.outboxDao().getAll(ACCOUNT).count { it.aggregateId == "contact" })
            })
        }
    }

    @Test
    fun contactDeleteIsAtomicAtEveryCheckpoint() = runBlocking {
        CONTACT_DELETE_CHECKPOINTS.forEach { checkpoint ->
            runScenario("contact-delete", checkpoint, setup = { database ->
                seedContact(database, contact("contact", "Before"))
            }, mutation = { database ->
                val result = runCatching {
                    faultingRepository(database, checkpoint).deleteContact(ACCOUNT, "contact")
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val stored = requireNotNull(database.contactDao().get(ACCOUNT, "contact"))
                val outbox = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, "contact"),
                )
                if (checkpoint.isAfterCommit) {
                    assertTrue(stored.contact.isDeleted)
                    assertTrue(stored.values.isEmpty())
                    assertEquals(2L, stored.contact.revision)
                    assertEquals(MutationOperation.DELETE.name, outbox.operation)
                    assertEquals(2L, outbox.revision)
                } else {
                    assertFalse(stored.contact.isDeleted)
                    assertEquals("created@example.test", stored.values.single().value)
                    assertEquals(1L, stored.contact.revision)
                    assertEquals(MutationOperation.UPSERT.name, outbox.operation)
                    assertEquals(1L, outbox.revision)
                }
                assertEquals("baseline-Before", payload(database, "contact")?.remoteBaseline)
            })
        }
    }

    @Test
    fun groupCreateIsAtomicAtEveryCheckpoint() = runBlocking {
        GROUP_SAVE_CHECKPOINTS.forEach { checkpoint ->
            runScenario("group-create", checkpoint, setup = { database ->
                seedContact(database, contact("member", "Member"))
            }, mutation = { database ->
                val result = runCatching {
                    faultingRepository(database, checkpoint).saveGroup(group("group", "Created group"))
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val stored = database.contactGroupDao().get(ACCOUNT, "group")
                val outbox = database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, "group")
                if (checkpoint.isAfterCommit) {
                    assertEquals("Created group", stored?.group?.name)
                    assertEquals(1, stored?.memberships?.size)
                    assertEquals(1L, stored?.group?.revision)
                    assertEquals(1L, outbox?.revision)
                } else {
                    assertNull(stored)
                    assertNull(outbox)
                }
                assertNotNull(database.contactDao().get(ACCOUNT, "member"))
            })
        }
    }

    @Test
    fun groupUpdateIsAtomicAtEveryCheckpoint() = runBlocking {
        GROUP_SAVE_CHECKPOINTS.forEach { checkpoint ->
            runScenario("group-update", checkpoint, setup = { database ->
                seedContact(database, contact("member", "Member"))
                seedGroup(database, group("group", "Before group"))
            }, mutation = { database ->
                val before = requireNotNull(database.contactGroupDao().get(ACCOUNT, "group"))
                val updated = before.toDomainForTest().copy(name = "After group", memberships = emptyList())
                val result = runCatching {
                    faultingRepository(database, checkpoint).saveGroup(updated)
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val stored = requireNotNull(database.contactGroupDao().get(ACCOUNT, "group"))
                val outbox = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, "group"),
                )
                if (checkpoint.isAfterCommit) {
                    assertEquals("After group", stored.group.name)
                    assertTrue(stored.memberships.isEmpty())
                    assertEquals(2L, stored.group.revision)
                    assertEquals(2L, outbox.revision)
                } else {
                    assertEquals("Before group", stored.group.name)
                    assertEquals(1, stored.memberships.size)
                    assertEquals(1L, stored.group.revision)
                    assertEquals(1L, outbox.revision)
                }
            })
        }
    }

    @Test
    fun groupDeleteIsAtomicAtEveryCheckpoint() = runBlocking {
        GROUP_DELETE_CHECKPOINTS.forEach { checkpoint ->
            runScenario("group-delete", checkpoint, setup = { database ->
                seedContact(database, contact("member", "Member"))
                seedGroup(database, group("group", "Before group"))
            }, mutation = { database ->
                val result = runCatching {
                    faultingRepository(database, checkpoint).deleteGroup(ACCOUNT, "group")
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val stored = requireNotNull(database.contactGroupDao().get(ACCOUNT, "group"))
                val outbox = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, "group"),
                )
                if (checkpoint.isAfterCommit) {
                    assertTrue(stored.group.isDeleted)
                    assertTrue(stored.memberships.isEmpty())
                    assertEquals(2L, stored.group.revision)
                    assertEquals(MutationOperation.DELETE.name, outbox.operation)
                    assertEquals(2L, outbox.revision)
                } else {
                    assertFalse(stored.group.isDeleted)
                    assertEquals(1, stored.memberships.size)
                    assertEquals(1L, stored.group.revision)
                    assertEquals(MutationOperation.UPSERT.name, outbox.operation)
                    assertEquals(1L, outbox.revision)
                }
                assertNotNull(database.contactDao().get(ACCOUNT, "member"))
            })
        }
    }

    @Test
    fun contactEmailRemovalAndAssignmentIntentsAreAtomic() = runBlocking {
        CONTACT_SAVE_ASSIGNMENT_CHECKPOINTS.forEach { checkpoint ->
            runScenario("contact-assignment-update", checkpoint, setup = { database ->
                seedContact(database, contact("member", "Member"))
                seedGroup(database, group("group", "Member group"))
            }, mutation = { database ->
                val before = requireNotNull(database.contactDao().get(ACCOUNT, "member")).toDomainForTest()
                val result = runCatching {
                    faultingRepository(database, checkpoint).saveContact(before.copy(values = emptyList()))
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val contact = requireNotNull(database.contactDao().get(ACCOUNT, "member"))
                val group = requireNotNull(database.contactGroupDao().get(ACCOUNT, "group"))
                val contactIntent = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, "member"),
                )
                val groupIntent = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, "group"),
                )
                if (checkpoint.isAfterCommit) {
                    assertTrue(contact.values.isEmpty())
                    assertEquals(2L, contact.contact.revision)
                    assertTrue(group.memberships.isEmpty())
                    assertEquals(2L, group.group.revision)
                    assertEquals(2L, contactIntent.revision)
                    assertEquals(MutationOperation.UPSERT.name, contactIntent.operation)
                    assertEquals(2L, groupIntent.revision)
                    assertEquals(MutationOperation.UPSERT.name, groupIntent.operation)
                } else {
                    assertEquals(1, contact.values.size)
                    assertEquals(1L, contact.contact.revision)
                    assertEquals(1, group.memberships.size)
                    assertEquals(1L, group.group.revision)
                    assertEquals(1L, contactIntent.revision)
                    assertEquals(1L, groupIntent.revision)
                }
                assertExactlyOneIntent(database, AggregateType.CONTACT, "member")
                assertExactlyOneIntent(database, AggregateType.GROUP, "group")
            })
        }
    }

    @Test
    fun contactMemberDeleteAndAssignmentIntentsAreAtomic() = runBlocking {
        CONTACT_DELETE_ASSIGNMENT_CHECKPOINTS.forEach { checkpoint ->
            runScenario("contact-assignment-delete", checkpoint, setup = { database ->
                seedContact(database, contact("member", "Member"))
                seedGroup(database, group("group", "Member group"))
            }, mutation = { database ->
                val result = runCatching {
                    faultingRepository(database, checkpoint).deleteContact(ACCOUNT, "member")
                }
                assertInjected(result, checkpoint)
            }, verify = { database ->
                val contact = requireNotNull(database.contactDao().get(ACCOUNT, "member"))
                val group = requireNotNull(database.contactGroupDao().get(ACCOUNT, "group"))
                val contactIntent = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, "member"),
                )
                val groupIntent = requireNotNull(
                    database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, "group"),
                )
                if (checkpoint.isAfterCommit) {
                    assertTrue(contact.contact.isDeleted)
                    assertTrue(contact.values.isEmpty())
                    assertEquals(2L, contact.contact.revision)
                    assertTrue(group.memberships.isEmpty())
                    assertEquals(2L, group.group.revision)
                    assertEquals(MutationOperation.DELETE.name, contactIntent.operation)
                    assertEquals(2L, contactIntent.revision)
                    assertEquals(MutationOperation.UPSERT.name, groupIntent.operation)
                    assertEquals(2L, groupIntent.revision)
                } else {
                    assertFalse(contact.contact.isDeleted)
                    assertEquals(1, contact.values.size)
                    assertEquals(1L, contact.contact.revision)
                    assertEquals(1, group.memberships.size)
                    assertEquals(1L, group.group.revision)
                    assertEquals(MutationOperation.UPSERT.name, contactIntent.operation)
                    assertEquals(1L, contactIntent.revision)
                    assertEquals(1L, groupIntent.revision)
                }
                assertExactlyOneIntent(database, AggregateType.CONTACT, "member")
                assertExactlyOneIntent(database, AggregateType.GROUP, "group")
            })
        }
    }

    @Test
    fun contactEmailRemovalRollsBackAcrossTwoGroupsAtOrdinalCheckpoints() = runBlocking {
        MULTI_GROUP_SAVE_FAULTS.forEach { fault ->
            runScenario("contact-multigroup-update-${fault.checkpoint.name.lowercase()}", fault.checkpoint,
                setup = { database -> seedTwoGroupMemberships(database) },
                mutation = { database ->
                    val before = requireNotNull(database.contactDao().get(ACCOUNT, "member")).toDomainForTest()
                    val result = runCatching {
                        faultingRepository(database, fault.checkpoint, fault.occurrence)
                            .saveContact(before.copy(values = emptyList()))
                    }
                    assertInjected(result, fault.checkpoint)
                },
                verify = { database -> verifyTwoGroupBaseline(database) },
            )
        }
    }

    @Test
    fun contactDeleteRollsBackAcrossTwoGroupsAtOrdinalCheckpoints() = runBlocking {
        MULTI_GROUP_DELETE_FAULTS.forEach { fault ->
            runScenario("contact-multigroup-delete-${fault.checkpoint.name.lowercase()}", fault.checkpoint,
                setup = { database -> seedTwoGroupMemberships(database) },
                mutation = { database ->
                    val result = runCatching {
                        faultingRepository(database, fault.checkpoint, fault.occurrence)
                            .deleteContact(ACCOUNT, "member")
                    }
                    assertInjected(result, fault.checkpoint)
                },
                verify = { database -> verifyTwoGroupBaseline(database) },
            )
        }
    }

    private suspend fun runScenario(
        operation: String,
        checkpoint: LocalMutationCheckpoint,
        setup: suspend (ContakoDatabase) -> Unit = {},
        mutation: suspend (ContakoDatabase) -> Unit,
        verify: suspend (ContakoDatabase) -> Unit,
    ) {
        val databaseName = "atomic-$operation-${checkpoint.name.lowercase()}.db"
        context.deleteDatabase(databaseName)
        var database = ContakoDatabase.create(context, databaseName)
        try {
            setup(database)
            mutation(database)
            database.close()
            repeat(2) {
                database = ContakoDatabase.create(context, databaseName)
                verify(database)
                database.close()
            }
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun faultingRepository(
        database: ContakoDatabase,
        faultAt: LocalMutationCheckpoint,
        occurrence: Int = 1,
    ) = RoomContactRepository(
        database = database,
        clock = { 20_000L },
        idFactory = { "generated" },
        checkpointHook = object : LocalMutationCheckpointHook {
            private var matchCount = 0

            override fun onCheckpoint(checkpoint: LocalMutationCheckpoint) {
                if (checkpoint == faultAt && ++matchCount == occurrence) {
                    throw InjectedCheckpointFailure(checkpoint)
                }
            }
        },
    )

    private suspend fun seedTwoGroupMemberships(database: ContakoDatabase) {
        seedContact(database, contact("member", "Member"))
        seedGroup(database, group("group-a", "Group A"))
        seedGroup(database, group("group-b", "Group B"))
    }

    private suspend fun verifyTwoGroupBaseline(database: ContakoDatabase) {
        val contact = requireNotNull(database.contactDao().get(ACCOUNT, "member"))
        assertFalse(contact.contact.isDeleted)
        assertEquals(1, contact.values.size)
        assertEquals(1L, contact.contact.revision)
        listOf("group-a", "group-b").forEach { groupId ->
            val group = requireNotNull(database.contactGroupDao().get(ACCOUNT, groupId))
            assertFalse(group.group.isDeleted)
            assertEquals(1L, group.group.revision)
            assertEquals(1, group.memberships.size)
            assertExactlyOneIntent(database, AggregateType.GROUP, groupId)
            assertEquals(
                1L,
                database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, groupId)?.revision,
            )
        }
        assertExactlyOneIntent(database, AggregateType.CONTACT, "member")
        assertEquals(
            1L,
            database.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, "member")?.revision,
        )
        assertEquals(3, database.outboxDao().getAll(ACCOUNT).size)
    }

    private suspend fun seedContact(database: ContakoDatabase, contact: CanonicalContact) {
        val result = RoomContactRepository(database, clock = { 10_000L }).saveContact(contact)
        assertTrue(result is SaveResult.Saved)
    }

    private suspend fun seedGroup(database: ContakoDatabase, group: ContactGroup) {
        val result = RoomContactRepository(database, clock = { 10_000L }).saveGroup(group)
        assertTrue(result is SaveResult.Saved)
    }

    private fun contact(id: String, name: String) = CanonicalContact(
        accountId = ACCOUNT,
        id = id,
        firstName = name,
        values = listOf(
            ContactValue(
                id = "$id-email",
                kind = ContactValueKind.EMAIL,
                value = "created@example.test",
                order = 0,
            ),
        ),
        preservationEnvelope = PreservationEnvelope(remoteBaseline = "baseline-$name"),
    )

    private fun group(id: String, name: String) = ContactGroup(
        accountId = ACCOUNT,
        id = id,
        name = name,
        memberships = listOf(GroupMembership("member", "member-email")),
    )

    private fun assertInjected(result: Result<*>, checkpoint: LocalMutationCheckpoint) {
        val failure = result.exceptionOrNull()
        assertTrue("Checkpoint $checkpoint was not reached", failure is InjectedCheckpointFailure)
        assertEquals(checkpoint, (failure as InjectedCheckpointFailure).checkpoint)
    }

    private suspend fun payload(database: ContakoDatabase, contactId: String) =
        database.contactPayloadDao().get("$ACCOUNT\u0000$contactId")

    private suspend fun assertExactlyOneIntent(
        database: ContakoDatabase,
        type: AggregateType,
        aggregateId: String,
    ) {
        assertEquals(
            1,
            database.outboxDao().getAll(ACCOUNT).count {
                it.aggregateType == type.name && it.aggregateId == aggregateId
            },
        )
    }

    private val LocalMutationCheckpoint.isAfterCommit: Boolean
        get() = name.endsWith("_AFTER_COMMIT")

    private class InjectedCheckpointFailure(val checkpoint: LocalMutationCheckpoint) :
        RuntimeException(checkpoint.name)

    private data class OrdinalFault(
        val checkpoint: LocalMutationCheckpoint,
        val occurrence: Int,
    )

    private companion object {
        const val ACCOUNT = "atomic@example.test"

        val CONTACT_SAVE_CHECKPOINTS = LocalMutationCheckpoint.entries
            .filter { it.name.startsWith("CONTACT_SAVE_") && "_ASSIGNMENT_" !in it.name }
        val CONTACT_DELETE_CHECKPOINTS = LocalMutationCheckpoint.entries
            .filter { it.name.startsWith("CONTACT_DELETE_") && "_ASSIGNMENT_" !in it.name }
        val CONTACT_SAVE_ASSIGNMENT_CHECKPOINTS = LocalMutationCheckpoint.entries.filter {
            it.name.startsWith("CONTACT_SAVE_ASSIGNMENT_") ||
                it == LocalMutationCheckpoint.CONTACT_SAVE_AFTER_COMMIT
        }
        val CONTACT_DELETE_ASSIGNMENT_CHECKPOINTS = LocalMutationCheckpoint.entries.filter {
            it.name.startsWith("CONTACT_DELETE_ASSIGNMENT_") ||
                it == LocalMutationCheckpoint.CONTACT_DELETE_AFTER_COMMIT
        }
        val GROUP_SAVE_CHECKPOINTS = LocalMutationCheckpoint.entries
            .filter { it.name.startsWith("GROUP_SAVE_") }
        val GROUP_DELETE_CHECKPOINTS = LocalMutationCheckpoint.entries
            .filter { it.name.startsWith("GROUP_DELETE_") }
        val MULTI_GROUP_SAVE_FAULTS = listOf(
            OrdinalFault(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_VALUES, 1),
            OrdinalFault(LocalMutationCheckpoint.CONTACT_SAVE_ASSIGNMENT_BEFORE_GROUP, 2),
            OrdinalFault(LocalMutationCheckpoint.CONTACT_SAVE_ASSIGNMENT_AFTER_GROUP, 2),
            OrdinalFault(LocalMutationCheckpoint.CONTACT_SAVE_ASSIGNMENT_AFTER_OUTBOX, 2),
        )
        val MULTI_GROUP_DELETE_FAULTS = listOf(
            OrdinalFault(LocalMutationCheckpoint.CONTACT_DELETE_AFTER_VALUES, 1),
            OrdinalFault(LocalMutationCheckpoint.CONTACT_DELETE_ASSIGNMENT_BEFORE_GROUP, 2),
            OrdinalFault(LocalMutationCheckpoint.CONTACT_DELETE_ASSIGNMENT_AFTER_GROUP, 2),
            OrdinalFault(LocalMutationCheckpoint.CONTACT_DELETE_ASSIGNMENT_AFTER_OUTBOX, 2),
        )
    }
}

private fun ContactWithValues.toDomainForTest() = CanonicalContact(
    accountId = contact.accountId,
    id = contact.id,
    firstName = contact.firstName,
    lastName = contact.lastName,
    displayName = contact.displayName,
    values = values.map {
        ContactValue(
            id = it.id,
            kind = ContactValueKind.valueOf(it.kind),
            value = it.value,
            label = it.label,
            order = it.position,
            isPrimary = it.isPrimary,
        )
    },
    revision = contact.revision,
    updatedAtEpochMillis = contact.updatedAtEpochMillis,
    remoteContactId = contact.remoteContactId,
    remoteVCardUid = contact.remoteVCardUid,
    remoteVersion = contact.remoteVersion,
)

private fun ContactGroupWithMemberships.toDomainForTest() = ContactGroup(
    accountId = group.accountId,
    id = group.id,
    name = group.name,
    color = group.color,
    order = group.displayOrder,
    isVisible = group.isVisible,
    revision = group.revision,
    updatedAtEpochMillis = group.updatedAtEpochMillis,
    remoteLabelId = group.remoteLabelId,
    remoteVersion = group.remoteVersion,
    memberships = memberships.map { GroupMembership(it.contactId, it.emailValueId) },
)
