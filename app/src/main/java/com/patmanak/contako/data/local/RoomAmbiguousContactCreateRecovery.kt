package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.sync.BoundedContactInventoryReader
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.data.sync.MutationPreparationActionRequiredReason
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RemoteMutationOperation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exceptional lost-create reconciliation; ordinary unchanged synchronization never scans full cards. */
internal class RoomAmbiguousContactCreateRecovery(
    private val account: AccountScope,
    private val database: ContakoDatabase,
    inventory: ProtonContactInventoryGateway,
    private val cards: ProtonVerifiedContactCardGateway,
) {
    private val inventoryReader = BoundedContactInventoryReader(inventory)

    suspend fun prepare(command: DurableMutationCommand): GatewayOutcome<MutationPreparation> {
        if (command.accountId != account.value) return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        if (command.aggregateType != AggregateType.CONTACT.name || !command.requiresReconciliation ||
            command.operation !in setOf(RemoteMutationOperation.CREATE, RemoteMutationOperation.DELETE)) return stale()
        val before = database.withTransaction { snapshot(command) } ?: return stale()
        val uid = before.contact.contact.remoteVCardUid ?: before.contact.contact.id
        val context = currentCoroutineContext()
        val first = when (val read = inventoryReader.read(account) { context.ensureActive(); false }) {
            is GatewayOutcome.Failure -> return read
            is GatewayOutcome.Success -> read.value
        }
        if (!first.hasCompleteAuthority()) return unstable()

        // Keep at most the one card needed for binding. A second matching identity
        // establishes ambiguity without accumulating any more contact payloads.
        var match: VerifiedContactCard? = null
        var duplicate = false
        for (metadata in first.contacts) {
            context.ensureActive()
            val card = when (val read = cards.fetch(account, metadata.id)) {
                is GatewayOutcome.Failure -> return read
                is GatewayOutcome.Success -> read.value
            }
            if (card.id != metadata.id || card.version == null || card.contact.accountId != account.value) {
                return GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
            }
            if (card.contact.remoteVCardUid == uid) {
                if (match != null) { duplicate = true; break }
                match = card
            }
        }
        val second = when (val read = inventoryReader.read(account) { context.ensureActive(); false }) {
            is GatewayOutcome.Failure -> return read
            is GatewayOutcome.Success -> read.value
        }
        if (!second.hasCompleteAuthority() || first.snapshotAuthority != second.snapshotAuthority ||
            first.identityVersions() != second.identityVersions()) return unstable()
        if (duplicate) return unidentified()

        return database.withTransaction {
            context.ensureActive()
            val current = snapshot(command) ?: return@withTransaction stale()
            if (current != before) return@withTransaction stale()
            val identities = database.contactDao().getEntitiesByStableCreateUid(account.value, uid)
            if (identities.size != 1 || identities.single().id != command.aggregateId) return@withTransaction unidentified()
            val matched = match
            if (matched == null) {
                // Only this exact intent's ambiguity flag changes. Newer edits,
                // retry history, source payload and pending deletion stay durable.
                database.outboxDao().upsert(current.intent.copy(requiresReconciliation = false))
                GatewayOutcome.Success(if (command.operation == RemoteMutationOperation.DELETE)
                    MutationPreparation.AlreadyApplied(RemoteMutationAcknowledgement(null, null))
                else MutationPreparation.UploadAllowed)
            } else {
                if (database.contactDao().getEntitiesByRemoteContactId(account.value, matched.id.value)
                        .any { it.id != command.aggregateId }) return@withTransaction unidentified()
                RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(account, matched)
                GatewayOutcome.Success(MutationPreparation.ActionRequired(
                    MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT))
            }
        }
    }

    private suspend fun snapshot(command: DurableMutationCommand): Snapshot? {
        val contact = database.contactDao().get(account.value, command.aggregateId) ?: return null
        val intent = database.outboxDao().get(account.value, AggregateType.CONTACT.name, command.aggregateId) ?: return null
        if (contact.contact.revision != command.revision || contact.contact.pendingMutationRevision != command.revision ||
            contact.contact.remoteContactId != null || intent.revision != command.revision || intent.remoteIdentity != null ||
            !intent.requiresReconciliation || intent.state != DurableMutationState.IN_FLIGHT.name ||
            intent.remoteOperation() != command.operation) return null
        return Snapshot(contact.copy(values = contact.values.sortedBy { it.id }), intent,
            database.contactPayloadDao().get(contact.contact.ownerKey))
    }

    private data class Snapshot(val contact: ContactWithValues, val intent: OutboxMutationEntity,
        val payload: ContactPayloadEntity?) {
        override fun toString() = "AmbiguousCreateSnapshot(REDACTED)"
    }

    private fun ValidatedCompleteInventory.hasCompleteAuthority() = snapshotAuthority in setOf(
        ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY,
        ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
    )

    private fun ValidatedCompleteInventory.identityVersions() = contacts.associate { metadata ->
        metadata.id to Triple(metadata.version, metadata.versionProvenance, metadata.coverage)
    }

    private fun stale(): GatewayOutcome<MutationPreparation> = GatewayOutcome.Success(
        MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.REVISION_MISMATCH))

    private fun unidentified(): GatewayOutcome<MutationPreparation> = GatewayOutcome.Success(
        MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.RECONCILIATION_REMOTE_IDENTITY_MISSING))

    private fun unstable(): GatewayOutcome<MutationPreparation> = GatewayOutcome.Failure(GatewayFailureCategory.REMOTE_SERVICE_FAILURE)
}
