package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.MutationExecutionStore
import com.patmanak.contako.data.sync.MutationRemainingWork
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RetryDecision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RoomMutationExecutionStore(
    private val database: ContakoDatabase,
    private val existence: com.patmanak.contako.data.gateway.ProtonContactExistenceGateway? = null,
    private val recoveryGeneration: () -> Long = { 0 },
    private val evidenceClock: () -> Long = System::currentTimeMillis,
) : MutationExecutionStore {
    private val store = RoomOutboxStore(database)
    private val recoveryLock = Mutex()
    private val lastRecoveredGenerations = mutableMapOf<String, Long>()

    override suspend fun remaining(accountId: String): MutationRemainingWork {
        val rows = database.outboxDao().getAll(accountId).filter { it.state != DurableMutationState.PARTIAL.name }
        val blocked = rows.filter { it.state == DurableMutationState.ACTION_REQUIRED.name || it.blockedReason != null }
        return MutationRemainingWork(blocked.size, rows.size - blocked.size, blocked.map { row ->
            GatewayFailureCategory.entries.firstOrNull { it.name == row.errorCategory } ?: GatewayFailureCategory.UNKNOWN
        }.toSet())
    }

    override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int {
        var recovered = RoomMutationRetryRequestStore(database).consume(
            com.patmanak.contako.data.gateway.AccountScope(accountId), nowEpochMillis)
        recovered += store.recoverInterrupted(accountId, nowEpochMillis)
        recovered += recoverSessionAndCapabilityBlocks(accountId, nowEpochMillis)
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

    /**
     * Called by the serialized engine after current remote prerequisites/catalog acquisition.
     * A new authenticated/capability generation permits one fresh attempt, never acknowledgement.
     */
    private suspend fun recoverSessionAndCapabilityBlocks(accountId: String, nowEpochMillis: Long): Int =
        recoveryLock.withLock {
            val generation = recoveryGeneration()
            if (lastRecoveredGenerations[accountId] == generation) return@withLock 0
            val recovered = try {
                database.withTransaction {
                    if (recoveryGeneration() != generation) throw ChangedRecoveryGeneration()
                    var count = 0
                    for (intent in database.outboxDao().getAll(accountId)) {
                        if (!intent.isSessionOrCapabilityBlock()) continue
                        val sameCanonicalIntent = when (intent.aggregateType) {
                            AggregateType.CONTACT.name -> database.contactDao().get(accountId, intent.aggregateId)?.contact?.let {
                                it.revision == intent.revision && it.pendingMutationRevision == intent.revision &&
                                    it.remoteContactId == intent.remoteIdentity && it.remoteVersion == intent.remoteVersion &&
                                    (it.conflictState == null || intent.isBoundAmbiguousCreateConflict()) &&
                                    ((intent.operation == MutationOperation.DELETE.name && it.isDeleted) ||
                                        (intent.operation == MutationOperation.UPSERT.name && !it.isDeleted))
                            } == true
                            AggregateType.GROUP.name -> database.contactGroupDao().get(accountId, intent.aggregateId)?.group?.let {
                                it.revision == intent.revision && it.pendingMutationRevision == intent.revision &&
                                    it.remoteLabelId == intent.remoteIdentity && it.remoteVersion == intent.remoteVersion &&
                                    it.conflictState == null &&
                                    ((intent.operation == MutationOperation.DELETE.name && it.isDeleted) ||
                                        (intent.operation in setOf(MutationOperation.UPSERT.name, MutationOperation.ASSIGNMENTS.name) && !it.isDeleted))
                            } == true
                            else -> false
                        }
                        if (!sameCanonicalIntent) continue
                        // Idempotency, attempt history, remote baseline and ambiguous-delivery
                        // reconciliation are deliberately retained from this exact durable row.
                        database.outboxDao().upsert(intent.copy(state = DurableMutationState.PENDING.name,
                            blockedReason = null, errorCategory = null, nextAttemptAtEpochMillis = nowEpochMillis))
                        count++
                    }
                    if (recoveryGeneration() != generation) throw ChangedRecoveryGeneration()
                    count
                }
            } catch (_: ChangedRecoveryGeneration) {
                return@withLock 0
            }
            // Remember only a committed stable generation. A concurrent invalidation remains
            // eligible for a new probe on the next pass rather than being consumed here.
            if (recoveryGeneration() == generation) lastRecoveredGenerations[accountId] = generation
            recovered
        }

    override suspend fun eligible(
        accountId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<DurableMutationCommand> = store.eligible(accountId, nowEpochMillis, limit).map { it.toCommand() }

    override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long): Boolean =
        entity(command)?.let { store.claim(it, nowEpochMillis) } ?: false

    override suspend fun defer(command: DurableMutationCommand): Boolean = database.withTransaction {
        val current = entity(command) ?: return@withTransaction false
        database.outboxDao().markRetry(current.accountId, current.aggregateType, current.aggregateId,
            current.revision, current.attemptCount, current.nextAttemptAtEpochMillis,
            current.errorCategory, current.requiresReconciliation) == 1
    }

    override suspend fun awaitLocalInput(command: DurableMutationCommand): Boolean =
        RoomPartialGroupMutationStore(database).park(command)

    override suspend fun recordFailure(
        command: DurableMutationCommand,
        category: GatewayFailureCategory,
        decision: RetryDecision,
    ): Boolean = database.withTransaction {
        val current = entity(command) ?: return@withTransaction false
        if (!store.applyRetryDecision(current, category, decision)) return@withTransaction false
        if ((decision == RetryDecision.ActionRequired || decision is RetryDecision.Exhausted) &&
            command.aggregateType == AggregateType.CONTACT.name) {
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
        val inFlight = entity(command) ?: return@withTransaction retainSupersededContactAcknowledgement(command, acknowledgement)
        val stored = if (command.aggregateType == AggregateType.CONTACT.name) {
            database.contactDao().get(command.accountId, command.aggregateId)
                ?.takeIf { it.contact.revision == command.revision }
                ?: return@withTransaction false
        } else null
        if (!store.acknowledge(inFlight, acknowledgement.remoteIdentity, acknowledgement.remoteVersion)) return@withTransaction false
        // Persist service identities in the same transaction as acknowledgement/cleanup, before
        // any queued group assignment can read them. Canonical value IDs/memberships stay stable.
        val identities = acknowledgement.emailIdsByValueId
        if (stored != null && identities != null) RoomContactUploadOverlays(database)
            .acknowledged(command.accountId, command.aggregateId, command.revision, identities)
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
        if (stored != null) RoomPartialGroupMutationStore(database).wakeForContact(command.accountId, command.aggregateId)
        val acknowledged = entity(command) ?: return@withTransaction false
        val finished = store.finishAcknowledged(acknowledged)
        if (finished && stored != null && acknowledgement.remoteIdentity != null &&
            command.operation != com.patmanak.contako.data.sync.RemoteMutationOperation.ASSIGNMENTS &&
            !RoomContactUploadOverlays(database).has(command.accountId, command.aggregateId)) {
            val kind = if (command.operation == com.patmanak.contako.data.sync.RemoteMutationOperation.DELETE)
                com.patmanak.contako.domain.sync.ContactSyncEvidenceKind.CONFIRMED_ABSENCE
                else com.patmanak.contako.domain.sync.ContactSyncEvidenceKind.WRITE_ACKNOWLEDGEMENT
            RoomContactSyncEvidenceStore(database).recordProton(command.accountId, command.aggregateId, command.revision,
                acknowledgement.remoteIdentity, acknowledgement.remoteVersion, kind, evidenceClock())
        }
        finished
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

    /** The response covers the submitted revision, never the newer local values. */
    private suspend fun retainSupersededContactAcknowledgement(
        command: DurableMutationCommand,
        acknowledgement: RemoteMutationAcknowledgement,
    ): Boolean {
        if (command.aggregateType != AggregateType.CONTACT.name || command.operation !in setOf(
                com.patmanak.contako.data.sync.RemoteMutationOperation.CREATE,
                com.patmanak.contako.data.sync.RemoteMutationOperation.UPDATE,
            )) return false
        val identity = acknowledgement.remoteIdentity?.takeIf(String::isNotBlank) ?: return false
        val current = database.contactDao().getEntity(command.accountId, command.aggregateId) ?: return false
        val pending = database.outboxDao().get(command.accountId, command.aggregateType, command.aggregateId) ?: return false
        if (current.revision <= command.revision || current.pendingMutationRevision != current.revision ||
            pending.revision != current.revision || current.conflictState != null || pending.blockedReason != null ||
            pending.state != DurableMutationState.PENDING.name) return false
        if (command.operation == com.patmanak.contako.data.sync.RemoteMutationOperation.UPDATE) {
            if (command.expectedRemoteIdentity != identity || current.remoteContactId != identity ||
                pending.remoteIdentity != identity || current.remoteVersion != command.expectedRemoteVersion ||
                pending.remoteVersion != command.expectedRemoteVersion) return false
        } else if (current.remoteContactId != null || pending.remoteIdentity != null || !pending.requiresReconciliation) return false
        if (database.contactDao().getEntitiesByRemoteContactId(command.accountId, identity).any { it.id != current.id }) return false
        val ledger = database.androidProjectionLedgerDao().get(command.accountId, command.aggregateId)
        if (ledger != null && ledger.sourceIdentity != identity && (ledger.sourceIdentity != null ||
                ledger.adoptionState != com.patmanak.contako.data.android.AndroidAdoptionState.AWAITING_REMOTE_ID.name)) return false
        val latest = database.contactDao().get(command.accountId, command.aggregateId)?.toDomain() ?: return false
        if (!RoomContactUploadOverlays(database).retainAcknowledgedIdentities(latest, acknowledgement.emailIdsByValueId.orEmpty())) return false
        if (!store.attachContactIdentity(command.accountId, command.aggregateId, identity, current.isDeleted)) return false
        database.contactDao().upsert(current.copy(remoteContactId = identity, remoteVersion = acknowledgement.remoteVersion))
        database.outboxDao().upsert(pending.copy(remoteIdentity = identity, remoteVersion = acknowledgement.remoteVersion,
            requiresReconciliation = acknowledgement.remoteVersion == null))
        // Only stable surviving occurrences inherited service identities; newer content remains pending.
        // Keeping the newer outbox row also keeps dependent assignments gated.
        return true
    }
}

private fun OutboxMutationEntity.isSessionOrCapabilityBlock(): Boolean {
    if (state != DurableMutationState.ACTION_REQUIRED.name) return false
    if (isBoundAmbiguousCreateConflict()) return true
    // Earlier versions blocked an ambiguous creation before an authoritative recheck
    // was available. A fresh request generation reopens the check, never a blind POST.
    if (aggregateType == AggregateType.CONTACT.name && remoteIdentity == null && requiresReconciliation &&
        operation in setOf(MutationOperation.UPSERT.name, MutationOperation.DELETE.name) &&
        errorCategory == GatewayFailureCategory.CONFLICT.name && blockedReason == "CONFLICT_RECOVERY_REQUIRED") return true
    return when (errorCategory) {
        GatewayFailureCategory.AUTHENTICATION_REQUIRED.name -> blockedReason == "AUTHENTICATION_REQUIRED"
        GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED.name -> blockedReason == "INTERACTIVE_AUTHENTICATION_REQUIRED"
        GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED.name -> when (aggregateType) {
            AggregateType.GROUP.name -> blockedReason == "GROUP_CAPABILITY_REQUIRED"
            // Older builds incorrectly used the group capability wording for contact writes.
            AggregateType.CONTACT.name -> blockedReason in setOf("REMOTE_PERMISSION_REQUIRED", "GROUP_CAPABILITY_REQUIRED")
            else -> false
        }
        else -> false
    }
}

private fun OutboxMutationEntity.isBoundAmbiguousCreateConflict(): Boolean =
    aggregateType == AggregateType.CONTACT.name && remoteIdentity != null && remoteVersion == null &&
        requiresReconciliation && errorCategory == "CONFLICT" && blockedReason == RoomContactConflictStore.CONFLICT &&
        operation in setOf(MutationOperation.UPSERT.name, MutationOperation.DELETE.name)

private class ChangedRecoveryGeneration : RuntimeException(null, null, false, false)

private fun OutboxMutationEntity.toCommand() = DurableMutationCommand(
    accountId = accountId,
    aggregateType = aggregateType,
    aggregateId = aggregateId,
    revision = revision,
    operation = remoteOperation(),
    attemptCount = attemptCount,
    nextEligibleEpochMillis = nextAttemptAtEpochMillis,
    requiresReconciliation = requiresReconciliation,
    expectedRemoteIdentity = remoteIdentity,
    expectedRemoteVersion = remoteVersion,
)
