package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ContactEntity::class,
        ContactSyncEvidenceEntity::class,
        RemoteHydrationObligationEntity::class,
        TargetedRecoveryRequestEntity::class,
        ContactConflictEntity::class,
        ContactConflictChunk::class,
        DeletionRecoveryEntity::class,
        DeletionRecoveryChunk::class,
        DeletionRecoveryGroup::class,
        ContactValueEntity::class,
        ContactPayloadEntity::class,
        ContactGroupEntity::class,
        GroupMembershipEntity::class,
        OutboxMutationEntity::class,
        ContactInventoryCheckpointEntity::class,
        ContactInventoryEntryEntity::class,
        ContactInventoryGroupEntity::class,
        ContactInventoryEmailMembershipEntity::class,
        ContactInventoryEmailGroupEntity::class,
        SyncAccountStatusEntity::class,
        FullRepairProgressEntity::class,
        AndroidProjectionAccountEntity::class,
        AndroidProjectionLedgerEntity::class,
        AndroidProviderIdentityOwnerEntity::class,
        AndroidProviderRowBindingEntity::class,
        AndroidProjectionBaselineEntity::class,
        AndroidGroupProjectionLedgerEntity::class,
        AndroidGroupProjectionBaselineEntity::class,
        AndroidGroupMembershipProjectionLedgerEntity::class,
        AndroidGroupMembershipBaselineEntity::class,
        AndroidGroupMembershipCommitReceiptEntity::class,
        AndroidGroupObservationCommitReceiptEntity::class,
        AndroidUnifiedObservationCommitReceiptEntity::class,
        AndroidGroupProviderWriteJournalEntity::class,
        AndroidPhotoProviderWriteJournalEntity::class,
        AndroidPhotoProjectionReceiptEntity::class,
        AndroidContactIssueEntity::class,
    ],
    version = 25,
    exportSchema = true,
)
internal abstract class ContakoDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun contactSyncEvidenceDao(): ContactSyncEvidenceDao
    abstract fun remoteHydrationObligationDao(): RemoteHydrationObligationDao
    abstract fun targetedRecoveryRequestDao(): TargetedRecoveryRequestDao
    abstract fun contactConflictDao(): ContactConflictDao
    abstract fun deletionRecoveryDao(): DeletionRecoveryDao
    abstract fun contactGroupDao(): ContactGroupDao
    abstract fun contactPayloadDao(): ContactPayloadDao
    abstract fun accountRemovalDao(): AccountRemovalDao
    abstract fun outboxDao(): OutboxDao
    abstract fun contactInventoryCheckpointDao(): ContactInventoryCheckpointDao
    abstract fun syncAccountStatusDao(): SyncAccountStatusDao
    abstract fun fullRepairProgressDao(): FullRepairProgressDao
    abstract fun androidProjectionLedgerDao(): AndroidProjectionLedgerDao
    abstract fun androidGroupProjectionDao(): AndroidGroupProjectionDao
    abstract fun androidProviderIdentityDao(): AndroidProviderIdentityDao
    abstract fun androidContactIssueDao(): AndroidContactIssueDao

    companion object {
        const val DATABASE_NAME = "contako.db"

        fun create(context: Context, name: String = DATABASE_NAME): ContakoDatabase =
            Room.databaseBuilder(context.applicationContext, ContakoDatabase::class.java, name)
                .openHelperFactory(PreservingSQLiteOpenHelperFactory())
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                )
                .build()

        val MIGRATION_24_25: Migration = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS deletion_recoveries (
                        account_id TEXT NOT NULL,contact_id TEXT NOT NULL,generation TEXT NOT NULL,
                        local_revision INTEGER NOT NULL,draft_revision INTEGER NOT NULL,remote_id TEXT NOT NULL,
                        display_name TEXT NOT NULL,state TEXT NOT NULL,choice TEXT,blocker TEXT NOT NULL,
                        has_draft INTEGER NOT NULL,absence_verified INTEGER NOT NULL,destination_contact_id TEXT,
                        cleanup_epoch INTEGER,cleanup_raw INTEGER,cleanup_version INTEGER,cleanup_ledger_revision INTEGER,destination_revision INTEGER,
                        verification_order INTEGER NOT NULL DEFAULT 0,
                        source_retired INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(account_id,contact_id)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS deletion_recovery_chunks (
                        account_id TEXT NOT NULL,contact_id TEXT NOT NULL,position INTEGER NOT NULL,bytes BLOB NOT NULL,
                        PRIMARY KEY(account_id,contact_id,position),
                        FOREIGN KEY(account_id,contact_id) REFERENCES deletion_recoveries(account_id,contact_id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS deletion_recovery_groups (
                        account_id TEXT NOT NULL,contact_id TEXT NOT NULL,group_id TEXT NOT NULL,email_value_id TEXT NOT NULL,
                        remote_label_id TEXT,group_name TEXT NOT NULL,
                        PRIMARY KEY(account_id,contact_id,group_id,email_value_id),
                        FOREIGN KEY(account_id,contact_id) REFERENCES deletion_recoveries(account_id,contact_id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_23_24: Migration = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contact_sync_evidence (
                        account_id TEXT NOT NULL,contact_id TEXT NOT NULL,peer TEXT NOT NULL,
                        canonical_revision INTEGER NOT NULL,confirmed_at INTEGER NOT NULL,kind TEXT NOT NULL,
                        source_identity TEXT,source_version TEXT,provider_epoch INTEGER,android_account_name TEXT,
                        raw_contact_locator INTEGER,ledger_revision INTEGER,canonical_fingerprint TEXT,baseline_fingerprint TEXT,
                        membership_revision INTEGER,membership_fingerprint TEXT,membership_baseline_fingerprint TEXT,
                        membership_baseline_integrity TEXT,preferred_email_id TEXT,photo_receipt_key TEXT,
                        PRIMARY KEY(account_id,contact_id,peer),
                        FOREIGN KEY(account_id,contact_id) REFERENCES contacts(account_id,id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_22_23: Migration = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE targeted_recovery_requests ADD COLUMN failure_category TEXT")
                db.execSQL("ALTER TABLE targeted_recovery_requests ADD COLUMN hydration_category TEXT")
            }
        }

        val MIGRATION_21_22: Migration = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE remote_hydration_obligations ADD COLUMN generation TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE remote_hydration_obligations ADD COLUMN baseline_version TEXT")
                db.execSQL("UPDATE remote_hydration_obligations SET generation=lower(hex(randomblob(16)))")
                db.execSQL("ALTER TABLE sync_account_status ADD COLUMN scoped_issues_only INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS targeted_recovery_requests (
                        account_id TEXT NOT NULL,kind TEXT NOT NULL,target_id TEXT NOT NULL,
                        request_generation TEXT NOT NULL,source_generation TEXT NOT NULL,
                        expected_revision INTEGER,expected_epoch INTEGER,state TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL,next_eligible_at INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,claim_token TEXT,prerequisite TEXT,
                        PRIMARY KEY(account_id,kind,target_id)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_20_21: Migration = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `android_contact_issues` (
                        `account_id` TEXT NOT NULL, `canonical_contact_id` TEXT NOT NULL,
                        `origin` TEXT NOT NULL, `provider_epoch` INTEGER NOT NULL,
                        `canonical_revision` INTEGER NOT NULL, `reason` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`, `origin`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                        REFERENCES `contacts`(`account_id`, `id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_19_20: Migration = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `remote_hydration_obligations` (
                        `account_id` TEXT NOT NULL, `remote_contact_id` TEXT NOT NULL,
                        `display_name` TEXT, `category` TEXT NOT NULL, `hydration_category` TEXT,
                        PRIMARY KEY(`account_id`, `remote_contact_id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `android_photo_projection_receipts` (
                        `account_id` TEXT NOT NULL, `canonical_contact_id` TEXT NOT NULL,
                        `android_account_name` TEXT NOT NULL, `provider_epoch` INTEGER NOT NULL,
                        `raw_contact_locator` INTEGER NOT NULL, `data_row_locator` INTEGER NOT NULL,
                        `canonical_value_id` TEXT NOT NULL, `binary_reference` TEXT NOT NULL,
                        `source_sha256` TEXT NOT NULL, `source_size` INTEGER NOT NULL,
                        `readback_sha256` TEXT NOT NULL, `readback_size` INTEGER NOT NULL,
                        `raw_contact_version` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                        REFERENCES `android_projection_ledger`(`account_id`, `canonical_contact_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contact_inventory_checkpoints ADD COLUMN event_cursor TEXT")
            }
        }

        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `contact_conflicts` (
                        `owner_key` TEXT NOT NULL, `account_id` TEXT NOT NULL, `contact_id` TEXT NOT NULL,
                        `generation` TEXT NOT NULL, `local_revision` INTEGER NOT NULL, `remote_id` TEXT NOT NULL,
                        `remote_version` TEXT NOT NULL, `choice` TEXT, `remote_deleted` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`owner_key`), FOREIGN KEY(`owner_key`) REFERENCES `contacts`(`owner_key`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `contact_conflict_chunks` (
                        `owner_key` TEXT NOT NULL, `side` TEXT NOT NULL, `position` INTEGER NOT NULL, `bytes` BLOB NOT NULL,
                        PRIMARY KEY(`owner_key`, `side`, `position`),
                        FOREIGN KEY(`owner_key`) REFERENCES `contact_conflicts`(`owner_key`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_inventory_checkpoints` (
                        `account_id` TEXT NOT NULL,
                        `generation` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_inventory_entries` (
                        `account_id` TEXT NOT NULL,
                        `contact_id` TEXT NOT NULL,
                        `display_name` TEXT,
                        `remote_version` TEXT NOT NULL,
                        `size_bytes` INTEGER NOT NULL,
                        `modified_at_epoch_seconds` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`, `contact_id`),
                        FOREIGN KEY(`account_id`) REFERENCES `contact_inventory_checkpoints`(`account_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_inventory_groups` (
                        `account_id` TEXT NOT NULL,
                        `contact_id` TEXT NOT NULL,
                        `group_id` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `contact_id`, `group_id`),
                        FOREIGN KEY(`account_id`, `contact_id`)
                            REFERENCES `contact_inventory_entries`(`account_id`, `contact_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_inventory_email_memberships` (
                        `account_id` TEXT NOT NULL,
                        `contact_id` TEXT NOT NULL,
                        `email_id` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `contact_id`, `email_id`),
                        FOREIGN KEY(`account_id`, `contact_id`)
                            REFERENCES `contact_inventory_entries`(`account_id`, `contact_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_inventory_email_groups` (
                        `account_id` TEXT NOT NULL,
                        `contact_id` TEXT NOT NULL,
                        `email_id` TEXT NOT NULL,
                        `group_id` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `contact_id`, `email_id`, `group_id`),
                        FOREIGN KEY(`account_id`, `contact_id`, `email_id`)
                            REFERENCES `contact_inventory_email_memberships`(`account_id`, `contact_id`, `email_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `requires_reconciliation` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `last_attempt_at_epoch_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `device_elapsed_realtime_millis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `server_offset_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `calibration_age_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `round_trip_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `server_precision_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `uncertainty_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `interval_earliest_epoch_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `interval_latest_epoch_millis` INTEGER")
                db.execSQL("ALTER TABLE `outbox_mutations` ADD COLUMN `clock_jump_detected` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_account_status` (
                        `account_id` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `last_success_at_epoch_millis` INTEGER,
                        `pending_mutation_count` INTEGER NOT NULL,
                        `action_required_count` INTEGER NOT NULL,
                        `action_reason` TEXT,
                        `blocked_since_epoch_millis` INTEGER,
                        `notification_claimed_for_block_epoch_millis` INTEGER,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `full_repair_progress` (
                        `account_id` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `phase` TEXT NOT NULL,
                        `phase_rank` INTEGER NOT NULL,
                        `completed_units` INTEGER NOT NULL,
                        `total_units` INTEGER,
                        `started_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `non_wifi_confirmed` INTEGER NOT NULL,
                        `cancellation_requested` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_projection_accounts` (
                        `account_id` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_projection_ledger` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `raw_contact_locator` INTEGER,
                        `source_identity` TEXT,
                        `canonical_projection_fingerprint` TEXT,
                        `android_baseline_fingerprint` TEXT,
                        `pending_projection_fingerprint` TEXT,
                        `observed_android_fingerprint` TEXT,
                        `projection_state` TEXT NOT NULL,
                        `ingestion_state` TEXT NOT NULL,
                        `tombstone_state` TEXT NOT NULL,
                        `adoption_state` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`) REFERENCES `android_projection_accounts`(`account_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `contacts`(`account_id`, `id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_android_projection_ledger_account_id_provider_epoch_raw_contact_locator` " +
                        "ON `android_projection_ledger` (`account_id`, `provider_epoch`, `raw_contact_locator`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_android_projection_ledger_account_id_source_identity` " +
                        "ON `android_projection_ledger` (`account_id`, `source_identity`)",
                )
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_provider_identity_owners` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `canonical_value_id` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`, `canonical_value_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `contacts`(`account_id`, `id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_android_provider_identity_owners_account_id_canonical_contact_id` " +
                        "ON `android_provider_identity_owners` (`account_id`, `canonical_contact_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_provider_row_bindings` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `raw_contact_locator` INTEGER NOT NULL,
                        `binding_primary_id` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `canonical_value_id` TEXT NOT NULL,
                        `android_account_name` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `data_row_locator` INTEGER,
                        `state` TEXT NOT NULL,
                        PRIMARY KEY(
                            `account_id`, `canonical_contact_id`, `provider_epoch`,
                            `raw_contact_locator`, `binding_primary_id`, `role`
                        ),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `android_projection_ledger`(`account_id`, `canonical_contact_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`account_id`, `canonical_contact_id`, `canonical_value_id`)
                            REFERENCES `android_provider_identity_owners`(
                                `account_id`, `canonical_contact_id`, `canonical_value_id`
                            ) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_android_provider_row_bindings_account_id_canonical_contact_id` " +
                        "ON `android_provider_row_bindings` (`account_id`, `canonical_contact_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_android_provider_row_bindings_account_id_canonical_contact_id_canonical_value_id` " +
                        "ON `android_provider_row_bindings` " +
                        "(`account_id`, `canonical_contact_id`, `canonical_value_id`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_android_provider_row_bindings_account_id_canonical_contact_id_provider_epoch_canonical_value_id` " +
                        "ON `android_provider_row_bindings` " +
                        "(`account_id`, `canonical_contact_id`, `provider_epoch`, `canonical_value_id`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_android_provider_row_bindings_account_id_canonical_contact_id_provider_epoch_raw_contact_locator_data_row_locator_role` " +
                        "ON `android_provider_row_bindings` " +
                        "(`account_id`, `canonical_contact_id`, `provider_epoch`, `raw_contact_locator`, " +
                        "`data_row_locator`, `role`)",
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_projection_baselines` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `encoded_snapshot` BLOB NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `android_projection_ledger`(`account_id`, `canonical_contact_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                // Schema v7 retained only fingerprints. Claiming BASELINED/CLEAN without the
                // provider-neutral snapshot would make a concurrent Android edit unrecoverable.
                db.execSQL(
                    """
                    UPDATE `android_projection_ledger`
                    SET `android_baseline_fingerprint` = NULL,
                        `pending_projection_fingerprint` = NULL,
                        `observed_android_fingerprint` = NULL,
                        `projection_state` = CASE
                            WHEN `tombstone_state` = 'NONE' THEN 'REPAIR_REQUIRED'
                            ELSE 'DETACHED'
                        END,
                        `ingestion_state` = CASE
                            WHEN `tombstone_state` = 'NONE' THEN 'NONE'
                            ELSE `ingestion_state`
                        END
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_group_projection_ledger` (
                        `account_id` TEXT NOT NULL,
                        `canonical_group_id` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `group_row_locator` INTEGER,
                        `source_identity` TEXT,
                        `canonical_projection_fingerprint` TEXT,
                        `android_baseline_fingerprint` TEXT,
                        `pending_projection_fingerprint` TEXT,
                        `projection_state` TEXT NOT NULL,
                        `ingestion_state` TEXT NOT NULL,
                        `tombstone_state` TEXT NOT NULL,
                        `adoption_state` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_group_id`),
                        FOREIGN KEY(`account_id`) REFERENCES `android_projection_accounts`(`account_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`account_id`, `canonical_group_id`)
                            REFERENCES `contact_groups`(`account_id`, `id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_android_group_projection_ledger_account_id_provider_epoch_group_row_locator` " +
                        "ON `android_group_projection_ledger` " +
                        "(`account_id`, `provider_epoch`, `group_row_locator`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_android_group_projection_ledger_account_id_source_identity` " +
                        "ON `android_group_projection_ledger` (`account_id`, `source_identity`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_group_projection_baselines` (
                        `account_id` TEXT NOT NULL,
                        `canonical_group_id` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `encoded_snapshot` BLOB NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_group_id`),
                        FOREIGN KEY(`account_id`, `canonical_group_id`)
                            REFERENCES `android_group_projection_ledger`(`account_id`, `canonical_group_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_group_membership_projection_ledger` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `raw_contact_locator` INTEGER,
                        `preferred_email_value_id` TEXT,
                        `canonical_projection_fingerprint` TEXT,
                        `android_baseline_fingerprint` TEXT,
                        `pending_projection_fingerprint` TEXT,
                        `projection_state` TEXT NOT NULL,
                        `ingestion_state` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `android_projection_ledger`(`account_id`, `canonical_contact_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_android_group_membership_projection_ledger_account_id_provider_epoch_raw_contact_locator` " +
                        "ON `android_group_membership_projection_ledger` " +
                        "(`account_id`, `provider_epoch`, `raw_contact_locator`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_group_membership_baselines` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `encoded_snapshot` BLOB NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `android_group_membership_projection_ledger`(
                                `account_id`, `canonical_contact_id`
                            ) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_group_membership_commit_receipts` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `expected_account_revision` INTEGER NOT NULL,
                        `expected_membership_ledger_revision` INTEGER NOT NULL,
                        `committed_account_revision` INTEGER NOT NULL,
                        `committed_membership_ledger_revision` INTEGER NOT NULL,
                        `command_fingerprint` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `android_group_membership_projection_ledger`(
                                `account_id`, `canonical_contact_id`
                            ) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `android_projection_accounts` " +
                        "ADD COLUMN `android_account_name` TEXT",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_group_provider_write_journal` (
                        `account_id` TEXT NOT NULL,
                        `canonical_group_id` TEXT NOT NULL,
                        `android_account_name` TEXT NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `operation` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `expected_account_revision` INTEGER NOT NULL,
                        `expected_canonical_group_revision` INTEGER NOT NULL,
                        `expected_group_ledger_revision` INTEGER NOT NULL,
                        `expected_group_row_locator` INTEGER,
                        `expected_provider_version` INTEGER,
                        `expected_source_identity` TEXT,
                        `source_identity_after_write` TEXT,
                        `expected_deleted` INTEGER NOT NULL,
                        `desired_semantic_fingerprint` TEXT,
                        `desired_snapshot_integrity_fingerprint` TEXT,
                        `desired_snapshot` BLOB,
                        `command_fingerprint` TEXT NOT NULL,
                        `result_group_row_locator` INTEGER,
                        `result_provider_version` INTEGER,
                        `result_source_identity` TEXT,
                        `result_deleted` INTEGER,
                        `result_provider_state_fingerprint` TEXT,
                        `completed_account_revision` INTEGER,
                        `completed_group_ledger_revision` INTEGER,
                        PRIMARY KEY(`account_id`, `canonical_group_id`),
                        FOREIGN KEY(`account_id`, `canonical_group_id`)
                            REFERENCES `android_group_projection_ledger`(`account_id`, `canonical_group_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_android_group_provider_write_journal_account_id_state` " +
                        "ON `android_group_provider_write_journal` (`account_id`, `state`)",
                )
            }
        }

        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `android_group_projection_ledger` " +
                        "ADD COLUMN `provider_version` INTEGER",
                )
                db.execSQL(
                    "UPDATE `android_group_projection_ledger` SET `provider_version` = (" +
                        "SELECT CASE WHEN j.completed_group_ledger_revision IS NOT NULL " +
                        "THEN j.result_provider_version ELSE j.expected_provider_version END " +
                        "FROM `android_group_provider_write_journal` j " +
                        "WHERE j.account_id = android_group_projection_ledger.account_id " +
                        "AND j.canonical_group_id = android_group_projection_ledger.canonical_group_id" +
                        ") WHERE EXISTS (SELECT 1 FROM `android_group_provider_write_journal` j " +
                        "WHERE j.account_id = android_group_projection_ledger.account_id " +
                        "AND j.canonical_group_id = android_group_projection_ledger.canonical_group_id)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_group_observation_commit_receipts` (
                        `account_id` TEXT NOT NULL,
                        `canonical_group_id` TEXT NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `expected_account_revision` INTEGER NOT NULL,
                        `expected_group_ledger_revision` INTEGER NOT NULL,
                        `committed_account_revision` INTEGER NOT NULL,
                        `committed_group_ledger_revision` INTEGER NOT NULL,
                        `command_fingerprint` TEXT NOT NULL,
                        `post_state_fingerprint` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_group_id`),
                        FOREIGN KEY(`account_id`, `canonical_group_id`)
                            REFERENCES `android_group_projection_ledger`(`account_id`, `canonical_group_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_unified_observation_commit_receipts` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `raw_contact_locator` INTEGER NOT NULL,
                        `raw_contact_version` INTEGER NOT NULL,
                        `expected_account_revision` INTEGER NOT NULL,
                        `committed_account_revision` INTEGER NOT NULL,
                        `committed_contact_ledger_revision` INTEGER NOT NULL,
                        `committed_membership_ledger_revision` INTEGER NOT NULL,
                        `command_fingerprint` TEXT NOT NULL,
                        `post_state_fingerprint` TEXT NOT NULL,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `android_projection_ledger`(`account_id`, `canonical_contact_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `android_projection_accounts` " +
                        "ADD COLUMN `provider_repair_pending` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `android_photo_provider_write_journal` (
                        `account_id` TEXT NOT NULL,
                        `canonical_contact_id` TEXT NOT NULL,
                        `android_account_name` TEXT NOT NULL,
                        `provider_epoch` INTEGER NOT NULL,
                        `raw_contact_locator` INTEGER NOT NULL,
                        `expected_raw_contact_version` INTEGER NOT NULL,
                        `expected_source_identity` TEXT NOT NULL,
                        `expected_canonical_revision` INTEGER NOT NULL,
                        `expected_ledger_revision` INTEGER NOT NULL,
                        `canonical_value_id` TEXT NOT NULL,
                        `binary_reference` TEXT NOT NULL,
                        `content_size` INTEGER NOT NULL,
                        `content_sha256` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `result_raw_contact_version` INTEGER,
                        PRIMARY KEY(`account_id`, `canonical_contact_id`),
                        FOREIGN KEY(`account_id`, `canonical_contact_id`)
                            REFERENCES `android_projection_ledger`(`account_id`, `canonical_contact_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_android_photo_provider_write_journal_account_id_state` " +
                        "ON `android_photo_provider_write_journal` (`account_id`, `state`)",
                )
            }
        }

        /**
         * `D-096`: contact inventory size and modification time become nullable.
         *
         * The maintained public directory carries neither, so the columns cannot stay NOT NULL.
         * SQLite cannot relax a column constraint in place, so the table is rebuilt. Existing rows
         * came from an authoritative inventory and keep their values; nothing is discarded.
         */
        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_inventory_entries_new` (
                        `account_id` TEXT NOT NULL,
                        `contact_id` TEXT NOT NULL,
                        `display_name` TEXT,
                        `remote_version` TEXT NOT NULL,
                        `size_bytes` INTEGER,
                        `modified_at_epoch_seconds` INTEGER,
                        PRIMARY KEY(`account_id`, `contact_id`),
                        FOREIGN KEY(`account_id`)
                            REFERENCES `contact_inventory_checkpoints`(`account_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO `contact_inventory_entries_new` " +
                        "(`account_id`, `contact_id`, `display_name`, `remote_version`, " +
                        "`size_bytes`, `modified_at_epoch_seconds`) " +
                        "SELECT `account_id`, `contact_id`, `display_name`, `remote_version`, " +
                        "`size_bytes`, `modified_at_epoch_seconds` FROM `contact_inventory_entries`",
                )
                db.execSQL("DROP TABLE `contact_inventory_entries`")
                db.execSQL(
                    "ALTER TABLE `contact_inventory_entries_new` RENAME TO `contact_inventory_entries`",
                )
            }
        }
    }
}
