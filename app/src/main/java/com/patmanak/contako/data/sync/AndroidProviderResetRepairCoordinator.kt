package com.patmanak.contako.data.sync

import androidx.room.withTransaction
import com.patmanak.contako.data.android.AndroidProviderEpochResult
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.provider.AndroidProviderEpochProofWriteResult
import com.patmanak.contako.data.android.provider.AndroidProviderEpochProofWriter
import com.patmanak.contako.data.local.ContakoDatabase

internal class AndroidProviderResetRepairCoordinator(
    private val database: ContakoDatabase,
    private val projection: AndroidBoundedProjectionCoordinator,
    private val proofWriter: AndroidProviderEpochProofWriter,
) : AndroidInteroperabilityRepairCoordinator {
    override suspend fun repair(
        context: AndroidInteroperabilityContext,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityStageResult {
        if (isCancelled()) return AndroidInteroperabilityStageResult.Cancelled
        var account = database.androidProjectionLedgerDao().getAccount(context.account.value)
            ?: return AndroidInteroperabilityStageResult.ActionRequired
        if (account.androidAccountName != context.androidAccountName) {
            return AndroidInteroperabilityStageResult.ActionRequired
        }
        if (!account.providerRepairPending) {
            when (RoomAndroidProjectionLedger(database).advanceProviderEpoch(
                context.account,
                account.revision,
            )) {
                is AndroidProviderEpochResult.Advanced -> Unit
                AndroidProviderEpochResult.Stale -> return AndroidInteroperabilityStageResult.RetryWaiting
            }
            account = database.androidProjectionLedgerDao().getAccount(context.account.value)
                ?: return AndroidInteroperabilityStageResult.ActionRequired
        }
        val repairContext = database.withTransaction {
            val dao = database.androidProjectionLedgerDao()
            val current = dao.getAccount(context.account.value)
                ?: return@withTransaction null
            if (!current.providerRepairPending) return@withTransaction null
            AndroidInteroperabilityContext(
                context.account,
                requireNotNull(current.androidAccountName),
                current.revision,
                current.providerEpoch,
            )
        } ?: return AndroidInteroperabilityStageResult.ActionRequired

        var after: String? = null
        do {
            if (isCancelled()) return AndroidInteroperabilityStageResult.Cancelled
            val (page, result) = projection.projectPage(repairContext, after)
            when (result) {
                // Reconstruction continues past skipped contacts: stopping would leave the rest of
                // the projection unrepaired because of one unprojectable entry.
                AndroidBoundedPageResult.Applied,
                AndroidBoundedPageResult.PartiallyApplied,
                -> Unit
                AndroidBoundedPageResult.ReplanRequired -> return AndroidInteroperabilityStageResult.RetryWaiting
                AndroidBoundedPageResult.RepairRequired -> return AndroidInteroperabilityStageResult.ActionRequired
                AndroidBoundedPageResult.LocalPersistenceFailure ->
                    return AndroidInteroperabilityStageResult.LocalPersistenceFailure
            }
            after = page.nextKey
        } while (after != null)

        when (proofWriter.write(repairContext.androidAccountName, repairContext.providerEpoch)) {
            AndroidProviderEpochProofWriteResult.Written -> Unit
            AndroidProviderEpochProofWriteResult.PermissionDenied ->
                return AndroidInteroperabilityStageResult.ActionRequired
            AndroidProviderEpochProofWriteResult.ProviderUnavailable ->
                return AndroidInteroperabilityStageResult.RetryWaiting
            AndroidProviderEpochProofWriteResult.AccountMissing ->
                return AndroidInteroperabilityStageResult.ActionRequired
        }
        val completed = database.androidProjectionLedgerDao().completeProviderRepair(
            repairContext.account.value,
            repairContext.accountRevision,
            repairContext.providerEpoch,
        )
        return if (completed == 1) AndroidInteroperabilityStageResult.Success
        else AndroidInteroperabilityStageResult.RetryWaiting
    }
}
