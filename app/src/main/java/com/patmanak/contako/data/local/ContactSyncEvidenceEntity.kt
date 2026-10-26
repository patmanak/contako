package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Private confirmation metadata, never contact values or a replacement for domain receipts. */
@Entity(tableName = "contact_sync_evidence", primaryKeys = ["account_id", "contact_id", "peer"], foreignKeys = [ForeignKey(
    entity = ContactEntity::class, parentColumns = ["account_id", "id"], childColumns = ["account_id", "contact_id"],
    onDelete = ForeignKey.CASCADE,
)])
internal data class ContactSyncEvidenceEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    val peer: String,
    @ColumnInfo(name = "canonical_revision") val canonicalRevision: Long,
    @ColumnInfo(name = "confirmed_at") val confirmedAtEpochMillis: Long,
    val kind: String,
    @ColumnInfo(name = "source_identity") val sourceIdentity: String?,
    @ColumnInfo(name = "source_version") val sourceVersion: String? = null,
    @ColumnInfo(name = "provider_epoch") val providerEpoch: Long? = null,
    @ColumnInfo(name = "android_account_name") val androidAccountName: String? = null,
    @ColumnInfo(name = "raw_contact_locator") val rawContactLocator: Long? = null,
    @ColumnInfo(name = "ledger_revision") val ledgerRevision: Long? = null,
    @ColumnInfo(name = "canonical_fingerprint") val canonicalFingerprint: String? = null,
    @ColumnInfo(name = "baseline_fingerprint") val baselineFingerprint: String? = null,
    @ColumnInfo(name = "membership_revision") val membershipRevision: Long? = null,
    @ColumnInfo(name = "membership_fingerprint") val membershipFingerprint: String? = null,
    @ColumnInfo(name = "membership_baseline_fingerprint") val membershipBaselineFingerprint: String? = null,
    @ColumnInfo(name = "membership_baseline_integrity") val membershipBaselineIntegrity: String? = null,
    @ColumnInfo(name = "preferred_email_id") val preferredEmailId: String? = null,
    @ColumnInfo(name = "photo_receipt_key") val photoReceiptKey: String? = null,
) {
    override fun toString() = "ContactSyncEvidenceEntity(REDACTED, peer=$peer, kind=$kind)"
}

/** Receipt metadata only: binary_reference can contain an entire image and MUST NOT be queried. */
internal data class ContactPhotoReceiptMetadata(
    val androidAccountName: String, val providerEpoch: Long, val rawContactLocator: Long,
    val dataRowLocator: Long, val canonicalValueId: String, val sourceSha256: String,
    val sourceSize: Long, val readbackSha256: String, val readbackSize: Long, val rawContactVersion: Long,
) {
    override fun toString() = "ContactPhotoReceiptMetadata(REDACTED)"
}

internal data class ContactComplementMetadata(val id: String, val kind: String, val label: String?,
    @ColumnInfo(name = "metadata_encoding") val metadataEncoding: String,
    @ColumnInfo(name = "preservation_key") val preservationKey: String?) {
    override fun toString() = "ContactComplementMetadata(REDACTED)"
}

@Dao
internal interface ContactSyncEvidenceDao {
    @Query("SELECT * FROM contact_sync_evidence WHERE account_id=:accountId AND contact_id=:contactId AND peer=:peer")
    suspend fun get(accountId: String, contactId: String, peer: String): ContactSyncEvidenceEntity?
    @Upsert suspend fun upsert(value: ContactSyncEvidenceEntity)
    @Query("DELETE FROM contact_sync_evidence WHERE account_id=:accountId")
    suspend fun deleteAccount(accountId: String)

    @Query("SELECT fingerprint FROM android_projection_baselines WHERE account_id=:accountId AND canonical_contact_id=:contactId")
    suspend fun contactBaselineFingerprint(accountId: String, contactId: String): String?
    @Query("SELECT fingerprint FROM android_group_membership_baselines WHERE account_id=:accountId AND canonical_contact_id=:contactId")
    suspend fun membershipBaselineIntegrity(accountId: String, contactId: String): String?

