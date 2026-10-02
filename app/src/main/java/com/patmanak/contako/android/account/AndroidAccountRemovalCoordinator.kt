package com.patmanak.contako.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonLocalSessionCleanupGateway
import com.patmanak.contako.data.android.provider.AndroidAccountProjectionCleaner
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.sync.AccountSyncRunner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface AccountRemovalCoordinator {
    suspend fun remove(account: Account): Boolean
}

internal interface ContakoAccountRemovalRuntime {
    val accountRemovalCoordinator: AccountRemovalCoordinator
}

internal class AndroidAccountRemovalCoordinator(
    private val providerCleaner: AndroidAccountProjectionCleaner,
    private val localStore: AccountRemovalLocalStore,
    private val accountScope: AccountScope,
    private val protonSession: ProtonLocalSessionCleanupGateway,
    private val runner: AccountSyncRunner,
    private val platformCleanup: AccountPlatformCleanup = AccountPlatformCleanup.None,
    private val checkpoints: AccountRemovalCheckpointStore = AccountRemovalCheckpointStore.Memory(),
) : AccountRemovalCoordinator {
    private val removalMutex = Mutex()
    constructor(
        providerCleaner: AndroidAccountProjectionCleaner,
        database: ContakoDatabase,
        accountScope: AccountScope,
        protonSession: ProtonLocalSessionCleanupGateway,
        runner: AccountSyncRunner,
        platformCleanup: AccountPlatformCleanup = AccountPlatformCleanup.None,
        checkpoints: AccountRemovalCheckpointStore = AccountRemovalCheckpointStore.Memory(),
    ) : this(
        providerCleaner,
        RoomAccountRemovalLocalStore(database),
        accountScope,
        protonSession,
        runner,
        platformCleanup,
        checkpoints,
    )

    override suspend fun remove(account: Account): Boolean =
        remove(account, removeAndroidAccount = false, discardPendingChanges = true)

    suspend fun removeFromApp(account: Account, discardPendingChanges: Boolean = false): Boolean =
        remove(account, removeAndroidAccount = true, discardPendingChanges = discardPendingChanges)

    private suspend fun remove(
        account: Account,
        removeAndroidAccount: Boolean,
        discardPendingChanges: Boolean,
    ): Boolean = removalMutex.withLock {
        removeLocked(account, removeAndroidAccount, discardPendingChanges)
    }

    private suspend fun removeLocked(account: Account, removeAndroidAccount: Boolean, discardPendingChanges: Boolean): Boolean {
        if (account.type != ContakoAndroidAccountContract.ACCOUNT_TYPE) return false
        var phase = checkpoints.load(accountScope, account)
        if (phase == null && !localStore.hasExactBinding(accountScope, account)) return false

        return try {
            if (phase == null) {
                runner.cancelForAccountRemoval()
                runner.awaitIdle()
                phase = AccountRemovalPhase.WORK_STOPPED.also { checkpoints.save(accountScope, account, it) }
            }
            val completed = if (!discardPendingChanges && phase == AccountRemovalPhase.WORK_STOPPED) {
                // Stop the runner before acquiring Room's write transaction: a running pass may
                // itself need that database. Keep local mutations excluded through cleanup.
                val localRemoved = localStore.withoutPendingMutations(accountScope, account) {
                    finishRemoval(account, removeAndroidAccount, phase!!, requireClean = true, stopAfterLocal = true)
                }
                if (localRemoved) {
                    // Only checkpoint Room deletion once its transaction has committed.
                    checkpoints.save(accountScope, account, AccountRemovalPhase.LOCAL_CLEARED)
                    finishRemoval(account, removeAndroidAccount, AccountRemovalPhase.LOCAL_CLEARED, requireClean = true)
                } else false
            } else finishRemoval(account, removeAndroidAccount, phase!!, requireClean = !discardPendingChanges)
            if (!completed && checkpoints.load(accountScope, account) == AccountRemovalPhase.WORK_STOPPED) {
                // No destructive step ran: retain the session and restore the existing scheduler.
                checkpoints.clear(accountScope, account)
                runner.reactivateAfterAccountProvisioning()
            }
            completed
        } catch (_: Exception) {
            if (checkpoints.load(accountScope, account) == AccountRemovalPhase.WORK_STOPPED) {
                checkpoints.clear(accountScope, account)
                runner.reactivateAfterAccountProvisioning()
            }
            false
        }
    }

    private suspend fun finishRemoval(
        account: Account,
        removeAndroidAccount: Boolean,
        initialPhase: AccountRemovalPhase,
        requireClean: Boolean,
        stopAfterLocal: Boolean = false,
    ): Boolean {
        var phase = initialPhase
        // A retry after partial cleanup is not consent to discard newly created native edits.
        if (requireClean && phase != AccountRemovalPhase.WORK_STOPPED &&
            providerCleaner.hasPendingChanges(account.name, account.type)
        ) return false
        if (phase == AccountRemovalPhase.WORK_STOPPED) {
            val deleted = if (requireClean) providerCleaner.deleteIfClean(account.name, account.type)
                else providerCleaner.delete(account.name, account.type)
            if (!deleted) return false
            phase = AccountRemovalPhase.PROJECTION_CLEARED_SESSION_PENDING.also {
                checkpoints.save(accountScope, account, it)
            }
        }
        platformCleanup.stopScheduledWork(account)
        if (phase == AccountRemovalPhase.PROJECTION_CLEARED_SESSION_PENDING) {
            val cleanup = if (removeAndroidAccount) protonSession.clearAfterBestEffortRevocation(accountScope)
                else protonSession.clearLocal(accountScope)
            if (cleanup !is GatewayOutcome.Success) return false
            phase = AccountRemovalPhase.PROJECTION_CLEARED.also { checkpoints.save(accountScope, account, it) }
        }
        // Resume checkpoints written by older versions, which cleared the session first.
        if (phase == AccountRemovalPhase.SESSION_CLEARED) {
            val deleted = if (requireClean) providerCleaner.deleteIfClean(account.name, account.type)
                else providerCleaner.delete(account.name, account.type)
            if (!deleted) return false
            phase = AccountRemovalPhase.PROJECTION_CLEARED.also { checkpoints.save(accountScope, account, it) }
        }
        if (phase == AccountRemovalPhase.PROJECTION_CLEARED) {
            platformCleanup.deleteScopedFiles(accountScope)
            localStore.delete(accountScope)
            if (stopAfterLocal) return true
            phase = AccountRemovalPhase.LOCAL_CLEARED.also { checkpoints.save(accountScope, account, it) }
        }
        if (phase == AccountRemovalPhase.LOCAL_CLEARED) {
            platformCleanup.clearNotifications()
            if (removeAndroidAccount) {
                if (!platformCleanup.removeAndroidAccount(account)) return false
                phase = AccountRemovalPhase.ACCOUNT_REMOVED.also { checkpoints.save(accountScope, account, it) }
            }
        }
        checkpoints.clear(accountScope, account)
        return true
    }

}

