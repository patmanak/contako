package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room files only: no account, provider or remote operations. */
@RunWith(AndroidJUnit4::class)
class EventCheckpointPersistenceDeviceTest {
    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ContakoDatabase::class.java,
    )
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun strategyCursorAndFullScanTimeSurviveRestartAndStaleCasCannotReplaceThem() = runBlocking {
        val name = "event-checkpoint-restart.db"
        context.deleteDatabase(name)
        var db = ContakoDatabase.create(context, name)
        try {
            assertTrue(RoomContactInventoryCheckpointStore(db).compareAndSet(ACCOUNT, null, checkpoint("first", 1, 400L)))
            assertTrue(RoomContactInventoryCheckpointStore(db).compareAndSet(FOREIGN, null, checkpoint("foreign", 0, null)))
            db.close()
            db = ContakoDatabase.create(context, name)
            val store = RoomContactInventoryCheckpointStore(db)
            assertCheckpoint(db, "first", 1, 400L)
            assertFalse(store.compareAndSet(ACCOUNT, 7L, checkpoint("stale", 0, 900L)))
            assertCheckpoint(db, "first", 1, 400L)
            assertEquals("foreign", store.load(FOREIGN)!!.checkpoint.eventCursor)
            assertNull(store.load(AccountScope("missing-owner")))
            assertTrue(store.compareAndSet(ACCOUNT, 0L, checkpoint("next", 1, 500L)))
            assertCheckpoint(db, "next", 1, 500L)
            assertEquals(1L, store.load(ACCOUNT)!!.generation)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun failedCheckpointReplacementRollsBackCursorStrategyTimeAndMembershipsTogether() = runBlocking {
        val name = "event-checkpoint-rollback.db"
        context.deleteDatabase(name)
        val db = ContakoDatabase.create(context, name)
        try {
            val store = RoomContactInventoryCheckpointStore(db)
            assertTrue(store.compareAndSet(ACCOUNT, null, checkpoint("retained", 1, 100L)))
            for (failurePoint in listOf(
                InventoryCheckpointWriteCheckpoint.AFTER_GENERATION_CAS,
                InventoryCheckpointWriteCheckpoint.AFTER_ENTRY_DELETE,
                InventoryCheckpointWriteCheckpoint.AFTER_ENTRY_INSERT,
            )) {
                val faulting = RoomContactInventoryCheckpointStore(db, InventoryCheckpointWriteHook {
                    if (it == failurePoint) error("SYNTHETIC_CHECKPOINT_FAILURE")
                })
                val replacement = ContactInventoryCheckpoint(emptyList(), "replacement", 0, 200L)
                assertTrue(runCatching { faulting.compareAndSet(ACCOUNT, 0L, replacement) }.isFailure)
                assertCheckpoint(db, "retained", 1, 100L)
                assertEquals(0L, store.load(ACCOUNT)!!.generation)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun migrationPreservesLegacyCursorAndEntriesWithoutInventingEventReadiness() = runBlocking {
        val name = "event-checkpoint-migration-27-28.db"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 27).use { old ->
            old.execSQL("INSERT INTO contact_inventory_checkpoints VALUES ('event-owner',9,'legacy-cursor')")
            old.execSQL("INSERT INTO contact_inventory_entries VALUES ('event-owner','contact','Synthetic','index-version',NULL,NULL)")
            old.execSQL("INSERT INTO contact_inventory_groups VALUES ('event-owner','contact','group')")
            old.execSQL("INSERT INTO contact_inventory_email_memberships VALUES ('event-owner','contact','email')")
            old.execSQL("INSERT INTO contact_inventory_email_groups VALUES ('event-owner','contact','email','group')")
        }
        try {
            migrations.runMigrationsAndValidate(name, 28, true, ContakoDatabase.MIGRATION_27_28).use { upgraded ->
                upgraded.query("SELECT generation,event_cursor,event_strategy_version,last_full_scan_at_epoch_millis FROM contact_inventory_checkpoints").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(9L, it.getLong(0))
                    assertEquals("legacy-cursor", it.getString(1))
                    assertEquals(0, it.getInt(2))
                    assertTrue(it.isNull(3))
                }
                upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            val db = ContakoDatabase.create(context, name)
            try {
                val checkpoint = RoomContactInventoryCheckpointStore(db).load(ACCOUNT)!!
                assertEquals(9L, checkpoint.generation)
                assertCheckpoint(db, "legacy-cursor", 0, null)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun assertCheckpoint(db: ContakoDatabase, cursor: String, strategy: Int, scanTime: Long?) {
        val value = RoomContactInventoryCheckpointStore(db).load(ACCOUNT)!!.checkpoint
        assertEquals(cursor, value.eventCursor)
        assertEquals(strategy, value.eventStrategyVersion)
        assertEquals(scanTime, value.lastFullScanAtEpochMillis)
        val entry = value.entries.single()
        assertEquals(RemoteContactId("contact"), entry.id)
        assertEquals(RemoteVersion("index-version"), entry.version)
        assertEquals(listOf(RemoteGroupId("group")), entry.groupIds)
        val membership = entry.emailGroupMemberships.single()
        assertEquals(RemoteEmailId("email"), membership.emailId)
        assertEquals(listOf(RemoteGroupId("group")), membership.groupIds)
    }

    private fun checkpoint(cursor: String, strategy: Int, scanTime: Long?) = ContactInventoryCheckpoint(
        listOf(ContactInventoryBaseline(
            RemoteContactId("contact"), "Synthetic", RemoteVersion("index-version"), null, null,
            listOf(RemoteGroupId("group")), listOf(RemoteEmailGroupMembership(RemoteEmailId("email"), listOf(RemoteGroupId("group")))),
        )), cursor, strategy, scanTime,
    )

    private companion object {
        val ACCOUNT = AccountScope("event-owner")
        val FOREIGN = AccountScope("other-event-owner")
    }
}
