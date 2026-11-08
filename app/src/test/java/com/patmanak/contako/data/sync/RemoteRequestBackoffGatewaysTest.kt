package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.ProtonContactEventsGateway
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteRequestBackoffGatewaysTest {
    @Test fun anInventoryRateLimitGatesEveryRemoteBoundaryWithoutExtendingTheOriginalDeadline() = runTest {
        val memory = MemoryBackoff()
        var calls = 0
        val original = dependencies(remote = { calls++; GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 90_000) })
        val wrapped = original.withRemoteRequestBackoff(memory)
        assertEquals(90_000L, (wrapped.inventory.page(ACCOUNT, null) as GatewayOutcome.Failure).retryAfterMillis)
        assertEquals(1, calls)
        assertEquals(1, memory.records)
        memory.now += 1_000
        val contact = RemoteContactId("synthetic-contact")
        val group = RemoteGroupId("synthetic-group")
        val blocked = listOf<suspend () -> GatewayOutcome<*>>(
            { wrapped.session.restore(ACCOUNT) }, { wrapped.session.refresh(ACCOUNT) },
            { wrapped.inventory.page(ACCOUNT, null) }, { wrapped.verifiedCards.fetch(ACCOUNT, contact) },
            { wrapped.existence.check(ACCOUNT, contact) }, { wrapped.events!!.read(ACCOUNT, null) },
            { wrapped.contactMutations.apply(ACCOUNT, ContactMutation.Delete(contact, null)) },
            { wrapped.groups.list(ACCOUNT) }, { wrapped.groups.create(ACCOUNT, ContactGroupMutation.Create("Synthetic", null)) },
            { wrapped.groups.update(ACCOUNT, ContactGroupMutation.Update(group, "Synthetic", null)) },
            { wrapped.groups.delete(ACCOUNT, ContactGroupMutation.Delete(group)) },
            { wrapped.emailLabels.apply(ACCOUNT, EmailLabelMutation.Assign(group, listOf(RemoteEmailId("synthetic-email")))) },
            { wrapped.membershipReader.members(ACCOUNT, group) },
        )
        blocked.forEach { call ->
            val failure = call() as GatewayOutcome.Failure
            assertEquals(GatewayFailureCategory.RATE_LIMITED, failure.category)
            assertEquals(89_000L, failure.retryAfterMillis)
            assertNull("A local gate must not invent an HTTP response", failure.httpStatusCode)
        }
        assertEquals(1, calls)
        assertEquals(1, memory.records)
        memory.now = 190_000L
        wrapped.inventory.page(ACCOUNT, null)
        assertEquals(2, calls)
    }

    @Test fun localCleanupAndSecurityOperationsRemainPossibleDuringTheContactCooldown() = runTest {
        val memory = MemoryBackoff().also { it.deadlines[ACCOUNT.value] = Long.MAX_VALUE }
        var remoteCalls = 0
        var localCalls = 0
        val wrapped = dependencies({ remoteCalls++; GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN) },
            local = { localCalls++ }).withRemoteRequestBackoff(memory)
        assertTrue(wrapped.session.lockKeys(ACCOUNT) is GatewayOutcome.Success)
        assertTrue(wrapped.session.revokeAndClear(ACCOUNT) is GatewayOutcome.Success)
        assertTrue(wrapped.localSessionCleanup.clearLocal(ACCOUNT) is GatewayOutcome.Success)
        wrapped.groups.capabilities()
        assertEquals(3, localCalls)
        assertEquals(0, remoteCalls)
    }

    @Test fun noServerDelayOrAnotherFailureCategoryDoesNotCreateAThrottleAndCancellationPropagates() = runTest {
        val memory = MemoryBackoff()
        memory.guarded(ACCOUNT) { GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED) }
        memory.guarded(ACCOUNT) { GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT, retryAfterMillis = 90_000) }
        assertEquals(0L, memory.remainingMillis(ACCOUNT))
        val calls = memory.records
        try {
            memory.guarded<Unit>(ACCOUNT) { throw CancellationException("Synthetic cancellation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(calls, memory.records)
    }

    @Test fun aCooldownIsAccountOwnedAndNullableEventsRemainAbsent() = runTest {
        val memory = MemoryBackoff().also { it.deadlines[ACCOUNT.value] = 190_000L }
        var calls = 0
        val wrapped = dependencies(remote = { calls++; GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN) }).copy(events = null)
            .withRemoteRequestBackoff(memory)
        assertNull(wrapped.events)
        wrapped.inventory.page(AccountScope("foreign-account"), null)
        assertEquals(1, calls)
        assertEquals(90_000L, memory.remainingMillis(ACCOUNT))
    }

    private class MemoryBackoff : RemoteRequestBackoffStore {
        var now = 100_000L
        var records = 0
        val deadlines = mutableMapOf<String, Long>()
        override suspend fun remainingMillis(account: AccountScope) = ((deadlines[account.value] ?: 0L) - now).coerceAtLeast(0L)
        override suspend fun record(account: AccountScope, failure: GatewayOutcome.Failure) {
            records++
            if (failure.category == GatewayFailureCategory.RATE_LIMITED && failure.retryAfterMillis != null)
                deadlines[account.value] = maxOf(deadlines[account.value] ?: 0L, now + requireNotNull(failure.retryAfterMillis))
        }
    }

    private fun dependencies(remote: () -> GatewayOutcome.Failure, local: () -> Unit = {}) = ProductionSyncDependencies(
        account = ACCOUNT,
        session = object : ProtonSessionGateway {
            override suspend fun restore(account: AccountScope) = remote()
            override suspend fun refresh(account: AccountScope) = remote()
            override suspend fun unlockKeys(account: AccountScope, password: OperationSecret) = remote()
            override suspend fun lockKeys(account: AccountScope): GatewayOutcome<Unit> { local(); return GatewayOutcome.Success(Unit) }
            override suspend fun revokeAndClear(account: AccountScope): GatewayOutcome<Unit> { local(); return GatewayOutcome.Success(Unit) }
        },
        inventory = ProtonContactInventoryGateway { _, _ -> remote() },
        verifiedCards = ProtonVerifiedContactCardGateway { _, _ -> remote() },
        contactMutations = object : ProtonContactMutationGateway {
            override suspend fun apply(account: AccountScope, mutation: ContactMutation) = remote()
        },
        groups = object : ProtonContactGroupGateway {
            override fun capabilities() = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE
            override suspend fun list(account: AccountScope) = remote()
            override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create) = remote()
            override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update) = remote()
            override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete) = remote()
        },
        emailLabels = object : ProtonContactEmailLabelGateway {
            override suspend fun apply(account: AccountScope, mutation: EmailLabelMutation) = remote()
        },
        membershipReader = ProtonEmailGroupMembershipReader { _, _ -> remote() },
        localSessionCleanup = ProtonLocalSessionCleanupGateway { local(); GatewayOutcome.Success(Unit) },
        existence = ProtonContactExistenceGateway { _, _ -> remote() },
        events = ProtonContactEventsGateway { _, _ -> remote() },
    )

    companion object { private val ACCOUNT = AccountScope("synthetic-backoff-owner") }
}
