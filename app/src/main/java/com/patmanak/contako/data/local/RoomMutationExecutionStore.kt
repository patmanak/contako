package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.MutationExecutionStore
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RetryDecision

internal class RoomMutationExecutionStore(
    private val database: ContakoDatabase,
) : MutationExecutionStore {
    private val store = RoomOutboxStore(database)

    override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int =
        store.recoverInterrupted(accountId, nowEpochMillis)

    override suspend fun eligible(
        accountId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<DurableMutationCommand> = store.eligible(accountId, nowEpochMillis, limit).map { it.toCommand() }

    override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long): Boolean =
        entity(command)?.let { store.claim(it, nowEpochMillis) } ?: false

    override suspend fun recordFailure(
        command: DurableMutationCommand,
        category: GatewayFailureCategory,
        decision: RetryDecision,
    ): Boolean {
        val current = entity(command) ?: return false
        if (!store.applyRetryDecision(current, category, decision)) return false
        if (decision == RetryDecision.DeleteConverged) {
            val acknowledged = entity(command) ?: return false
            return store.finishAcknowledged(acknowledged)
        }
        return true
    }

    override suspend fun acknowledgeAndFinish(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
    ): Boolean = database.withTransaction {
        val inFlight = entity(command) ?: return@withTransaction false
        val stored = if (command.aggregateType == AggregateType.CONTACT.name) {
            database.contactDao().get(command.accountId, command.aggregateId)
                ?.takeIf { it.contact.revision == command.revision }
                ?: return@withTransaction false
        } else null
        if (!store.acknowledge(inFlight, acknowledgement.remoteIdentity, acknowledgement.remoteVersion)) return@withTransaction false
        // Persist service identities in the same transaction as acknowledgement/cleanup, before
        // any queued group assignment can read them. Canonical value IDs/memberships stay stable.
        val identities = acknowledgement.emailIdsByValueId
        if (stored != null && identities != null) {
            database.contactDao().upsertValues(stored.values.filter { it.kind == "EMAIL" }.map { value ->
                val metadata = StringMapCodec.decode(value.metadataEncoding).toMutableMap()
                metadata.remove(com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY)
                identities[value.id]?.let { metadata[com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY] = it }
                value.copy(metadataEncoding = StringMapCodec.encode(metadata))
            })
        }
        val acknowledged = entity(command) ?: return@withTransaction false
        store.finishAcknowledged(acknowledged)
    }

    override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand): Boolean {
        val inFlight = entity(command) ?: return false
        if (!store.acknowledge(inFlight, inFlight.remoteIdentity, inFlight.remoteVersion)) return false
        val acknowledged = entity(command) ?: return false
        return store.finishAcknowledged(acknowledged)
    }

    override suspend fun continueAfterPartialProgress(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
        nowEpochMillis: Long,
    ): Boolean = database.withTransaction {
        when (AggregateType.valueOf(command.aggregateType)) {
            AggregateType.CONTACT -> database.contactDao().get(command.accountId, command.aggregateId)?.let { stored ->
                if (stored.contact.revision == command.revision) {
                    database.contactDao().upsert(
                        stored.contact.copy(
                            remoteContactId = acknowledgement.remoteIdentity,
                            remoteVersion = acknowledgement.remoteVersion,
                        ),
                    )
                }
            }
            AggregateType.GROUP -> database.contactGroupDao().get(command.accountId, command.aggregateId)?.let { stored ->
                if (stored.group.revision == command.revision) {
                    database.contactGroupDao().upsert(
                        stored.group.copy(
                            remoteLabelId = acknowledgement.remoteIdentity,
                            remoteVersion = acknowledgement.remoteVersion,
                        ),
                    )
                }
            }
        }
        database.outboxDao().markProgressPending(
            command.accountId,
            command.aggregateType,
            command.aggregateId,
            command.revision,
            nowEpochMillis,
            operation = when (requireNotNull(acknowledgement.nextOperation)) {
                com.patmanak.contako.data.sync.RemoteMutationOperation.ASSIGNMENTS -> MutationOperation.ASSIGNMENTS.name
                com.patmanak.contako.data.sync.RemoteMutationOperation.CREATE,
                com.patmanak.contako.data.sync.RemoteMutationOperation.UPDATE,
                -> MutationOperation.UPSERT.name
                com.patmanak.contako.data.sync.RemoteMutationOperation.DELETE -> MutationOperation.DELETE.name
            },
            remoteIdentity = acknowledgement.remoteIdentity,
            remoteVersion = acknowledgement.remoteVersion,
        ) == 1
    }

    private suspend fun entity(command: DurableMutationCommand): OutboxMutationEntity? =
        database.outboxDao().get(command.accountId, command.aggregateType, command.aggregateId)
            ?.takeIf { it.revision == command.revision }
}

private fun OutboxMutationEntity.toCommand() = DurableMutationCommand(
    accountId = accountId,
    aggregateType = aggregateType,
    aggregateId = aggregateId,
    revision = revision,
    operation = remoteOperation(),
    attemptCount = attemptCount,
    nextEligibleEpochMillis = nextAttemptAtEpochMillis,
    requiresReconciliation = requiresReconciliation,
)
