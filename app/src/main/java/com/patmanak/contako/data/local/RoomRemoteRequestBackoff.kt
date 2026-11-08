package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.sync.RemoteRequestBackoffStore

/** Server-directed delay only; no account credentials, contact data or raw response is retained. */
@Entity(tableName = "remote_request_backoffs", primaryKeys = ["account_id"])
internal data class RemoteRequestBackoffEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "next_attempt_at_epoch_millis") val nextAttemptAtEpochMillis: Long,
) {
    init { require(accountId.isNotBlank() && nextAttemptAtEpochMillis >= 0) }
    override fun toString() = "RemoteRequestBackoffEntity(REDACTED)"
}

@Dao
internal interface RemoteRequestBackoffDao {
    @Query("SELECT * FROM remote_request_backoffs WHERE account_id=:accountId")
    suspend fun get(accountId: String): RemoteRequestBackoffEntity?
    @Upsert suspend fun upsert(value: RemoteRequestBackoffEntity)
    @Query("DELETE FROM remote_request_backoffs WHERE account_id=:accountId")
    suspend fun deleteAccount(accountId: String)
    @Query("SELECT EXISTS(SELECT 1 FROM remote_request_backoffs)")
    suspend fun hasAny(): Boolean
}

internal class RoomRemoteRequestBackoff(private val database: ContakoDatabase,
    private val clock: () -> Long = System::currentTimeMillis) : RemoteRequestBackoffStore {
    override suspend fun remainingMillis(account: AccountScope): Long {
        val deadline = database.remoteRequestBackoffDao().get(account.value)?.nextAttemptAtEpochMillis ?: return 0L
        val now = clock().coerceAtLeast(0L)
        return (deadline - now).coerceAtLeast(0L)
    }

    override suspend fun record(account: AccountScope, failure: GatewayOutcome.Failure) {
        val delay = failure.retryAfterMillis?.takeIf { failure.category == GatewayFailureCategory.RATE_LIMITED } ?: return
        database.withTransaction {
            val now = clock().coerceAtLeast(0L)
            val next = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay
            val previous = database.remoteRequestBackoffDao().get(account.value)?.nextAttemptAtEpochMillis ?: 0L
            database.remoteRequestBackoffDao().upsert(RemoteRequestBackoffEntity(account.value, maxOf(previous, next)))
        }
    }
}
