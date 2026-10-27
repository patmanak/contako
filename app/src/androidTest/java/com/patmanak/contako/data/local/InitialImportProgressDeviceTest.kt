package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.sync.InitialImportSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room-only observations. No Android account, provider row or network is accessed. */
@RunWith(AndroidJUnit4::class)
class InitialImportProgressDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase

    @Before fun setup() {
        context.deleteDatabase(NAME)
        database = ContakoDatabase.create(context, NAME)
    }

    @After fun cleanup() {
        if (database.isOpen) database.close()
        context.deleteDatabase(NAME)
    }

    @Test fun partialDurableBatchesAreAvailableBeforeCheckpointAndSurviveReopenWithoutDoubleCounting() = runBlocking {
        assertEquals(InitialImportSnapshot(false, 0, null, 0, 0), snapshot())
        database.withTransaction {
            repeat(25) { index -> contact("card-$index") }
        }
        val partial = snapshot()
        assertEquals(InitialImportSnapshot(false, 25, null, 0, 25), partial)
        assertFalse(partial.remoteComplete)
        assertTrue(database.outboxDao().getAll(OWNER).isEmpty())
        database.close()
        database = ContakoDatabase.create(context, NAME)
        assertEquals(partial, snapshot())
        repeat(3) { assertEquals(partial, snapshot()) }
    }

    @Test fun establishedEmptyInventoryIsDifferentFromUnknownAndOtherOwnersCannotSupplyItsTotal() = runBlocking {
        checkpoint(OTHER, 3)
        contact("same-id", OTHER)
        assertEquals(InitialImportSnapshot(false, 0, null, 0, 0), snapshot())
        checkpoint(OWNER, 0)
        assertEquals(InitialImportSnapshot(true, 0, 0, 0, 0), snapshot())
        assertTrue(snapshot().remoteComplete)
    }

    @Test fun deferredCardsAndAndroidCopiesRemainSeparateAndEachIdentityCountsOnlyOnce() = runBlocking {
        checkpoint(OWNER, 3)
        contact("one")
        contact("two")
        contact("deleted", deleted = true)
        contact("one", OTHER)
        val rejected = RemoteHydrationObligationEntity(OWNER, "remote-three", "Synthetic rejected",
            "MALFORMED_RESPONSE", "VCARD_PARSE_ENVELOPE")
        repeat(2) { database.remoteHydrationObligationDao().upsert(rejected) }
        database.remoteHydrationObligationDao().upsert(rejected.copy(accountId = OTHER))
        assertEquals(InitialImportSnapshot(true, 2, 3, 1, 2), snapshot())
        assertFalse(snapshot().remoteComplete)
        database.remoteHydrationObligationDao().delete(OWNER, setOf("remote-three"))
        assertTrue(snapshot().remoteComplete)
        assertEquals(2, snapshot().androidPendingContactCount)

        RoomAndroidProjectionLedger(database).ensureAccount(AccountScope(OWNER))
        database.androidProjectionLedgerDao().insert(ledger("one"))
        assertEquals(1, snapshot().androidPendingContactCount)
        assertEquals(database.androidProjectionLedgerDao().observePendingContactIds(OWNER).first().size,
            snapshot().androidPendingContactCount)
        database.androidProjectionLedgerDao().update(ledger("one").copy(pendingProjectionFingerprint = "a".repeat(64)))
        assertEquals(2, snapshot().androidPendingContactCount)
        database.androidProjectionLedgerDao().update(ledger("one").copy(projectionState = "WRITE_PENDING"))
        assertEquals(2, snapshot().androidPendingContactCount)
        assertEquals(1, database.remoteHydrationObligationDao().getAll(OTHER).size)
    }

    @Test fun failedTransactionDoesNotPublishInventedAvailabilityAndOwnerCleanupResetsOnlyItsProgress() = runBlocking {
        contact("retained")
        contact("foreign", OTHER)
        checkpoint(OTHER, 2)
        val before = snapshot()
        val foreignBefore = snapshot(OTHER)
        try {
            database.withTransaction {
                contact("uncommitted")
                database.contactInventoryCheckpointDao().insertInitialCheckpoint(ContactInventoryCheckpointEntity(OWNER, 0))
                database.contactInventoryCheckpointDao().insertEntries(listOf(entry(OWNER, 0)))
                throw SyntheticRollback()
            }
            fail("The synthetic transaction must roll back")
        } catch (_: SyntheticRollback) { }
        assertEquals(before, snapshot())
        assertEquals(foreignBefore, snapshot(OTHER))
        database.accountRemovalDao().deleteAccount(OWNER)
        assertEquals(InitialImportSnapshot(false, 0, null, 0, 0), snapshot())
        assertEquals(foreignBefore, snapshot(OTHER))
    }

    @Test fun currentProjectionIssuesRemainPendingEvenWithCleanLedgerOrDeletedContactWithoutDoubleCounting() = runBlocking {
        contact("one")
        contact("deleted", deleted = true)
        RoomAndroidProjectionLedger(database).ensureAccount(AccountScope(OWNER))
        database.androidProjectionLedgerDao().insert(ledger("one"))
        assertEquals(0, snapshot().androidPendingContactCount)
        val issue = AndroidContactIssueEntity(OWNER, "one", "PROJECTION", 0, 1, "REPAIR_REQUIRED")
        database.androidContactIssueDao().upsert(issue)
        assertEquals(1, snapshot().androidPendingContactCount)
        database.androidContactIssueDao().upsert(issue.copy(origin = "INGEST"))
        database.androidProjectionLedgerDao().update(ledger("one").copy(pendingProjectionFingerprint = "a".repeat(64)))
        assertEquals(1, snapshot().androidPendingContactCount)
        database.androidProjectionLedgerDao().update(ledger("one"))

        // A failure from a different provider epoch is historical. Ingestion-only failures
        // remain separate from copy obligations, matching the dashboard's existing union.
        database.androidContactIssueDao().upsert(issue.copy(providerEpoch = 1))
        assertEquals(0, snapshot().androidPendingContactCount)
        database.androidContactIssueDao().upsert(issue)
        assertEquals(1, snapshot().androidPendingContactCount)
        database.androidContactIssueDao().delete(OWNER, "one", "PROJECTION", 0)
        assertEquals(0, snapshot().androidPendingContactCount)

        database.androidContactIssueDao().upsert(issue.copy(canonicalContactId = "deleted"))
        assertEquals(1, snapshot().localContactCount)
        assertEquals(1, snapshot().androidPendingContactCount)
        database.androidContactIssueDao().delete(OWNER, "deleted", "PROJECTION", 0)
        assertEquals(0, snapshot().androidPendingContactCount)
    }

    @Test fun synchronizedCountRequiresCurrentAcknowledgedActiveCopiesAndExcludesIssues() = runBlocking {
        contact("one")
        contact("two")
        contact("deleted", deleted = true)
        contact("foreign", OTHER)
        val dao = database.androidProjectionLedgerDao()
        RoomAndroidProjectionLedger(database).ensureAccount(AccountScope(OWNER))
        suspend fun count() = database.contactInventoryCheckpointDao().observeInitialImportProgress(OWNER)
            .first().androidSynchronizedContactCount
        assertEquals(0, count())
        dao.insert(ledger("one"))
        assertEquals(0, count()) // A bare CLEAN flag without an acknowledged copy is insufficient.
        val clean = ledger("one").copy(rawContactLocator = 1,
            canonicalProjectionFingerprint = "a".repeat(64), androidBaselineFingerprint = "b".repeat(64))
        dao.update(clean)
        assertEquals(1, count())
        dao.insert(clean.copy(canonicalContactId = "deleted", rawContactLocator = 2))
        database.androidContactIssueDao().upsert(AndroidContactIssueEntity(
            OWNER, "deleted", "PROJECTION", 0, 1, "REPAIR_REQUIRED"))
        assertEquals(1, count()) // Deletion backlog cannot subtract an unrelated live copy.
        listOf(clean.copy(providerEpoch = 1), clean.copy(projectionState = "WRITE_PENDING"),
            clean.copy(pendingProjectionFingerprint = "c".repeat(64)), clean.copy(tombstoneState = "PENDING")
        ).forEach { unverified ->
            dao.update(unverified)
            assertEquals(0, count())
        }
        dao.update(clean)
        val issue = AndroidContactIssueEntity(OWNER, "one", "INGEST", 0, 1, "REPAIR_REQUIRED")
        database.androidContactIssueDao().upsert(issue)
        assertEquals(0, count())
        database.androidContactIssueDao().delete(OWNER, "one", "INGEST", 0)
        database.androidContactIssueDao().upsert(issue.copy(providerEpoch = 1))
        assertEquals(1, count())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE android_projection_accounts SET provider_repair_pending=1 WHERE account_id=?", arrayOf(OWNER))
        assertEquals(0, count())
    }

    private suspend fun snapshot(owner: String = OWNER): InitialImportSnapshot {
        val counts = database.contactInventoryCheckpointDao().observeInitialImportProgress(owner).first()
        return InitialImportSnapshot(counts.inventoryEstablished, counts.localContactCount, counts.inventoryContactCount,
            counts.deferredContactCount, counts.androidPendingContactCount)
    }

    private suspend fun contact(id: String, owner: String = OWNER, deleted: Boolean = false) {
        database.contactDao().upsert(CanonicalContact(owner, id, displayName = "Synthetic contact",
            remoteContactId = "remote-$id", revision = 1, isDeleted = deleted).toEntity())
    }

    private suspend fun checkpoint(owner: String, count: Int) = database.withTransaction {
        database.contactInventoryCheckpointDao().insertInitialCheckpoint(ContactInventoryCheckpointEntity(owner, 0))
        database.contactInventoryCheckpointDao().insertEntries(List(count) { entry(owner, it) })
    }

    private fun entry(owner: String, index: Int) = ContactInventoryEntryEntity(owner, "inventory-$index",
        "Synthetic inventory", "synthetic-version", null, null)

    private fun ledger(id: String) = AndroidProjectionLedgerEntity(OWNER, id, 0, 0,
        null, null, null, null, null, null, "CLEAN", "BASELINED", "NONE", "ADOPTED")

    private class SyntheticRollback : RuntimeException()
    private companion object {
        const val NAME = "initial-import-progress-room.db"
        const val OWNER = "initial-import-synthetic-owner"
        const val OTHER = "initial-import-synthetic-other"
    }
}
