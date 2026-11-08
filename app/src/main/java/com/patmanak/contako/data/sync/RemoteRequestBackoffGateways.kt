package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.ProtonContactEventsGateway
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader

internal interface RemoteRequestBackoffStore {
    suspend fun remainingMillis(account: AccountScope): Long
    suspend fun record(account: AccountScope, failure: GatewayOutcome.Failure)
}

/** Check before invoking any delegate. A local gate rejection must not extend the original deadline. */
internal suspend fun <T> RemoteRequestBackoffStore.guarded(account: AccountScope,
    block: suspend () -> GatewayOutcome<T>): GatewayOutcome<T> {
    val remaining = remainingMillis(account)
    if (remaining > 0) return GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = remaining)
    return block().also { result -> if (result is GatewayOutcome.Failure) record(account, result) }
}

/** One account-scoped server delay covers all remote contacts work, including recovery and verification.
 * Local cleanup, revocation and key locking remain delegated without a network cooldown gate.
 */
internal fun ProductionSyncDependencies.withRemoteRequestBackoff(store: RemoteRequestBackoffStore): ProductionSyncDependencies = copy(
    session = object : ProtonSessionGateway by session {
        override suspend fun restore(account: AccountScope) = store.guarded(account) { session.restore(account) }
        override suspend fun refresh(account: AccountScope) = store.guarded(account) { session.refresh(account) }
    },
    inventory = ProtonContactInventoryGateway { account, cursor -> store.guarded(account) { inventory.page(account, cursor) } },
    verifiedCards = ProtonVerifiedContactCardGateway { account, id -> store.guarded(account) { verifiedCards.fetch(account, id) } },
    existence = ProtonContactExistenceGateway { account, id -> store.guarded(account) { existence.check(account, id) } },
    events = events?.let { delegate -> ProtonContactEventsGateway { account, cursor -> store.guarded(account) { delegate.read(account, cursor) } } },
    contactMutations = object : ProtonContactMutationGateway {
        override suspend fun apply(account: AccountScope, mutation: ContactMutation) =
            store.guarded(account) { contactMutations.apply(account, mutation) }
    },
    groups = object : ProtonContactGroupGateway by groups {
        override suspend fun list(account: AccountScope) = store.guarded(account) { groups.list(account) }
        override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create) =
            store.guarded(account) { groups.create(account, mutation) }
        override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update) =
            store.guarded(account) { groups.update(account, mutation) }
        override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete) =
            store.guarded(account) { groups.delete(account, mutation) }
    },
    emailLabels = object : ProtonContactEmailLabelGateway {
        override suspend fun apply(account: AccountScope, mutation: EmailLabelMutation) =
            store.guarded(account) { emailLabels.apply(account, mutation) }
    },
    membershipReader = ProtonEmailGroupMembershipReader { account, id -> store.guarded(account) { membershipReader.members(account, id) } },
)
