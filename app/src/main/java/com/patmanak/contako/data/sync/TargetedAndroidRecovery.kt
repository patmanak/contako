package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.domain.sync.TargetedRecoveryPrerequisite

/** A receipt of scoped work, not a replacement for its durable provider/write guards. */
internal sealed interface TargetedRecoveryWorkResult {
    data class Resolved(val canonicalRevision: Long? = null) : TargetedRecoveryWorkResult
    data class Stale(val prerequisite: TargetedRecoveryPrerequisite = TargetedRecoveryPrerequisite.CONTACT_CHANGED) : TargetedRecoveryWorkResult
    data object Cancelled : TargetedRecoveryWorkResult
    data class Failed(val category: GatewayFailureCategory,
        val prerequisite: TargetedRecoveryPrerequisite? = null,
        val retryAfterMillis: Long? = null,
        val hydrationCategory: GatewayContactHydrationCategory? = null) : TargetedRecoveryWorkResult
}

/** Invoked only within the account's existing serialized synchronization executor. */
internal fun interface TargetedAndroidRecovery {
    suspend fun recover(account: AccountScope, contactId: String, expectedRevision: Long,
        expectedProviderEpoch: Long, isCancelled: () -> Boolean): TargetedRecoveryWorkResult
}
