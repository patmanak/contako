package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.MutationExecutionStore
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RetryDecision

internal class RoomMutationExecutionStore(
    private val database: ContakoDatabase,
    private val existence: com.patmanak.contako.data.gateway.ProtonContactExistenceGateway? = null,
) : MutationExecutionStore {
    private val store = RoomOutboxStore(database)

    override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int {
        var recovered = store.recoverInterrupted(accountId, nowEpochMillis)
        val reader = existence ?: return recovered
        // Older blocked deletes remain durable but are not eligible for a normal retry.
        // Reopen only a matching delete whose remote absence has just been confirmed.
        for (intent in database.outboxDao().getAll(accountId).filter {
            it.aggregateType == AggregateType.CONTACT.name && it.operation == MutationOperation.DELETE.name &&
                it.state == DurableMutationState.ACTION_REQUIRED.name && it.remoteIdentity != null
        }) {
            val remoteId = requireNotNull(intent.remoteIdentity)
            val proof = reader.check(com.patmanak.contako.data.gateway.AccountScope(accountId),
                com.patmanak.contako.data.gateway.RemoteContactId(remoteId))
            if (proof !is com.patmanak.contako.data.gateway.GatewayOutcome.Success ||
                proof.value != com.patmanak.contako.data.gateway.RemoteContactPresence.CONFIRMED_ABSENT) continue
            recovered += database.withTransaction {
                val current = database.outboxDao().get(accountId, intent.aggregateType, intent.aggregateId)
                val local = database.contactDao().get(accountId, intent.aggregateId)?.contact
                if (current != intent || local == null || !local.isDeleted ||
                    local.revision != intent.revision || local.pendingMutationRevision != intent.revision ||
                    local.remoteContactId != remoteId) return@withTransaction 0
                database.outboxDao().upsert(current.copy(state = DurableMutationState.PENDING.name,
                    blockedReason = null, errorCategory = null, nextAttemptAtEpochMillis = nowEpochMillis))
                1
            }
        }
        return recovered
    }

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
    ): Boolean = database.withTransaction {
        val current = entity(command) ?: return@withTransaction false
        if (!store.applyRetryDecision(current, category, decision)) return@withTransaction false
        if (decision == RetryDecision.ActionRequired && command.aggregateType == AggregateType.CONTACT.name) {
            database.contactConflictDao().invalidateChoice(command.accountId, command.aggregateId, command.revision)
        }
        if (decision == RetryDecision.DeleteConverged) {
            val acknowledged = entity(command) ?: return@withTransaction false
            return@withTransaction store.finishAcknowledged(acknowledged)
        }
        true
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
            val replacedEmailIds = stored.values.filter { it.kind == "EMAIL" }.filter { value ->
                val oldIdentity = StringMapCodec.decode(value.metadataEncoding)[com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY]
                val newIdentity = identities[value.id]
                newIdentity != null && newIdentity != oldIdentity
            }.map { it.id }.toSet()
            database.contactDao().upsertValues(stored.values.filter { it.kind == "EMAIL" }.map { value ->
                val metadata = StringMapCodec.decode(value.metadataEncoding).toMutableMap()
                metadata.remove(com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY)
                identities[value.id]?.let { metadata[com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY] = it }
                value.copy(metadataEncoding = StringMapCodec.encode(metadata))
            })
            // Recover older assignment failures only when this acknowledged contact write
            // supplies a replacement service identity used by that exact pending group.
            // Reconciliation stays mandatory before retrying a possibly delivered write.
            if (replacedEmailIds.isNotEmpty()) {
                database.contactGroupDao().getAll(command.accountId).filter { group ->
                    group.memberships.any { it.contactId == command.aggregateId && it.emailValueId in replacedEmailIds }
                }.forEach { group ->
                    val pending = database.outboxDao().get(command.accountId, AggregateType.GROUP.name, group.group.id)
                    if (pending != null && pending.state == DurableMutationState.ACTION_REQUIRED.name &&
                        pending.operation == MutationOperation.ASSIGNMENTS.name &&
                        pending.errorCategory == GatewayFailureCategory.MALFORMED_RESPONSE.name &&
                        pending.revision == group.group.revision && group.group.pendingMutationRevision == pending.revision) {
                        database.outboxDao().upsert(pending.copy(state = DurableMutationState.PENDING.name,
                            blockedReason = null, errorCategory = null, requiresReconciliation = true,
                            nextAttemptAtEpochMillis = 0L))
                    }
                }
            }
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
        if (command.aggregateType == AggregateType.GROUP.name &&
            command.operation == com.patmanak.contako.data.sync.RemoteMutationOperation.CREATE) {
            val identity = acknowledgement.remoteIdentity?.takeIf(String::isNotBlank) ?: return@withTransaction false
            val current = database.contactGroupDao().get(command.accountId, command.aggregateId)?.group
                ?: return@withTransaction false
            val intent = database.outboxDao().get(command.accountId, command.aggregateType, command.aggregateId)
                ?: return@withTransaction false
            if (current.revision < command.revision || intent.revision != current.revision ||
                (current.remoteLabelId != null && current.remoteLabelId != identity) ||
                (intent.remoteIdentity != null && intent.remoteIdentity != identity)) return@withTransaction false
            if (current.revision > command.revision) {
                // A received identity belongs to the creation attempt, not its obsolete content.
                database.contactGroupDao().upsert(current.copy(remoteLabelId = identity, remoteVersion = acknowledgement.remoteVersion))
                database.outboxDao().upsert(intent.copy(
                    remoteIdentity = identity,
                    remoteVersion = acknowledgement.remoteVersion,
                    requiresReconciliation = false,
                    state = DurableMutationState.PENDING.name,
                    errorCategory = null,
                    blockedReason = null,
                    nextAttemptAtEpochMillis = nowEpochMillis,
                ))
                return@withTransaction true
            }
        }
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