internal interface AccountRemovalLocalStore {
    suspend fun hasExactBinding(scope: AccountScope, account: Account): Boolean
    suspend fun delete(scope: AccountScope)
    suspend fun withoutPendingMutations(scope: AccountScope, account: Account, action: suspend () -> Boolean): Boolean
}

internal class RoomAccountRemovalLocalStore(private val database: ContakoDatabase) : AccountRemovalLocalStore {
    override suspend fun withoutPendingMutations(scope: AccountScope, account: Account, action: suspend () -> Boolean): Boolean =
        withContext(NonCancellable) {
            database.withTransaction {
                if (!hasExactBinding(scope, account) || database.accountRemovalDao().countPending(scope.value) != 0) false else {
                    // Provider/session/checkpoint operations cannot roll back with Room. Preserve
                    // completed local cleanup if a later platform step fails, for checkpoint retry.
                    try { action() } catch (_: Exception) { false }
                }
            }
        }
    override suspend fun hasExactBinding(scope: AccountScope, account: Account): Boolean {
        val durable = database.androidProjectionLedgerDao().getAccount(scope.value)
        return durable?.androidAccountName == account.name &&
            database.androidProjectionLedgerDao().countOtherAccountsBoundToAndroidName(scope.value, account.name) == 0
    }

    override suspend fun delete(scope: AccountScope) = database.accountRemovalDao().deleteAccount(scope.value)
}

