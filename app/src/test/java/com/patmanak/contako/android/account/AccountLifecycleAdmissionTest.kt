package com.patmanak.contako.android.account

import android.accounts.Account
import com.patmanak.contako.data.android.AndroidAccountBindingResult
import com.patmanak.contako.data.android.AndroidProjectionAccountSnapshot
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLifecycleAdmissionTest {
    @Test fun cleanupWaitsForAdmittedMutationAndRejectsQueuedIntentEvenAfterNextLogin() = runTest {
        val gate = AccountLifecycleAdmission()
        val generationA = gate.access.value.generation
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val durable = mutableListOf<String>()
        val firstWrite = async {
            gate.run(generationA) { entered.complete(Unit); resume.await(); durable += "owner-A" }
        }
        entered.await()
        val staleWrite = async { gate.run(generationA) { durable += "stale-A" } }
        var purged = false
        val cleanup = async { gate.cleanup { durable.clear(); purged = true } }
        runCurrent()
        assertTrue(gate.access.value.blocked)
        assertFalse(purged)
        resume.complete(Unit)
        assertTrue(firstWrite.await())
        assertFalse(staleWrite.await())
        cleanup.await()
        assertTrue(purged)
        assertTrue(durable.isEmpty())
        assertFalse(gate.run(generationA) { durable += "old-callback" })
        assertTrue(gate.run(gate.access.value.generation) { durable += "owner-B" })
        assertEquals(listOf("owner-B"), durable)
    }

    @Test fun predestructiveRefusalReopensOnlyFreshIntentsAndPreservesDurableChanges() = runTest {
        var pendingCheckpoint = false
        val gate = AccountLifecycleAdmission(hasPendingCleanup = { pendingCheckpoint })
        val staleGeneration = gate.access.value.generation
        val durable = mutableListOf("already-committed")
        assertFalse(gate.cleanup { false })
        assertFalse(gate.access.value.blocked)
        assertFalse(gate.run(staleGeneration) { durable.clear() })
        assertTrue(gate.run(gate.access.value.generation) { durable += "new-intent" })
        assertEquals(listOf("already-committed", "new-intent"), durable)
        gate.cleanup { pendingCheckpoint = true }
        assertTrue(gate.access.value.blocked)
        assertFalse(gate.run(gate.access.value.generation) { error("Cleanup is incomplete") })
        gate.cleanup { pendingCheckpoint = false }
        assertTrue(gate.run(gate.access.value.generation) {})
    }

    @Test fun provisioningAlreadyInsideRevisionReadFinishesBeforeSignOutSelectsItsCleanupPath() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val store = Store { entered.complete(Unit); resume.await() }
        val platform = Platform()
        var ready = true
        val gate = AccountLifecycleAdmission({ ready })
        val provisioning = AndroidAccountProvisioningCoordinator(store, platform)
        val runner = AccountSyncRunner(backgroundScope) { SyncPassOutcome.SUCCESS }
        var selectedPath = false
        var noAndroidCalls = 0
        var reactivations = 0
        val signOut = AccountSignOutCoordinator(ACCOUNT, PendingMutationReader { 0 },
            BoundAndroidAccountReader {
                selectedPath = true
                store.boundName?.let { Account(it, ContakoAndroidAccountContract.ACCOUNT_TYPE) }
            }, runner, InAppAccountRemoval { _, _ ->
                assertEquals(1, reactivations)
                ready = false
                store.boundName = null
                platform.present = false
                true
            }, noAndroidRemoval = { noAndroidCalls++; error("Provisioning must finish before this path is selected") },
            lifecycleAdmission = gate)
        val retry = async {
            gate.authenticated(AccountProvisioningResult.UNAVAILABLE) {
                provisioning.retryMissing(ACCOUNT) { ADDRESS }.also { reactivations++ }
            }
        }
        entered.await()
        val cleanup = async { signOut.signOut(SignOutChoice.DISCARD) }
        runCurrent()
        assertFalse(selectedPath)
        resume.complete(Unit)
        assertEquals(AccountProvisioningResult.READY, retry.await())
        assertEquals(SignOutResult.SignedOut, cleanup.await())
        assertEquals(0, noAndroidCalls)
        assertFalse(platform.present)
        assertNull(store.boundName)
    }

    @Test fun queuedProvisioningRechecksSessionAndCleanupMarkerAfterUnprovisionedSignOut() = runTest {
        for (revokeSucceeds in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val store = Store()
            val platform = Platform()
            var ready = true
            var owner = true
            var dataPresent = true
            var reactivations = 0
            val checkpoints = UnprovisionedRemovalCheckpointStore.Memory()
            val gate = AccountLifecycleAdmission({ ready }, { checkpoints.load(ACCOUNT) != null })
            val runner = AccountSyncRunner(backgroundScope) { SyncPassOutcome.SUCCESS }
            val rawCleanup = UnprovisionedAccountSignOut(ACCOUNT, runner,
                ownerIsVerified = { owner }, hasNoAndroidBinding = { store.boundName == null },
                pendingMutations = PendingMutationReader { 0 }, revokeSession = {
                    entered.complete(Unit)
                    resume.await()
                    if (revokeSucceeds) ready = false
                    revokeSucceeds
                }, clearLocalData = { dataPresent = false; true }, releaseOwner = { owner = false },
                ownerIdentity = { if (owner) "synthetic-owner" else null }, hasNoCoreAccount = { !ready },
                isLocalDataEmpty = { !dataPresent }, checkpoints = checkpoints)
            val signOut = AccountSignOutCoordinator(ACCOUNT, PendingMutationReader { 0 }, BoundAndroidAccountReader { null }, runner,
                InAppAccountRemoval { _, _ -> error("No Android account") }, noAndroidRemoval = rawCleanup::remove,
                lifecycleAdmission = gate)
            val provisioning = AndroidAccountProvisioningCoordinator(store, platform)
            val cleanup = async { signOut.signOut(SignOutChoice.DISCARD) }
            entered.await()
            val retry = async {
                gate.authenticated(AccountProvisioningResult.UNAVAILABLE) {
                    provisioning.retryMissing(ACCOUNT) { ADDRESS }.also { reactivations++ }
                }
            }
            runCurrent()
            assertNotNull(checkpoints.load(ACCOUNT))
            assertEquals(0, store.ensureCalls)
            resume.complete(Unit)
            assertEquals(if (revokeSucceeds) SignOutResult.SignedOut else SignOutResult.CleanupFailed, cleanup.await())
            assertEquals(AccountProvisioningResult.UNAVAILABLE, retry.await())
            assertEquals(0, store.ensureCalls)
            assertEquals(0, platform.addCalls)
            assertEquals(0, reactivations)
            assertNull(store.boundName)
            assertEquals(!revokeSucceeds, dataPresent)
        }
    }

    @Test fun systemRemovalUsesTheSameAdmissionAndCannotBeFollowedByStaleReactivation() = runTest {
        var ready = true
        var reactivated = false
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gate = AccountLifecycleAdmission({ ready })
        val systemRemoval = AccountRemovalCoordinator {
            gate.cleanup { entered.complete(Unit); resume.await(); ready = false; true }
        }
        val removal = async { systemRemoval.remove(Account(ADDRESS, ContakoAndroidAccountContract.ACCOUNT_TYPE)) }
        entered.await()
        val reactivation = async { gate.authenticated(false) { reactivated = true; true } }
        runCurrent()
        assertFalse(reactivated)
        resume.complete(Unit)
        assertTrue(removal.await())
        assertFalse(reactivation.await())
    }

    private class Store(private val ensureBarrier: suspend () -> Unit = {}) : AndroidAccountBindingStore {
        var boundName: String? = null
        var ensureCalls = 0
        override suspend fun boundAccountName(scope: AccountScope) = boundName
        override suspend fun ensureAccountRevision(scope: AccountScope): Long { ensureCalls++; ensureBarrier(); return 0 }
        override suspend fun bind(scope: AccountScope, expectedRevision: Long, androidAccountName: String): AndroidAccountBindingResult {
            boundName = androidAccountName
            return AndroidAccountBindingResult.Bound(AndroidProjectionAccountSnapshot(revision = 1, providerEpoch = 0))
        }
        override suspend fun providerEpoch(scope: AccountScope) = 0L
    }

    private class Platform : AndroidAccountPlatform {
        var present = false
        var addCalls = 0
        override fun existingAccountNames() = if (present) listOf(ADDRESS) else emptyList()
        override fun addAccount(account: Account): Boolean { addCalls++; present = true; return true }
        override fun enableContactsSync(account: Account) = Unit
        override fun writeProviderEpochProof(androidAccountName: String, providerEpoch: Long) = true
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-lifecycle-scope")
        const val ADDRESS = "synthetic-lifecycle@example.test"
    }
}
