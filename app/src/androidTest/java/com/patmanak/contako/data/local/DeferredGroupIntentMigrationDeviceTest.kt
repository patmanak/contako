package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.gateway.AccountScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated database only: migration must not manufacture deferred-edge authority. */
@RunWith(AndroidJUnit4::class)
class DeferredGroupIntentMigrationDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ContakoDatabase::class.java)

    @Test fun existingDependencyProofAndEventCursorSurviveWithoutInventedDeferredScope() = runBlocking {
        val name = "deferred-group-migration-28-29.db"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 28).use { db ->
            db.execSQL("INSERT INTO remote_group_dependency_baselines VALUES ('owner','group','label',1)")
            db.execSQL("INSERT INTO remote_group_dependency_edges VALUES ('owner','group','contact','value','email',7)")
            db.execSQL("INSERT INTO contact_inventory_checkpoints VALUES ('owner',9,'retained-cursor',1,100)")
        }
        try {
            migrations.runMigrationsAndValidate(name, 29, true, ContakoDatabase.MIGRATION_28_29).use { db ->
                db.query("SELECT remote_label_id,complete,deferred_only_revision FROM remote_group_dependency_baselines").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("label", it.getString(0))
                    assertEquals(1, it.getInt(1))
                    assertTrue(it.isNull(2))
                }
                db.query("SELECT remote_email_id,captured_revision,is_deferred_intent FROM remote_group_dependency_edges").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("email", it.getString(0))
                    assertEquals(7L, it.getLong(1))
                    assertEquals(0, it.getInt(2))
                }
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            val reopened = ContakoDatabase.create(context, name)
            try {
                val checkpoint = RoomContactInventoryCheckpointStore(reopened).load(AccountScope("owner"))!!
                assertEquals(9L, checkpoint.generation)
                assertEquals("retained-cursor", checkpoint.checkpoint.eventCursor)
                assertEquals(1, checkpoint.checkpoint.eventStrategyVersion)
                assertEquals(100L, checkpoint.checkpoint.lastFullScanAtEpochMillis)
                reopened.openHelper.readableDatabase.query("SELECT COUNT(*) FROM remote_group_dependency_edges").use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
