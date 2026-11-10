package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.RemoteGroupId

internal fun interface RemoteGroupReconciliationStore {
    suspend fun knownRemoteIds(account: AccountScope): Set<RemoteGroupId> = emptySet()
    suspend fun commitDelta(account: AccountScope, upserts: AvailableContactGroups, deletedIds: Set<RemoteGroupId>) {
        error("Targeted group reconciliation unavailable")
    }
    suspend fun commit(account: AccountScope, groups: AvailableContactGroups): Unit
}

internal sealed interface RemoteGroupStageResult {
    data class Success(val groupCount: Int) : RemoteGroupStageResult
    data class RetryWaiting(val category: GatewayFailureCategory) : RemoteGroupStageResult
    data object Unavailable : RemoteGroupStageResult
    data object ActionRequired : RemoteGroupStageResult
    data object Cancelled : RemoteGroupStageResult
    data object LocalPersistenceFailure : RemoteGroupStageResult
}

internal class IncrementalRemoteGroupStage(
    private val gateway: ProtonContactGroupGateway,
    private val store: RemoteGroupReconciliationStore,
    private val failureObserver: (GatewayFailureCategory) -> Unit = {},
    private val exceptionObserver: (SyncPassExceptionCategory) -> Unit = {},
    private val gatewayFailureObserver: GatewayFailureObserver = GatewayFailureObserver { _, _, _ -> },
    private val eventSession: EventDrivenRemoteReadSession? = null,
) {
    var lastFailureCategory: GatewayFailureCategory? = null
        private set
    var lastExceptionCategory: SyncPassExceptionCategory? = null
        private set
    suspend fun run(account: AccountScope, isCancellationRequested: () -> Boolean): RemoteGroupStageResult =
        run(account, isCancellationRequested, forceFullBaseline = false)

    suspend fun run(
        account: AccountScope,
        isCancellationRequested: () -> Boolean = { false },
        forceFullBaseline: Boolean = false,
    ): RemoteGroupStageResult {
        lastFailureCategory = null
        lastExceptionCategory = null
        if (isCancellationRequested()) return RemoteGroupStageResult.Cancelled
        if (forceFullBaseline) eventSession?.requireFullBaseline()
        val context = when (val result = eventSession?.prepare(account)) {
            null -> null
            is GatewayOutcome.Success -> result.value
            is GatewayOutcome.Failure -> return failed(result)
        }
        if (context != null && !context.fullInventory) {
            val events = context.events
            val known = store.knownRemoteIds(account)
            val changed = when (val result = requireNotNull(eventSession).resources.groups(account, events.changedGroups)) {
                is GatewayOutcome.Success -> result.value
                is GatewayOutcome.Failure -> {
                    if (result.category !in setOf(GatewayFailureCategory.NOT_FOUND, GatewayFailureCategory.MALFORMED_RESPONSE))
                        return failed(result)
                    // A label may disappear after its update notification. Only a validated
                    // complete catalog may recover that race; a partial result is never empty.
                    context.fullInventory = true
                    null
                }
            }
            if (changed != null) {
                if (isCancellationRequested()) return RemoteGroupStageResult.Cancelled
                return try {
                    store.commitDelta(account, changed, events.deletedGroups)
                    // An unknown new group can already contain members. Until its membership
                    // notifications are independently known, acquire the contact baseline once.
                    if ((events.changedGroups - known).isNotEmpty()) context.fullInventory = true
                    context.groupsDurable = true
                    RemoteGroupStageResult.Success((known + events.changedGroups - events.deletedGroups).size)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { persistenceFailure(error) }
            }
        }
        val groups = when (val result = gateway.list(account)) {
            is GatewayOutcome.Success -> result.value
            is GatewayOutcome.Failure -> {
                return failed(result)
            }
        }
        if (isCancellationRequested()) return RemoteGroupStageResult.Cancelled
        return try {
            store.commit(account, groups)
            context?.groupsDurable = true
            context?.fullGroupCatalogDurable = true
            RemoteGroupStageResult.Success(groups.groups.size)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            lastExceptionCategory = syncPassExceptionCategory(error)
            runCatching { exceptionObserver(syncPassExceptionCategory(error)) }
            RemoteGroupStageResult.LocalPersistenceFailure
        }
    }

    private fun failed(result: GatewayOutcome.Failure): RemoteGroupStageResult {
        lastFailureCategory = result.category
        runCatching { failureObserver(result.category) }
        runCatching { gatewayFailureObserver.onFailure(GatewayDiagnosticBoundary.GROUP_READ, result, null) }
        return result.category.toGroupStageResult()
    }

    private fun persistenceFailure(error: Exception): RemoteGroupStageResult {
        lastExceptionCategory = syncPassExceptionCategory(error)
        runCatching { exceptionObserver(syncPassExceptionCategory(error)) }
        return RemoteGroupStageResult.LocalPersistenceFailure
    }
}

private fun GatewayFailureCategory.toGroupStageResult(): RemoteGroupStageResult = when (this) {
    GatewayFailureCategory.CANCELLED -> RemoteGroupStageResult.Cancelled
    GatewayFailureCategory.NETWORK_UNAVAILABLE,
    GatewayFailureCategory.TIMEOUT,
    GatewayFailureCategory.RATE_LIMITED,
    GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
    GatewayFailureCategory.UNKNOWN,
    -> RemoteGroupStageResult.RetryWaiting(this)
    GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED -> RemoteGroupStageResult.Unavailable
    else -> RemoteGroupStageResult.ActionRequired
}
