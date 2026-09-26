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
) {
    suspend fun pendingCount(): Int = pendingMutations.count(accountScope)

    suspend fun signOut(choice: SignOutChoice): SignOutResult {
        val pending = if (choice == SignOutChoice.DISCARD) 0 else try {
            pendingCount()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SignOutResult.PendingChanges
        }
        if (pending > 0 && choice == SignOutChoice.CONFIRM) return SignOutResult.PendingChanges
        if (choice == SignOutChoice.SYNC_NOW) {
            runner.request(SyncTrigger.MANUAL)
            runner.awaitIdle()
            val remaining = try { pendingCount() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { return SignOutResult.SyncFailed }
            if (runner.lastCompletion.value?.outcome != SyncPassOutcome.SUCCESS || remaining != 0) {
                return SignOutResult.SyncFailed
            }
        }
        val account = accountReader.read(accountScope) ?: return SignOutResult.CleanupFailed
        return if (removal.remove(account, choice == SignOutChoice.DISCARD)) {
            SignOutResult.SignedOut
        } else SignOutResult.CleanupFailed
    }
}
