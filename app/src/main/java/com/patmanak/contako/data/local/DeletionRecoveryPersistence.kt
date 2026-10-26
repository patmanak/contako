package com.patmanak.contako.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Independent of the source contact's lifetime. Every field is private account-scoped state. */
@Entity(tableName = "deletion_recoveries", primaryKeys = ["account_id", "contact_id"])
internal data class DeletionRecoveryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    val generation: String,
    @ColumnInfo(name = "local_revision") val localRevision: Long,
    @ColumnInfo(name = "draft_revision") val draftRevision: Long,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val state: String,
    val choice: String? = null,
    val blocker: String = "NONE",
    @ColumnInfo(name = "has_draft") val hasDraft: Boolean = false,
    @ColumnInfo(name = "absence_verified") val absenceVerified: Boolean = false,
    @ColumnInfo(name = "destination_contact_id") val destinationContactId: String? = null,
    @ColumnInfo(name = "cleanup_epoch") val cleanupEpoch: Long? = null,
    @ColumnInfo(name = "cleanup_raw") val cleanupRaw: Long? = null,
    @ColumnInfo(name = "cleanup_version") val cleanupVersion: Long? = null,
    @ColumnInfo(name = "cleanup_ledger_revision") val cleanupLedgerRevision: Long? = null,
    @ColumnInfo(name = "destination_revision") val destinationRevision: Long? = null,
    @ColumnInfo(name = "verification_order", defaultValue = "0") val verificationOrder: Long = 0,
    @ColumnInfo(name = "source_retired", defaultValue = "0") val sourceRetired: Boolean = false,
) {
    override fun toString() = "DeletionRecoveryEntity(REDACTED, state=$state, blocker=$blocker)"
}

@Entity(tableName = "deletion_recovery_chunks", primaryKeys = ["account_id", "contact_id", "position"],
    foreignKeys = [ForeignKey(entity = DeletionRecoveryEntity::class,
        parentColumns = ["account_id", "contact_id"], childColumns = ["account_id", "contact_id"], onDelete = ForeignKey.CASCADE)])
internal data class DeletionRecoveryChunk(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    val position: Int,
    val bytes: ByteArray,
) { override fun toString() = "DeletionRecoveryChunk(REDACTED)" }

@Entity(tableName = "deletion_recovery_groups", primaryKeys = ["account_id", "contact_id", "group_id", "email_value_id"],
    foreignKeys = [ForeignKey(entity = DeletionRecoveryEntity::class,
        parentColumns = ["account_id", "contact_id"], childColumns = ["account_id", "contact_id"], onDelete = ForeignKey.CASCADE)])
internal data class DeletionRecoveryGroup(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "email_value_id") val emailValueId: String,
    @ColumnInfo(name = "remote_label_id") val remoteLabelId: String?,
    @ColumnInfo(name = "group_name") val groupName: String,
) { override fun toString() = "DeletionRecoveryGroup(REDACTED)" }

@Dao
internal interface DeletionRecoveryDao {
    @Query("SELECT * FROM deletion_recoveries WHERE account_id=:accountId ORDER BY contact_id")
    fun observe(accountId: String): Flow<List<DeletionRecoveryEntity>>
    @Query("SELECT * FROM deletion_recoveries WHERE account_id=:accountId ORDER BY contact_id")
    suspend fun getAll(accountId: String): List<DeletionRecoveryEntity>
    @Query("SELECT * FROM deletion_recoveries WHERE account_id=:accountId AND contact_id=:contactId")
    suspend fun get(accountId: String, contactId: String): DeletionRecoveryEntity?
    @Query("SELECT * FROM deletion_recoveries WHERE account_id=:accountId AND destination_contact_id=:destinationId LIMIT 1")
    suspend fun forDestination(accountId: String, destinationId: String): DeletionRecoveryEntity?
    @Upsert suspend fun upsert(row: DeletionRecoveryEntity)
    @Query("SELECT * FROM deletion_recovery_chunks WHERE account_id=:accountId AND contact_id=:contactId ORDER BY position")
    suspend fun chunks(accountId: String, contactId: String): List<DeletionRecoveryChunk>
    @Query("DELETE FROM deletion_recovery_chunks WHERE account_id=:accountId AND contact_id=:contactId")
    suspend fun deleteChunks(accountId: String, contactId: String)
    @Insert suspend fun insertChunks(rows: List<DeletionRecoveryChunk>)
    @Query("SELECT * FROM deletion_recovery_groups WHERE account_id=:accountId AND contact_id=:contactId")
    suspend fun groups(accountId: String, contactId: String): List<DeletionRecoveryGroup>
    @Query("DELETE FROM deletion_recovery_groups WHERE account_id=:accountId AND contact_id=:contactId")
    suspend fun deleteGroups(accountId: String, contactId: String)
    @Query("DELETE FROM deletion_recovery_groups WHERE account_id=:accountId AND contact_id=:contactId AND email_value_id IN (:emailIds)")
    suspend fun deleteGroupsForEmails(accountId: String, contactId: String, emailIds: List<String>)
    @Insert suspend fun insertGroups(rows: List<DeletionRecoveryGroup>)
}
