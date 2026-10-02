package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidLedgerCasResult
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.data.sync.RetryDecision

/** Durable compare-and-set lifecycle boundary used by the synchronization engine. */
internal class RoomOutboxStore(private val database: ContakoDatabase) {
    suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int): List<OutboxMutationEntity> {
        require(accountId.isNotBlank())
        require(limit in 1..MAX_BATCH_SIZE)
        return database.outboxDao().getEligible(accountId, nowEpochMillis, limit)
    }

    suspend fun claim(mutation: OutboxMutationEntity, nowEpochMillis: Long): Boolean =
        database.outboxDao().claim(
            mutation.accountId,
            mutation.aggregateType,
            mutation.aggregateId,
            mutation.revision,
            nowEpochMillis,
        ) == 1

    suspend fun applyRetryDecision(
        mutation: OutboxMutationEntity,
        category: GatewayFailureCategory,
        decision: RetryDecision,
    ): Boolean = when (decision) {
        is RetryDecision.RetryAt -> database.outboxDao().markRetry(
            mutation.accountId,
            mutation.aggregateType,
            mutation.aggregateId,
            mutation.revision,
            decision.attemptCount,
            decision.nextEligibleEpochMillis,
            category.name,
            decision.reconcileBeforeReplay,
        ) == 1
        RetryDecision.ActionRequired -> database.outboxDao().markActionRequired(
            mutation.accountId,
            mutation.aggregateType,
            mutation.aggregateId,
            mutation.revision,
            blockedReason = category.toBlockedReason(),
            errorCategory = category.name,
        ) == 1
        RetryDecision.Cancelled -> database.outboxDao().markRetry(
            mutation.accountId,
            mutation.aggregateType,
            mutation.aggregateId,
            mutation.revision,
            mutation.attemptCount,
            mutation.nextAttemptAtEpochMillis,
            GatewayFailureCategory.CANCELLED.name,
            mutation.requiresReconciliation,
        ) == 1
        RetryDecision.DeleteConverged -> acknowledge(mutation, mutation.remoteIdentity, mutation.remoteVersion)
    }

    suspend fun acknowledge(
        mutation: OutboxMutationEntity,
        remoteIdentity: String?,
        remoteVersion: String?,
    ): Boolean = database.outboxDao().acknowledge(
        mutation.accountId,
        mutation.aggregateType,
        mutation.aggregateId,
        mutation.revision,
        remoteIdentity,
        remoteVersion,
    ) == 1

    suspend fun finishAcknowledged(mutation: OutboxMutationEntity): Boolean = database.withTransaction {
        val current = database.outboxDao().get(mutation.accountId, mutation.aggregateType, mutation.aggregateId)
            ?: return@withTransaction true
        if (current.revision != mutation.revision || current.state != DurableMutationState.ACKNOWLEDGED.name) {
            return@withTransaction false
        }
        val canonicalReconciled = when (AggregateType.valueOf(current.aggregateType)) {
            AggregateType.CONTACT -> reconcileContact(current)
            AggregateType.GROUP -> reconcileGroup(current)
        }
        if (!canonicalReconciled) return@withTransaction false
        if (current.aggregateType == AggregateType.CONTACT.name) {
            database.contactConflictDao().delete(current.accountId, current.aggregateId)
        }
        database.outboxDao().deleteAcknowledged(
            current.accountId,
            current.aggregateType,
            current.aggregateId,
            current.revision,
        ) == 1
    }

    suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int =
        database.outboxDao().recoverInterrupted(accountId, nowEpochMillis)

    private suspend fun reconcileContact(mutation: OutboxMutationEntity): Boolean {
        val stored = database.contactDao().get(mutation.accountId, mutation.aggregateId) ?: return false
        if (stored.contact.revision != mutation.revision) return false
        if (stored.contact.remoteContactId == null && mutation.remoteIdentity != null) {
            val currentLedger = database.androidProjectionLedgerDao().get(
                mutation.accountId,
                mutation.aggregateId,
            )
            if (currentLedger == null && !stored.contact.isDeleted) {
                // A Contako-created contact has no native row yet. Register its verified
                // remote identity in this same acknowledgement transaction, before dequeue.
                RoomAndroidProjectionLedger(database).attachCanonicalContact(
                    AccountScope(mutation.accountId), mutation.aggregateId, mutation.remoteIdentity,
                )
            } else if (currentLedger != null) {
                when {
                    currentLedger.sourceIdentity == mutation.remoteIdentity -> Unit
                    currentLedger.sourceIdentity != null ||
                        currentLedger.adoptionState != AndroidAdoptionState.AWAITING_REMOTE_ID.name -> return false
                    RoomAndroidProjectionLedger(database).attachSourceIdentity(
                        AccountScope(mutation.accountId),
                        mutation.aggregateId,
                        currentLedger.revision,
                        mutation.remoteIdentity,
                    ) !is AndroidLedgerCasResult.Updated -> return false
                }
            }
        }
        database.contactDao().upsert(
            stored.contact.copy(
                remoteContactId = mutation.remoteIdentity,
                remoteVersion = mutation.remoteVersion,
                pendingMutationRevision = null,
                conflictState = null,
            ),
        )
        return true
    }

    private suspend fun reconcileGroup(mutation: OutboxMutationEntity): Boolean {
        val stored = database.contactGroupDao().get(mutation.accountId, mutation.aggregateId) ?: return false
        if (stored.group.revision != mutation.revision) return false
        database.contactGroupDao().upsert(
            stored.group.copy(
                remoteLabelId = mutation.remoteIdentity,
                remoteVersion = mutation.remoteVersion,
                pendingMutationRevision = null,
                conflictState = null,
            ),
        )
        return true
    }

    private fun GatewayFailureCategory.toBlockedReason(): String = when (this) {
        GatewayFailureCategory.AUTHENTICATION_REQUIRED -> "AUTHENTICATION_REQUIRED"
        GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED -> "INTERACTIVE_AUTHENTICATION_REQUIRED"
        GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED -> "GROUP_CAPABILITY_REQUIRED"
        GatewayFailureCategory.VALIDATION_REJECTED -> "VALIDATION_REJECTED"
        GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED -> "CRYPTOGRAPHIC_VERIFICATION_FAILED"
        GatewayFailureCategory.CONFLICT, GatewayFailureCategory.NOT_FOUND -> "CONFLICT_RECOVERY_REQUIRED"
        else -> "REMOTE_ACTION_REQUIRED"
    }

    companion object {
        const val MAX_BATCH_SIZE = 100
    }
}

internal fun OutboxMutationEntity.remoteOperation(): RemoteMutationOperation = when (MutationOperation.valueOf(operation)) {
    MutationOperation.DELETE -> RemoteMutationOperation.DELETE
    MutationOperation.ASSIGNMENTS -> RemoteMutationOperation.ASSIGNMENTS
    MutationOperation.UPSERT -> if (remoteIdentity == null) RemoteMutationOperation.CREATE else RemoteMutationOperation.UPDATE
}
