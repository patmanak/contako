package com.patmanak.contako.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

internal data class ContactWithValues(
    @Embedded val contact: ContactEntity,
    @Relation(
        parentColumn = "owner_key",
        entityColumn = "owner_key",
    )
    val values: List<ContactValueEntity>,
)

@Dao
internal interface ContactDao {
    @Transaction
    @Query(
        """
        SELECT * FROM contacts
        WHERE account_id = :accountId AND is_deleted = 0
        ORDER BY sort_name COLLATE NOCASE, id
        """,
    )
    fun observeActive(accountId: String): Flow<List<ContactWithValues>>

    @Transaction
    @Query("SELECT * FROM contacts WHERE account_id = :accountId AND id = :contactId")
    suspend fun get(accountId: String, contactId: String): ContactWithValues?

    @Transaction
    @Query("SELECT * FROM contacts WHERE account_id = :accountId AND id IN (:contactIds)")
    suspend fun getForProjectionPage(accountId: String, contactIds: List<String>): List<ContactWithValues>

    @Transaction
    @Query("SELECT * FROM contacts WHERE account_id = :accountId AND remote_contact_id = :remoteContactId LIMIT 1")
    suspend fun getByRemoteContactId(accountId: String, remoteContactId: String): ContactWithValues?

    @Transaction
    @Query("SELECT * FROM contacts WHERE account_id = :accountId AND remote_vcard_uid = :remoteVCardUid LIMIT 1")
    suspend fun getByRemoteVCardUid(accountId: String, remoteVCardUid: String): ContactWithValues?

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(contact: ContactEntity): Long

    @Query(
        "UPDATE contacts SET revision = revision + 1 " +
            "WHERE account_id = :accountId AND id = :contactId AND revision = :expectedRevision",
    )
    suspend fun compareAndSetRevision(accountId: String, contactId: String, expectedRevision: Long): Int

    @Query("SELECT id FROM contact_values WHERE account_id = :accountId AND contact_id = :contactId")
    suspend fun getValueIds(accountId: String, contactId: String): List<String>

    @Query(
        """
        SELECT * FROM contact_values
        WHERE account_id = :accountId AND contact_id = :contactId AND id = :valueId
        """,
    )
    suspend fun getValue(accountId: String, contactId: String, valueId: String): ContactValueEntity?

    @Query("DELETE FROM contact_values WHERE account_id = :accountId AND contact_id = :contactId")
    suspend fun deleteValues(accountId: String, contactId: String)

    @Query(
        """
        DELETE FROM contact_values
        WHERE account_id = :accountId AND contact_id = :contactId AND id NOT IN (:retainedIds)
        """,
    )
    suspend fun deleteValuesNotIn(accountId: String, contactId: String, retainedIds: List<String>)

    @Upsert
    suspend fun upsertValues(values: List<ContactValueEntity>)
}

@Dao
internal interface ContactPayloadDao {
    @Query("SELECT * FROM contact_payloads WHERE owner_key = :ownerKey")
    suspend fun get(ownerKey: String): ContactPayloadEntity?

    @Upsert
    suspend fun upsert(payload: ContactPayloadEntity)
}

@Dao
internal interface ContactGroupDao {
    @Transaction
    @Query(
        """
        SELECT * FROM contact_groups
        WHERE account_id = :accountId AND is_deleted = 0
        ORDER BY name COLLATE NOCASE, id
        """,
    )
    fun observeActive(accountId: String): Flow<List<ContactGroupWithMemberships>>

    @Transaction
    @Query("SELECT * FROM contact_groups WHERE account_id = :accountId AND id = :groupId")
    suspend fun get(accountId: String, groupId: String): ContactGroupWithMemberships?

    @Transaction
    @Query("SELECT * FROM contact_groups WHERE account_id = :accountId ORDER BY id")
    suspend fun getAll(accountId: String): List<ContactGroupWithMemberships>

    @Transaction
    @Query("SELECT * FROM contact_groups WHERE account_id = :accountId AND remote_label_id = :remoteLabelId LIMIT 1")
    suspend fun getByRemoteLabelId(accountId: String, remoteLabelId: String): ContactGroupWithMemberships?

    @Upsert
    suspend fun upsert(group: ContactGroupEntity)

    @Query(
        "UPDATE contact_groups SET revision = revision + 1 " +
            "WHERE account_id = :accountId AND id = :groupId AND revision = :expectedRevision",
    )
    suspend fun compareAndSetRevision(
        accountId: String,
        groupId: String,
        expectedRevision: Long,
    ): Int

    @Query("DELETE FROM group_memberships WHERE account_id = :accountId AND group_id = :groupId")
    suspend fun deleteMemberships(accountId: String, groupId: String)

