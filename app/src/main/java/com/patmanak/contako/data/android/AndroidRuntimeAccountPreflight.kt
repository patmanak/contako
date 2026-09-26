package com.patmanak.contako.data.android

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflight
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflightResult
import com.patmanak.contako.data.sync.AndroidInteroperabilityRepairReason
import kotlinx.coroutines.CancellationException

internal data class AndroidDurableAccountContext(
    val account: AccountScope,
    val androidAccountName: String?,
    val accountRevision: Long,
    val providerEpoch: Long,
    val hasCrossAccountBinding: Boolean,
)

internal fun interface AndroidDurableAccountContextReader {
    suspend fun load(account: AccountScope): AndroidDurableAccountContext?
}

internal sealed interface AndroidRuntimeProbeResult {
    data object Ready : AndroidRuntimeProbeResult
    data object PermissionDenied : AndroidRuntimeProbeResult
    data object ProviderUnavailable : AndroidRuntimeProbeResult
    data object AndroidAccountMissing : AndroidRuntimeProbeResult
    data object AuthorityMismatch : AndroidRuntimeProbeResult
    data object ProviderStateDiverged : AndroidRuntimeProbeResult
}

internal fun interface AndroidRuntimeProbe {
    fun inspect(androidAccountName: String, expectedProviderEpoch: Long): AndroidRuntimeProbeResult
}

/** Read-only gate. Repair execution is deliberately outside this boundary. */
internal class AndroidRuntimeAccountPreflight(
    private val durableContextReader: AndroidDurableAccountContextReader,
    private val runtimeProbe: AndroidRuntimeProbe,
) : AndroidInteroperabilityPreflight {
    override suspend fun check(
        account: AccountScope,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityPreflightResult {
        if (isCancelled()) return AndroidInteroperabilityPreflightResult.Cancelled
        val durable = try {
            durableContextReader.load(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AndroidInteroperabilityPreflightResult.LocalPersistenceFailure
        } ?: return AndroidInteroperabilityPreflightResult.RepairRequired(
            AndroidInteroperabilityRepairReason.DURABLE_ACCOUNT_CONTEXT_MISSING,
        )
        if (isCancelled()) return AndroidInteroperabilityPreflightResult.Cancelled
        val androidAccountName = durable.androidAccountName
            ?: return AndroidInteroperabilityPreflightResult.RepairRequired(
                AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_NAME_UNBOUND,
            )
        if (durable.account != account || durable.hasCrossAccountBinding) {
            return AndroidInteroperabilityPreflightResult.RepairRequired(
                AndroidInteroperabilityRepairReason.CROSS_ACCOUNT_BINDING,
            )
        }
        val probe = runtimeProbe.inspect(androidAccountName, durable.providerEpoch)
        if (isCancelled()) return AndroidInteroperabilityPreflightResult.Cancelled
        return when (probe) {
            AndroidRuntimeProbeResult.Ready -> AndroidInteroperabilityPreflightResult.Ready(
                AndroidInteroperabilityContext(
                    account,
                    androidAccountName,
                    durable.accountRevision,
                    durable.providerEpoch,
                ),
            )
            AndroidRuntimeProbeResult.PermissionDenied ->
                AndroidInteroperabilityPreflightResult.PermissionDenied
            AndroidRuntimeProbeResult.ProviderUnavailable ->
                AndroidInteroperabilityPreflightResult.ProviderUnavailable
            AndroidRuntimeProbeResult.AndroidAccountMissing ->
                AndroidInteroperabilityPreflightResult.RepairRequired(
                    AndroidInteroperabilityRepairReason.ANDROID_ACCOUNT_MISSING,
                )
            AndroidRuntimeProbeResult.AuthorityMismatch ->
                AndroidInteroperabilityPreflightResult.RepairRequired(
                    AndroidInteroperabilityRepairReason.AUTHORITY_MISMATCH,
                )
            AndroidRuntimeProbeResult.ProviderStateDiverged ->
                AndroidInteroperabilityPreflightResult.RepairRequired(
                    AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED,
                    AndroidInteroperabilityContext(
                        account,
                        androidAccountName,
                        durable.accountRevision,
                        durable.providerEpoch,
                    ),
                )
        }
    }
}
