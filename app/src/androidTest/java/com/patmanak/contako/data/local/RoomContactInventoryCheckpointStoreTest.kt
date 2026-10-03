package com.patmanak.contako.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import com.patmanak.contako.data.proton.ContactInventoryPlan
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.StaleContactInventoryPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomContactInventoryCheckpointStoreTest {
    @Test
    fun contactEventCursorSurvivesRestartAndFailedCheckpointTransaction() = runBlocking {
        val name = "inventory-events-restart.db"
        context.deleteDatabase(name)
        var database = ContakoDatabase.create(context, name)
        try {
            val snapshot = inventory(metadata("contact", "v1"))
            val first = planner(database).plan(ACCOUNT, snapshot, events =
                com.patmanak.contako.data.proton.ContactEventsDelta("start", emptySet(), true))
            planner(database).commit(ACCOUNT, first.fullyCompleted())
            database.close()
            database = ContakoDatabase.create(context, name)
            assertEquals("start", planner(database).eventCursor(ACCOUNT))
            assertNull(planner(database).eventCursor(FOREIGN_ACCOUNT))
            val faulting = PersistentContactInventoryPlanner(RoomContactInventoryCheckpointStore(database,
                InventoryCheckpointWriteHook { if (it == InventoryCheckpointWriteCheckpoint.AFTER_GENERATION_CAS) error("SYNTHETIC_FAILURE") }))
            val changed = faulting.plan(ACCOUNT, snapshot, events =
                com.patmanak.contako.data.proton.ContactEventsDelta("next", setOf(RemoteContactId("contact")), false))
            assertTrue(runCatching { faulting.commit(ACCOUNT, changed.fullyCompleted()) }.isFailure)
            assertEquals("start", planner(database).eventCursor(ACCOUNT))
            val replay = planner(database).plan(ACCOUNT, snapshot, events =
                com.patmanak.contako.data.proton.ContactEventsDelta("next", setOf(RemoteContactId("contact")), false))
            planner(database).commit(ACCOUNT, replay.fullyCompleted())
            assertEquals("next", planner(database).eventCursor(ACCOUNT))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrationOneToElevenPreservesExistingRowsAndRoomValidatesFinalSchema() = runBlocking {
        val databaseName = "inventory-migration.db"
        context.deleteDatabase(databaseName)
        val versionOne = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_ONE_SCHEMA.forEach(db::execSQL)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("UNEXPECTED_TEST_UPGRADE")
                })
                .build(),
        )
        versionOne.writableDatabase.execSQL(
                """
                INSERT INTO outbox_mutations(
                    account_id, aggregate_type, aggregate_id, operation, revision,
                    created_at_epoch_millis, updated_at_epoch_millis, attempt_count,
                    next_attempt_at_epoch_millis, error_category, blocked_reason,
                    remote_identity, remote_version, idempotency_key
                ) VALUES (
                    'migration-account', 'CONTACT', 'migration-contact', 'UPSERT', 1,
                    1, 1, 0, 0, NULL, NULL, NULL, NULL, 'migration-key'
                )
                """.trimIndent(),
        )
        versionOne.close()

        val migrated = ContakoDatabase.create(context, databaseName)
        try {
            val mutation = requireNotNull(
                migrated.outboxDao().get("migration-account", "CONTACT", "migration-contact"),
            )
            assertEquals(DurableMutationState.PENDING.name, mutation.state)
            assertFalse(mutation.requiresReconciliation)
            assertEquals(0L, mutation.deviceElapsedRealtimeMillis)
            assertNull(mutation.intervalEarliestEpochMillis)
            assertNull(migrated.syncAccountStatusDao().get("migration-account"))
            assertNull(migrated.fullRepairProgressDao().get("migration-account"))
            val sqlite = migrated.openHelper.writableDatabase
            listOf(
                "contact_inventory_checkpoints",
                "contact_inventory_entries",
                "contact_inventory_groups",
                "contact_inventory_email_memberships",
                "contact_inventory_email_groups",
                "full_repair_progress",
                "android_projection_accounts",
                "android_projection_ledger",
                "android_provider_identity_owners",
                "android_provider_row_bindings",
                "android_projection_baselines",
                "android_group_projection_ledger",
                "android_group_projection_baselines",
                "android_group_membership_projection_ledger",
                "android_group_membership_baselines",
                "android_group_membership_commit_receipts",
                "android_group_provider_write_journal",
            ).forEach { table ->
                sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationNineToElevenCreatesReceiptAndProviderJournalTables() {
        val databaseName = "android-group-receipt-migration.db"
        context.deleteDatabase(databaseName)
        val versionNine = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE android_projection_accounts (
                                account_id TEXT NOT NULL,
                                revision INTEGER NOT NULL,
                                provider_epoch INTEGER NOT NULL,
                                PRIMARY KEY(account_id)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE android_group_membership_projection_ledger (
                                account_id TEXT NOT NULL,
                                canonical_contact_id TEXT NOT NULL,
                                revision INTEGER NOT NULL,
                                provider_epoch INTEGER NOT NULL,
                                raw_contact_locator INTEGER,
                                preferred_email_value_id TEXT,
                                canonical_projection_fingerprint TEXT,
                                android_baseline_fingerprint TEXT,
                                pending_projection_fingerprint TEXT,
                                projection_state TEXT NOT NULL,
                                ingestion_state TEXT NOT NULL,
                                PRIMARY KEY(account_id, canonical_contact_id)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO android_group_membership_projection_ledger VALUES (
                                'account', 'contact', 4, 2, 10, 'email', NULL, NULL, NULL,
                                'CLEAN', 'BASELINED'
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("UNEXPECTED_TEST_UPGRADE")
                })
                .build(),
        )
        versionNine.writableDatabase
        versionNine.close()

        val versionTen = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("UNEXPECTED_TEST_CREATE")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(9, oldVersion)
                        assertEquals(10, newVersion)
                        ContakoDatabase.MIGRATION_9_10.migrate(db)
                    }
                })
                .build(),
        )
        try {
            val db = versionTen.writableDatabase
            db.execSQL("PRAGMA foreign_keys = ON")
            db.query("SELECT COUNT(*) FROM android_group_membership_commit_receipts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL(
                """
                INSERT INTO android_group_membership_commit_receipts VALUES (
                    'account', 'contact', 2, 7, 4, 8, 5, '${"a".repeat(64)}'
                )
                """.trimIndent(),
            )
            db.execSQL(
                "DELETE FROM android_group_membership_projection_ledger " +
                    "WHERE account_id = 'account' AND canonical_contact_id = 'contact'",
            )
            db.query("SELECT COUNT(*) FROM android_group_membership_commit_receipts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            versionTen.close()
            val versionEleven = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(databaseName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                        override fun onCreate(db: SupportSQLiteDatabase) = error("UNEXPECTED_TEST_CREATE")

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            assertEquals(10, oldVersion)
                            assertEquals(11, newVersion)
                            ContakoDatabase.MIGRATION_10_11.migrate(db)
                        }
                    })
                    .build(),
            )
            try {
                versionEleven.writableDatabase.query(
                    "SELECT COUNT(*) FROM android_group_provider_write_journal",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                versionEleven.close()
            }
        } finally {
            versionTen.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationEightToNineCreatesEmptyAccountScopedGroupProjectionTables() {
        val databaseName = "android-group-ledger-migration.db"
        context.deleteDatabase(databaseName)
        val versionEight = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_EIGHT_GROUP_PARENT_SCHEMA.forEach(db::execSQL)
                        db.execSQL(
                            """
                            INSERT INTO contact_groups VALUES (
                                'account', 'group', 'account:group', 'Same name', '#6D4AFF',
                                0, 1, 3, 1, 'remote-group', 'version', NULL, NULL, 0
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("UNEXPECTED_TEST_UPGRADE")
                })
                .build(),
        )
        versionEight.writableDatabase
        versionEight.close()

        val versionNine = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("UNEXPECTED_TEST_CREATE")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(8, oldVersion)
                        assertEquals(9, newVersion)
                        ContakoDatabase.MIGRATION_8_9.migrate(db)
                    }
                })
                .build(),
        )
        try {
            val db = versionNine.writableDatabase
            db.execSQL("PRAGMA foreign_keys = ON")
            listOf(
                "android_group_projection_ledger",
                "android_group_projection_baselines",
                "android_group_membership_projection_ledger",
                "android_group_membership_baselines",
            ).forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            db.query("SELECT name, remote_label_id FROM contact_groups").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Same name", cursor.getString(0))
                assertEquals("remote-group", cursor.getString(1))
            }
            listOf(
                "index_android_group_projection_ledger_account_id_provider_epoch_group_row_locator",
                "index_android_group_projection_ledger_account_id_source_identity",
                "index_android_group_membership_projection_ledger_account_id_provider_epoch_raw_contact_locator",
            ).forEach { index ->
                db.query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
                    arrayOf(index),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
        } finally {
            versionNine.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationSevenToEightInvalidatesFingerprintOnlyBaselines() {
        val databaseName = "android-baseline-migration.db"
        context.deleteDatabase(databaseName)
        val versionSeven = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(VERSION_SEVEN_LEDGER_SCHEMA)
                        db.execSQL(
                            """
                            INSERT INTO android_projection_ledger VALUES
                                ('account', 'active', 3, 0, 10, 'source-active',
                                 '${"a".repeat(64)}', '${"b".repeat(64)}', '${"c".repeat(64)}',
                                 '${"d".repeat(64)}', 'CLEAN', 'BASELINED', 'NONE', 'ADOPTED'),
                                ('account', 'deleted', 4, 0, 11, 'source-deleted',
                                 '${"a".repeat(64)}', '${"b".repeat(64)}', '${"c".repeat(64)}',
                                 '${"d".repeat(64)}', 'DETACHED', 'CANONICAL_DELTA_COMMITTED',
                                 'CANONICAL_COMMITTED', 'ADOPTED')
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("UNEXPECTED_TEST_UPGRADE")
                })
                .build(),
        )
        versionSeven.writableDatabase
        versionSeven.close()

        val versionEight = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("UNEXPECTED_TEST_CREATE")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(7, oldVersion)
                        assertEquals(8, newVersion)
                        ContakoDatabase.MIGRATION_7_8.migrate(db)
                    }
                })
                .build(),
        )
        try {
            val db = versionEight.writableDatabase
            db.query(
                """
                SELECT canonical_contact_id, android_baseline_fingerprint,
                       pending_projection_fingerprint, observed_android_fingerprint,
                       projection_state, ingestion_state
                FROM android_projection_ledger ORDER BY canonical_contact_id
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("active", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertEquals("REPAIR_REQUIRED", cursor.getString(4))
                assertEquals("NONE", cursor.getString(5))
                assertTrue(cursor.moveToNext())
                assertEquals("deleted", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertEquals("DETACHED", cursor.getString(4))
                assertEquals("CANONICAL_DELTA_COMMITTED", cursor.getString(5))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM android_projection_baselines").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            versionEight.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun processDeathBeforeAndAfterCommitRepeatsOnlyUncommittedReconciliation() = runBlocking {
        val databaseName = "inventory-process-death.db"
        context.deleteDatabase(databaseName)
        var database = ContakoDatabase.create(context, databaseName)
        try {
            val inventory = inventory(metadata("contact", "version-1", includeEmptyEmailMembership = true))
            val firstPlanner = planner(database)
            val pending = firstPlanner.plan(ACCOUNT, inventory)

            assertTrue(runCatching { firstPlanner.commit(ACCOUNT, pending) }.isFailure)
            database.close()

            database = ContakoDatabase.create(context, databaseName)
            val afterPreCommitDeath = planner(database).plan(ACCOUNT, inventory)
            assertEquals(setOf(RemoteContactId("contact")), afterPreCommitDeath.hydrate)
            assertNull(RoomContactInventoryCheckpointStore(database).load(ACCOUNT))

            planner(database).commit(ACCOUNT, afterPreCommitDeath.fullyCompleted())
            database.close()

            database = ContakoDatabase.create(context, databaseName)
            val durable = requireNotNull(RoomContactInventoryCheckpointStore(database).load(ACCOUNT))
            assertEquals(0L, durable.generation)
            assertEquals(1, durable.checkpoint.entries.size)
            assertEquals(1, durable.checkpoint.entries.single().emailGroupMemberships.size)
            assertTrue(durable.checkpoint.entries.single().emailGroupMemberships.single().groupIds.isEmpty())
            val afterPostCommitDeath = planner(database).plan(ACCOUNT, inventory)
            assertTrue(afterPostCommitDeath.hydrate.isEmpty())
            assertTrue(afterPostCommitDeath.labelOnly.isEmpty())
            assertTrue(afterPostCommitDeath.deleted.isEmpty())
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun generationCasRejectsStaleReplayAndRemainsAccountScopedAcrossRestart() = runBlocking {
        val databaseName = "inventory-cas.db"
        context.deleteDatabase(databaseName)
        var database = ContakoDatabase.create(context, databaseName)
        try {
            val inventory = inventory(metadata("contact", "version-1"))
            val first = planner(database).plan(ACCOUNT, inventory)
            val concurrent = planner(database).plan(ACCOUNT, inventory)
            planner(database).commit(ACCOUNT, first.fullyCompleted())

            assertTrue(runCatching {
                planner(database).commit(ACCOUNT, concurrent.fullyCompleted())
            }.exceptionOrNull() is StaleContactInventoryPlan)
            assertTrue(runCatching {
                planner(database).commit(ACCOUNT, first.fullyCompleted())
            }.exceptionOrNull() is StaleContactInventoryPlan)

            val foreignInventory = inventory(metadata("foreign-contact", "foreign-version"))
            val foreignPlan = planner(database).plan(FOREIGN_ACCOUNT, foreignInventory)
            planner(database).commit(FOREIGN_ACCOUNT, foreignPlan.fullyCompleted())
            database.close()

            database = ContakoDatabase.create(context, databaseName)
            val primary = requireNotNull(RoomContactInventoryCheckpointStore(database).load(ACCOUNT))
            val foreign = requireNotNull(RoomContactInventoryCheckpointStore(database).load(FOREIGN_ACCOUNT))
            assertEquals(0L, primary.generation)
            assertEquals(0L, foreign.generation)
            assertEquals(RemoteContactId("contact"), primary.checkpoint.entries.single().id)
            assertEquals(RemoteContactId("foreign-contact"), foreign.checkpoint.entries.single().id)
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun injectedProcessDeathRollsBackEveryPreCommitBoundaryAndPreservesPostCommitCas() = runBlocking {
        InventoryCheckpointWriteCheckpoint.entries.forEach { faultAt ->
            val databaseName = "inventory-fault-${faultAt.name.lowercase()}.db"
            context.deleteDatabase(databaseName)
            var database = ContakoDatabase.create(context, databaseName)
            try {
                val initialInventory = inventory(metadata("contact", "version-1"))
                val initialPlan = planner(database).plan(ACCOUNT, initialInventory)
                planner(database).commit(ACCOUNT, initialPlan.fullyCompleted())
                val changedInventory = inventory(metadata("contact", "version-2"))
                val changedPlan = planner(database).plan(ACCOUNT, changedInventory)
                val faultingStore = RoomContactInventoryCheckpointStore(
                    database,
                    InventoryCheckpointWriteHook { checkpoint ->
                        if (checkpoint == faultAt) throw SimulatedProcessDeath()
                    },
                )

                assertTrue(runCatching {
                    PersistentContactInventoryPlanner(faultingStore)
                        .commit(ACCOUNT, changedPlan.fullyCompleted())
                }.exceptionOrNull() is SimulatedProcessDeath)
                database.close()

                database = ContakoDatabase.create(context, databaseName)
                val loaded = requireNotNull(RoomContactInventoryCheckpointStore(database).load(ACCOUNT))
                if (faultAt == InventoryCheckpointWriteCheckpoint.AFTER_COMMIT) {
                    assertEquals(1L, loaded.generation)
                    assertEquals(RemoteVersion("version-2"), loaded.checkpoint.entries.single().version)
                    assertTrue(planner(database).plan(ACCOUNT, changedInventory).hydrate.isEmpty())
                    assertTrue(runCatching {
                        planner(database).commit(ACCOUNT, changedPlan.fullyCompleted())
                    }.exceptionOrNull() is StaleContactInventoryPlan)
                } else {
                    assertEquals(0L, loaded.generation)
                    assertEquals(RemoteVersion("version-1"), loaded.checkpoint.entries.single().version)
                    assertEquals(setOf(RemoteContactId("contact")), planner(database).plan(ACCOUNT, changedInventory).hydrate)
                }
            } finally {
                if (database.isOpen) database.close()
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun checkpointEntitiesAndModelsExposeOnlyRedactedDiagnostics() {
        val entity = ContactInventoryEntryEntity(
            accountId = "private-account",
            contactId = "private-contact",
            displayName = "Private Person",
            remoteVersion = "private-version",
            sizeBytes = 1,
            modifiedAtEpochSeconds = 1,
        )
        val text = entity.toString()
        assertTrue(text.contains("REDACTED"))
        listOf("private-account", "private-contact", "Private Person", "private-version").forEach {
            assertFalse(text.contains(it))
        }
    }

    private fun planner(database: ContakoDatabase) =
        PersistentContactInventoryPlanner(RoomContactInventoryCheckpointStore(database))

    private fun ContactInventoryPlan.fullyCompleted(): ContactInventoryPlan =
        completedAfterDurableReconciliation(
            hydrated = hydrate,
            labelReconciled = labelOnly,
            deletionsReconciled = deleted,
            canonicalPersistenceCommitted = true,
        )

    private fun inventory(vararg contacts: ContactInventoryMetadata): ValidatedCompleteInventory =
        ValidatedCompleteInventory.fromPages(
            listOf(
                ContactInventoryPage(
                    contacts.toList(),
                    null,
                    null,
                    contacts.size,
                    ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            ),
        )

    private fun metadata(
        id: String,
        version: String,
        includeEmptyEmailMembership: Boolean = false,
    ): ContactInventoryMetadata {
        val emailId = RemoteEmailId("email-$id")
        val groupId = RemoteGroupId("group-$id")
        return ContactInventoryMetadata(
            id = RemoteContactId(id),
            displayName = "Fixture",
            version = RemoteVersion(version),
            sizeBytes = 1,
            modifiedAtEpochSeconds = 1,
            emailIds = listOf(emailId),
            groupIds = listOf(groupId),
            versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
            coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
            emailGroupMemberships = listOf(
                RemoteEmailGroupMembership(emailId, if (includeEmptyEmailMembership) emptyList() else listOf(groupId)),
            ),
        )
    }

    private class SimulatedProcessDeath : RuntimeException()

    private companion object {
        val ACCOUNT = AccountScope("primary")
        val FOREIGN_ACCOUNT = AccountScope("foreign")

        val VERSION_ONE_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `contacts` (`account_id` TEXT NOT NULL, `id` TEXT NOT NULL, `owner_key` TEXT NOT NULL, `first_name` TEXT NOT NULL, `last_name` TEXT NOT NULL, `display_name` TEXT NOT NULL, `sort_name` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, `remote_contact_id` TEXT, `remote_vcard_uid` TEXT, `remote_version` TEXT, `action_required_reasons` TEXT NOT NULL, `pending_mutation_revision` INTEGER, `conflict_state` TEXT, `is_deleted` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_contacts_owner_key` ON `contacts` (`owner_key`)",
            "CREATE INDEX IF NOT EXISTS `index_contacts_account_id_is_deleted_sort_name` ON `contacts` (`account_id`, `is_deleted`, `sort_name`)",
            "CREATE TABLE IF NOT EXISTS `contact_values` (`account_id` TEXT NOT NULL, `contact_id` TEXT NOT NULL, `owner_key` TEXT NOT NULL, `id` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `label` TEXT, `position` INTEGER NOT NULL, `is_primary` INTEGER NOT NULL, `components_encoding` TEXT NOT NULL, `metadata_encoding` TEXT NOT NULL, `binary_reference` TEXT, `preservation_key` TEXT, PRIMARY KEY(`account_id`, `contact_id`, `id`), FOREIGN KEY(`owner_key`) REFERENCES `contacts`(`owner_key`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_contact_values_owner_key` ON `contact_values` (`owner_key`)",
            "CREATE INDEX IF NOT EXISTS `index_contact_values_account_id_contact_id_kind_position` ON `contact_values` (`account_id`, `contact_id`, `kind`, `position`)",
            "CREATE TABLE IF NOT EXISTS `contact_payloads` (`owner_key` TEXT NOT NULL, `raw_properties_encoding` TEXT NOT NULL, `remote_baseline` TEXT, PRIMARY KEY(`owner_key`), FOREIGN KEY(`owner_key`) REFERENCES `contacts`(`owner_key`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `contact_groups` (`account_id` TEXT NOT NULL, `id` TEXT NOT NULL, `owner_key` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT NOT NULL, `display_order` INTEGER NOT NULL, `is_visible` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, `remote_label_id` TEXT, `remote_version` TEXT, `pending_mutation_revision` INTEGER, `conflict_state` TEXT, `is_deleted` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_groups_owner_key` ON `contact_groups` (`owner_key`)",
            "CREATE INDEX IF NOT EXISTS `index_contact_groups_account_id_is_deleted_name` ON `contact_groups` (`account_id`, `is_deleted`, `name`)",
            "CREATE TABLE IF NOT EXISTS `group_memberships` (`account_id` TEXT NOT NULL, `group_id` TEXT NOT NULL, `group_owner_key` TEXT NOT NULL, `contact_id` TEXT NOT NULL, `email_value_id` TEXT NOT NULL, PRIMARY KEY(`account_id`, `group_id`, `contact_id`, `email_value_id`), FOREIGN KEY(`group_owner_key`) REFERENCES `contact_groups`(`owner_key`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`account_id`, `contact_id`, `email_value_id`) REFERENCES `contact_values`(`account_id`, `contact_id`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_group_memberships_group_owner_key` ON `group_memberships` (`group_owner_key`)",
            "CREATE INDEX IF NOT EXISTS `index_group_memberships_account_id_contact_id_email_value_id` ON `group_memberships` (`account_id`, `contact_id`, `email_value_id`)",
            "CREATE TABLE IF NOT EXISTS `outbox_mutations` (`account_id` TEXT NOT NULL, `aggregate_type` TEXT NOT NULL, `aggregate_id` TEXT NOT NULL, `operation` TEXT NOT NULL, `revision` INTEGER NOT NULL, `created_at_epoch_millis` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, `attempt_count` INTEGER NOT NULL, `next_attempt_at_epoch_millis` INTEGER NOT NULL, `error_category` TEXT, `blocked_reason` TEXT, `remote_identity` TEXT, `remote_version` TEXT, `idempotency_key` TEXT NOT NULL, PRIMARY KEY(`account_id`, `aggregate_type`, `aggregate_id`))",
            "CREATE INDEX IF NOT EXISTS `index_outbox_mutations_account_id_next_attempt_at_epoch_millis` ON `outbox_mutations` (`account_id`, `next_attempt_at_epoch_millis`)",
        )

        val VERSION_EIGHT_GROUP_PARENT_SCHEMA = listOf(
            "CREATE TABLE `contact_groups` (`account_id` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                "`owner_key` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT NOT NULL, " +
                "`display_order` INTEGER NOT NULL, `is_visible` INTEGER NOT NULL, `revision` INTEGER NOT NULL, " +
                "`updated_at_epoch_millis` INTEGER NOT NULL, `remote_label_id` TEXT, `remote_version` TEXT, " +
                "`pending_mutation_revision` INTEGER, `conflict_state` TEXT, `is_deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`account_id`, `id`))",
            "CREATE TABLE `android_projection_accounts` (`account_id` TEXT NOT NULL, " +
                "`revision` INTEGER NOT NULL, `provider_epoch` INTEGER NOT NULL, PRIMARY KEY(`account_id`))",
            "CREATE TABLE `android_projection_ledger` (`account_id` TEXT NOT NULL, " +
                "`canonical_contact_id` TEXT NOT NULL, PRIMARY KEY(`account_id`, `canonical_contact_id`))",
        )

        const val VERSION_SEVEN_LEDGER_SCHEMA =
            "CREATE TABLE android_projection_ledger (" +
                "account_id TEXT NOT NULL, canonical_contact_id TEXT NOT NULL, revision INTEGER NOT NULL, " +
                "provider_epoch INTEGER NOT NULL, raw_contact_locator INTEGER, source_identity TEXT, " +
                "canonical_projection_fingerprint TEXT, android_baseline_fingerprint TEXT, " +
                "pending_projection_fingerprint TEXT, observed_android_fingerprint TEXT, " +
                "projection_state TEXT NOT NULL, ingestion_state TEXT NOT NULL, " +
                "tombstone_state TEXT NOT NULL, adoption_state TEXT NOT NULL, " +
                "PRIMARY KEY(account_id, canonical_contact_id))"
    }
}
