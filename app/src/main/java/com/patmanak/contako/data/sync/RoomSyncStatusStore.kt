package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.SyncAccountStatusEntity
import com.patmanak.contako.domain.sync.SyncPassOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SyncHealthState { IDLE, CHECKING, PENDING, OFFLINE, ACTION_REQUIRED, FAILED }

enum class SyncActionReason(val immediateNotification: Boolean) {
    AUTHENTICATION_REQUIRED(true),
    INTERACTIVE_AUTHENTICATION_REQUIRED(true),
    VALIDATION_REJECTED(false),
    CONFLICT_RECOVERY_REQUIRED(false),
    CRYPTOGRAPHIC_VERIFICATION_FAILED(false),
    GROUP_CAPABILITY_REQUIRED(false),

    /**
     * Android interoperability is degraded while local and Proton work continues under `D-062`.
     *
     * Previously this collapsed into [INTERNAL_FAILURE], which told the user that changes needed
     * attention even when the outbox was empty and nothing was actually pending.
     */
    ANDROID_INTEROPERABILITY_DEGRADED(false),

    /**
     * Some contacts could not be written to the Android contacts app and were skipped.
     *
     * Distinct from [ANDROID_INTEROPERABILITY_DEGRADED]: Android access itself works and most
     * contacts did project, so the user needs a count of what is missing rather than a blanket
     * "phone contacts unavailable".
     */
    ANDROID_CONTACTS_PARTIALLY_PROJECTED(false),
    INTERNAL_FAILURE(false),
}

enum class SyncNotificationReason { IMMEDIATE_ACTION_REQUIRED, BLOCKED_FOR_24_HOURS }

data class SyncStatusSnapshot(
    val state: SyncHealthState,
    val updatedAtEpochMillis: Long,
    val lastSuccessAtEpochMillis: Long?,
    val pendingMutationCount: Int,
    val actionRequiredCount: Int,
    val actionReason: SyncActionReason?,
    val blockedSinceEpochMillis: Long?,
    val notificationClaimed: Boolean,
)

data class SyncStatusUpdate(
    val outcome: SyncPassOutcome,
    val pendingMutationCount: Int,
    val actionRequiredCount: Int,
    val actionReason: SyncActionReason? = null,
    val offline: Boolean = false,
) {
    init {
        require(pendingMutationCount >= 0)
        require(actionRequiredCount >= 0)
        require((actionRequiredCount > 0) == (actionReason != null))
    }
}

fun interface SyncPassStatusPublisher {
    suspend fun publish(outcome: SyncPassOutcome)
}

/** Account-scoped durable status and atomic notification deduplication. */
internal class RoomSyncStatusStore(private val database: ContakoDatabase) {
    fun observe(accountId: String): Flow<SyncStatusSnapshot?> {
        require(accountId.isNotBlank())
        return database.syncAccountStatusDao().observe(accountId).map { it?.toSnapshot() }
    }

    suspend fun load(accountId: String): SyncStatusSnapshot? {
        require(accountId.isNotBlank())
        return database.syncAccountStatusDao().get(accountId)?.toSnapshot()
    }

    suspend fun publish(accountId: String, nowEpochMillis: Long, update: SyncStatusUpdate): SyncStatusSnapshot {
        require(accountId.isNotBlank())
        require(nowEpochMillis >= 0)
        return database.withTransaction {
            val previous = database.syncAccountStatusDao().get(accountId)
            val state = update.toHealthState()
            val sameBlock = state == SyncHealthState.ACTION_REQUIRED &&
                previous?.state == SyncHealthState.ACTION_REQUIRED.name &&
                previous.actionReason == update.actionReason?.name
            val blockedSince = when {
                state != SyncHealthState.ACTION_REQUIRED -> null
                sameBlock -> previous?.blockedSinceEpochMillis ?: nowEpochMillis
                else -> nowEpochMillis
            }
            val entity = SyncAccountStatusEntity(
                accountId = accountId,
                state = state.name,
                updatedAtEpochMillis = nowEpochMillis,
                lastSuccessAtEpochMillis = if (update.outcome == SyncPassOutcome.SUCCESS) {
                    nowEpochMillis
                } else {
                    previous?.lastSuccessAtEpochMillis
                },
                pendingMutationCount = update.pendingMutationCount,
                actionRequiredCount = update.actionRequiredCount,
                actionReason = update.actionReason?.name,
                blockedSinceEpochMillis = blockedSince,
                notificationClaimedForBlockEpochMillis = if (sameBlock) {
                    previous?.notificationClaimedForBlockEpochMillis
                } else {
                    null
                },
            )
            database.syncAccountStatusDao().upsert(entity)
            entity.toSnapshot()
        }
    }

