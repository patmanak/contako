package com.patmanak.contako.data.sync

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.sync.TargetedRecoveryPrerequisite
import kotlinx.coroutines.CancellationException

/** Exact native ingestion followed by the existing guarded projector; no Proton boundary. */
internal class ProductionTargetedAndroidRecovery(
    private val database: ContakoDatabase,
    private val preflight: AndroidInteroperabilityPreflight,
    private val stage: BoundedAndroidInteroperabilityStage,
    private val projectionCoordinator: RoomBoundedAndroidProjectionCoordinator,
) : TargetedAndroidRecovery {
    override suspend fun recover(
        account: AccountScope,
        contactId: String,
        expectedRevision: Long,
        expectedProviderEpoch: Long,
        isCancelled: () -> Boolean,
    ): TargetedRecoveryWorkResult = try {
        recoverCurrent(account, contactId, expectedRevision, expectedProviderEpoch, isCancelled)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        failed(GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.UNAVAILABLE)
    }

    private suspend fun recoverCurrent(
        account: AccountScope,
        contactId: String,
        expectedRevision: Long,
        expectedProviderEpoch: Long,
        isCancelled: () -> Boolean,
    ): TargetedRecoveryWorkResult {
        require(contactId.isNotBlank() && expectedRevision >= 0 && expectedProviderEpoch >= 0)
        if (isCancelled()) return TargetedRecoveryWorkResult.Cancelled
        val first = preflight.check(account, isCancelled)
        preflightFailure(first)?.let { return it }
        val context = (first as AndroidInteroperabilityPreflightResult.Ready).context
        if (context.account != account || context.providerEpoch != expectedProviderEpoch) {
            return TargetedRecoveryWorkResult.Stale(TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
        }
        val initial = database.withTransaction {
            val canonical = database.contactDao().getEntity(account.value, contactId)
                ?: return@withTransaction null
            val bound = database.androidProjectionLedgerDao().getAccount(account.value)
            if (canonical.revision != expectedRevision || canonical.conflictState != null || bound == null ||
                bound.revision != context.accountRevision || bound.providerEpoch != expectedProviderEpoch ||
                bound.androidAccountName != context.androidAccountName || bound.providerRepairPending
            ) return@withTransaction null
            canonical to database.androidProjectionLedgerDao().get(account.value, contactId)
        } ?: return TargetedRecoveryWorkResult.Stale()
        val ledger = initial.second
        // A native creation must first receive its remote identity through the ordinary
        // durable upload path. A scoped copy cannot invent or adopt that identity.
        if (initial.first.remoteContactId == null || ledger != null && ledger.sourceIdentity == null) {
            return failed(GatewayFailureCategory.VALIDATION_REJECTED, TargetedRecoveryPrerequisite.REMOTE_IDENTITY)
        }
        if (ledger != null && (ledger.providerEpoch != expectedProviderEpoch ||
                ledger.sourceIdentity != initial.first.remoteContactId)) {
            return TargetedRecoveryWorkResult.Stale(TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
        }
        if (isCancelled()) return TargetedRecoveryWorkResult.Cancelled
        when (stage.ingestTarget(context, contactId, ledger?.rawContactLocator, ledger?.sourceIdentity, isCancelled)) {
            AndroidInteroperabilityStageResult.Success -> Unit
            AndroidInteroperabilityStageResult.Cancelled -> return TargetedRecoveryWorkResult.Cancelled
            AndroidInteroperabilityStageResult.RetryWaiting -> return failed(
                GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
            AndroidInteroperabilityStageResult.ActionRequired -> return failed(
                GatewayFailureCategory.VALIDATION_REJECTED, TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
            AndroidInteroperabilityStageResult.LocalPersistenceFailure -> return failed(
                GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.UNAVAILABLE)
        }
        if (isCancelled()) return TargetedRecoveryWorkResult.Cancelled
        val refreshed = preflight.check(account, isCancelled)
        preflightFailure(refreshed)?.let { return it }
        val currentContext = (refreshed as AndroidInteroperabilityPreflightResult.Ready).context
        if (currentContext.account != account || currentContext.providerEpoch != expectedProviderEpoch ||
            currentContext.androidAccountName != context.androidAccountName
        ) return TargetedRecoveryWorkResult.Stale(TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
        // Native ingestion can legitimately advance the canonical revision. The projector
        // plans from that freshly committed value, never from the preview's earlier payload.
        val current = database.contactDao().getEntity(account.value, contactId)
            ?: return TargetedRecoveryWorkResult.Stale()
        if (current.conflictState != null) return failed(GatewayFailureCategory.CONFLICT,
            TargetedRecoveryPrerequisite.CONTACT_CHANGED)
        if (isCancelled()) return TargetedRecoveryWorkResult.Cancelled
        val result = projectionCoordinator.projectContact(currentContext, contactId, current.revision)
        return if (isCancelled()) TargetedRecoveryWorkResult.Cancelled else result
    }

    private fun preflightFailure(result: AndroidInteroperabilityPreflightResult): TargetedRecoveryWorkResult? = when (result) {
        is AndroidInteroperabilityPreflightResult.Ready -> null
        AndroidInteroperabilityPreflightResult.Cancelled -> TargetedRecoveryWorkResult.Cancelled
        AndroidInteroperabilityPreflightResult.PermissionDenied -> failed(
            GatewayFailureCategory.VALIDATION_REJECTED, TargetedRecoveryPrerequisite.ANDROID_PERMISSION)
        AndroidInteroperabilityPreflightResult.ProviderUnavailable -> failed(
            GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
        is AndroidInteroperabilityPreflightResult.RepairRequired -> failed(
            GatewayFailureCategory.VALIDATION_REJECTED, TargetedRecoveryPrerequisite.ANDROID_CONTEXT)
        AndroidInteroperabilityPreflightResult.LocalPersistenceFailure -> failed(
            GatewayFailureCategory.UNKNOWN, TargetedRecoveryPrerequisite.UNAVAILABLE)
    }

    private fun failed(category: GatewayFailureCategory, prerequisite: TargetedRecoveryPrerequisite) =
        TargetedRecoveryWorkResult.Failed(category, prerequisite)
}
