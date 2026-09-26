package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomObservedGroupMembershipTransactionDeviceTest {
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
    fun multiGroupDeltaPreservesSecondaryMembershipsAndCompactsOneIntentPerGroup() = runBlocking {
        val repository = seedThreeGroupContext()
        replaceGroupIntent(GROUP_B, MutationOperation.ASSIGNMENTS)
        replaceGroupIntent(GROUP_C, MutationOperation.ASSIGNMENTS)
        val expectedStates = expectedGroupStates()
        val beforeA = requireNotNull(database.contactGroupDao().get(ACCOUNT, GROUP_A)).toDomain()
        val beforeB = requireNotNull(database.contactGroupDao().get(ACCOUNT, GROUP_B)).toDomain()
        val beforeC = requireNotNull(database.contactGroupDao().get(ACCOUNT, GROUP_C)).toDomain()

        val result = database.withTransaction {
            repository.applyObservedGroupMembershipsInCurrentTransaction(
                accountId = ACCOUNT,
                contactId = CONTACT,
                expectedCanonicalRevision = 1L,
                expectedPreferredEmailValueId = PREFERRED_EMAIL,
                expectedGroupStates = expectedStates,
                observedCanonicalGroupIds = setOf(GROUP_A, GROUP_C),
            )
        }

        assertEquals(
            RoomObservedGroupMembershipMutationResult.Applied(listOf(GROUP_A, GROUP_B)),
            result,
        )
        val afterA = requireNotNull(database.contactGroupDao().get(ACCOUNT, GROUP_A)).toDomain()
        val afterB = requireNotNull(database.contactGroupDao().get(ACCOUNT, GROUP_B)).toDomain()
        val afterC = requireNotNull(database.contactGroupDao().get(ACCOUNT, GROUP_C)).toDomain()
        assertEquals(2L, afterA.revision)
        assertEquals(2L, afterB.revision)
        assertEquals(1L, afterC.revision)
        assertEquals(FIXED_NOW, afterA.updatedAtEpochMillis)
        assertEquals(FIXED_NOW, afterB.updatedAtEpochMillis)
        assertEquals(2L, afterA.pendingMutationRevision)
        assertEquals(2L, afterB.pendingMutationRevision)
        assertEquals(beforeA.copy(revision = 2L, updatedAtEpochMillis = FIXED_NOW, pendingMutationRevision = 2L,
            memberships = beforeA.memberships + GroupMembership(CONTACT, PREFERRED_EMAIL)), afterA)
        assertEquals(
            beforeB.copy(
                revision = 2L,
                updatedAtEpochMillis = FIXED_NOW,
                pendingMutationRevision = 2L,
                memberships = beforeB.memberships.filterNot {
                    it == GroupMembership(CONTACT, PREFERRED_EMAIL)
                },
            ),
            afterB,
        )
        assertEquals(beforeC, afterC)
        assertEquals(1L, requireNotNull(database.contactDao().get(ACCOUNT, CONTACT)).contact.revision)

        val intentA = requireNotNull(groupIntent(GROUP_A))
        val intentB = requireNotNull(groupIntent(GROUP_B))
        val intentC = requireNotNull(groupIntent(GROUP_C))
        assertEquals(MutationOperation.UPSERT.name, intentA.operation)
        assertEquals(2L, intentA.revision)
        assertEquals(MutationOperation.ASSIGNMENTS.name, intentB.operation)
        assertEquals(2L, intentB.revision)
        assertEquals(MutationOperation.ASSIGNMENTS.name, intentC.operation)
        assertEquals(1L, intentC.revision)
        assertEquals(3, database.outboxDao().getAll(ACCOUNT).count { it.aggregateType == AggregateType.GROUP.name })
    }

    @Test
    fun deleteIntentConflictRejectsAllChangedGroupsBeforeTheFirstCas() = runBlocking {
        val repository = seedThreeGroupContext()
        replaceGroupIntent(GROUP_B, MutationOperation.DELETE)
        val expectedStates = expectedGroupStates()
        val beforeGroups = database.contactGroupDao().getAll(ACCOUNT).map(ContactGroupWithMemberships::toDomain)
        val beforeOutbox = database.outboxDao().getAll(ACCOUNT)

        val result = database.withTransaction {
            repository.applyObservedGroupMembershipsInCurrentTransaction(
                accountId = ACCOUNT,
                contactId = CONTACT,
                expectedCanonicalRevision = 1L,
                expectedPreferredEmailValueId = PREFERRED_EMAIL,
                expectedGroupStates = expectedStates,
                observedCanonicalGroupIds = setOf(GROUP_A, GROUP_C),
            )
        }

        assertEquals(RoomObservedGroupMembershipMutationResult.DeleteIntentConflict, result)
        assertEquals(beforeGroups, database.contactGroupDao().getAll(ACCOUNT).map(ContactGroupWithMemberships::toDomain))
        assertEquals(beforeOutbox, database.outboxDao().getAll(ACCOUNT))
    }

    @Test
    fun staleContactPreferredEmailOrCompleteGroupVectorHasNoEffect() = runBlocking {
        val repository = seedThreeGroupContext()
        val expectedStates = expectedGroupStates()
        val beforeGroups = database.contactGroupDao().getAll(ACCOUNT).map(ContactGroupWithMemberships::toDomain)
        val beforeOutbox = database.outboxDao().getAll(ACCOUNT)

        val staleRevision = database.withTransaction {
            repository.applyObservedGroupMembershipsInCurrentTransaction(
                ACCOUNT,
                CONTACT,
                expectedCanonicalRevision = 0L,
                expectedPreferredEmailValueId = PREFERRED_EMAIL,
                expectedGroupStates = expectedStates,
                observedCanonicalGroupIds = setOf(GROUP_A, GROUP_C),
            )
        }
        val stalePreferred = database.withTransaction {
            repository.applyObservedGroupMembershipsInCurrentTransaction(
                ACCOUNT,
                CONTACT,
                expectedCanonicalRevision = 1L,
                expectedPreferredEmailValueId = SECONDARY_EMAIL,
                expectedGroupStates = expectedStates,
                observedCanonicalGroupIds = setOf(GROUP_A, GROUP_C),
            )
        }
        val incompleteVector = database.withTransaction {
            repository.applyObservedGroupMembershipsInCurrentTransaction(
                ACCOUNT,
                CONTACT,
                expectedCanonicalRevision = 1L,
                expectedPreferredEmailValueId = PREFERRED_EMAIL,
                expectedGroupStates = expectedStates.dropLast(1),
                observedCanonicalGroupIds = setOf(GROUP_A, GROUP_C),
            )
        }

        assertEquals(RoomObservedGroupMembershipMutationResult.StaleContact, staleRevision)
        assertEquals(RoomObservedGroupMembershipMutationResult.StaleContact, stalePreferred)
        assertEquals(RoomObservedGroupMembershipMutationResult.StaleGroupContext, incompleteVector)
        assertEquals(beforeGroups, database.contactGroupDao().getAll(ACCOUNT).map(ContactGroupWithMemberships::toDomain))
        assertEquals(beforeOutbox, database.outboxDao().getAll(ACCOUNT))
    }

    @Test
    fun noChangeDoesNotReviseGroupsOrReplaceOutboxIntents() = runBlocking {
        var clockCalls = 0
        val repository = seedThreeGroupContext {
            clockCalls += 1
            FIXED_NOW
        }
        replaceGroupIntent(GROUP_B, MutationOperation.ASSIGNMENTS)
        val expectedStates = expectedGroupStates()
        val beforeGroups = database.contactGroupDao().getAll(ACCOUNT).map(ContactGroupWithMemberships::toDomain)
        val beforeOutbox = database.outboxDao().getAll(ACCOUNT)
        clockCalls = 0

        val result = database.withTransaction {
            repository.applyObservedGroupMembershipsInCurrentTransaction(
                accountId = ACCOUNT,
                contactId = CONTACT,
                expectedCanonicalRevision = 1L,
                expectedPreferredEmailValueId = PREFERRED_EMAIL,
                expectedGroupStates = expectedStates,
                observedCanonicalGroupIds = setOf(GROUP_B, GROUP_C),
            )
        }

        assertEquals(RoomObservedGroupMembershipMutationResult.NoChange, result)
        assertEquals(0, clockCalls)
        assertEquals(beforeGroups, database.contactGroupDao().getAll(ACCOUNT).map(ContactGroupWithMemberships::toDomain))
        assertEquals(beforeOutbox, database.outboxDao().getAll(ACCOUNT))
    }

    private suspend fun seedThreeGroupContext(
        clock: () -> Long = { FIXED_NOW },
    ): RoomContactRepository {
        val repository = RoomContactRepository(
            database = database,
            clock = clock,
            elapsedRealtimeClock = { FIXED_NOW },
        )
        repository.saveContact(contact(CONTACT, PREFERRED_EMAIL, SECONDARY_EMAIL)).requireSaved()
        repository.saveContact(contact(OTHER_CONTACT, OTHER_EMAIL)).requireSaved()
        repository.saveGroup(
            group(
                id = GROUP_A,
                memberships = listOf(
                    GroupMembership(CONTACT, SECONDARY_EMAIL),
                    GroupMembership(OTHER_CONTACT, OTHER_EMAIL),
                ),
            ),
        ).requireSaved()
        repository.saveGroup(
            group(
                id = GROUP_B,
                memberships = listOf(
                    GroupMembership(CONTACT, PREFERRED_EMAIL),
                    GroupMembership(CONTACT, SECONDARY_EMAIL),
                    GroupMembership(OTHER_CONTACT, OTHER_EMAIL),
                ),
            ),
        ).requireSaved()
        repository.saveGroup(
            group(
                id = GROUP_C,
                memberships = listOf(GroupMembership(CONTACT, PREFERRED_EMAIL)),
            ),
        ).requireSaved()
        return repository
    }

    private fun contact(id: String, vararg emailIds: String) = CanonicalContact(
        accountId = ACCOUNT,
        id = id,
        firstName = "Synthetic",
        values = emailIds.mapIndexed { index, emailId ->
            ContactValue(
                id = emailId,
                kind = ContactValueKind.EMAIL,
                value = "$emailId@example.test",
                order = index,
                isPrimary = index == 0,
            )
        },
    )

    private fun group(id: String, memberships: List<GroupMembership>) = ContactGroup(
        accountId = ACCOUNT,
        id = id,
        name = "Synthetic $id",
        color = "#123456",
        order = id.last().code,
        remoteLabelId = "remote-$id",
        remoteVersion = "version-$id",
        memberships = memberships,
    )

    private suspend fun expectedGroupStates(): List<RoomExpectedContactGroupState> =
        database.contactGroupDao().getAll(ACCOUNT).map { stored ->
            RoomExpectedContactGroupState(
                groupId = stored.group.id,
                revision = stored.group.revision,
                isDeleted = stored.group.isDeleted,
            )
        }

    private suspend fun replaceGroupIntent(groupId: String, operation: MutationOperation) {
        val existing = requireNotNull(groupIntent(groupId))
        database.outboxDao().upsert(
            existing.copy(
                operation = operation.name,
                idempotencyKey = "$ACCOUNT:${AggregateType.GROUP.name}:$groupId:${existing.revision}:${operation.name}",
            ),
        )
    }

    private suspend fun groupIntent(groupId: String): OutboxMutationEntity? =
        database.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, groupId)

    private fun <T> SaveResult<T>.requireSaved(): T {
        assertTrue(this is SaveResult.Saved)
        return (this as SaveResult.Saved).value
    }

    private companion object {
        const val DATABASE_NAME = "observed-group-membership-transaction.db"
        const val ACCOUNT = "account"
        const val CONTACT = "contact"
        const val OTHER_CONTACT = "other-contact"
        const val PREFERRED_EMAIL = "preferred-email"
        const val SECONDARY_EMAIL = "secondary-email"
        const val OTHER_EMAIL = "other-email"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
        const val GROUP_C = "group-c"
        const val FIXED_NOW = 8_888L
    }
}