    suspend fun claimNotificationIfDue(
        accountId: String,
        nowEpochMillis: Long,
    ): SyncNotificationReason? = database.withTransaction {
        require(accountId.isNotBlank())
        require(nowEpochMillis >= 0)
        val status = database.syncAccountStatusDao().get(accountId) ?: return@withTransaction null
        if (status.state != SyncHealthState.ACTION_REQUIRED.name) return@withTransaction null
        val blockedSince = status.blockedSinceEpochMillis ?: return@withTransaction null
        if (status.notificationClaimedForBlockEpochMillis != null) return@withTransaction null
        val reason = status.actionReason?.let(SyncActionReason::valueOf) ?: return@withTransaction null
        val due = reason.immediateNotification || elapsedAtLeast(blockedSince, nowEpochMillis, BLOCK_NOTIFICATION_DELAY)
        if (!due) return@withTransaction null
        if (database.syncAccountStatusDao().claimNotification(accountId, blockedSince) != 1) {
            return@withTransaction null
        }
        if (reason.immediateNotification) {
            SyncNotificationReason.IMMEDIATE_ACTION_REQUIRED
        } else {
            SyncNotificationReason.BLOCKED_FOR_24_HOURS
        }
    }

    suspend fun clear(accountId: String): Boolean = database.syncAccountStatusDao().delete(accountId) == 1

    private fun SyncStatusUpdate.toHealthState(): SyncHealthState = when {
        actionRequiredCount > 0 -> SyncHealthState.ACTION_REQUIRED
        outcome == SyncPassOutcome.SUCCESS && pendingMutationCount == 0 -> SyncHealthState.IDLE
        outcome == SyncPassOutcome.RETRY_WAITING && offline -> SyncHealthState.OFFLINE
        outcome == SyncPassOutcome.FAILED -> SyncHealthState.FAILED
        else -> SyncHealthState.PENDING
    }

    private fun SyncAccountStatusEntity.toSnapshot() = SyncStatusSnapshot(
        state = SyncHealthState.valueOf(state),
        updatedAtEpochMillis = updatedAtEpochMillis,
        lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
        pendingMutationCount = pendingMutationCount,
        actionRequiredCount = actionRequiredCount,
        actionReason = actionReason?.let(SyncActionReason::valueOf),
        blockedSinceEpochMillis = blockedSinceEpochMillis,
        notificationClaimed = notificationClaimedForBlockEpochMillis != null,
    )

    private fun elapsedAtLeast(start: Long, now: Long, duration: Long): Boolean =
        now >= start && now - start >= duration

    private companion object {
        const val BLOCK_NOTIFICATION_DELAY = 24 * 60 * 60 * 1_000L
    }
}

/** Production publisher derives counts/reasons from the durable outbox, never from UI memory. */
internal class RoomSyncPassStatusPublisher(
    private val accountId: String,
    private val database: ContakoDatabase,
    private val statusStore: RoomSyncStatusStore = RoomSyncStatusStore(database),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val isOffline: suspend () -> Boolean = { false },
    private val externalActionReason: suspend () -> SyncActionReason? = { null },
) : SyncPassStatusPublisher {
    override suspend fun publish(outcome: SyncPassOutcome) {
        val all = database.outboxDao().getAll(accountId)
        val blocked = database.outboxDao().getActionRequired(accountId)
        val reason = blocked.toActionReason() ?: if (outcome == SyncPassOutcome.ACTION_REQUIRED) {
            externalActionReason() ?: SyncActionReason.INTERNAL_FAILURE
        } else {
            null
        }
        statusStore.publish(
            accountId,
            wallClock(),
            SyncStatusUpdate(
                outcome = outcome,
                pendingMutationCount = all.size,
                actionRequiredCount = if (reason == null) 0 else maxOf(1, blocked.size),
                actionReason = reason,
                offline = outcome == SyncPassOutcome.RETRY_WAITING && isOffline(),
            ),
        )
    }

    private fun List<com.patmanak.contako.data.local.OutboxMutationEntity>.toActionReason(): SyncActionReason? {
        if (isEmpty()) return null
        val reasons = mapNotNull { it.blockedReason }.toSet()
        return when {
            "AUTHENTICATION_REQUIRED" in reasons -> SyncActionReason.AUTHENTICATION_REQUIRED
            "INTERACTIVE_AUTHENTICATION_REQUIRED" in reasons ->
                SyncActionReason.INTERACTIVE_AUTHENTICATION_REQUIRED
            "CRYPTOGRAPHIC_VERIFICATION_FAILED" in reasons ->
                SyncActionReason.CRYPTOGRAPHIC_VERIFICATION_FAILED
            "VALIDATION_REJECTED" in reasons || reasons.any { it.contains("INVALID") || it.contains("MISSING") } ->
                SyncActionReason.VALIDATION_REJECTED
            "GROUP_CAPABILITY_REQUIRED" in reasons -> SyncActionReason.GROUP_CAPABILITY_REQUIRED
            reasons.any { it.contains("CONFLICT") || it.contains("RECOVERY_REQUIRED") } ->
                SyncActionReason.CONFLICT_RECOVERY_REQUIRED
            else -> SyncActionReason.INTERNAL_FAILURE
        }
    }
}