    @Query("""
        SELECT android_account_name AS androidAccountName,provider_epoch AS providerEpoch,
            raw_contact_locator AS rawContactLocator,data_row_locator AS dataRowLocator,
            canonical_value_id AS canonicalValueId,source_sha256 AS sourceSha256,source_size AS sourceSize,
            readback_sha256 AS readbackSha256,readback_size AS readbackSize,raw_contact_version AS rawContactVersion
        FROM android_photo_projection_receipts WHERE account_id=:accountId AND canonical_contact_id=:contactId
    """)
    suspend fun photoReceiptMetadata(accountId: String, contactId: String): ContactPhotoReceiptMetadata?

    @Query("""
        SELECT id,kind,label,metadata_encoding,preservation_key FROM contact_values
        WHERE account_id=:accountId AND contact_id=:contactId
            AND kind IN ('CUSTOM_DATE','PHONETIC_NAME','URL','RELATIONSHIP','POSTAL_ADDRESS')
            AND (:afterId IS NULL OR id>:afterId)
        ORDER BY id LIMIT 128
    """)
    suspend fun localComplementMetadata(accountId: String, contactId: String, afterId: String?): List<ContactComplementMetadata>

    @Query("""
        SELECT COUNT(*) FROM contact_values WHERE account_id=:accountId AND contact_id=:contactId
            AND kind IN ('CUSTOM_DATE','PHONETIC_NAME','URL','RELATIONSHIP','POSTAL_ADDRESS')
    """)
    suspend fun localComplementCandidates(accountId: String, contactId: String): Int

    @Query("SELECT COUNT(*) FROM contact_values WHERE account_id=:accountId AND contact_id=:contactId AND kind='PHOTO'")
    suspend fun canonicalPhotoCount(accountId: String, contactId: String): Int

    @Query("""
        SELECT EXISTS(SELECT 1 FROM group_memberships m JOIN outbox_mutations o
            ON o.account_id=m.account_id AND o.aggregate_type='GROUP' AND o.aggregate_id=m.group_id
            WHERE m.account_id=:accountId AND m.contact_id=:contactId)
    """)
    suspend fun knownGroupChangesPending(accountId: String, contactId: String): Boolean

    // Every subquery is scoped to this contact (or its one account context). Room tracks all
    // referenced tables; a wakeup always rebuilds one atomic local snapshot, never a directory.
    @Query("""
        SELECT (SELECT COUNT(*) FROM contacts WHERE account_id=:accountId AND id=:contactId)
          + (SELECT COUNT(*) FROM contact_sync_evidence WHERE account_id=:accountId AND contact_id=:contactId)
          + (SELECT COUNT(*) FROM outbox_mutations WHERE account_id=:accountId AND aggregate_type='CONTACT' AND aggregate_id=:contactId)
          + (SELECT COUNT(*) FROM android_projection_ledger WHERE account_id=:accountId AND canonical_contact_id=:contactId)
          + (SELECT COUNT(*) FROM android_projection_accounts WHERE account_id=:accountId)
          + (SELECT COUNT(*) FROM android_group_membership_projection_ledger WHERE account_id=:accountId AND canonical_contact_id=:contactId)
          + (SELECT COUNT(*) FROM android_projection_baselines WHERE account_id=:accountId AND canonical_contact_id=:contactId)
          + (SELECT COUNT(*) FROM android_group_membership_baselines WHERE account_id=:accountId AND canonical_contact_id=:contactId)
          + (SELECT COUNT(*) FROM android_photo_projection_receipts WHERE account_id=:accountId AND canonical_contact_id=:contactId)
          + (SELECT COUNT(*) FROM android_contact_issues WHERE account_id=:accountId AND canonical_contact_id=:contactId)
          + (SELECT COUNT(*) FROM contact_values WHERE account_id=:accountId AND contact_id=:contactId)
          + (SELECT COUNT(*) FROM contact_conflicts WHERE account_id=:accountId AND contact_id=:contactId)
          + (SELECT COUNT(*) FROM remote_hydration_obligations h JOIN contacts c
               ON c.account_id=h.account_id AND c.remote_contact_id=h.remote_contact_id
               WHERE c.account_id=:accountId AND c.id=:contactId)
          + (SELECT COUNT(*) FROM group_memberships m LEFT JOIN outbox_mutations o
               ON o.account_id=m.account_id AND o.aggregate_type='GROUP' AND o.aggregate_id=m.group_id
               WHERE m.account_id=:accountId AND m.contact_id=:contactId)
    """)
    fun observeInvalidation(accountId: String, contactId: String): Flow<Int>
}
