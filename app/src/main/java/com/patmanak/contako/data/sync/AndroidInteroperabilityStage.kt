package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope

internal sealed interface AndroidInteroperabilityStageResult {
    data object Success : AndroidInteroperabilityStageResult
    data object RetryWaiting : AndroidInteroperabilityStageResult
    data object ActionRequired : AndroidInteroperabilityStageResult
    data object Cancelled : AndroidInteroperabilityStageResult
    data object LocalPersistenceFailure : AndroidInteroperabilityStageResult
}

internal fun interface AndroidInteroperabilityRepairCoordinator {
    suspend fun repair(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult
}

/** Local Android boundary used by the one account-scoped synchronization runner. */
internal interface AndroidInteroperabilityStage {
    suspend fun ingest(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult

    suspend fun project(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult
}
