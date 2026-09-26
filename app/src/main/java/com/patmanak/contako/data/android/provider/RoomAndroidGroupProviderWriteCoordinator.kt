package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import com.patmanak.contako.data.local.AndroidGroupProviderWriteCommitResult
import com.patmanak.contako.data.local.AndroidGroupProviderWriteCompletionResult
import com.patmanak.contako.data.local.AndroidGroupProviderWritePreparation
import com.patmanak.contako.data.local.AndroidProviderAccountMutationLocks
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomAndroidGroupProviderWriteJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal sealed interface AndroidGroupProviderWriteExecutionResult {
    data class Completed(val committedGroupLedgerRevision: Long) : AndroidGroupProviderWriteExecutionResult
    data class AlreadyCompleted(val committedGroupLedgerRevision: Long) : AndroidGroupProviderWriteExecutionResult
    data object Stale : AndroidGroupProviderWriteExecutionResult
    data object Busy : AndroidGroupProviderWriteExecutionResult
    data object ProviderConflict : AndroidGroupProviderWriteExecutionResult
}

/**
 * The only production entry point for a journaled Android Groups mutation.
 * The raw gateway return is deliberately ignored; a fresh provider classification is the sole
 * source of the COMMITTED proof.
 */
internal class RoomAndroidGroupProviderWriteCoordinator(
    database: ContakoDatabase,
    contentResolver: ContentResolver,
) {
    private val journal = RoomAndroidGroupProviderWriteJournal(database)
    private val gateway = AndroidGroupLifecycleGateway(
        contentResolver = contentResolver,
        writeAuthorizer = journal.authorizer,
    )

    suspend fun execute(
        authorization: AndroidGroupWriteAuthorization,
        providerMutation: suspend (AndroidGroupLifecycleGateway) -> Unit,
    ): AndroidGroupProviderWriteExecutionResult =
        AndroidProviderAccountMutationLocks.withAccountLock(authorization.context.accountId) {
            when (journal.prepare(authorization)) {
                AndroidGroupProviderWritePreparation.Stale ->
                    return@withAccountLock AndroidGroupProviderWriteExecutionResult.Stale
                AndroidGroupProviderWritePreparation.Busy ->
                    return@withAccountLock AndroidGroupProviderWriteExecutionResult.Busy
                is AndroidGroupProviderWritePreparation.AlreadyCommitted ->
                    return@withAccountLock journal.completeCommitted(authorization).toExecutionResult()
                is AndroidGroupProviderWritePreparation.Prepared,
                is AndroidGroupProviderWritePreparation.AlreadyPrepared,
                -> Unit
            }

            val verified = try {
                when (val before = gateway.classifyProviderState(authorization)) {
                    is AndroidGroupProviderStateClassification.ExactPostState -> before.state
                    AndroidGroupProviderStateClassification.ExactPreState -> {
                        providerMutation(gateway)
                        when (val after = gateway.classifyProviderState(authorization)) {
                            is AndroidGroupProviderStateClassification.ExactPostState -> after.state
                            AndroidGroupProviderStateClassification.ExactPreState,
                            AndroidGroupProviderStateClassification.Conflict,
                            -> {
                                journal.markRepairRequired(authorization)
                                return@withAccountLock AndroidGroupProviderWriteExecutionResult.ProviderConflict
                            }
                        }
                    }
                    AndroidGroupProviderStateClassification.Conflict -> {
                        journal.markRepairRequired(authorization)
                        return@withAccountLock AndroidGroupProviderWriteExecutionResult.ProviderConflict
                    }
                }
            } catch (failure: Exception) {
                try {
                    withContext(NonCancellable) { journal.markRepairRequired(authorization) }
                } catch (repairFailure: Exception) {
                    if (repairFailure !is CancellationException) failure.addSuppressed(repairFailure)
                }
                throw failure
            }
            when (journal.markProviderCommitted(authorization, verified)) {
                AndroidGroupProviderWriteCommitResult.Stale -> {
                    journal.markRepairRequired(authorization)
                    return@withAccountLock AndroidGroupProviderWriteExecutionResult.Stale
                }
                AndroidGroupProviderWriteCommitResult.Committed,
                AndroidGroupProviderWriteCommitResult.AlreadyCommitted,
                -> Unit
            }
            journal.completeCommitted(authorization).toExecutionResult()
        }

    private fun AndroidGroupProviderWriteCompletionResult.toExecutionResult():
        AndroidGroupProviderWriteExecutionResult = when (this) {
        is AndroidGroupProviderWriteCompletionResult.Completed ->
            AndroidGroupProviderWriteExecutionResult.Completed(committedGroupLedgerRevision)
        is AndroidGroupProviderWriteCompletionResult.AlreadyCompleted ->
            AndroidGroupProviderWriteExecutionResult.AlreadyCompleted(committedGroupLedgerRevision)
        AndroidGroupProviderWriteCompletionResult.Stale -> AndroidGroupProviderWriteExecutionResult.Stale
    }
}
