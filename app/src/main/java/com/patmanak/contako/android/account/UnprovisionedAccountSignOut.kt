package com.patmanak.contako.android.account

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.AccountSyncRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Explicit sign-out for a verified local owner whose Android projection was never provisioned.
 * No provider or Android AccountManager operation belongs to this path.
 *
 * [clearLocalData] MUST atomically recheck absence of an Android binding and, unless discard was
 * explicitly requested, absence of pending mutations, then commit the scoped deletion. Returning
 * true MUST mean that transaction has committed. [releaseOwner] MUST recheck partition emptiness.
 * Both session cleanup and local cleanup MUST be idempotent so a failed cleanup can be retried
 * after Core has already removed the session. Owner metadata remains the durable recovery fence.
 */
internal class UnprovisionedAccountSignOut(
    private val accountScope: AccountScope,
    private val runner: AccountSyncRunner,
    private val ownerIsVerified: suspend () -> Boolean,
    private val hasNoAndroidBinding: suspend () -> Boolean,
    private val pendingMutations: PendingMutationReader,
    private val revokeSession: suspend () -> Boolean,
    private val clearLocalData: suspend (discardPendingChanges: Boolean) -> Boolean,
    private val releaseOwner: suspend () -> Unit,
    private val stopScheduling: () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun remove(discardPendingChanges: Boolean): Boolean = mutex.withLock {
        var runnerStopped = false
        var destructiveStarted = false
        try {
            if (!ownerIsVerified() || !hasNoAndroidBinding()) return@withLock false
            if (!discardPendingChanges && pendingMutations.count(accountScope) != 0) return@withLock false
            runner.cancelForAccountRemoval()
            runnerStopped = true
            runner.awaitIdle()
            if (!ownerIsVerified() || !hasNoAndroidBinding() ||
                (!discardPendingChanges && pendingMutations.count(accountScope) != 0)) {
                runner.reactivateAfterAccountProvisioning()
                return@withLock false
            }
            // Once the user-confirmed destructive sequence starts, cancellation must not split it.
            withContext(NonCancellable) {
                destructiveStarted = true
                stopScheduling()
                if (!revokeSession()) return@withContext false
                if (!hasNoAndroidBinding()) return@withContext false
                if (!clearLocalData(discardPendingChanges)) return@withContext false
                releaseOwner()
                true
            }
        } catch (cancelled: CancellationException) {
            if (runnerStopped && !destructiveStarted) withContext(NonCancellable) {
                runner.awaitIdle()
                runner.reactivateAfterAccountProvisioning()
            }
            throw cancelled
        } catch (_: Exception) {
            if (runnerStopped && !destructiveStarted) withContext(NonCancellable) {
                runner.awaitIdle()
                runner.reactivateAfterAccountProvisioning()
            }
            // Preserve the durable owner fence and intent on an uncertain/partial cleanup.
            false
        }
    }
}
