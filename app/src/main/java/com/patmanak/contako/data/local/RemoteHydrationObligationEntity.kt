package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.sync.RemoteHydrationObligationStore
import com.patmanak.contako.data.sync.RemoteHydrationReadDisposition
import com.patmanak.contako.data.sync.isTransientHydrationFailure
import kotlinx.coroutines.flow.Flow

/** Private local state; names/remote identifiers MUST NOT be included in diagnostics. */
@Entity(tableName = "remote_hydration_obligations", primaryKeys = ["account_id", "remote_contact_id"])
internal data class RemoteHydrationObligationEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "remote_contact_id") val remoteContactId: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    val category: String,
    @ColumnInfo(name = "hydration_category") val hydrationCategory: String?,
    @ColumnInfo(name = "generation", defaultValue = "''") val generation: String = "",
    @ColumnInfo(name = "baseline_version") val baselineVersion: String? = null,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at_epoch_millis", defaultValue = "0") val nextAttemptAtEpochMillis: Long = 0,
    @ColumnInfo(name = "retry_after_millis") val retryAfterMillis: Long? = null,
    @ColumnInfo(name = "is_transient", defaultValue = "0") val isTransient: Boolean = false,
    @ColumnInfo(name = "ignored_baseline_version") val ignoredBaselineVersion: String? = null,
) {
    override fun toString() = "RemoteHydrationObligationEntity(REDACTED)"
}

@Dao
internal interface RemoteHydrationObligationDao {
    @Query("SELECT * FROM remote_hydration_obligations WHERE account_id=:accountId AND remote_contact_id=:remoteId")
    suspend fun get(accountId: String, remoteId: String): RemoteHydrationObligationEntity?
    @Query("SELECT * FROM remote_hydration_obligations WHERE account_id = :accountId ORDER BY remote_contact_id")
    suspend fun getAll(accountId: String): List<RemoteHydrationObligationEntity>
    @Query("SELECT * FROM remote_hydration_obligations WHERE account_id = :accountId ORDER BY remote_contact_id")
    fun observe(accountId: String): Flow<List<RemoteHydrationObligationEntity>>
    @Upsert suspend fun upsert(value: RemoteHydrationObligationEntity)
    @Query("DELETE FROM remote_hydration_obligations WHERE account_id = :accountId AND remote_contact_id IN (:ids)")
    suspend fun delete(accountId: String, ids: Set<String>)
    @Query("""UPDATE remote_hydration_obligations
        SET ignored_baseline_version = baseline_version, generation = :nextGeneration
        WHERE account_id=:accountId AND remote_contact_id=:remoteId AND generation=:generation
        AND baseline_version IS NOT NULL AND is_transient=0""")
    suspend fun ignore(accountId: String, remoteId: String, generation: String, nextGeneration: String): Int
    @Query("""UPDATE remote_hydration_obligations
        SET ignored_baseline_version = NULL, next_attempt_at_epoch_millis = 0,
            is_transient=1, generation = :nextGeneration
        WHERE account_id=:accountId AND remote_contact_id=:remoteId AND generation=:generation
        AND (is_transient=0 OR next_attempt_at_epoch_millis <= :nowEpochMillis)""")
    suspend fun resume(accountId: String, remoteId: String, generation: String, nextGeneration: String, nowEpochMillis: Long): Int
    @Query("""UPDATE remote_hydration_obligations SET ignored_baseline_version=NULL, generation=:nextGeneration
        WHERE account_id=:accountId AND remote_contact_id=:remoteId AND generation=:generation""")
    suspend fun sourceChanged(accountId: String, remoteId: String, generation: String, nextGeneration: String): Int
}

internal class RoomRemoteHydrationObligationStore(private val database: ContakoDatabase,
    private val clock: () -> Long = System::currentTimeMillis) : RemoteHydrationObligationStore {
    private val dao = database.remoteHydrationObligationDao()
    override suspend fun pending(account: AccountScope) = dao.getAll(account.value).map { RemoteContactId(it.remoteContactId) }.toSet()
    override suspend fun defer(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) {
        persistFailure(account, baseline, failure, attempted = true)
    }
    override suspend fun postpone(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) {
        persistFailure(account, baseline, failure, attempted = false)
    }
    private suspend fun persistFailure(account: AccountScope, baseline: ContactInventoryBaseline,
        failure: GatewayOutcome.Failure, attempted: Boolean) = database.withTransaction {
        val previous = dao.get(account.value, baseline.id.value)
        val sameSource = previous != null && previous.baselineVersion == baseline.version.value &&
            previous.category == failure.category.name && previous.hydrationCategory == failure.contactHydrationCategory?.name
        val attempts = (if (previous?.baselineVersion == baseline.version.value) previous.attemptCount else 0)
            .let { if (attempted) (it.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else it }
        val transient = failure.category.isTransientHydrationFailure
        val serverDelay = failure.retryAfterMillis
        val delay = if (transient) maxOf(serverDelay ?: 0L,
            (5_000L shl (attempts - 1).coerceIn(0, 32)).coerceAtMost(6 * 60 * 60 * 1_000L)) else 0L
        val now = clock()
        val nextAttempt = if (!transient) 0L else maxOf(previous?.takeIf { it.isTransient }?.nextAttemptAtEpochMillis ?: 0L,
            if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay)
        dao.upsert(RemoteHydrationObligationEntity(account.value, baseline.id.value, baseline.displayName,
            failure.category.name, failure.contactHydrationCategory?.name,
            if (sameSource) requireNotNull(previous).generation else java.util.UUID.randomUUID().toString(),
            baseline.version.value, attempts, nextAttempt, serverDelay, transient))
    }
    override suspend fun readDisposition(account: AccountScope, baseline: ContactInventoryBaseline,
        sourceChanged: Boolean): RemoteHydrationReadDisposition = database.withTransaction {
        val saved = dao.get(account.value, baseline.id.value) ?: return@withTransaction RemoteHydrationReadDisposition.READ
        // A remote change or explicit repair does not shorten a server-directed retry delay.
        if (saved.isTransient && saved.nextAttemptAtEpochMillis > clock()) return@withTransaction RemoteHydrationReadDisposition.RETRY_WAITING
        // The public fingerprint does not reveal private-only edits: the event feed is also authoritative here.
        if (sourceChanged || saved.baselineVersion != baseline.version.value) {
            check(dao.sourceChanged(account.value, baseline.id.value, saved.generation,
                java.util.UUID.randomUUID().toString()) == 1)
            return@withTransaction RemoteHydrationReadDisposition.READ
        }
        if (saved.ignoredBaselineVersion == baseline.version.value) return@withTransaction RemoteHydrationReadDisposition.IGNORED
        if (!saved.isTransient) return@withTransaction RemoteHydrationReadDisposition.ACTION_REQUIRED
        if (saved.nextAttemptAtEpochMillis > clock()) RemoteHydrationReadDisposition.RETRY_WAITING
            else RemoteHydrationReadDisposition.READ
    }
    override suspend fun ignore(account: AccountScope, id: RemoteContactId, generation: String): Boolean =
        dao.ignore(account.value, id.value, generation, java.util.UUID.randomUUID().toString()) == 1
    override suspend fun resume(account: AccountScope, id: RemoteContactId, generation: String): Boolean =
        dao.resume(account.value, id.value, generation, java.util.UUID.randomUUID().toString(), clock()) == 1
    override suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>) {
        if (ids.isNotEmpty()) dao.delete(account.value, ids.map { it.value }.toSet())
    }
}
