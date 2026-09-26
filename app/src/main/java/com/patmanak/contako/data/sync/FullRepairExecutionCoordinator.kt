package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope

internal interface FullRepairExecution {
    val progress: FullRepairProgress
    fun isCancellationRequested(): Boolean
    suspend fun checkpoint(phase: FullRepairPhase, completedUnits: Long, totalUnits: Long): Boolean
    suspend fun clearCancellation(): Boolean
    suspend fun clearAfterPublished(): Boolean
}

internal fun interface FullRepairExecutionCoordinator {
    suspend fun resume(account: AccountScope): FullRepairExecution?
}

internal object MissingFullRepairExecutionCoordinator : FullRepairExecutionCoordinator {
    override suspend fun resume(account: AccountScope): FullRepairExecution? = null
}

internal class RoomFullRepairExecutionCoordinator(
    private val account: AccountScope,
    private val store: RoomFullRepairProgressStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : FullRepairExecutionCoordinator {
    override suspend fun resume(account: AccountScope): FullRepairExecution? {
        require(account == this.account)
        val loaded = store.load(account) ?: return null
        return Session(loaded)
    }

    private inner class Session(initial: FullRepairProgress) : FullRepairExecution {
        override var progress = initial
            private set

        override fun isCancellationRequested(): Boolean =
            progress.cancellationRequested || store.isCancellationRequested(account)

        override suspend fun checkpoint(
            phase: FullRepairPhase,
            completedUnits: Long,
            totalUnits: Long,
        ): Boolean {
            if (!store.checkpoint(
                    account,
                    progress.revision,
                    phase,
                    completedUnits,
                    totalUnits,
                    clock(),
                )
            ) return false
            progress = requireNotNull(store.load(account))
            return true
        }

        override suspend fun clearCancellation(): Boolean {
            progress = store.load(account) ?: return true
            return store.clearAfterExplicitCancellation(account, progress.revision)
        }

        override suspend fun clearAfterPublished(): Boolean {
            progress = store.load(account) ?: return true
            return store.clearAfterSuccessfulPublish(account, progress.revision)
        }
    }
}
