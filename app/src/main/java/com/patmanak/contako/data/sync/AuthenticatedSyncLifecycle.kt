package com.patmanak.contako.data.sync

/**
 * Prevents activity startup from racing authentication and submitting duplicate initial work.
 *
 * The first Android lifecycle start necessarily precedes retained-session restore or interactive
 * sign-in. An authenticated, owner-validated Proton session opens the remote scheduling gate;
 * Android account provisioning is an independent, recoverable projection prerequisite.
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

    fun onAuthenticationReady() {
        ready = true
        onAccountReady()
    }

    fun onAccountProvisioned() = onAuthenticationReady()

    fun onAuthenticationUnavailable() {
        ready = false
    }
}
