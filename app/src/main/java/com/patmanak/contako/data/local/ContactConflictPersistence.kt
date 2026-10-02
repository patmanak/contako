package com.patmanak.contako.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contact_conflicts", foreignKeys = [ForeignKey(
    entity = ContactEntity::class, parentColumns = ["owner_key"], childColumns = ["owner_key"], onDelete = ForeignKey.CASCADE,
)])
internal data class ContactConflictEntity(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    val generation: String,
    @ColumnInfo(name = "local_revision") val localRevision: Long,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    @ColumnInfo(name = "remote_version") val remoteVersion: String,
    val choice: String?,
    @ColumnInfo(name = "remote_deleted", defaultValue = "0") val remoteDeleted: Boolean = false,
) {
    override fun toString() = "ContactConflictEntity(REDACTED)"
}

/** Chunking avoids Android CursorWindow limits for photo-bearing snapshots. */
@Entity(tableName = "contact_conflict_chunks", primaryKeys = ["owner_key", "side", "position"], foreignKeys = [ForeignKey(
    entity = ContactConflictEntity::class, parentColumns = ["owner_key"], childColumns = ["owner_key"], onDelete = ForeignKey.CASCADE,
)])
internal data class ContactConflictChunk(
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    val side: String,
    val position: Int,
    val bytes: ByteArray,
) {
    override fun toString() = "ContactConflictChunk(REDACTED)"
}

@Dao
internal interface ContactConflictDao {
    @Query("SELECT * FROM contact_conflicts WHERE account_id = :accountId")
    fun observe(accountId: String): Flow<List<ContactConflictEntity>>
    @Query("SELECT * FROM contact_conflicts WHERE account_id = :accountId AND contact_id = :contactId")
    suspend fun get(accountId: String, contactId: String): ContactConflictEntity?
    @Upsert suspend fun upsert(conflict: ContactConflictEntity)
    @Query("UPDATE contact_conflicts SET choice = NULL WHERE account_id = :accountId AND contact_id = :contactId AND local_revision = :revision")
    suspend fun invalidateChoice(accountId: String, contactId: String, revision: Long)
    @Insert suspend fun insert(chunks: List<ContactConflictChunk>)
    @Query("SELECT * FROM contact_conflict_chunks WHERE owner_key = :ownerKey AND side = :side ORDER BY position")
    suspend fun chunks(ownerKey: String, side: String): List<ContactConflictChunk>
    @Query("DELETE FROM contact_conflicts WHERE account_id = :accountId AND contact_id = :contactId")
    suspend fun delete(accountId: String, contactId: String)
}
