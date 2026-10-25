package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Private request metadata only; payloads remain in their existing canonical stores. */
@Entity(tableName = "targeted_recovery_requests", primaryKeys = ["account_id", "kind", "target_id"])
internal data class TargetedRecoveryRequestEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val kind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "request_generation") val requestGeneration: String,
    @ColumnInfo(name = "source_generation") val sourceGeneration: String,
    @ColumnInfo(name = "expected_revision") val expectedRevision: Long?,
    @ColumnInfo(name = "expected_epoch") val expectedEpoch: Long?,
    val state: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_eligible_at") val nextEligibleAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "claim_token") val claimToken: String?,
    val prerequisite: String?,
) {
    override fun toString() = "TargetedRecoveryRequestEntity(REDACTED, kind=$kind, state=$state)"
}

internal data class TargetedRecoveryRequestWithName(
    @Embedded val request: TargetedRecoveryRequestEntity,
    @ColumnInfo(name = "display_name") val displayName: String?,
) {
    override fun toString() = "TargetedRecoveryRequestWithName(REDACTED)"
}

@Dao
internal interface TargetedRecoveryRequestDao {
    @Query("SELECT * FROM targeted_recovery_requests WHERE account_id=:accountId AND kind=:kind AND target_id=:targetId")
    suspend fun get(accountId: String, kind: String, targetId: String): TargetedRecoveryRequestEntity?

    @Query("SELECT * FROM targeted_recovery_requests WHERE account_id=:accountId ORDER BY created_at,kind,target_id")
    suspend fun all(accountId: String): List<TargetedRecoveryRequestEntity>

    @Query("""
        SELECT r.*, COALESCE(
            (SELECT c.display_name FROM contacts c WHERE c.account_id=r.account_id AND
                ((r.kind='ANDROID_COPY' AND c.id=r.target_id) OR
                 (r.kind='PROTON_CARD' AND c.remote_contact_id=r.target_id)) LIMIT 1),
            (SELECT o.display_name FROM remote_hydration_obligations o
                WHERE o.account_id=r.account_id AND o.remote_contact_id=r.target_id AND r.kind='PROTON_CARD')
        ) AS display_name FROM targeted_recovery_requests r WHERE r.account_id=:accountId
        ORDER BY r.created_at,r.kind,r.target_id
    """)
    fun observe(accountId: String): Flow<List<TargetedRecoveryRequestWithName>>

    @Query("SELECT MIN(next_eligible_at) FROM targeted_recovery_requests WHERE account_id=:accountId AND state IN ('REQUESTED','DEFERRED','RUNNING')")
    fun observeNextEligible(accountId: String): Flow<Long?>

    @Query("""
        SELECT * FROM targeted_recovery_requests WHERE account_id=:accountId
            AND state IN ('REQUESTED','DEFERRED') AND next_eligible_at<=:now
        ORDER BY created_at,kind,target_id LIMIT :limit
    """)
    suspend fun eligible(accountId: String, now: Long, limit: Int): List<TargetedRecoveryRequestEntity>

    @Query("""
        UPDATE targeted_recovery_requests SET state='DEFERRED',claim_token=NULL,
            next_eligible_at=MAX(next_eligible_at,:now),updated_at=:now
        WHERE account_id=:accountId AND state='RUNNING'
    """)
    suspend fun recoverInterrupted(accountId: String, now: Long): Int

    @Upsert suspend fun upsert(request: TargetedRecoveryRequestEntity)

    @Query("DELETE FROM targeted_recovery_requests WHERE account_id=:accountId")
    suspend fun deleteAccount(accountId: String)
}