internal enum class AccountRemovalPhase {
    WORK_STOPPED,
    PROJECTION_CLEARED_SESSION_PENDING,
    SESSION_CLEARED,
    PROJECTION_CLEARED,
    LOCAL_CLEARED,
    ACCOUNT_REMOVED,
}

internal interface AccountRemovalCheckpointStore {
    fun load(scope: AccountScope, account: Account): AccountRemovalPhase?
    fun save(scope: AccountScope, account: Account, phase: AccountRemovalPhase)
    fun clear(scope: AccountScope, account: Account)

    class Memory : AccountRemovalCheckpointStore {
        private val values = mutableMapOf<String, AccountRemovalPhase>()
        private fun key(scope: AccountScope, account: Account) = removalCheckpointKey(scope, account)
        override fun load(scope: AccountScope, account: Account) = values[key(scope, account)]
        override fun save(scope: AccountScope, account: Account, phase: AccountRemovalPhase) { values[key(scope, account)] = phase }
        override fun clear(scope: AccountScope, account: Account) { values.remove(key(scope, account)) }
    }
}

internal interface AccountPlatformCleanup {
    fun stopScheduledWork(account: Account)
    fun deleteScopedFiles(account: AccountScope)
    fun clearNotifications()
    fun removeAndroidAccount(account: Account): Boolean

    data object None : AccountPlatformCleanup {
        override fun stopScheduledWork(account: Account) = Unit
        override fun deleteScopedFiles(account: AccountScope) = Unit
        override fun clearNotifications() = Unit
        override fun removeAndroidAccount(account: Account) = true
    }
}

internal class FrameworkAccountPlatformCleanup(
    context: Context,
    private val onStopScheduling: () -> Unit,
) : AccountPlatformCleanup {
    private val applicationContext = context.applicationContext

    override fun stopScheduledWork(account: Account) {
        onStopScheduling()
        ContentResolver.cancelSync(account, ContakoAndroidAccountContract.CONTACTS_AUTHORITY)
        ContentResolver.removePeriodicSync(account, ContakoAndroidAccountContract.CONTACTS_AUTHORITY, Bundle.EMPTY)
    }

    override fun deleteScopedFiles(account: AccountScope) {
        val directory = applicationContext.filesDir.resolve("accounts").resolve(account.value)
        if (directory.exists() && !directory.deleteRecursively()) error("Scoped account files could not be deleted")
    }

    override fun clearNotifications() {
        applicationContext.getSystemService(NotificationManager::class.java).cancelAll()
    }

    override fun removeAndroidAccount(account: Account): Boolean =
        AccountManager.get(applicationContext).removeAccountExplicitly(account)
}

internal class SharedPreferencesAccountRemovalCheckpointStore(context: Context) : AccountRemovalCheckpointStore {
    private val preferences = context.applicationContext.getSharedPreferences("account-removal", Context.MODE_PRIVATE)
    private fun key(scope: AccountScope, account: Account) = removalCheckpointKey(scope, account)
    override fun load(scope: AccountScope, account: Account): AccountRemovalPhase? =
        preferences.getString(key(scope, account), null)?.let(AccountRemovalPhase::valueOf)
    override fun save(scope: AccountScope, account: Account, phase: AccountRemovalPhase) {
        check(preferences.edit().putString(key(scope, account), phase.name).commit())
    }
    override fun clear(scope: AccountScope, account: Account) {
        check(preferences.edit().remove(key(scope, account)).commit())
    }
}

private fun removalCheckpointKey(scope: AccountScope, account: Account): String =
    listOf(scope.value, account.name, account.type).joinToString(separator = "") { "${it.length}:$it" }
