package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.proton.ContactInventoryBaseline

/** Durable coverage for cards which cannot be verified yet. An event cursor is not proof of hydration. */
internal interface RemoteHydrationObligationStore {
    suspend fun pending(account: AccountScope): Set<RemoteContactId>
    suspend fun defer(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure)
    suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>)
}
