package com.patmanak.contako.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncRequestDisposition
import com.patmanak.contako.domain.sync.SyncTrigger
import java.io.Closeable

class AuthenticatedSyncLifecycleTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `reconnection reopens first import even when Android has no eligible account`() = runTest {
        var passes = 0
        val runner = AccountSyncRunner(backgroundScope) { passes++; SyncPassOutcome.SUCCESS }
        runner.cancelForAccountRemoval()
        runner.awaitIdle()
        assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, runner.request(SyncTrigger.FIRST_IMPORT))
        val policy = SyncSchedulingPolicy(this, SchedulingClock { 1_000 }, SyncAccountEligibility { null },
            LastSuccessfulSyncReader { null }, object : SyncWorkScheduler {
                override fun ensurePeriodic(accountName: String, intervalSeconds: Long) = error("No provisioned Android account")
                override fun request(accountName: String, triggers: Set<SyncTrigger>) = error("No provisioned Android account")
            }, object : SchedulingNetworkMonitor {
                override fun current() = SchedulingNetworkState.ONLINE
                override fun observe(listener: (SchedulingNetworkState) -> Unit) = Closeable { }
            }, foregroundFirstImport = { runner.request(SyncTrigger.FIRST_IMPORT) }, isProtonSessionReady = { true })
        val lifecycle = AuthenticatedSyncLifecycle(policy::onAccountReady, policy::onAppStartup)
        try {
            // Production does this only after readyOwnerUserId validates durable ownership.
            runner.reactivateAfterAccountProvisioning()
            lifecycle.onAuthenticationReady()
            testScheduler.runCurrent()
            runner.awaitIdle()
            assertEquals(1, passes)
        } finally { policy.close() }
    }

    @Test fun `validated Proton authentication starts import before Android provisioning`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)
        lifecycle.onAuthenticationReady()
        assertEquals(listOf("ACCOUNT_READY"), events)
        lifecycle.onActivityStarted()
        assertEquals(listOf("ACCOUNT_READY", "AUTHENTICATED_STARTUP"), events)
    }

    @Test
    fun `startup before authentication does not race the first import`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)

        lifecycle.onActivityStarted()
        lifecycle.onAccountProvisioned()

        assertEquals(listOf("ACCOUNT_READY"), events)
    }

    @Test
    fun `later authenticated startup performs only the staleness check`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)

        lifecycle.onAccountProvisioned()
        lifecycle.onActivityStarted()

        assertEquals(listOf("ACCOUNT_READY", "AUTHENTICATED_STARTUP"), events)
    }

    @Test
    fun `authentication loss closes the startup scheduling gate`() {
        val events = mutableListOf<String>()
        val lifecycle = lifecycle(events)

        lifecycle.onAccountProvisioned()
        lifecycle.onAuthenticationUnavailable()
        lifecycle.onActivityStarted()

        assertEquals(listOf("ACCOUNT_READY"), events)
    }

    private fun lifecycle(events: MutableList<String>) = AuthenticatedSyncLifecycle(
        onAccountReady = { events += "ACCOUNT_READY" },
        onAuthenticatedStartup = { events += "AUTHENTICATED_STARTUP" },
    )
}
