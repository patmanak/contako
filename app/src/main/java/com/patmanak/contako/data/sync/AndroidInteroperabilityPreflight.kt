package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope

internal data class AndroidInteroperabilityContext(
    val account: AccountScope,
    val androidAccountName: String,
    val accountRevision: Long,
    val providerEpoch: Long,
) {
    init {
        require(androidAccountName.isNotBlank())
        require(accountRevision >= 0)
        require(providerEpoch >= 0)
    }

    override fun toString(): String =
        "AndroidInteroperabilityContext(REDACTED, accountRevision=$accountRevision, " +
            "providerEpoch=$providerEpoch)"
}

internal enum class AndroidInteroperabilityRepairReason {
    DURABLE_ACCOUNT_CONTEXT_MISSING,
    ANDROID_ACCOUNT_NAME_UNBOUND,
    CROSS_ACCOUNT_BINDING,
    ANDROID_ACCOUNT_MISSING,
    AUTHORITY_MISMATCH,
    PROVIDER_STATE_DIVERGED,
}

internal sealed interface AndroidInteroperabilityPreflightResult {
    data class Ready(val context: AndroidInteroperabilityContext) : AndroidInteroperabilityPreflightResult
    data object PermissionDenied : AndroidInteroperabilityPreflightResult
    data object ProviderUnavailable : AndroidInteroperabilityPreflightResult
    data class RepairRequired(
        val reason: AndroidInteroperabilityRepairReason,
        val context: AndroidInteroperabilityContext? = null,
    ) : AndroidInteroperabilityPreflightResult
    data object Cancelled : AndroidInteroperabilityPreflightResult
    data object LocalPersistenceFailure : AndroidInteroperabilityPreflightResult
}

internal fun interface AndroidInteroperabilityPreflight {
    suspend fun check(
        account: AccountScope,
        isCancelled: () -> Boolean,
    ): AndroidInteroperabilityPreflightResult
}
