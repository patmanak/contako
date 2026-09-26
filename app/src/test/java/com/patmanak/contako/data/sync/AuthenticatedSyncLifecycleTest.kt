package com.patmanak.contako.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticatedSyncLifecycleTest {
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
