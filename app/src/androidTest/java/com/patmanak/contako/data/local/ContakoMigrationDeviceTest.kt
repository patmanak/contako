package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContakoMigrationDeviceTest {
    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ContakoDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyExportedSchemaUpgradesToCurrentWithoutLosingExistingRows() {
        (1 until CURRENT_VERSION).forEach { startVersion ->
            val name = "migration-$startVersion-to-$CURRENT_VERSION.db"
            context.deleteDatabase(name)
            val old = migrations.createDatabase(name, startVersion)
            MigrationFixture.seed(old)
            val before = MigrationFixture.snapshot(old)
            val originalColumns = before.keys.associateWith(old::columns)
            old.close()

            val upgraded = migrations.runMigrationsAndValidate(name, CURRENT_VERSION, true, *ALL_MIGRATIONS)
            try {
                assertEquals(before, MigrationFixture.snapshot(upgraded, before.keys, originalColumns))
                assertEquals(CURRENT_VERSION, upgraded.version)
                upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally {
                upgraded.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun currentSchemaRepresentativeSnapshotSurvivesCloseAndReopenExactly() {
        val name = "migration-current-reopen.db"
        context.deleteDatabase(name)
        val created = migrations.createDatabase(name, CURRENT_VERSION)
        MigrationFixture.seed(created)
        val expected = MigrationFixture.snapshot(created)
        created.close()

        val reopened = ContakoDatabase.create(context, name)
        try {
            assertEquals(expected, MigrationFixture.snapshot(reopened.openHelper.writableDatabase))
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun downgradeFailsClosedAndLeavesDatabaseBytesPresent() {
        val name = "migration-downgrade.db"
        context.deleteDatabase(name)
        val current = migrations.createDatabase(name, CURRENT_VERSION)
        MigrationFixture.seed(current)
        val expected = MigrationFixture.snapshot(current)
        current.close()
        val file = context.getDatabasePath(name)
        val sizeBefore = file.length()

        val failure = runCatching {
            FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(CURRENT_VERSION - 1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = error("UNEXPECTED_CREATE")
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            error("UNEXPECTED_UPGRADE")
                    })
                    .build(),
            ).use { it.writableDatabase }
        }.exceptionOrNull()

        assertTrue(generateSequence(failure) { it.cause }.any { it is android.database.sqlite.SQLiteException })
        assertTrue(file.exists())
        assertEquals(sizeBefore, file.length())
        val reopened = ContakoDatabase.create(context, name)
        try {
            assertEquals(expected, MigrationFixture.snapshot(reopened.openHelper.writableDatabase))
        } finally {
            reopened.close()
        }
        context.deleteDatabase(name)
    }

    @Test
    fun corruptDatabaseFailsWithoutSilentCreationOrReset() {
        val name = "migration-corrupt.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val corrupt = "not-a-sqlite-database-synthetic".toByteArray()
        file.writeBytes(corrupt)

        repeat(2) {
            val database = ContakoDatabase.create(context, name)
            val failure = try {
                runCatching { database.openHelper.writableDatabase }.exceptionOrNull()
            } finally {
                database.close()
            }
            assertTrue(
                "CORRUPT_OPEN_RESULT: exception=${failure?.javaClass?.simpleName ?: "NONE"}, filePresent=${file.exists()}, originalBytes=${file.exists() && file.readBytes().contentEquals(corrupt)}",
                generateSequence(failure) { it.cause }.any { it is android.database.sqlite.SQLiteException },
            )
            assertTrue(file.exists())
            assertTrue(file.readBytes().contentEquals(corrupt))
        }
        context.deleteDatabase(name)
    }

    @Test
    fun failedMigrationRollsBackSchemaDataAndUserVersionAcrossReopen() {
        val name = "migration-transaction-fault.db"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 1).use { db ->
            MigrationFixture.seed(db)
        }
        val fault = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE contacts SET display_name = 'must-roll-back'")
                db.execSQL("CREATE TABLE must_roll_back(value TEXT NOT NULL)")
                throw SimulatedMigrationProcessDeath()
            }
        }

        val failure = runCatching {
            migrations.runMigrationsAndValidate(name, 2, false, fault)
        }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it is SimulatedMigrationProcessDeath })

        val reopened = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("UNEXPECTED_CREATE")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("UNEXPECTED_UPGRADE")
                })
                .build(),
        )
        try {
            val db = reopened.writableDatabase
            assertEquals(1, db.version)
            assertEquals("Synthetic Person", db.scalar("SELECT display_name FROM contacts"))
            assertFalse(db.tableExists("must_roll_back"))
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    private class SimulatedMigrationProcessDeath : RuntimeException()

    private companion object {
        const val CURRENT_VERSION = 17
        val ALL_MIGRATIONS = arrayOf(
            ContakoDatabase.MIGRATION_1_2,
            ContakoDatabase.MIGRATION_2_3,
            ContakoDatabase.MIGRATION_3_4,
            ContakoDatabase.MIGRATION_4_5,
            ContakoDatabase.MIGRATION_5_6,
            ContakoDatabase.MIGRATION_6_7,
            ContakoDatabase.MIGRATION_7_8,
            ContakoDatabase.MIGRATION_8_9,
            ContakoDatabase.MIGRATION_9_10,
            ContakoDatabase.MIGRATION_10_11,
            ContakoDatabase.MIGRATION_11_12,
            ContakoDatabase.MIGRATION_12_13,
            ContakoDatabase.MIGRATION_13_14,
            ContakoDatabase.MIGRATION_14_15,
            ContakoDatabase.MIGRATION_15_16,
            ContakoDatabase.MIGRATION_16_17,
        )
    }
}