    @Query(
        "SELECT COUNT(*) FROM group_memberships WHERE account_id = :accountId AND group_id = :groupId",
    )
    suspend fun countMembershipsForGroup(accountId: String, groupId: String): Int

    @Query(
        "DELETE FROM group_memberships " +
            "WHERE account_id = :accountId AND group_id = :groupId " +
            "AND contact_id = :contactId AND email_value_id = :emailValueId",
    )
    suspend fun deleteMembership(
        accountId: String,
        groupId: String,
        contactId: String,
        emailValueId: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemberships(memberships: List<GroupMembershipEntity>)

    @Query(
        """
        SELECT DISTINCT group_id FROM group_memberships
        WHERE account_id = :accountId AND contact_id = :contactId
          AND email_value_id IN (:emailValueIds)
        """,
    )
    suspend fun findGroupIdsForEmailValues(
        accountId: String,
        contactId: String,
        emailValueIds: List<String>,
    ): List<String>
}

internal data class ContactGroupWithMemberships(
    @Embedded val group: ContactGroupEntity,
    @Relation(
        parentColumn = "owner_key",
        entityColumn = "group_owner_key",
    )
    val memberships: List<GroupMembershipEntity>,
)

@Dao
internal interface OutboxDao {
    @Query(
        """
        SELECT * FROM outbox_mutations
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId
        """,
    )
    suspend fun get(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
    ): OutboxMutationEntity?

    @Upsert
    suspend fun upsert(mutation: OutboxMutationEntity)

    @Query("SELECT COUNT(*) FROM outbox_mutations WHERE account_id = :accountId")
    fun observePendingCount(accountId: String): Flow<Int>

    @Query("SELECT * FROM outbox_mutations WHERE account_id = :accountId ORDER BY created_at_epoch_millis")
    suspend fun getAll(accountId: String): List<OutboxMutationEntity>

    @Query(
        """
        SELECT * FROM outbox_mutations
        WHERE account_id = :accountId AND (state = 'ACTION_REQUIRED' OR blocked_reason IS NOT NULL)
        ORDER BY created_at_epoch_millis, aggregate_type, aggregate_id
        """,
    )
    suspend fun getActionRequired(accountId: String): List<OutboxMutationEntity>

    @Query("""
        SELECT aggregate_id FROM outbox_mutations
        WHERE account_id = :accountId AND aggregate_type = 'CONTACT'
          AND (state = 'ACTION_REQUIRED' OR blocked_reason IS NOT NULL)
        ORDER BY created_at_epoch_millis, aggregate_id
    """)
    fun observeBlockedContactIds(accountId: String): Flow<List<String>>

