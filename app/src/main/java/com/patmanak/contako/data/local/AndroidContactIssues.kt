package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Private presentation state only. It neither acknowledges a mutation nor authorizes a write. */
@Entity(
    tableName = "android_contact_issues",
    primaryKeys = ["account_id", "canonical_contact_id", "origin"],
    foreignKeys = [ForeignKey(
        entity = ContactEntity::class,
        parentColumns = ["account_id", "id"],
        childColumns = ["account_id", "canonical_contact_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class AndroidContactIssueEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "canonical_contact_id") val canonicalContactId: String,
    val origin: String,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long,
    @ColumnInfo(name = "canonical_revision") val canonicalRevision: Long,
    val reason: String,
) {
    init {
        require(accountId.isNotBlank() && canonicalContactId.isNotBlank())
        require(origin in setOf("INGEST", "PROJECTION"))
        require(providerEpoch >= 0 && canonicalRevision >= 0)
        // The store accepts only typed enum categories; never persist raw messages.
        require(reason.matches(Regex("[A-Z][A-Z0-9_]{0,95}")))
    }

    override fun toString(): String = "AndroidContactIssueEntity(REDACTED, origin=$origin)"
}

internal data class AndroidContactIssueWithContact(
    @Embedded val issue: AndroidContactIssueEntity,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
) {
    override fun toString() = "AndroidContactIssueWithContact(REDACTED)"
}

@Dao
internal interface AndroidContactIssueDao {
    @Query("""
        SELECT issue.* FROM android_contact_issues issue
        JOIN android_projection_accounts account ON account.account_id = issue.account_id
            AND account.provider_epoch = issue.provider_epoch
        JOIN contacts contact ON contact.account_id = issue.account_id
            AND contact.id = issue.canonical_contact_id
        WHERE issue.account_id = :accountId
        ORDER BY issue.canonical_contact_id, issue.origin
    """)
    fun observeCurrent(accountId: String): Flow<List<AndroidContactIssueEntity>>

    @Query("""
        SELECT issue.*, COALESCE(NULLIF(contact.display_name, ''), TRIM(contact.first_name || ' ' || contact.last_name)) AS display_name,
            contact.is_deleted
        FROM android_contact_issues issue
        JOIN android_projection_accounts account ON account.account_id = issue.account_id
            AND account.provider_epoch = issue.provider_epoch
        JOIN contacts contact ON contact.account_id = issue.account_id AND contact.id = issue.canonical_contact_id
        WHERE issue.account_id = :accountId ORDER BY issue.canonical_contact_id, issue.origin
    """)
    fun observeCurrentWithContacts(accountId: String): Flow<List<AndroidContactIssueWithContact>>

    @Query("SELECT * FROM android_contact_issues WHERE account_id = :accountId ORDER BY canonical_contact_id, origin")
    suspend fun getAll(accountId: String): List<AndroidContactIssueEntity>

    @Query("""
        SELECT * FROM android_contact_issues WHERE account_id = :accountId
            AND canonical_contact_id IN (:contactIds)
            AND provider_epoch = :providerEpoch
    """)
    suspend fun getForContactPage(accountId: String, contactIds: List<String>, providerEpoch: Long): List<AndroidContactIssueEntity>

    @Query("""
        SELECT * FROM android_contact_issues WHERE account_id = :accountId
            AND canonical_contact_id = :canonicalContactId AND origin = :origin
    """)
    suspend fun get(accountId: String, canonicalContactId: String, origin: String): AndroidContactIssueEntity?

    @Upsert
    suspend fun upsert(issue: AndroidContactIssueEntity)

    @Query("""
        DELETE FROM android_contact_issues WHERE account_id = :accountId
            AND canonical_contact_id = :canonicalContactId AND origin = :origin
            AND provider_epoch = :providerEpoch
    """)
    suspend fun delete(accountId: String, canonicalContactId: String, origin: String, providerEpoch: Long): Int
}
