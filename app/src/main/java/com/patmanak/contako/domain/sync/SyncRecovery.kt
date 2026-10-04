package com.patmanak.contako.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class SyncDashboardState {
    CURRENT,
    PENDING,
    BLOCKED,
    FAILED,
    OFFLINE,
    AUTHENTICATION_REQUIRED,
    ANDROID_DEGRADED,
    ANDROID_PARTIAL,
}

enum class SyncActivity { IDLE, SCHEDULED, RUNNING }

data class SyncDashboardSnapshot(
    val state: SyncDashboardState,
    val pendingMutationCount: Int = 0,
    val actionRequiredCount: Int = 0,
    val lastSuccessAtEpochMillis: Long? = null,
    val problem: SyncProblem? = null,
    /** Local UI locators only; never include this set in exported diagnostics. */
    val androidPendingContactIds: Set<String> = emptySet(),
    /** Local UI locators for blocked Proton writes; never exported or logged. */
    val blockedMutationContactIds: Set<String> = emptySet(),
) {
    override fun toString(): String = "SyncDashboardSnapshot(state=$state, pendingMutationCount=$pendingMutationCount, " +
        "actionRequiredCount=$actionRequiredCount, problem=$problem, androidPendingCount=${androidPendingContactIds.size}, " +
        "blockedMutationContactCount=${blockedMutationContactIds.size})"
}

/** Closed diagnostic categories only; never contact values, identifiers or exception text. */
enum class SyncProblem {
    AUTHENTICATION_REQUIRED, INTERACTIVE_AUTHENTICATION_REQUIRED, VALIDATION_REJECTED,
    CONFLICT_RECOVERY_REQUIRED, CRYPTOGRAPHIC_VERIFICATION_FAILED, GROUP_CAPABILITY_REQUIRED,
    REMOTE_PERMISSION_REQUIRED,
    ANDROID_INTEROPERABILITY_DEGRADED, ANDROID_CONTACTS_PARTIALLY_PROJECTED, INTERNAL_FAILURE,
}

enum class RepairPhase { REMOTE_ENUMERATION, CANONICAL_RECONCILIATION, ANDROID_PROJECTION, PUBLISHING }

data class RepairProgress(
    val phase: RepairPhase,
    val completedUnits: Long,
    val totalUnits: Long?,
    val cancellationRequested: Boolean,
)

enum class RepairStartResult { CONFIRMATION_REQUIRED, STARTED, RESUMED }

/** Presentation-neutral recovery port implemented by the synchronization data adapter. */
interface SyncRecoveryDataSource {
    fun observeConflicts(accountId: String): Flow<List<ContactConflictSummary>> = flowOf(emptyList())
    suspend fun loadConflict(accountId: String, contactId: String): ContactConflictDetail? = null
    suspend fun chooseConflict(accountId: String, expected: ContactConflictSummary, choice: ContactConflictChoice): Boolean = false
    fun observeStatus(accountId: String): Flow<SyncDashboardSnapshot?>
    fun observeRepair(accountId: String): Flow<RepairProgress?>
    fun observeActivity(accountId: String): Flow<SyncActivity>
    suspend fun beginRepair(accountId: String, mobileDataConfirmed: Boolean): RepairStartResult
    suspend fun cancelRepair(accountId: String): Boolean
    suspend fun requestSync(accountId: String)
}

object EmptySyncRecoveryDataSource : SyncRecoveryDataSource {
    override fun observeStatus(accountId: String): Flow<SyncDashboardSnapshot?> = flowOf(null)
    override fun observeRepair(accountId: String): Flow<RepairProgress?> = flowOf(null)
    override fun observeActivity(accountId: String): Flow<SyncActivity> = flowOf(SyncActivity.IDLE)
    override suspend fun beginRepair(accountId: String, mobileDataConfirmed: Boolean) = RepairStartResult.STARTED
    override suspend fun cancelRepair(accountId: String) = false
    override suspend fun requestSync(accountId: String) = Unit
}