    @Query(
        """
        SELECT * FROM outbox_mutations
        WHERE account_id = :accountId AND state = 'PENDING' AND blocked_reason IS NULL
          AND next_attempt_at_epoch_millis <= :nowEpochMillis
        ORDER BY created_at_epoch_millis, aggregate_type, aggregate_id
        LIMIT :limit
        """,
    )
    suspend fun getEligible(accountId: String, nowEpochMillis: Long, limit: Int): List<OutboxMutationEntity>

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'IN_FLIGHT', last_attempt_at_epoch_millis = :nowEpochMillis
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision
          AND state = 'PENDING' AND blocked_reason IS NULL
          AND next_attempt_at_epoch_millis <= :nowEpochMillis
        """,
    )
    suspend fun claim(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        nowEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'PENDING', attempt_count = :attemptCount,
          next_attempt_at_epoch_millis = :nextAttemptAtEpochMillis,
          error_category = :errorCategory, requires_reconciliation = :requiresReconciliation
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision AND state = 'IN_FLIGHT'
        """,
    )
    suspend fun markRetry(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        attemptCount: Int,
        nextAttemptAtEpochMillis: Long,
        errorCategory: String,
        requiresReconciliation: Boolean,
    ): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'ACTION_REQUIRED', blocked_reason = :blockedReason,
          error_category = :errorCategory
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision AND state = 'IN_FLIGHT'
        """,
    )
    suspend fun markActionRequired(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        blockedReason: String,
        errorCategory: String,
    ): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'ACKNOWLEDGED', remote_identity = :remoteIdentity,
          remote_version = :remoteVersion, error_category = NULL,
          requires_reconciliation = 0
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision AND state = 'IN_FLIGHT'
        """,
    )
    suspend fun acknowledge(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        remoteIdentity: String?,
        remoteVersion: String?,
    ): Int

    @Query(
        """
        DELETE FROM outbox_mutations
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision AND state = 'ACKNOWLEDGED'
        """,
    )
    suspend fun deleteAcknowledged(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
    ): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'PENDING', requires_reconciliation = 1, error_category = 'UNKNOWN',
          next_attempt_at_epoch_millis = :nowEpochMillis
        WHERE account_id = :accountId AND state = 'IN_FLIGHT'
        """,
    )
    suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET remote_identity = :remoteIdentity, remote_version = :remoteVersion
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision AND state = 'PENDING'
        """,
    )
    suspend fun updateCheckedRemoteBaseline(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        remoteIdentity: String?,
        remoteVersion: String?,
    ): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'PENDING', blocked_reason = NULL, error_category = NULL,
          next_attempt_at_epoch_millis = 0
        WHERE account_id = :accountId AND aggregate_type = 'CONTACT'
          AND aggregate_id = :contactId AND revision = :revision AND operation = 'DELETE'
          AND state = 'ACTION_REQUIRED' AND error_category = 'CONFLICT'
          AND blocked_reason = 'EDIT_DELETE_RECOVERY_REQUIRED'
        """,
    )
    suspend fun resumeUnchangedDeleteConflict(accountId: String, contactId: String, revision: Long): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'ACTION_REQUIRED', blocked_reason = :blockedReason,
          error_category = 'CONFLICT'
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision
          AND state IN ('PENDING', 'IN_FLIGHT')
        """,
    )
    suspend fun blockConflict(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        blockedReason: String,
    ): Int

    @Query(
        """
        DELETE FROM outbox_mutations
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision
        """,
    )
    suspend fun deleteRevision(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
    ): Int

    @Query(
        """
        UPDATE outbox_mutations
        SET state = 'PENDING', next_attempt_at_epoch_millis = :nowEpochMillis,
          error_category = NULL, requires_reconciliation = 0, operation = :operation,
          remote_identity = :remoteIdentity, remote_version = :remoteVersion
        WHERE account_id = :accountId AND aggregate_type = :aggregateType
          AND aggregate_id = :aggregateId AND revision = :revision AND state = 'IN_FLIGHT'
        """,
    )
    suspend fun markProgressPending(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        nowEpochMillis: Long,
        operation: String,
        remoteIdentity: String?,
        remoteVersion: String?,
    ): Int
}

@Dao
internal interface ContactInventoryCheckpointDao {
    @Query("SELECT * FROM contact_inventory_checkpoints WHERE account_id = :accountId")
    suspend fun getCheckpoint(accountId: String): ContactInventoryCheckpointEntity?

    @Query("SELECT * FROM contact_inventory_entries WHERE account_id = :accountId ORDER BY contact_id")
    suspend fun getEntries(accountId: String): List<ContactInventoryEntryEntity>

    @Query(
        "SELECT * FROM contact_inventory_groups " +
            "WHERE account_id = :accountId ORDER BY contact_id, group_id",
    )
    suspend fun getGroups(accountId: String): List<ContactInventoryGroupEntity>

    @Query(
        "SELECT * FROM contact_inventory_email_memberships " +
            "WHERE account_id = :accountId ORDER BY contact_id, email_id",
    )
    suspend fun getEmailMemberships(accountId: String): List<ContactInventoryEmailMembershipEntity>

    @Query(
        "SELECT * FROM contact_inventory_email_groups " +
            "WHERE account_id = :accountId ORDER BY contact_id, email_id, group_id",
    )
    suspend fun getEmailGroups(accountId: String): List<ContactInventoryEmailGroupEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInitialCheckpoint(checkpoint: ContactInventoryCheckpointEntity): Long

    @Query(
        "UPDATE contact_inventory_checkpoints SET generation = :nextGeneration " +
            "WHERE account_id = :accountId AND generation = :expectedGeneration",
    )
    suspend fun compareAndSetGeneration(
        accountId: String,
        expectedGeneration: Long,
        nextGeneration: Long,
    ): Int

    @Query("DELETE FROM contact_inventory_entries WHERE account_id = :accountId")
    suspend fun deleteEntries(accountId: String)

    @Insert
    suspend fun insertEntries(entries: List<ContactInventoryEntryEntity>)

    @Insert
    suspend fun insertGroups(groups: List<ContactInventoryGroupEntity>)

    @Insert
    suspend fun insertEmailMemberships(memberships: List<ContactInventoryEmailMembershipEntity>)

    @Insert
    suspend fun insertEmailGroups(groups: List<ContactInventoryEmailGroupEntity>)
}

@Dao
internal interface SyncAccountStatusDao {
    @Query("SELECT * FROM sync_account_status WHERE account_id = :accountId")
    fun observe(accountId: String): Flow<SyncAccountStatusEntity?>

    @Query("SELECT * FROM sync_account_status WHERE account_id = :accountId")
    suspend fun get(accountId: String): SyncAccountStatusEntity?

