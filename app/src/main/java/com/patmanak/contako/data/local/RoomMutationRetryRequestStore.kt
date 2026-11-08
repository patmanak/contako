package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.MutationRetryPolicy
import com.patmanak.contako.domain.sync.SyncMutationRetry
import com.patmanak.contako.domain.sync.SyncMutationRetryReason
import kotlinx.coroutines.flow.Flow

/** A single explicit retry credit, never a replacement for automatic attempt history. */
@Entity(tableName = "mutation_retry_requests", primaryKeys = ["account_id", "aggregate_type", "aggregate_id"])
internal data class MutationRetryRequestEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    val revision: Long,
    @ColumnInfo(name = "requested_at_epoch_millis") val requestedAtEpochMillis: Long,
    @ColumnInfo(name = "next_request_at_epoch_millis") val nextRequestAtEpochMillis: Long,
    @ColumnInfo(name = "consumed_at_epoch_millis") val consumedAtEpochMillis: Long? = null,
) {
    override fun toString() = "MutationRetryRequestEntity(REDACTED)"
}

@Dao
internal interface MutationRetryRequestDao {
    @Query("SELECT * FROM mutation_retry_requests WHERE account_id=:accountId AND aggregate_type=:type AND aggregate_id=:id")
    suspend fun get(accountId: String, type: String, id: String): MutationRetryRequestEntity?
    @Query("SELECT * FROM mutation_retry_requests WHERE account_id=:accountId")
    fun observe(accountId: String): Flow<List<MutationRetryRequestEntity>>
    @Query("""SELECT * FROM mutation_retry_requests WHERE account_id=:accountId AND consumed_at_epoch_millis IS NULL
        ORDER BY requested_at_epoch_millis,aggregate_type,aggregate_id LIMIT 100""")
    suspend fun queued(accountId: String): List<MutationRetryRequestEntity>
    @Upsert suspend fun upsert(row: MutationRetryRequestEntity)
    @Query("""UPDATE outbox_mutations SET state='PENDING',blocked_reason=NULL
        WHERE account_id=:accountId AND aggregate_type=:type AND aggregate_id=:id AND revision=:revision
        AND state='ACTION_REQUIRED' AND blocked_reason='REMOTE_ACTION_REQUIRED' AND error_category=:category
        AND next_attempt_at_epoch_millis<=:nowEpochMillis""")
    suspend fun reopen(accountId: String, type: String, id: String, revision: Long, category: String, nowEpochMillis: Long): Int
    @Query("DELETE FROM mutation_retry_requests WHERE account_id=:accountId")
    suspend fun deleteAccount(accountId: String)
    @Query("SELECT EXISTS(SELECT 1 FROM mutation_retry_requests)")
    suspend fun hasAny(): Boolean
}

internal class RoomMutationRetryRequestStore(private val database: ContakoDatabase) {
    private val dao get() = database.mutationRetryRequestDao()

    suspend fun availability(account: AccountScope, type: String, id: String): SyncMutationRetry? = database.withTransaction {
        val intent = database.outboxDao().get(account.value, type, id)?.takeIf { eligible(it) && current(it) }
            ?: return@withTransaction null
        val request = dao.get(account.value, type, id)
        SyncMutationRetry(intent.revision,
            if (intent.errorCategory == "RECONCILIATION_INCOMPLETE") SyncMutationRetryReason.VERIFICATION else SyncMutationRetryReason.NETWORK,
            maxOf(intent.nextAttemptAtEpochMillis, request?.nextRequestAtEpochMillis ?: 0L),
            request?.revision == intent.revision && request.consumedAtEpochMillis == null)
    }

    suspend fun queue(account: AccountScope, type: String, id: String, revision: Long, nowEpochMillis: Long): Boolean = database.withTransaction {
        if (nowEpochMillis < 0) return@withTransaction false
        val intent = database.outboxDao().get(account.value, type, id)?.takeIf { it.revision == revision && eligible(it) && current(it) }
            ?: return@withTransaction false
        val previous = dao.get(account.value, type, id)
        if (previous?.revision == revision && previous.consumedAtEpochMillis == null) return@withTransaction true
        if (nowEpochMillis < intent.nextAttemptAtEpochMillis || nowEpochMillis < (previous?.nextRequestAtEpochMillis ?: 0L))
            return@withTransaction false
        val cooldown = if (nowEpochMillis > Long.MAX_VALUE - COOLDOWN_MILLIS) Long.MAX_VALUE else nowEpochMillis + COOLDOWN_MILLIS
        dao.upsert(MutationRetryRequestEntity(account.value, type, id, revision, nowEpochMillis, cooldown))
        true
    }

    /** Called only under the shared account runner, before selecting the next outbox batch. */
    suspend fun consume(account: AccountScope, nowEpochMillis: Long): Int = database.withTransaction {
        var reopened = 0
        dao.queued(account.value).forEach { request ->
            val intent = database.outboxDao().get(account.value, request.aggregateType, request.aggregateId)
            if (intent == null || intent.revision != request.revision || !eligible(intent) || !current(intent)) {
                dao.upsert(request.copy(consumedAtEpochMillis = nowEpochMillis))
                return@forEach
            }
            if (intent.nextAttemptAtEpochMillis > nowEpochMillis) return@forEach
            if (dao.reopen(account.value, request.aggregateType, request.aggregateId, request.revision,
                    requireNotNull(intent.errorCategory), nowEpochMillis) == 1) {
                // One grant produces one attempt. A failed exhausted attempt becomes terminal
                // again under the unchanged retry policy; no count, deadline or ambiguity reset.
                dao.upsert(request.copy(consumedAtEpochMillis = nowEpochMillis))
                reopened++
            }
        }
        reopened
    }

    private fun eligible(intent: OutboxMutationEntity): Boolean = intent.state == DurableMutationState.ACTION_REQUIRED.name &&
        intent.blockedReason == "REMOTE_ACTION_REQUIRED" && intent.errorCategory in RETRYABLE_FAILURES &&
        intent.attemptCount >= MutationRetryPolicy.DEFAULT_MAXIMUM_AUTOMATIC_ATTEMPTS

    private suspend fun current(intent: OutboxMutationEntity): Boolean = when (intent.aggregateType) {
        AggregateType.CONTACT.name -> database.contactDao().getEntity(intent.accountId, intent.aggregateId)?.let {
            val verificationConflict = intent.requiresReconciliation && intent.remoteVersion == null && it.remoteVersion == null &&
                intent.remoteIdentity != null && it.remoteContactId == intent.remoteIdentity &&
                it.conflictState == "REMOTE_UPDATE_CONFLICT" && intent.errorCategory == "RECONCILIATION_INCOMPLETE"
            it.revision == intent.revision && it.pendingMutationRevision == intent.revision &&
                it.remoteContactId == intent.remoteIdentity && it.remoteVersion == intent.remoteVersion &&
                (it.conflictState == null || verificationConflict)
        } == true
        AggregateType.GROUP.name -> database.contactGroupDao().get(intent.accountId, intent.aggregateId)?.group?.let {
            it.revision == intent.revision && it.pendingMutationRevision == intent.revision && it.conflictState == null &&
                it.remoteLabelId == intent.remoteIdentity && it.remoteVersion == intent.remoteVersion
        } == true
        else -> false
    }

    companion object {
        const val COOLDOWN_MILLIS = 5 * 60 * 1_000L
        private val RETRYABLE_FAILURES = setOf("NETWORK_UNAVAILABLE", "TIMEOUT", "RATE_LIMITED", "REMOTE_SERVICE_FAILURE", "UNKNOWN", "RECONCILIATION_INCOMPLETE")
    }
}
