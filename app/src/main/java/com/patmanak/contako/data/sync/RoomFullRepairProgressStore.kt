package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.FullRepairProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal enum class FullRepairPhase {
    REMOTE_ENUMERATION,
    CANONICAL_RECONCILIATION,
    ANDROID_PROJECTION,
    PUBLISHING,
}

internal data class FullRepairProgress(
    val revision: Long,
    val phase: FullRepairPhase,
    val completedUnits: Long,
    val totalUnits: Long?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val nonWifiConfirmed: Boolean,
    val cancellationRequested: Boolean,
) {
    init {
        require(revision >= 0)
        require(completedUnits >= 0)
        require(totalUnits == null || totalUnits >= completedUnits)
        require(startedAtEpochMillis >= 0)
        require(updatedAtEpochMillis >= startedAtEpochMillis)
    }

    override fun toString(): String =
        "FullRepairProgress(REDACTED, revision=$revision, phase=$phase, " +
            "completedUnits=$completedUnits, totalUnits=$totalUnits, " +
            "cancellationRequested=$cancellationRequested)"
}

internal sealed interface FullRepairBeginResult {
    data object ConfirmationRequired : FullRepairBeginResult
    data class Started(val progress: FullRepairProgress) : FullRepairBeginResult
    data class Resumed(val progress: FullRepairProgress) : FullRepairBeginResult
}

/**
 * Durable full-repair scope. The row is intentionally deleted only after a complete publish or an
 * explicit cancellation. A process restart therefore resumes at the last committed safe boundary.
 */
internal class RoomFullRepairProgressStore(
    private val database: ContakoDatabase,
) {
    private val dao = database.fullRepairProgressDao()
    private val cancellationSignals = ConcurrentHashMap<String, AtomicBoolean>()

    suspend fun beginOrResume(
        account: AccountScope,
        nonWifiConfirmationRequired: Boolean,
        confirmationGranted: Boolean,
        nowEpochMillis: Long,
    ): FullRepairBeginResult = database.withTransaction {
        dao.get(account.value)?.let {
            cancellationSignal(account).set(it.cancellationRequested)
            return@withTransaction FullRepairBeginResult.Resumed(it.toDomain())
        }
        if (nonWifiConfirmationRequired && !confirmationGranted) {
            return@withTransaction FullRepairBeginResult.ConfirmationRequired
        }
        val initial = FullRepairProgressEntity(
            accountId = account.value,
            revision = 0,
            phase = FullRepairPhase.REMOTE_ENUMERATION.name,
            phaseRank = FullRepairPhase.REMOTE_ENUMERATION.ordinal,
            completedUnits = 0,
            totalUnits = null,
            startedAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            nonWifiConfirmed = nonWifiConfirmationRequired && confirmationGranted,
            cancellationRequested = false,
        )
        check(dao.insertIfAbsent(initial) != -1L)
        cancellationSignal(account).set(false)
        FullRepairBeginResult.Started(initial.toDomain())
    }

    suspend fun load(account: AccountScope): FullRepairProgress? = dao.get(account.value)?.toDomain()?.also {
        cancellationSignal(account).set(it.cancellationRequested)
    }

    fun observe(account: AccountScope): Flow<FullRepairProgress?> =
        dao.observe(account.value).map { it?.toDomain() }

    suspend fun checkpoint(
        account: AccountScope,
        expectedRevision: Long,
        phase: FullRepairPhase,
        completedUnits: Long,
        totalUnits: Long?,
        nowEpochMillis: Long,
    ): Boolean = database.withTransaction {
        require(completedUnits >= 0)
        require(totalUnits == null || totalUnits >= completedUnits)
        val current = dao.get(account.value) ?: return@withTransaction false
        if (current.revision != expectedRevision || current.cancellationRequested) return@withTransaction false
        require(phase.ordinal >= current.phaseRank) { "Full repair phase cannot move backwards" }
        if (phase.ordinal == current.phaseRank) {
            require(completedUnits >= current.completedUnits) { "Full repair progress cannot move backwards" }
        }
        if (phase.ordinal > current.phaseRank) {
            require(completedUnits == 0L) { "A new full repair phase must start at zero" }
        }
        require(nowEpochMillis >= current.updatedAtEpochMillis) { "Full repair time cannot move backwards" }
        dao.checkpoint(
            account.value,
            expectedRevision,
            phase.name,
            phase.ordinal,
            completedUnits,
            totalUnits,
            nowEpochMillis,
        ) == 1
    }

    suspend fun requestCancellation(account: AccountScope, nowEpochMillis: Long): Boolean =
        database.withTransaction {
            val current = dao.get(account.value) ?: return@withTransaction false
            if (current.cancellationRequested) return@withTransaction false
            require(nowEpochMillis >= current.updatedAtEpochMillis) { "Full repair time cannot move backwards" }
            (dao.requestCancellation(account.value, current.revision, nowEpochMillis) == 1).also { changed ->
                if (changed) cancellationSignal(account).set(true)
            }
        }

    suspend fun clearAfterExplicitCancellation(account: AccountScope, expectedRevision: Long): Boolean =
        (dao.clearCancelled(account.value, expectedRevision) == 1).also { cleared ->
            if (cleared) cancellationSignals.remove(account.value)
        }

    suspend fun clearAfterSuccessfulPublish(account: AccountScope, expectedRevision: Long): Boolean =
        (dao.clearPublished(account.value, expectedRevision) == 1).also { cleared ->
            if (cleared) cancellationSignals.remove(account.value)
        }

    internal fun isCancellationRequested(account: AccountScope): Boolean =
        cancellationSignal(account).get()

    private fun cancellationSignal(account: AccountScope): AtomicBoolean =
        cancellationSignals.getOrPut(account.value) { AtomicBoolean(false) }

    private fun FullRepairProgressEntity.toDomain(): FullRepairProgress = FullRepairProgress(
        revision = revision,
        phase = FullRepairPhase.valueOf(phase),
        completedUnits = completedUnits,
        totalUnits = totalUnits,
        startedAtEpochMillis = startedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        nonWifiConfirmed = nonWifiConfirmed,
        cancellationRequested = cancellationRequested,
    )
}
