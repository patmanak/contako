package com.patmanak.contako.android.account

import android.accounts.Account
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncTrigger
import kotlinx.coroutines.CancellationException

enum class SignOutChoice { CONFIRM, SYNC_NOW, DISCARD }

sealed interface SignOutResult {
    data object SignedOut : SignOutResult
    data object PendingChanges : SignOutResult
    data object SyncFailed : SignOutResult
    data object CleanupFailed : SignOutResult
}

internal fun interface PendingMutationReader {
    suspend fun count(account: AccountScope): Int
}

internal fun interface BoundAndroidAccountReader {
    suspend fun read(account: AccountScope): Account?
}

internal fun interface InAppAccountRemoval {
    suspend fun remove(account: Account, discardPendingChanges: Boolean): Boolean
}

internal class AccountSignOutCoordinator(
    private val accountScope: AccountScope,
    private val pendingMutations: PendingMutationReader,
    private val accountReader: BoundAndroidAccountReader,
    private val runner: AccountSyncRunner,
    private val removal: InAppAccountRemoval,
    private val noAndroidRemoval: suspend (discardPendingChanges: Boolean) -> Boolean = { false },
    private val hasIncompleteRemoval: suspend () -> Boolean = { false },
    private val lifecycleAdmission: AccountLifecycleAdmission = AccountLifecycleAdmission(),
    private val hasRetainedDrafts: suspend () -> Boolean = { false },
) {
    suspend fun pendingCount(): Int = pendingMutations.count(accountScope)
    suspend fun requiresRecovery(): Boolean = hasIncompleteRemoval()

    suspend fun signOut(choice: SignOutChoice): SignOutResult = lifecycleAdmission.cleanup { signOutLocked(choice) }

    private suspend fun signOutLocked(choice: SignOutChoice): SignOutResult {
        val pending = if (choice == SignOutChoice.DISCARD) 0 else try {
            pendingCount()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SignOutResult.PendingChanges
        }
        if (pending > 0 && choice == SignOutChoice.CONFIRM) return SignOutResult.PendingChanges
        if (choice == SignOutChoice.SYNC_NOW) {
            // A local recovery draft is intentionally not uploadable. Repeated synchronization
            // cannot resolve the owner's required choice and MUST NOT purge it as a side effect.
            val retained = try { hasRetainedDrafts() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { return SignOutResult.PendingChanges }
            if (retained) return SignOutResult.PendingChanges
            runner.request(SyncTrigger.MANUAL)
            runner.awaitIdle()
            val remaining = try { pendingCount() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { return SignOutResult.SyncFailed }
            if (runner.lastCompletion.value?.outcome != SyncPassOutcome.SUCCESS || remaining != 0) {
                return SignOutResult.SyncFailed
            }
        }
        val account = try { accountReader.read(accountScope) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { return SignOutResult.CleanupFailed }
        val removed = try {
            if (account != null) removal.remove(account, choice == SignOutChoice.DISCARD)
            else noAndroidRemoval(choice == SignOutChoice.DISCARD)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { return SignOutResult.CleanupFailed }
        return if (removed) {
            SignOutResult.SignedOut
        } else SignOutResult.CleanupFailed
    }
}
