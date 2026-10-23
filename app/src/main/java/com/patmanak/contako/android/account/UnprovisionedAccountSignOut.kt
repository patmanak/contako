package com.patmanak.contako.android.account

import android.content.Context
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
    private val ownerIdentity: suspend () -> String?,
    private val hasNoCoreAccount: suspend () -> Boolean,
    private val isLocalDataEmpty: suspend () -> Boolean,
    private val checkpoints: UnprovisionedRemovalCheckpointStore = UnprovisionedRemovalCheckpointStore.Memory(),
    private val stopScheduling: () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun remove(discardPendingChanges: Boolean): Boolean = mutex.withLock {
        var checkpoint = checkpoints.load(accountScope)
        var runnerStopped = false
        var destructiveStarted = checkpoint != null
        try {
            val expectedOwner = checkpoint?.ownerId ?: ownerIdentity() ?: return@withLock false
            val finishing = checkpoint?.phase == UnprovisionedRemovalPhase.OWNER_PENDING
            if (!hasNoAndroidBinding()) return@withLock false
            if (finishing) {
                val currentOwner = ownerIdentity()
                if ((currentOwner != null && currentOwner != expectedOwner) || !hasNoCoreAccount() || !isLocalDataEmpty()) return@withLock false
            } else {
                if (ownerIdentity() != expectedOwner || !ownerIsVerified()) return@withLock false
                if (!discardPendingChanges && pendingMutations.count(accountScope) != 0) return@withLock false
            }
            runner.cancelForAccountRemoval()
            runnerStopped = true
            runner.awaitIdle()
            if (!hasNoAndroidBinding() || (!finishing &&
                (ownerIdentity() != expectedOwner || !ownerIsVerified() ||
                    (!discardPendingChanges && pendingMutations.count(accountScope) != 0)))) {
                if (checkpoint == null) runner.reactivateAfterAccountProvisioning()
                return@withLock false
            }
            // Once the user-confirmed destructive sequence starts, cancellation must not split it.
            withContext(NonCancellable) {
                destructiveStarted = true
                if (checkpoint == null) {
                    checkpoint = UnprovisionedRemovalCheckpoint(expectedOwner, UnprovisionedRemovalPhase.SESSION_PENDING)
                    checkpoints.save(accountScope, checkpoint!!)
                }
                stopScheduling()
                if (checkpoint!!.phase == UnprovisionedRemovalPhase.SESSION_PENDING) {
                    if (ownerIdentity() != expectedOwner || !ownerIsVerified() || !revokeSession()) return@withContext false
                    checkpoint = checkpoint!!.withPhase(UnprovisionedRemovalPhase.LOCAL_PENDING)
                    checkpoints.save(accountScope, checkpoint!!)
                }
                if (checkpoint!!.phase == UnprovisionedRemovalPhase.LOCAL_PENDING) {
                    if (ownerIdentity() != expectedOwner || !hasNoCoreAccount() || !hasNoAndroidBinding()) return@withContext false
                    if (!clearLocalData(discardPendingChanges)) return@withContext false
                    checkpoint = checkpoint!!.withPhase(UnprovisionedRemovalPhase.OWNER_PENDING)
                    checkpoints.save(accountScope, checkpoint!!)
                }
                // This phase MUST NOT delete data or sessions, including after a process restart.
                // A crash after owner release can only clear the marker after proving emptiness.
                val currentOwner = ownerIdentity()
                if ((currentOwner != null && currentOwner != expectedOwner) || !hasNoCoreAccount() ||
                    !hasNoAndroidBinding() || !isLocalDataEmpty()) return@withContext false
                if (currentOwner != null) releaseOwner()
                checkpoints.clear(accountScope)
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

internal enum class UnprovisionedRemovalPhase { SESSION_PENDING, LOCAL_PENDING, OWNER_PENDING }
internal class UnprovisionedRemovalCheckpoint(val ownerId: String, val phase: UnprovisionedRemovalPhase) {
    init { require(ownerId.isNotBlank() && '\n' !in ownerId && '\r' !in ownerId) }
    fun withPhase(next: UnprovisionedRemovalPhase) = UnprovisionedRemovalCheckpoint(ownerId, next)
    override fun toString() = "UnprovisionedRemovalCheckpoint(REDACTED)"
}

internal interface UnprovisionedRemovalCheckpointStore {
    fun load(scope: AccountScope): UnprovisionedRemovalCheckpoint?
    fun save(scope: AccountScope, checkpoint: UnprovisionedRemovalCheckpoint)
    fun clear(scope: AccountScope)

    class Memory : UnprovisionedRemovalCheckpointStore {
        private val values = mutableMapOf<AccountScope, UnprovisionedRemovalCheckpoint>()
        override fun load(scope: AccountScope) = values[scope]
        override fun save(scope: AccountScope, checkpoint: UnprovisionedRemovalCheckpoint) { values[scope] = checkpoint }
        override fun clear(scope: AccountScope) { values.remove(scope) }
    }
}

/** Private identity metadata, covered by the application's explicit shared-preference exclusions. */
internal class SharedPreferencesUnprovisionedRemovalCheckpointStore(context: Context) : UnprovisionedRemovalCheckpointStore {
    private val preferences = context.applicationContext.getSharedPreferences("unprovisioned-account-removal", Context.MODE_PRIVATE)
    override fun load(scope: AccountScope): UnprovisionedRemovalCheckpoint? {
        val encoded = preferences.getString(scope.value, null) ?: return null
        val separator = encoded.indexOf('\n')
        require(separator > 0)
        return UnprovisionedRemovalCheckpoint(encoded.substring(separator + 1),
            UnprovisionedRemovalPhase.valueOf(encoded.substring(0, separator)))
    }
    override fun save(scope: AccountScope, checkpoint: UnprovisionedRemovalCheckpoint) {
        check(preferences.edit().putString(scope.value, "${checkpoint.phase.name}\n${checkpoint.ownerId}").commit())
    }
    override fun clear(scope: AccountScope) { check(preferences.edit().remove(scope.value).commit()) }
}
