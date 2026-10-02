package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.sync.SyncRecoveryDataSource
import com.patmanak.contako.domain.sync.RepairPhase
import com.patmanak.contako.domain.sync.RepairProgress
import com.patmanak.contako.domain.sync.RepairStartResult
import com.patmanak.contako.domain.sync.SyncActivity
import com.patmanak.contako.domain.sync.SyncDashboardSnapshot
import com.patmanak.contako.domain.sync.SyncDashboardState
import com.patmanak.contako.domain.sync.SyncProblem
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncRunState
import com.patmanak.contako.domain.sync.SyncScope
import com.patmanak.contako.domain.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

internal class RoomSyncRecoveryDataSource(
    database: ContakoDatabase,
    private val runner: AccountSyncRunner,
    private val networkStateProvider: NetworkStateProvider,
    private val expectedAccount: AccountScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncRecoveryDataSource {
    private val statusStore = RoomSyncStatusStore(database)
    private val repairStore = RoomFullRepairProgressStore(database)
    private val projectionDao = database.androidProjectionLedgerDao()
    private val outboxDao = database.outboxDao()
    private val conflicts = com.patmanak.contako.data.local.RoomContactConflictStore(database)

    override fun observeConflicts(accountId: String) = conflicts.observe(boundAccount(accountId).value)
    override suspend fun loadConflict(accountId: String, contactId: String) = conflicts.detail(boundAccount(accountId).value, contactId)
    override suspend fun chooseConflict(accountId: String,
        expected: com.patmanak.contako.domain.sync.ContactConflictSummary,
        choice: com.patmanak.contako.domain.sync.ContactConflictChoice): Boolean {
        val queued = conflicts.choose(boundAccount(accountId).value, expected, choice)
        if (queued) runner.request(SyncTrigger.MUTATION_COMMITTED, SyncScope.INCREMENTAL)
        return queued
    }

    override fun observeStatus(accountId: String): Flow<SyncDashboardSnapshot?> =
        combine(
            statusStore.observe(boundAccount(accountId).value),
            projectionDao.observePendingContactIds(boundAccount(accountId).value),
            outboxDao.observeBlockedContactIds(boundAccount(accountId).value),
        ) { status, pendingIds, blockedIds ->
            val snapshot = status?.toDomain() ?: SyncDashboardSnapshot(SyncDashboardState.CURRENT)
            snapshot.copy(
                // A completed older pass cannot qualify a newly outstanding native copy.
                state = if (snapshot.state == SyncDashboardState.CURRENT && pendingIds.isNotEmpty())
                    SyncDashboardState.PENDING else snapshot.state,
                androidPendingContactIds = pendingIds.toSet(), blockedMutationContactIds = blockedIds.toSet(),
            )
        }

    override fun observeRepair(accountId: String): Flow<RepairProgress?> =
        repairStore.observe(boundAccount(accountId)).map { it?.toDomain() }

    override fun observeActivity(accountId: String): Flow<SyncActivity> {
        boundAccount(accountId)
        return runner.state.map { state ->
            when (state) {
                is SyncRunState.Scheduled -> SyncActivity.SCHEDULED
                is SyncRunState.Running -> SyncActivity.RUNNING
                SyncRunState.Idle, is SyncRunState.Completed -> SyncActivity.IDLE
            }
        }
    }

    override suspend fun beginRepair(
        accountId: String,
        mobileDataConfirmed: Boolean,
    ): RepairStartResult {
        val account = boundAccount(accountId)
        val result = repairStore.beginOrResume(
            account = account,
            nonWifiConfirmationRequired = networkStateProvider.current() != NetworkState.WIFI,
            confirmationGranted = mobileDataConfirmed,
            nowEpochMillis = clock(),
        )
        if (result != FullRepairBeginResult.ConfirmationRequired) {
            runner.request(SyncTrigger.MANUAL, SyncScope.FULL_REPAIR)
        }
        return result.toDomain()
    }

    override suspend fun cancelRepair(accountId: String): Boolean {
        val cancelled = repairStore.requestCancellation(boundAccount(accountId), clock())
        if (cancelled) runner.cancelFullRepair()
        return cancelled
    }

    override suspend fun requestSync(accountId: String) {
        boundAccount(accountId)
        runner.request(SyncTrigger.MANUAL, SyncScope.INCREMENTAL)
    }

    private fun boundAccount(accountId: String): AccountScope {
        // The current UI exposes a local alias; production execution always uses the authenticated scope.
        require(accountId.isNotBlank())
        return expectedAccount
    }
}

private fun SyncStatusSnapshot.toDomain() = SyncDashboardSnapshot(
    state = when {
        actionReason == SyncActionReason.AUTHENTICATION_REQUIRED ||
            actionReason == SyncActionReason.INTERACTIVE_AUTHENTICATION_REQUIRED ->
            SyncDashboardState.AUTHENTICATION_REQUIRED
        actionReason == SyncActionReason.ANDROID_INTEROPERABILITY_DEGRADED ->
            SyncDashboardState.ANDROID_DEGRADED
        actionReason == SyncActionReason.ANDROID_CONTACTS_PARTIALLY_PROJECTED ->
            SyncDashboardState.ANDROID_PARTIAL
        state == SyncHealthState.ACTION_REQUIRED -> SyncDashboardState.BLOCKED
        state == SyncHealthState.FAILED -> SyncDashboardState.FAILED
        state == SyncHealthState.OFFLINE -> SyncDashboardState.OFFLINE
        state == SyncHealthState.PENDING || state == SyncHealthState.CHECKING -> SyncDashboardState.PENDING
        else -> SyncDashboardState.CURRENT
    },
    pendingMutationCount = pendingMutationCount,
    actionRequiredCount = actionRequiredCount,
    lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
    problem = actionReason?.let { SyncProblem.valueOf(it.name) },
)

private fun FullRepairProgress.toDomain() = RepairProgress(
    phase = RepairPhase.valueOf(phase.name),
    completedUnits = completedUnits,
    totalUnits = totalUnits,
    cancellationRequested = cancellationRequested,
)

private fun FullRepairBeginResult.toDomain() = when (this) {
    FullRepairBeginResult.ConfirmationRequired -> RepairStartResult.CONFIRMATION_REQUIRED
    is FullRepairBeginResult.Started -> RepairStartResult.STARTED
    is FullRepairBeginResult.Resumed -> RepairStartResult.RESUMED
}
