package com.patmanak.contako.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.sync.RemoteHydrationObligationStore
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
}

internal class RoomRemoteHydrationObligationStore(database: ContakoDatabase) : RemoteHydrationObligationStore {
    private val dao = database.remoteHydrationObligationDao()
    override suspend fun pending(account: AccountScope) = dao.getAll(account.value).map { RemoteContactId(it.remoteContactId) }.toSet()
    override suspend fun defer(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) {
        val previous = dao.get(account.value, baseline.id.value)
        val sameSource = previous != null && previous.baselineVersion == baseline.version.value &&
            previous.category == failure.category.name && previous.hydrationCategory == failure.contactHydrationCategory?.name
        dao.upsert(RemoteHydrationObligationEntity(account.value, baseline.id.value, baseline.displayName,
            failure.category.name, failure.contactHydrationCategory?.name,
            if (sameSource) requireNotNull(previous).generation else java.util.UUID.randomUUID().toString(),
            baseline.version.value))
    }
    override suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>) {
        if (ids.isNotEmpty()) dao.delete(account.value, ids.map { it.value }.toSet())
    }
}
