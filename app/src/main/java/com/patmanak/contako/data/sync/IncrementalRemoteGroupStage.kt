package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway

internal fun interface RemoteGroupReconciliationStore {
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
) {
    suspend fun run(
        account: AccountScope,
        isCancellationRequested: () -> Boolean = { false },
    ): RemoteGroupStageResult {
        if (isCancellationRequested()) return RemoteGroupStageResult.Cancelled
        val groups = when (val result = gateway.list(account)) {
            is GatewayOutcome.Success -> result.value
            is GatewayOutcome.Failure -> return result.category.toGroupStageResult()
        }
        if (isCancellationRequested()) return RemoteGroupStageResult.Cancelled
        return try {
            store.commit(account, groups)
            RemoteGroupStageResult.Success(groups.groups.size)
        } catch (_: Throwable) {
            RemoteGroupStageResult.LocalPersistenceFailure
        }
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
