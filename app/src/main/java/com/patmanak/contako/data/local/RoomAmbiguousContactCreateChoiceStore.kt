package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.MutationRetryPolicy

/** Explicit duplicate-risk acknowledgement; never an automatically inferred absence proof. */
internal class RoomAmbiguousContactCreateChoiceStore(private val database: ContakoDatabase) {
    /** Private UI revision token; absence or a changed revision disables the action. */
    suspend fun offerRevision(account: AccountScope, id: String): Long? = database.withTransaction {
        val row = database.outboxDao().get(account.value, AggregateType.CONTACT.name, id) ?: return@withTransaction null
        if (row.state != DurableMutationState.ACTION_REQUIRED.name || row.blockedReason != "REMOTE_ACTION_REQUIRED" ||
            row.errorCategory != "RECONCILIATION_INCOMPLETE" ||
            row.attemptCount < MutationRetryPolicy.DEFAULT_MAXIMUM_AUTOMATIC_ATTEMPTS || !current(row)) null
        else row.revision
    }

    /** Persists the user's acknowledged choice without discarding ambiguity or bypassing a deadline. */
    suspend fun confirm(account: AccountScope, id: String, revision: Long): Boolean = database.withTransaction {
        if (offerRevision(account, id) != revision) return@withTransaction false
        val row = database.outboxDao().get(account.value, AggregateType.CONTACT.name, id) ?: return@withTransaction false
        database.outboxDao().upsert(row.copy(state = DurableMutationState.PENDING.name, blockedReason = null,
            errorCategory = CONFIRMED))
        true
    }

    /** Only preparation of the exact claimed revision can consume this acknowledgement. */
    suspend fun confirmedStillCurrent(account: AccountScope, id: String, revision: Long): Boolean {
        val row = database.outboxDao().get(account.value, AggregateType.CONTACT.name, id) ?: return false
        return row.revision == revision && row.state == DurableMutationState.IN_FLIGHT.name &&
            row.blockedReason == null && row.errorCategory == CONFIRMED && current(row)
    }

    private suspend fun current(row: OutboxMutationEntity): Boolean {
        if (row.aggregateType != AggregateType.CONTACT.name || row.operation != MutationOperation.UPSERT.name ||
            !row.requiresReconciliation || row.remoteIdentity != null || row.remoteVersion != null) return false
        val contact = database.contactDao().getEntity(row.accountId, row.aggregateId) ?: return false
        if (contact.revision != row.revision || contact.pendingMutationRevision != row.revision || contact.isDeleted ||
            contact.remoteContactId != null || contact.remoteVersion != null || contact.conflictState != null ||
            database.contactConflictDao().get(row.accountId, row.aggregateId) != null) return false
        val uid = contact.remoteVCardUid ?: contact.id
        val owners = database.contactDao().getEntitiesByStableCreateUid(row.accountId, uid)
        return owners.size == 1 && owners.single().id == contact.id
    }

    companion object {
        const val CONFIRMED = "AMBIGUOUS_CREATE_ANYWAY_CONFIRMED"
    }
}