    @Upsert
    suspend fun upsert(status: SyncAccountStatusEntity)

    @Query(
        """
        UPDATE sync_account_status
        SET notification_claimed_for_block_epoch_millis = :blockedSinceEpochMillis
        WHERE account_id = :accountId AND state = 'ACTION_REQUIRED'
          AND blocked_since_epoch_millis = :blockedSinceEpochMillis
          AND notification_claimed_for_block_epoch_millis IS NULL
        """,
    )
    suspend fun claimNotification(accountId: String, blockedSinceEpochMillis: Long): Int

    @Query("DELETE FROM sync_account_status WHERE account_id = :accountId")
    suspend fun delete(accountId: String): Int
}

@Dao
internal interface FullRepairProgressDao {
    @Query("SELECT * FROM full_repair_progress WHERE account_id = :accountId")
    fun observe(accountId: String): Flow<FullRepairProgressEntity?>

    @Query("SELECT * FROM full_repair_progress WHERE account_id = :accountId")
    suspend fun get(accountId: String): FullRepairProgressEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(progress: FullRepairProgressEntity): Long

    @Query(
        """
        UPDATE full_repair_progress
        SET revision = revision + 1, phase = :phase, phase_rank = :phaseRank,
          completed_units = :completedUnits, total_units = :totalUnits,
          updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE account_id = :accountId AND revision = :expectedRevision
          AND cancellation_requested = 0
        """,
    )
    suspend fun checkpoint(
        accountId: String,
        expectedRevision: Long,
        phase: String,
        phaseRank: Int,
        completedUnits: Long,
        totalUnits: Long?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE full_repair_progress
        SET revision = revision + 1, cancellation_requested = 1,
          updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE account_id = :accountId AND revision = :expectedRevision
          AND cancellation_requested = 0
        """,
    )
    suspend fun requestCancellation(
        accountId: String,
        expectedRevision: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM full_repair_progress
        WHERE account_id = :accountId AND revision = :expectedRevision
          AND cancellation_requested = 1
        """,
    )
    suspend fun clearCancelled(accountId: String, expectedRevision: Long): Int

    @Query(
        """
        DELETE FROM full_repair_progress
        WHERE account_id = :accountId AND revision = :expectedRevision
          AND cancellation_requested = 0 AND phase = 'PUBLISHING'
          AND total_units IS NOT NULL AND completed_units = total_units
        """,
    )
    suspend fun clearPublished(accountId: String, expectedRevision: Long): Int
}

@Dao
internal interface AndroidProjectionLedgerDao {
    @Query("SELECT * FROM android_projection_accounts WHERE account_id = :accountId")
    suspend fun getAccount(accountId: String): AndroidProjectionAccountEntity?

