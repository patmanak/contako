package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room transactions with synthetic groups; no provider/account/remote operations. */
@RunWith(AndroidJUnit4::class)
class EventGroupDeltaDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private lateinit var store: RoomRemoteGroupReconciliationStore
    private lateinit var repository: RoomContactRepository

    @Before fun setUp() {
        context.deleteDatabase(NAME)
        database = ContakoDatabase.create(context, NAME)
        store = RoomRemoteGroupReconciliationStore(database)
        repository = RoomContactRepository(database, clock = { 1000L })
    }
    @After fun tearDown() { database.close(); context.deleteDatabase(NAME) }

    @Test fun knownIdentitiesExcludeDeletedLocalOnlyAndForeignGroups() = runBlocking {
        store.commit(ACCOUNT, groups(remote("a", "A"), remote("b", "B")))
        store.commit(FOREIGN, groups(remote("foreign", "Foreign")))
        save(ContactGroup(accountId = ACCOUNT.value, id = "local", name = "Local-only draft"))
        assertEquals(setOf(RemoteGroupId("a"), RemoteGroupId("b")), store.knownRemoteIds(ACCOUNT))
        store.commitDelta(ACCOUNT, groups(), setOf(RemoteGroupId("b")))
        assertEquals(setOf(RemoteGroupId("a")), store.knownRemoteIds(ACCOUNT))
        assertEquals(setOf(RemoteGroupId("foreign")), store.knownRemoteIds(FOREIGN))
    }

    @Test fun partialDeltaCannotDeleteUntouchedGroupsOrAdoptTheirPendingDraftBaselines() = runBlocking {
        store.commit(ACCOUNT, groups(remote("a", "A"), remote("b", "B")))
        store.commit(FOREIGN, groups(remote("a", "Foreign")))
        val originalB = group("b")
        save(originalB.copy(name = "Local pending name"))
        val pendingB = database.contactGroupDao().get(ACCOUNT.value, "b")!!
        val pendingIntent = database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, "b")!!
        val foreignBefore = database.contactGroupDao().getAll(FOREIGN.value)

        store.commitDelta(ACCOUNT, groups(remote("a", "Remote A"), remote("c", "New C")), emptySet())

        assertEquals("Remote A", group("a").name)
        assertEquals("New C", group("c").name)
        assertEquals(pendingB, database.contactGroupDao().get(ACCOUNT.value, "b"))
        assertEquals(pendingIntent, database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, "b"))
        assertEquals(foreignBefore, database.contactGroupDao().getAll(FOREIGN.value))
    }

    @Test fun replayedTombstoneDoesNotCreateRevisionsButStillAcknowledgesANewDeleteIntent() = runBlocking {
        store.commit(ACCOUNT, groups(remote("a", "A"), remote("b", "B")))
        store.commitDelta(ACCOUNT, groups(), setOf(RemoteGroupId("a")))
        val deleted = group("a")
        assertTrue(deleted.isDeleted)
        store.commitDelta(ACCOUNT, groups(), setOf(RemoteGroupId("a")))
        assertEquals(deleted, group("a"))
        assertFalse(group("b").isDeleted)

        save(deleted.copy(name = "New local edit"))
        repository.deleteGroup(ACCOUNT.value, "a")
        val pending = group("a")
        assertTrue(pending.isDeleted)
        assertTrue(pending.pendingMutationRevision != null)
        store.commitDelta(ACCOUNT, groups(), setOf(RemoteGroupId("a")))
        val converged = group("a")
        assertTrue(converged.isDeleted)
        assertNull(converged.pendingMutationRevision)
        assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, "a"))
    }

    @Test fun deletedGroupWithPendingEditRetainsDraftAndIntentWhileUntouchedGroupsStayUnchanged() = runBlocking {
        store.commit(ACCOUNT, groups(remote("a", "A"), remote("b", "B")))
        save(group("a").copy(name = "Retained local draft"))
        val before = database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, "a")!!
        val untouched = group("b")

        store.commitDelta(ACCOUNT, groups(), setOf(RemoteGroupId("a")))

        assertEquals("Retained local draft", group("a").name)
        assertFalse(group("a").isDeleted)
        assertEquals("REMOTE_GROUP_DELETION_RECOVERY_REQUIRED", group("a").conflictState)
        val retained = database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, "a")!!
        assertEquals(before.revision, retained.revision)
        assertEquals(before.remoteVersion, retained.remoteVersion)
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, retained.state)
        assertEquals(untouched, group("b"))
    }

    @Test fun invalidLocalIntentRollsBackEveryOtherDeltaChange() = runBlocking {
        store.commit(ACCOUNT, groups(remote("a", "A"), remote("b", "B")))
        val beforeA = group("a")
        val beforeB = database.contactGroupDao().get(ACCOUNT.value, "b")!!.group
        // Controlled broken local invariant: pending aggregate without its required durable intent.
        database.contactGroupDao().upsert(beforeB.copy(pendingMutationRevision = beforeB.revision, name = "Broken").also {
            assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, "b"))
        })
        val invalidB = database.contactGroupDao().get(ACCOUNT.value, "b")!!
        assertTrue(runCatching {
            store.commitDelta(ACCOUNT, groups(remote("a", "Changed A"), remote("c", "New C")), setOf(RemoteGroupId("b")))
        }.isFailure)
        assertEquals(beforeA, group("a"))
        assertNull(database.contactGroupDao().get(ACCOUNT.value, "c"))
        assertEquals(invalidB, database.contactGroupDao().get(ACCOUNT.value, "b"))
    }

    private suspend fun group(id: String): ContactGroup = database.contactGroupDao().get(ACCOUNT.value, id)!!.toDomain()
    private suspend fun save(value: ContactGroup): ContactGroup = when (val saved = repository.saveGroup(value)) {
        is SaveResult.Saved -> saved.value
        is SaveResult.Rejected -> error("SYNTHETIC_GROUP_REJECTED")
    }
    private fun groups(vararg values: RemoteContactGroup) = AvailableContactGroups(values.toList())
    private fun remote(id: String, name: String) = RemoteContactGroup(RemoteGroupId(id), name, "#123456")
    private companion object {
        const val NAME = "event-group-delta.db"
        val ACCOUNT = AccountScope("event-group-owner")
        val FOREIGN = AccountScope("other-event-group-owner")
    }
}