private object MigrationFixture {
    private val preservedTables = listOf(
        "contacts",
        "contact_values",
        "contact_payloads",
        "contact_groups",
        "group_memberships",
        "outbox_mutations",
        "contact_inventory_checkpoints",
        "contact_inventory_entries",
        "contact_inventory_groups",
        "contact_inventory_email_memberships",
        "contact_inventory_email_groups",
        "android_photo_provider_write_journal",
    )

    fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO contacts VALUES " +
                "('synthetic-account','synthetic-contact','synthetic-account:synthetic-contact'," +
                "'Synthetic','Person','Synthetic Person','Person Synthetic',7,1700000000000," +
                "'remote-contact','vcard-uid','remote-version','reason-a|reason-b',7,NULL,0)",
        )
        db.execSQL(
            "INSERT INTO contact_values VALUES " +
                "('synthetic-account','synthetic-contact','synthetic-account:synthetic-contact'," +
                "'synthetic-email','EMAIL','person@example.test','work',0,1,'component=exact'," +
                "'metadata=exact',NULL,'preserve-email')," +
                "('synthetic-account','synthetic-contact','synthetic-account:synthetic-contact'," +
                "'synthetic-photo','PHOTO','photo-metadata','profile',1,0,'encoding=binary'," +
                "'media=image/jpeg;size=4','photo://synthetic','preserve-photo')",
        )
        db.execSQL(
            "INSERT INTO contact_payloads VALUES " +
                "('synthetic-account:synthetic-contact','X-SYNTHETIC=preserve','baseline-synthetic')",
        )
        db.execSQL(
            "INSERT INTO contact_groups VALUES " +
                "('synthetic-account','synthetic-group','synthetic-account:synthetic-group'," +
                "'Synthetic Group','#123456',3,1,5,1700000000001,'remote-group','group-version',5,NULL,0)",
        )
        db.execSQL(
            "INSERT INTO group_memberships VALUES " +
                "('synthetic-account','synthetic-group','synthetic-account:synthetic-group'," +
                "'synthetic-contact','synthetic-email')",
        )
        val outboxColumns = db.columns("outbox_mutations")
        val outboxValues = linkedMapOf<String, Any?>(
            "account_id" to "synthetic-account", "aggregate_type" to "CONTACT",
            "aggregate_id" to "synthetic-contact", "operation" to "UPSERT", "revision" to 7,
            "created_at_epoch_millis" to 1700000000002L, "updated_at_epoch_millis" to 1700000000003L,
            "attempt_count" to 2, "next_attempt_at_epoch_millis" to 1700000001000L,
            "error_category" to "NETWORK", "blocked_reason" to null,
            "remote_identity" to "remote-contact", "remote_version" to "remote-version",
            "idempotency_key" to "synthetic-idempotency", "state" to "PENDING",
            "requires_reconciliation" to 1, "last_attempt_at_epoch_millis" to 1700000000004L,
            "device_elapsed_realtime_millis" to 1234L, "server_offset_millis" to 50L,
            "calibration_age_millis" to 60L, "round_trip_millis" to 70L,
            "server_precision_millis" to 80L, "uncertainty_millis" to 90L,
            "interval_earliest_epoch_millis" to 1699999999990L,
            "interval_latest_epoch_millis" to 1700000000010L, "clock_jump_detected" to 1,
        )
        db.insertValues("outbox_mutations", outboxValues.filterKeys(outboxColumns::contains))

        if (db.tableExists("contact_inventory_checkpoints")) {
            db.execSQL("INSERT INTO contact_inventory_checkpoints VALUES ('synthetic-account',11)")
            db.execSQL(
                "INSERT INTO contact_inventory_entries VALUES " +
                    "('synthetic-account','synthetic-contact','Synthetic Person','remote-version',321,1700000000)",
            )
            db.execSQL("INSERT INTO contact_inventory_groups VALUES ('synthetic-account','synthetic-contact','synthetic-group')")
            db.execSQL(
                "INSERT INTO contact_inventory_email_memberships VALUES " +
                    "('synthetic-account','synthetic-contact','synthetic-email')",
            )
            db.execSQL(
                "INSERT INTO contact_inventory_email_groups VALUES " +
                    "('synthetic-account','synthetic-contact','synthetic-email','synthetic-group')",
            )
        }
        seedPhotoJournalIfPresent(db)
    }

    private fun seedPhotoJournalIfPresent(db: SupportSQLiteDatabase) {
        if (!db.tableExists("android_photo_provider_write_journal")) return
        db.execSQL(
            "INSERT INTO android_projection_accounts VALUES " +
                "('synthetic-account',2,4,'synthetic@android.test',0)",
        )
        db.execSQL(
            "INSERT INTO android_projection_ledger VALUES " +
                "('synthetic-account','synthetic-contact',3,4,42,'source-synthetic',NULL,NULL,NULL,NULL," +
                "'CLEAN','BASELINED','NONE','ADOPTED')",
        )
        db.execSQL(
            "INSERT INTO android_photo_provider_write_journal VALUES " +
                "('synthetic-account','synthetic-contact','synthetic@android.test',4,42,8," +
                "'source-synthetic',7,3,'synthetic-photo','photo://synthetic',4," +
                "'${"a".repeat(64)}','PREPARED',NULL)",
        )
    }

    fun snapshot(
        db: SupportSQLiteDatabase,
        tables: Collection<String> = preservedTables,
        originalColumns: Map<String, List<String>>? = null,
    ): Map<String, List<List<String?>>> =
        tables.filter(db::tableExists).associateWith { table ->
            val columns = originalColumns?.get(table) ?: db.columns(table)
            db.query("SELECT ${columns.joinToString()} FROM `$table` ORDER BY ${columns.joinToString()}").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(columns.indices.map { index ->
                            if (cursor.isNull(index)) null else when (cursor.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it) }
                                else -> cursor.getString(index)
                            }
                        })
                    }
                }
            }
        }
}

private fun SupportSQLiteDatabase.tableExists(table: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

private fun SupportSQLiteDatabase.columns(table: String): List<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }

private fun SupportSQLiteDatabase.scalar(sql: String): String =
    query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

private fun SupportSQLiteDatabase.insertValues(table: String, values: Map<String, Any?>) {
    val columns = values.keys.toList()
    val bind = values.values.map { value ->
        when (value) {
            null -> "NULL"
            is Number -> value.toString()
            else -> "'${value.toString().replace("'", "''")}'"
        }
    }
    execSQL("INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${bind.joinToString()})")
}