    @Query(
        "SELECT COUNT(*) FROM android_projection_accounts " +
            "WHERE android_account_name = :androidAccountName AND account_id != :accountId",
    )
    suspend fun countOtherAccountsBoundToAndroidName(
        accountId: String,
        androidAccountName: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(account: AndroidProjectionAccountEntity): Long

    @Query(
        "UPDATE android_projection_accounts " +
            "SET revision = revision + 1, android_account_name = :androidAccountName " +
            "WHERE account_id = :accountId AND revision = :expectedRevision " +
            "AND android_account_name IS NULL",
    )
    suspend fun bindAndroidAccountName(
        accountId: String,
        expectedRevision: Long,
        androidAccountName: String,
    ): Int

    @Query(
        "UPDATE android_projection_accounts " +
            "SET revision = revision + 1, provider_epoch = provider_epoch + 1, provider_repair_pending = 1 " +
            "WHERE account_id = :accountId AND revision = :expectedRevision",
    )
    suspend fun advanceProviderEpoch(accountId: String, expectedRevision: Long): Int

    @Query(
        "UPDATE android_projection_accounts SET revision = revision + 1, " +
            "provider_epoch = provider_epoch + 1, provider_repair_pending = 1 " +
            "WHERE account_id = :accountId AND revision = :expectedRevision " +
            "AND provider_repair_pending = 0",
    )
    suspend fun beginProviderRepair(accountId: String, expectedRevision: Long): Int

    @Query(
        "UPDATE android_projection_accounts SET revision = revision + 1, provider_repair_pending = 0 " +
            "WHERE account_id = :accountId AND revision = :expectedRevision " +
            "AND provider_epoch = :expectedProviderEpoch AND provider_repair_pending = 1",
    )
    suspend fun completeProviderRepair(
        accountId: String,
        expectedRevision: Long,
        expectedProviderEpoch: Long,
    ): Int

    @Query(
        "UPDATE android_projection_accounts SET revision = revision + 1 " +
            "WHERE account_id = :accountId AND revision = :expectedRevision " +
            "AND provider_epoch = :expectedProviderEpoch",
    )
    suspend fun compareAndSetAccountRevisionAtProviderEpoch(
        accountId: String,
        expectedRevision: Long,
        expectedProviderEpoch: Long,
    ): Int

    @Query(
        "SELECT * FROM android_projection_ledger " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun get(accountId: String, canonicalContactId: String): AndroidProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_projection_ledger " +
            "WHERE account_id = :accountId AND source_identity = :sourceIdentity LIMIT 1",
    )
    suspend fun getBySourceIdentity(accountId: String, sourceIdentity: String): AndroidProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_projection_ledger " +
            "WHERE account_id = :accountId AND provider_epoch = :providerEpoch " +
            "AND raw_contact_locator = :rawContactLocator LIMIT 1",
    )
    suspend fun getByRawContactLocator(
        accountId: String,
        providerEpoch: Long,
        rawContactLocator: Long,
    ): AndroidProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_projection_ledger " +
            "WHERE account_id = :accountId ORDER BY canonical_contact_id",
    )
    suspend fun getAll(accountId: String): List<AndroidProjectionLedgerEntity>

    @Query(
        "SELECT c.id FROM contacts c LEFT JOIN android_projection_ledger l " +
            "ON l.account_id=c.account_id AND l.canonical_contact_id=c.id " +
            "WHERE c.account_id=:accountId AND c.is_deleted=0 " +
            "AND (l.canonical_contact_id IS NULL OR l.projection_state<>'CLEAN' " +
            "OR l.pending_projection_fingerprint IS NOT NULL)",
    )
    fun observePendingContactIds(accountId: String): Flow<List<String>>

    @Query(
        "SELECT c.* FROM contacts c LEFT JOIN android_projection_ledger l " +
            "ON l.account_id=c.account_id AND l.canonical_contact_id=c.id " +
            "WHERE c.account_id=:accountId AND c.id>:afterCanonicalContactId " +
            "AND c.is_deleted=0 AND c.remote_contact_id IS NOT NULL AND c.conflict_state IS NULL " +
            "AND l.canonical_contact_id IS NULL ORDER BY c.id LIMIT :limit",
    )
    suspend fun getContactsMissingProjectionLedger(
        accountId: String, afterCanonicalContactId: String, limit: Int,
    ): List<ContactEntity>

    @Query(
        "SELECT * FROM android_projection_ledger " +
            "WHERE account_id = :accountId AND canonical_contact_id > :afterCanonicalContactId " +
            "ORDER BY canonical_contact_id LIMIT :limit",
    )
    suspend fun getProjectionPage(
        accountId: String,
        afterCanonicalContactId: String,
        limit: Int,
    ): List<AndroidProjectionLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: AndroidProjectionLedgerEntity): Long

    @Query(
        "UPDATE android_projection_ledger SET revision = revision + 1 " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND revision = :expectedRevision",
    )
    suspend fun compareAndSetRevision(
        accountId: String,
        canonicalContactId: String,
        expectedRevision: Long,
    ): Int

    @Update
    suspend fun update(entry: AndroidProjectionLedgerEntity): Int

    @Update
    suspend fun update(entries: List<AndroidProjectionLedgerEntity>): Int

    @Query(
        "SELECT * FROM android_projection_baselines " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun getBaseline(
        accountId: String,
        canonicalContactId: String,
    ): AndroidProjectionBaselineEntity?

    @Upsert
    suspend fun upsertBaseline(baseline: AndroidProjectionBaselineEntity)

    @Query(
        "DELETE FROM android_projection_baselines " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun deleteBaseline(accountId: String, canonicalContactId: String): Int

    @Query("DELETE FROM android_projection_baselines WHERE account_id = :accountId")
    suspend fun deleteBaselines(accountId: String): Int

    @Query(
        "SELECT * FROM android_unified_observation_commit_receipts " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun getUnifiedObservationCommitReceipt(
        accountId: String,
        canonicalContactId: String,
    ): AndroidUnifiedObservationCommitReceiptEntity?

    @Upsert
    suspend fun upsertUnifiedObservationCommitReceipt(receipt: AndroidUnifiedObservationCommitReceiptEntity)

    @Query("DELETE FROM android_unified_observation_commit_receipts WHERE account_id = :accountId")
    suspend fun deleteUnifiedObservationCommitReceipts(accountId: String): Int
}

@Dao
internal interface AccountRemovalDao {
    @Query("SELECT COUNT(*) FROM outbox_mutations WHERE account_id = :accountId")
    suspend fun countPending(accountId: String): Int

    @Query("DELETE FROM outbox_mutations WHERE account_id = :accountId")
    suspend fun deleteOutbox(accountId: String)

    @Query("DELETE FROM sync_account_status WHERE account_id = :accountId")
    suspend fun deleteSyncStatus(accountId: String)

    @Query("DELETE FROM full_repair_progress WHERE account_id = :accountId")
    suspend fun deleteFullRepair(accountId: String)

    @Query("DELETE FROM contact_inventory_checkpoints WHERE account_id = :accountId")
    suspend fun deleteInventory(accountId: String)

    @Query("DELETE FROM android_projection_accounts WHERE account_id = :accountId")
    suspend fun deleteAndroidProjection(accountId: String)

    @Query("DELETE FROM contact_groups WHERE account_id = :accountId")
    suspend fun deleteGroups(accountId: String)

    @Query("DELETE FROM contacts WHERE account_id = :accountId")
    suspend fun deleteContacts(accountId: String)

    @Transaction
    suspend fun deleteAccount(accountId: String) {
        require(accountId.isNotBlank())
        deleteOutbox(accountId)
        deleteSyncStatus(accountId)
        deleteFullRepair(accountId)
        deleteInventory(accountId)
        deleteAndroidProjection(accountId)
        deleteGroups(accountId)
        deleteContacts(accountId)
    }
}

@Dao
internal interface AndroidGroupProjectionDao {
    @Query(
        "SELECT * FROM android_group_projection_ledger " +
            "WHERE account_id = :accountId AND canonical_group_id = :canonicalGroupId",
    )
    suspend fun getGroup(
        accountId: String,
        canonicalGroupId: String,
    ): AndroidGroupProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_group_projection_ledger " +
            "WHERE account_id = :accountId AND source_identity = :sourceIdentity LIMIT 1",
    )
    suspend fun getGroupBySourceIdentity(
        accountId: String,
        sourceIdentity: String,
    ): AndroidGroupProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_group_projection_ledger " +
            "WHERE account_id = :accountId AND provider_epoch = :providerEpoch " +
            "AND group_row_locator = :groupRowLocator LIMIT 1",
    )
    suspend fun getGroupByLocator(
        accountId: String,
        providerEpoch: Long,
        groupRowLocator: Long,
    ): AndroidGroupProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_group_projection_ledger " +
            "WHERE account_id = :accountId ORDER BY canonical_group_id",
    )
    suspend fun getAllGroups(accountId: String): List<AndroidGroupProjectionLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(entry: AndroidGroupProjectionLedgerEntity): Long

    @Query(
        "UPDATE android_group_projection_ledger SET revision = revision + 1 " +
            "WHERE account_id = :accountId AND canonical_group_id = :canonicalGroupId " +
            "AND revision = :expectedRevision",
    )
    suspend fun compareAndSetGroupRevision(
        accountId: String,
        canonicalGroupId: String,
        expectedRevision: Long,
    ): Int

    @Update
    suspend fun updateGroup(entry: AndroidGroupProjectionLedgerEntity): Int

    @Update
    suspend fun updateGroups(entries: List<AndroidGroupProjectionLedgerEntity>): Int

    @Query(
        "SELECT * FROM android_group_projection_baselines " +
            "WHERE account_id = :accountId AND canonical_group_id = :canonicalGroupId",
    )
    suspend fun getGroupBaseline(
        accountId: String,
        canonicalGroupId: String,
    ): AndroidGroupProjectionBaselineEntity?

    @Upsert
    suspend fun upsertGroupBaseline(baseline: AndroidGroupProjectionBaselineEntity)

    @Query(
        "DELETE FROM android_group_projection_baselines " +
            "WHERE account_id = :accountId AND canonical_group_id = :canonicalGroupId",
    )
    suspend fun deleteGroupBaseline(accountId: String, canonicalGroupId: String): Int

    @Query("DELETE FROM android_group_projection_baselines WHERE account_id = :accountId")
    suspend fun deleteGroupBaselines(accountId: String): Int

    @Query(
        "SELECT * FROM android_group_membership_projection_ledger " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun getMembership(
        accountId: String,
        canonicalContactId: String,
    ): AndroidGroupMembershipProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_group_membership_projection_ledger " +
            "WHERE account_id = :accountId ORDER BY canonical_contact_id",
    )
    suspend fun getAllMemberships(accountId: String): List<AndroidGroupMembershipProjectionLedgerEntity>

    @Query(
        "SELECT * FROM android_group_membership_projection_ledger " +
            "WHERE account_id = :accountId AND canonical_contact_id IN (:contactIds)",
    )
    suspend fun getMembershipsForProjectionPage(
        accountId: String,
        contactIds: List<String>,
    ): List<AndroidGroupMembershipProjectionLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(entry: AndroidGroupMembershipProjectionLedgerEntity): Long

    @Query(
        "UPDATE android_group_membership_projection_ledger SET revision = revision + 1 " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND revision = :expectedRevision",
    )
    suspend fun compareAndSetMembershipRevision(
        accountId: String,
        canonicalContactId: String,
        expectedRevision: Long,
    ): Int

    @Update
    suspend fun updateMembership(entry: AndroidGroupMembershipProjectionLedgerEntity): Int

    @Query(
        "DELETE FROM android_group_membership_commit_receipts " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun deleteMembershipCommitReceipt(accountId: String, canonicalContactId: String): Int

    @Update
    suspend fun updateMemberships(entries: List<AndroidGroupMembershipProjectionLedgerEntity>): Int

    @Query(
        "SELECT * FROM android_group_membership_baselines " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun getMembershipBaseline(
        accountId: String,
        canonicalContactId: String,
    ): AndroidGroupMembershipBaselineEntity?

    @Query(
        "SELECT * FROM android_group_membership_baselines " +
            "WHERE account_id = :accountId AND canonical_contact_id IN (:contactIds)",
    )
    suspend fun getMembershipBaselinesForProjectionPage(
        accountId: String,
        contactIds: List<String>,
    ): List<AndroidGroupMembershipBaselineEntity>

    @Upsert
    suspend fun upsertMembershipBaseline(baseline: AndroidGroupMembershipBaselineEntity)

    @Query(
        "DELETE FROM android_group_membership_baselines " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun deleteMembershipBaseline(accountId: String, canonicalContactId: String): Int

    @Query("DELETE FROM android_group_membership_baselines WHERE account_id = :accountId")
    suspend fun deleteMembershipBaselines(accountId: String): Int

    @Query(
        "SELECT * FROM android_group_membership_commit_receipts " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun getMembershipCommitReceipt(
        accountId: String,
        canonicalContactId: String,
    ): AndroidGroupMembershipCommitReceiptEntity?

    @Upsert
    suspend fun upsertMembershipCommitReceipt(receipt: AndroidGroupMembershipCommitReceiptEntity)

    @Query("DELETE FROM android_group_membership_commit_receipts WHERE account_id = :accountId")
    suspend fun deleteMembershipCommitReceipts(accountId: String): Int

    @Query(
        "SELECT * FROM android_group_observation_commit_receipts " +
            "WHERE account_id = :accountId AND canonical_group_id = :canonicalGroupId",
    )
    suspend fun getGroupObservationCommitReceipt(
        accountId: String,
        canonicalGroupId: String,
    ): AndroidGroupObservationCommitReceiptEntity?

    @Upsert
    suspend fun upsertGroupObservationCommitReceipt(receipt: AndroidGroupObservationCommitReceiptEntity)

    @Query("DELETE FROM android_group_observation_commit_receipts WHERE account_id = :accountId")
    suspend fun deleteGroupObservationCommitReceipts(accountId: String): Int

    @Query(
        "SELECT * FROM android_group_provider_write_journal " +
            "WHERE account_id = :accountId AND canonical_group_id = :canonicalGroupId",
    )
    suspend fun getGroupProviderWriteJournal(
        accountId: String,
        canonicalGroupId: String,
    ): AndroidGroupProviderWriteJournalEntity?

    @Upsert
    suspend fun upsertGroupProviderWriteJournal(journal: AndroidGroupProviderWriteJournalEntity)

    @Query(
        "DELETE FROM android_group_provider_write_journal " +
            "WHERE account_id = :accountId AND canonical_group_id = :canonicalGroupId " +
            "AND command_fingerprint = :commandFingerprint AND state = :state",
    )
    suspend fun deleteGroupProviderWriteJournal(
        accountId: String,
        canonicalGroupId: String,
        commandFingerprint: String,
        state: String,
    ): Int

    @Query("DELETE FROM android_group_provider_write_journal WHERE account_id = :accountId")
    suspend fun deleteGroupProviderWriteJournals(accountId: String): Int

    @Query(
        "SELECT * FROM android_photo_provider_write_journal " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId",
    )
    suspend fun getPhotoProviderWriteJournal(
        accountId: String,
        canonicalContactId: String,
    ): AndroidPhotoProviderWriteJournalEntity?

    @Upsert
    suspend fun upsertPhotoProviderWriteJournal(journal: AndroidPhotoProviderWriteJournalEntity)

    @Query(
        "DELETE FROM android_photo_provider_write_journal " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND content_sha256 = :contentSha256 AND state = 'COMMITTED'",
    )
    suspend fun deleteCommittedPhotoProviderWriteJournal(
        accountId: String,
        canonicalContactId: String,
        contentSha256: String,
    ): Int
}

@Dao
internal interface AndroidProviderIdentityDao {
    @Query(
        "SELECT * FROM android_projection_ledger " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND provider_epoch = :providerEpoch AND raw_contact_locator = :rawContactLocator",
    )
    fun getLedgerScope(
        accountId: String,
        canonicalContactId: String,
        providerEpoch: Long,
        rawContactLocator: Long,
    ): AndroidProjectionLedgerEntity?

    @Query(
        "SELECT * FROM android_provider_identity_owners " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND canonical_value_id = :canonicalValueId",
    )
    fun getOwner(
        accountId: String,
        canonicalContactId: String,
        canonicalValueId: String,
    ): AndroidProviderIdentityOwnerEntity?

    @Query(
        "SELECT * FROM android_provider_row_bindings " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND provider_epoch = :providerEpoch AND canonical_value_id = :canonicalValueId LIMIT 1",
    )
    fun getBindingByCanonicalValueId(
        accountId: String,
        canonicalContactId: String,
        providerEpoch: Long,
        canonicalValueId: String,
    ): AndroidProviderRowBindingEntity?

    @Query(
        "SELECT * FROM android_provider_row_bindings " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND provider_epoch = :providerEpoch AND raw_contact_locator = :rawContactLocator " +
            "AND binding_primary_id = :bindingPrimaryId ORDER BY role",
    )
    fun getBindingGroup(
        accountId: String,
        canonicalContactId: String,
        providerEpoch: Long,
        rawContactLocator: Long,
        bindingPrimaryId: String,
    ): List<AndroidProviderRowBindingEntity>

    @Query(
        "SELECT * FROM android_provider_row_bindings " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND provider_epoch = :providerEpoch AND raw_contact_locator = :rawContactLocator " +
            "AND data_row_locator = :dataRowLocator AND state = 'ATTACHED' ORDER BY role",
    )
    fun getAttachedBindingGroup(
        accountId: String,
        canonicalContactId: String,
        providerEpoch: Long,
        rawContactLocator: Long,
        dataRowLocator: Long,
    ): List<AndroidProviderRowBindingEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertOwners(owners: List<AndroidProviderIdentityOwnerEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBindings(bindings: List<AndroidProviderRowBindingEntity>)

    @Query(
        "UPDATE android_provider_row_bindings SET data_row_locator = :dataRowLocator, state = 'ATTACHED' " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND provider_epoch = :providerEpoch AND raw_contact_locator = :rawContactLocator " +
            "AND binding_primary_id = :bindingPrimaryId AND state = 'PENDING' AND data_row_locator IS NULL",
    )
    fun attachPendingBindingGroup(
        accountId: String,
        canonicalContactId: String,
        providerEpoch: Long,
        rawContactLocator: Long,
        bindingPrimaryId: String,
        dataRowLocator: Long,
    ): Int

    @Query(
        "UPDATE android_provider_row_bindings SET data_row_locator = :newLocator " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND provider_epoch = :providerEpoch AND raw_contact_locator = :rawContactLocator " +
            "AND canonical_value_id = :canonicalValueId AND binding_primary_id = :canonicalValueId " +
            "AND role = 'PRIMARY' AND kind = 'PHOTO' AND state = 'ATTACHED' AND data_row_locator = :oldLocator",
    )
    fun relocateCommittedPhotoBinding(
        accountId: String, canonicalContactId: String, providerEpoch: Long, rawContactLocator: Long,
        canonicalValueId: String, oldLocator: Long, newLocator: Long,
    ): Int

    @Query(
        "UPDATE android_provider_row_bindings SET data_row_locator = :newLocator " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "AND provider_epoch = :providerEpoch AND raw_contact_locator = :rawContactLocator " +
            "AND binding_primary_id = :bindingPrimaryId AND kind = :kind AND kind != 'PHOTO' " +
            "AND role IN ('PRIMARY', 'PHONETIC_NAME', 'TITLE', 'ROLE') " +
            "AND state = 'ATTACHED' AND data_row_locator = :oldLocator",
    )
    fun relocateProjectionBindingGroup(
        accountId: String, canonicalContactId: String, providerEpoch: Long, rawContactLocator: Long,
        bindingPrimaryId: String, kind: String, oldLocator: Long, newLocator: Long,
    ): Int

    @Query(
        "SELECT * FROM android_provider_row_bindings " +
            "WHERE account_id = :accountId AND canonical_contact_id = :canonicalContactId " +
            "ORDER BY provider_epoch, raw_contact_locator, binding_primary_id, role",
    )
    fun getAllForContact(accountId: String, canonicalContactId: String): List<AndroidProviderRowBindingEntity>
}
