package com.patmanak.contako.data.sync

/**
 * Prevents activity startup from racing authentication and submitting duplicate initial work.
 *
 * The first Android lifecycle start necessarily precedes retained-session restore or interactive
 * sign-in. Only a successfully provisioned authenticated account may open the scheduling gate.
 * Later foreground starts may then perform the normal fifteen-minute staleness check.
 */
internal class AuthenticatedSyncLifecycle(
    private val onAccountReady: () -> Unit,
    private val onAuthenticatedStartup: () -> Unit,
) {
    @Volatile
    private var ready = false

    fun onActivityStarted() {
        if (ready) onAuthenticatedStartup()
    }

    fun onAccountProvisioned() {
        ready = true
        onAccountReady()
    }

    fun onAuthenticationUnavailable() {
        ready = false
    }
}
