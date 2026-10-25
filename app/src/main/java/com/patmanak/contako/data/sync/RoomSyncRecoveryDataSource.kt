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
import com.patmanak.contako.domain.sync.SyncAndroidContactIssue
import com.patmanak.contako.domain.sync.SyncAndroidContactIssueKind
import com.patmanak.contako.domain.sync.SyncBlockedItem
import com.patmanak.contako.domain.sync.SyncBlockedItemType
import com.patmanak.contako.domain.sync.InitialImportSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

internal class RoomSyncRecoveryDataSource(
    private val database: ContakoDatabase,
    private val runner: AccountSyncRunner,
    private val networkStateProvider: NetworkStateProvider,
    private val expectedAccount: AccountScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncRecoveryDataSource {
    private val statusStore = RoomSyncStatusStore(database)
    private val repairStore = RoomFullRepairProgressStore(database)
    private val projectionDao = database.androidProjectionLedgerDao()
    private val outboxDao = database.outboxDao()
    private val hydrationDao = database.remoteHydrationObligationDao()
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
            hydrationDao.observe(boundAccount(accountId).value),
            database.androidContactIssueDao().observeCurrentWithContacts(boundAccount(accountId).value),
        ) { status, pendingIds, blockedIds, importFailures, androidFailures ->
            val snapshot = status?.toDomain() ?: SyncDashboardSnapshot(SyncDashboardState.CURRENT)
            val issues = androidFailures.map { failure -> SyncAndroidContactIssue(
                failure.issue.canonicalContactId,
                if (failure.issue.origin == AndroidContactIssueOrigin.INGEST.name)
                    SyncAndroidContactIssueKind.ANDROID_INGEST else SyncAndroidContactIssueKind.ANDROID_PROJECTION,
                failure.displayName, failure.isDeleted,
            ) }
            val projectionFailures = issues.filter { it.kind == SyncAndroidContactIssueKind.ANDROID_PROJECTION }
                .map { it.contactId }
            val localProblem = when {
                projectionFailures.isNotEmpty() -> SyncProblem.ANDROID_CONTACTS_PARTIALLY_PROJECTED
                issues.isNotEmpty() -> SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED
                else -> null
            }
            snapshot.copy(
                // A completed older pass cannot qualify a newly outstanding native copy.
                state = if (importFailures.isNotEmpty() && snapshot.state != SyncDashboardState.AUTHENTICATION_REQUIRED)
                    SyncDashboardState.BLOCKED
                    else if (issues.isNotEmpty() && snapshot.state in setOf(SyncDashboardState.CURRENT, SyncDashboardState.PENDING))
                        if (projectionFailures.isEmpty()) SyncDashboardState.ANDROID_DEGRADED else SyncDashboardState.ANDROID_PARTIAL
                    else if (snapshot.state == SyncDashboardState.CURRENT && pendingIds.isNotEmpty())
                    SyncDashboardState.PENDING else snapshot.state,
                problem = snapshot.problem ?: localProblem,
                actionRequiredCount = maxOf(snapshot.actionRequiredCount,
                    (blockedIds + issues.map { it.contactId }).toSet().size + importFailures.size),
                importProblems = importFailures.map {
                    com.patmanak.contako.domain.sync.SyncImportProblem(it.remoteContactId, it.displayName,
                        when (it.category) {
                            "CRYPTOGRAPHIC_VERIFICATION_FAILED" -> SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED
                            "VALIDATION_REJECTED" -> SyncProblem.VALIDATION_REJECTED
                            else -> SyncProblem.INTERNAL_FAILURE
                        })
                },
                androidPendingContactIds = (pendingIds + projectionFailures).toSet(),
                blockedMutationContactIds = blockedIds.toSet(), androidContactIssues = issues,
            )
        }.combine(outboxDao.observeBlockedItems(boundAccount(accountId).value)) { snapshot, blocked ->
            val items = blocked.map { item ->
                SyncBlockedItem(item.aggregateId,
                    if (item.aggregateType == "GROUP") SyncBlockedItemType.GROUP else SyncBlockedItemType.CONTACT,
                    item.displayName, item.isDeleted,
                    when (item.blockedReason) {
                        "AUTHENTICATION_REQUIRED" -> SyncProblem.AUTHENTICATION_REQUIRED
                        "INTERACTIVE_AUTHENTICATION_REQUIRED" -> SyncProblem.INTERACTIVE_AUTHENTICATION_REQUIRED
                        "CRYPTOGRAPHIC_VERIFICATION_FAILED" -> SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED
                        "VALIDATION_REJECTED" -> SyncProblem.VALIDATION_REJECTED
                        "GROUP_CAPABILITY_REQUIRED" -> if (item.aggregateType == "CONTACT" &&
                            item.errorCategory == "PERMISSION_OR_PLAN_DENIED") SyncProblem.REMOTE_PERMISSION_REQUIRED
                            else SyncProblem.GROUP_CAPABILITY_REQUIRED
                        "REMOTE_PERMISSION_REQUIRED" -> SyncProblem.REMOTE_PERMISSION_REQUIRED
                        else -> when {
                            item.blockedReason?.contains("INVALID") == true ||
                                item.blockedReason?.contains("MISSING") == true -> SyncProblem.VALIDATION_REJECTED
                            item.blockedReason?.contains("CONFLICT") == true ||
                                item.blockedReason?.contains("RECOVERY_REQUIRED") == true -> SyncProblem.CONFLICT_RECOVERY_REQUIRED
                            else -> SyncProblem.INTERNAL_FAILURE
                        }
                    })
            }
            val priority = listOf(SyncProblem.AUTHENTICATION_REQUIRED, SyncProblem.INTERACTIVE_AUTHENTICATION_REQUIRED,
                SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED, SyncProblem.VALIDATION_REJECTED,
                SyncProblem.GROUP_CAPABILITY_REQUIRED, SyncProblem.REMOTE_PERMISSION_REQUIRED,
                SyncProblem.CONFLICT_RECOVERY_REQUIRED, SyncProblem.INTERNAL_FAILURE)
            val blockedProblem = priority.firstOrNull { problem -> items.any { it.problem == problem } }
            val authentication = snapshot.state == SyncDashboardState.AUTHENTICATION_REQUIRED || blockedProblem in
                setOf(SyncProblem.AUTHENTICATION_REQUIRED, SyncProblem.INTERACTIVE_AUTHENTICATION_REQUIRED)
            val identities = items.map { it.type to it.id } + snapshot.androidContactIssues.map { SyncBlockedItemType.CONTACT to it.contactId }
            snapshot.copy(blockedItems = items,
                state = when {
                    authentication -> SyncDashboardState.AUTHENTICATION_REQUIRED
                    items.isNotEmpty() -> SyncDashboardState.BLOCKED
                    else -> snapshot.state
                },
                problem = if (snapshot.state == SyncDashboardState.AUTHENTICATION_REQUIRED) snapshot.problem
                    else blockedProblem ?: snapshot.problem,
                actionRequiredCount = maxOf(snapshot.actionRequiredCount,
                    identities.toSet().size + snapshot.importProblems.size))
        }.combine(database.contactInventoryCheckpointDao().observeInitialImportProgress(boundAccount(accountId).value)) { snapshot, counts ->
            val progress = InitialImportSnapshot(counts.inventoryEstablished, counts.localContactCount,
                counts.inventoryContactCount, counts.deferredContactCount, counts.androidPendingContactCount)
            snapshot.copy(initialImport = progress,
                state = if (snapshot.state == SyncDashboardState.CURRENT && !progress.remoteComplete)
                    SyncDashboardState.PENDING else snapshot.state)
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
