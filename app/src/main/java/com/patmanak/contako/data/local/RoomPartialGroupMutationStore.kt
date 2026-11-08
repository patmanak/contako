package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.RemoteMutationOperation

/** Retained group intent waiting for local input, not transport retry or remote acknowledgement. */
internal class RoomPartialGroupMutationStore(private val database: ContakoDatabase) {
    suspend fun park(command: DurableMutationCommand): Boolean = database.withTransaction {
        if (command.aggregateType != AggregateType.GROUP.name || command.operation != RemoteMutationOperation.ASSIGNMENTS)
            return@withTransaction false
        val intent = database.outboxDao().get(command.accountId, command.aggregateType, command.aggregateId)
            ?: return@withTransaction false
        val group = database.contactGroupDao().get(command.accountId, command.aggregateId)?.group
            ?: return@withTransaction false
        if (intent.revision != command.revision || intent.state != DurableMutationState.IN_FLIGHT.name ||
            intent.operation != MutationOperation.ASSIGNMENTS.name || intent.blockedReason != null ||
            group.isDeleted || group.conflictState != null || group.revision != command.revision || group.pendingMutationRevision != command.revision ||
            group.remoteLabelId == null || group.remoteLabelId != intent.remoteIdentity ||
            group.remoteVersion != intent.remoteVersion ||
            intent.remoteIdentity != command.expectedRemoteIdentity || intent.remoteVersion != command.expectedRemoteVersion)
            return@withTransaction false
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.PARTIAL.name, errorCategory = null))
        true
    }

    /** Called in the same canonical ACK/adoption transaction that may replace email identities. */
    suspend fun wakeForContact(accountId: String, contactId: String) = database.withTransaction {
        for (intent in database.outboxDao().getAll(accountId)) {
            if (intent.aggregateType != AggregateType.GROUP.name || intent.operation != MutationOperation.ASSIGNMENTS.name ||
                intent.state != DurableMutationState.PARTIAL.name || intent.blockedReason != null) continue
            val stored = database.contactGroupDao().get(accountId, intent.aggregateId) ?: continue
            val group = stored.group
            if (group.revision != intent.revision || group.pendingMutationRevision != intent.revision || group.isDeleted ||
                group.remoteLabelId == null || group.remoteLabelId != intent.remoteIdentity ||
                (stored.memberships.none { it.contactId == contactId } &&
                    database.remoteGroupAssignmentDependencyDao().edges(accountId, intent.aggregateId)
                        .none { it.contactId == contactId })) continue
            database.outboxDao().upsert(intent.copy(state = DurableMutationState.PENDING.name, requiresReconciliation = true))
        }
    }
}
