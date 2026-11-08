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
import com.patmanak.contako.data.sync.MutationRetryPolicy
import com.patmanak.contako.data.sync.LocalReconciliationInvariant
import com.patmanak.contako.data.sync.checkLocalReconciliationInvariant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exceptional lost-create reconciliation; ordinary unchanged synchronization never scans full cards. */
internal class RoomAmbiguousContactCreateRecovery(
    private val account: AccountScope,
    private val database: ContakoDatabase,
    inventory: ProtonContactInventoryGateway,
    private val cards: ProtonVerifiedContactCardGateway,
    private val scanPermit: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryPolicy: MutationRetryPolicy = MutationRetryPolicy(),
) {
    private val inventoryReader = BoundedContactInventoryReader(inventory)

    suspend fun prepare(command: DurableMutationCommand): GatewayOutcome<MutationPreparation> {
        if (command.accountId != account.value) return GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED)
        if (command.aggregateType != AggregateType.CONTACT.name || !command.requiresReconciliation ||
            command.operation !in setOf(RemoteMutationOperation.CREATE, RemoteMutationOperation.UPDATE,
                RemoteMutationOperation.DELETE)) return stale()
        var scanTime = clock()
        val targets = database.withTransaction {
            val current = snapshot(command) ?: return@withTransaction emptyList<Pair<DurableMutationCommand, Snapshot>>()
            scanTime = current.intent.lastAttemptAtEpochMillis ?: scanTime
            val others = database.outboxDao().getAll(account.value).asSequence()
                .filter { it.aggregateType == AggregateType.CONTACT.name && it.aggregateId != command.aggregateId &&
                    it.state == DurableMutationState.PENDING.name && it.blockedReason == null &&
                    it.nextAttemptAtEpochMillis <= scanTime && it.requiresReconciliation && it.remoteVersion == null }
                .take(MAX_RECOVERY_TARGETS - 1).toList().map { intent ->
                    val other = DurableMutationCommand(account.value, intent.aggregateType, intent.aggregateId,
                        intent.revision, intent.remoteOperation(), intent.attemptCount, intent.nextAttemptAtEpochMillis,
                        requiresReconciliation = true, expectedRemoteIdentity = intent.remoteIdentity)
                    other to snapshot(other, allowPending = true)
                }.mapNotNull { (other, value) -> value?.let { other to it } }.toList()
            listOf(command to current) + others
        }
        if (targets.isEmpty()) return stale()
        // An explicit, revision-bound risk acknowledgement is not an absence proof.
        // A local edit replaces the intent and drops this marker while retaining ambiguity.
        if (targets.first().second.intent.errorCategory == RoomAmbiguousContactCreateChoiceStore.CONFIRMED) {
            return database.withTransaction {
                val before = targets.first().second
                if (snapshot(command) != before || !RoomAmbiguousContactCreateChoiceStore(database)
                        .confirmedStillCurrent(account, command.aggregateId, command.revision)) return@withTransaction stale()
                database.outboxDao().upsert(before.intent.copy(requiresReconciliation = false, errorCategory = null))
                GatewayOutcome.Success(MutationPreparation.UploadAllowed)
            }
        }
        if (!scanPermit()) return GatewayOutcome.Success(MutationPreparation.WaitingForDependencies)
        val uids = targets.map { (_, target) -> target.contact.contact.remoteVCardUid ?: target.contact.contact.id }.toSet()
        val context = currentCoroutineContext()
        val first = when (val read = inventoryReader.read(account) { context.ensureActive(); false }) {
            is GatewayOutcome.Failure -> return read
            is GatewayOutcome.Success -> read.value
        }
        if (!first.hasCompleteAuthority()) return unstable()

        // Retain at most one card per bounded creation target. Another matching
        // identity establishes ambiguity without retaining its payload.
        val matches = mutableMapOf<String, VerifiedContactCard>()
        val expectedRemoteIds = targets.mapNotNull { (_, target) -> target.intent.remoteIdentity }.toSet()
        val boundMatches = mutableMapOf<String, VerifiedContactCard>()
        val duplicates = mutableSetOf<String>()
        var incomplete: GatewayOutcome.Failure? = null
        for (metadata in first.contacts) {
            context.ensureActive()
            val card = when (val read = cards.fetch(account, metadata.id)) {
                is GatewayOutcome.Failure -> {
                    val failure = recoveryReadFailure(read)
                    // A malformed/unreadable card cannot prove absence, but must not hide a
                    // later authenticated positive identity proof. Account/network failures
                    // still stop this exceptional scan; they do not start one timeout per card.
                    if (failure.category != GatewayFailureCategory.RECONCILIATION_INCOMPLETE) return failure
                    if (incomplete == null) incomplete = failure
                    continue
                }
                is GatewayOutcome.Success -> read.value
            }
            if (card.id != metadata.id || card.version == null || card.contact.accountId != account.value) {
                if (incomplete == null) incomplete = recoveryReadFailure(GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE,
                    contactHydrationCategory = com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.UID_ID_CONSISTENCY))
                continue
            }
            val uid = card.contact.remoteVCardUid
            if (card.id.value in expectedRemoteIds) boundMatches[card.id.value] = card
            if (uid != null && uid in uids) {
                if (matches.putIfAbsent(uid, card) != null) duplicates += uid
            }
        }
        val second = when (val read = inventoryReader.read(account) { context.ensureActive(); false }) {
            is GatewayOutcome.Failure -> return read
            is GatewayOutcome.Success -> read.value
        }
        if (!second.hasCompleteAuthority() || first.snapshotAuthority != second.snapshotAuthority ||
            first.identityVersions() != second.identityVersions()) return unstable()
        return database.withTransaction {
            context.ensureActive()
            var result: GatewayOutcome<MutationPreparation> = stale()
            // Both kinds of proof are tied to a captured intent, canonical fields and payload.
            // Apply them atomically after revalidation, never as a reusable absence cache for
            // another revision/intent. Shared failures use the ordinary retry lifecycle;
            // identity proofs do not reset retained deadlines or retry history.
            for ((targetCommand, before) in targets) {
                val isCurrent = targetCommand.aggregateId == command.aggregateId
                val current = snapshot(targetCommand, allowPending = !isCurrent)
                if (current != before) continue
                val uid = before.contact.contact.remoteVCardUid ?: before.contact.contact.id
                val owners = database.contactDao().getEntitiesByStableCreateUid(account.value, uid)
                val knownIdentity = before.intent.remoteIdentity
                val safe = (knownIdentity != null || uid !in duplicates) &&
                    owners.size == 1 && owners.single().id == targetCommand.aggregateId
                val matched = if (knownIdentity != null) boundMatches[knownIdentity] else matches[uid]
                val outcome = if (!safe) unidentified()
                else if (matched == null) {
                    if (incomplete != null) {
                        val failure = recordVerificationFailure(targetCommand, before, checkNotNull(incomplete), isCurrent, scanTime)
                        if (isCurrent) result = failure
                        continue
                    }
                    if (knownIdentity != null) unidentified()
                    else {
                        database.outboxDao().upsert(before.intent.copy(requiresReconciliation = false))
                        GatewayOutcome.Success(if (targetCommand.operation == RemoteMutationOperation.DELETE)
                            MutationPreparation.AlreadyApplied(RemoteMutationAcknowledgement(null, null))
                        else MutationPreparation.UploadAllowed)
                    }
                } else if (incomplete != null && knownIdentity == null) {
                    // An unreadable card could hide a duplicate UID. A visible candidate
                    // is enough to forbid a blind create, not enough to choose its identity.
                    recordVerificationFailure(targetCommand, before, GatewayOutcome.Failure(
                        GatewayFailureCategory.RECONCILIATION_MATCH_UNVERIFIED,
                        retryAfterMillis = checkNotNull(incomplete).retryAfterMillis), isCurrent, scanTime)
                } else if (database.contactDao().getEntitiesByRemoteContactId(account.value, matched.id.value)
                        .any { it.id != targetCommand.aggregateId }) unidentified()
                else {
                    RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(account, matched,
                        verifiedUniqueCreation = true)
                    GatewayOutcome.Success(if (database.contactDao().getEntity(account.value,
                            targetCommand.aggregateId)?.pendingMutationRevision == null)
                        MutationPreparation.ResolvedWithoutUpload
                    else MutationPreparation.ActionRequired(MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT))
                }
                if (isCurrent) result = outcome
            }
            result
        }
    }

    /** One shared scan verifies each captured intent, without charging the claimed one twice. */
    private suspend fun recordVerificationFailure(command: DurableMutationCommand, before: Snapshot,
        failure: GatewayOutcome.Failure, isCurrent: Boolean, scanTime: Long): GatewayOutcome.Failure {
        if (!isCurrent) {
            checkLocalReconciliationInvariant(RoomOutboxStore(database).claim(before.intent, scanTime),
                LocalReconciliationInvariant.INTENT_REVISION)
            val decision = retryPolicy.decide(failure.category, command.operation,
                before.intent.attemptCount, scanTime, failure.retryAfterMillis)
            checkLocalReconciliationInvariant(RoomMutationExecutionStore(database).recordFailure(
                command, failure.category, decision), LocalReconciliationInvariant.INTENT_REVISION)
        }
        return failure
    }

    private suspend fun snapshot(command: DurableMutationCommand, allowPending: Boolean = false): Snapshot? {
        val contact = database.contactDao().get(account.value, command.aggregateId) ?: return null
        val intent = database.outboxDao().get(account.value, AggregateType.CONTACT.name, command.aggregateId) ?: return null
        if (contact.contact.revision != command.revision || contact.contact.pendingMutationRevision != command.revision ||
            intent.revision != command.revision || contact.contact.remoteContactId != intent.remoteIdentity ||
            (intent.remoteIdentity != null && (intent.remoteVersion != null || contact.contact.remoteVersion != null ||
                contact.contact.conflictState != RoomContactConflictStore.CONFLICT)) ||
            !intent.requiresReconciliation || intent.state !in (if (allowPending)
                setOf(DurableMutationState.PENDING.name) else setOf(DurableMutationState.IN_FLIGHT.name)) ||
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

    private fun recoveryReadFailure(failure: GatewayOutcome.Failure): GatewayOutcome.Failure = when (failure.category) {
        GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED, GatewayFailureCategory.MALFORMED_RESPONSE,
        GatewayFailureCategory.VALIDATION_REJECTED, GatewayFailureCategory.NOT_FOUND, GatewayFailureCategory.CONFLICT,
        GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED -> failure.copy(
            category = GatewayFailureCategory.RECONCILIATION_INCOMPLETE, recoveryBlockerCategory = failure.category)
        else -> failure
    }

    private companion object { const val MAX_RECOVERY_TARGETS = 25 }
}
