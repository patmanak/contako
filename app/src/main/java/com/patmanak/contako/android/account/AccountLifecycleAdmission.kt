package com.patmanak.contako.android.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import com.patmanak.contako.ui.ContactMutationAccess
import com.patmanak.contako.ui.ContactMutationAdmission

/** One process-local admission boundary for provisioning, app sign-out and system removal. */
internal class AccountLifecycleAdmission(
    private val isAuthenticated: suspend () -> Boolean = { true },
    private val hasPendingCleanup: () -> Boolean = { false },
) : ContactMutationAdmission {
    private val mutex = Mutex()
    private val accessLock = Any()
    private var cleanupRequests = 0
    private val mutableAccess = MutableStateFlow(ContactMutationAccess())
    override val access: StateFlow<ContactMutationAccess> = mutableAccess

    suspend fun <T> authenticated(unavailable: T, action: suspend () -> T): T = mutex.withLock {
        // Re-evaluate after waiting for cleanup, never trust readiness captured by a queued caller.
        if (access.value.blocked || hasPendingCleanup() || !isAuthenticated()) unavailable else action()
    }

    override suspend fun run(generation: Long, action: suspend () -> Unit): Boolean = mutex.withLock {
        val allowed = try {
            accepts(generation) && !hasPendingCleanup() && isAuthenticated() && accepts(generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
        if (!allowed) return@withLock false
        action()
        true
    }

    suspend fun <T> cleanup(action: suspend () -> T): T {
        // Close admission before waiting: an already admitted write finishes before purge, but
        // queued UI work must never become an operation of the next owner of the same partition.
        synchronized(accessLock) {
            cleanupRequests++
            mutableAccess.value = ContactMutationAccess(mutableAccess.value.generation + 1, blocked = true)
        }
        try {
            return mutex.withLock { action() }
        } finally {
            synchronized(accessLock) {
                cleanupRequests--
                val cleanupRemains = try { hasPendingCleanup() } catch (_: Exception) { true }
                mutableAccess.value = ContactMutationAccess(
                    generation = mutableAccess.value.generation + 1,
                    blocked = cleanupRequests > 0 || cleanupRemains,
                )
            }
        }
    }
}
