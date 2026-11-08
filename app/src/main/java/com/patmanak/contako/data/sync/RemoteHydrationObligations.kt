package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.proton.ContactInventoryBaseline

/** Durable coverage for cards which cannot be verified yet. An event cursor is not proof of hydration. */
internal interface RemoteHydrationObligationStore {
    suspend fun pending(account: AccountScope): Set<RemoteContactId>
    suspend fun defer(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure)
    /** A shared 429 defers unread cards without counting them as failed requests. */
    suspend fun postpone(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) =
        defer(account, baseline, failure)
    suspend fun readDisposition(account: AccountScope, baseline: ContactInventoryBaseline,
        sourceChanged: Boolean = false): RemoteHydrationReadDisposition = RemoteHydrationReadDisposition.READ
    suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>)
    suspend fun ignore(account: AccountScope, id: RemoteContactId, generation: String): Boolean = false
    suspend fun resume(account: AccountScope, id: RemoteContactId, generation: String): Boolean = false
}

internal enum class RemoteHydrationReadDisposition { READ, RETRY_WAITING, ACTION_REQUIRED, IGNORED }

internal val GatewayFailureCategory.isTransientHydrationFailure: Boolean
    get() = this in setOf(GatewayFailureCategory.NETWORK_UNAVAILABLE, GatewayFailureCategory.TIMEOUT,
        GatewayFailureCategory.RATE_LIMITED, GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
        GatewayFailureCategory.UNKNOWN)
