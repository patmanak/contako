package com.patmanak.contako.android.account

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncRequestDisposition
import com.patmanak.contako.domain.sync.SyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnprovisionedAccountSignOutTest {
    @Test fun pendingIntentIsRetainedUnlessTheUserExplicitlyDiscardsIt() = runTest {
        val fixture = fixture().apply { pending = 3 }
        assertFalse(fixture.cleanup.remove(false))
        assertEquals(3, fixture.pending)
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.cleanup.remove(true))
        assertEquals(0, fixture.pending)
        assertFalse(fixture.owner)
        assertEquals(listOf("stop", "revoke-session", "commit-local:true", "release-owner"), fixture.events)
        assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, fixture.runner.request(SyncTrigger.RETRY))
    }

    @Test fun foreignOwnerOrAnyAndroidBindingRefusesAllCleanup() = runTest {
        val foreign = fixture().apply { owner = false }
        assertFalse(foreign.cleanup.remove(true))
        assertTrue(foreign.events.isEmpty())
        assertTrue(foreign.localDataPresent)
        val provisioned = fixture().apply { noAndroidBinding = false }
        assertFalse(provisioned.cleanup.remove(true))
        assertTrue(provisioned.events.isEmpty())
        assertTrue(provisioned.localDataPresent)
    }

    @Test fun bindingAppearingDuringRunnerStopRestoresRunnerAndDoesNotTouchSession() = runTest {
        val fixture = fixture()
        var probes = 0
        fixture.bindingProbe = { ++probes == 1 }
        assertFalse(fixture.cleanup.remove(false))
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.sessionPresent)
        assertEquals(SyncRequestDisposition.STARTED, fixture.runner.request(SyncTrigger.RETRY))
    }

    @Test fun newMutationAtAtomicDeletionBoundaryCannotBeDiscardedByAnEarlierZeroCount() = runTest {
        val fixture = fixture().apply { injectPendingAtCommit = true }
        assertFalse(fixture.cleanup.remove(false))
        assertEquals(1, fixture.pending)
        assertTrue(fixture.owner)
        assertTrue(fixture.localDataPresent)
        assertFalse(fixture.events.contains("release-owner"))
        assertTrue(fixture.cleanup.remove(true))
        assertEquals(0, fixture.pending)
        assertFalse(fixture.localDataPresent)
    }

    @Test fun localCleanupFailureCanBeRetriedAfterCoreSessionWasAlreadyRemoved() = runTest {
        val fixture = fixture().apply { failCommitOnce = true }
        assertFalse(fixture.cleanup.remove(false))
        assertFalse(fixture.sessionPresent)
        assertTrue(fixture.owner)
        assertTrue(fixture.localDataPresent)
        assertTrue(fixture.cleanup.remove(false))
        assertFalse(fixture.owner)
        assertFalse(fixture.localDataPresent)
        assertEquals(1, fixture.events.count { it == "revoke-session" })
        assertEquals(1, fixture.events.count { it == "release-owner" })
    }

    @Test fun cancellationBeforeDestructiveCleanupPreservesIntentAndRestoresRunner() = runTest {
        val passStarted = CompletableDeferred<Unit>()
        val finishPass = CompletableDeferred<Unit>()
        val runner = AccountSyncRunner(backgroundScope) { passStarted.complete(Unit); finishPass.await(); SyncPassOutcome.SUCCESS }
        val fixture = fixture(runner)
        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.MANUAL))
        runCurrent()
        assertTrue(passStarted.isCompleted)
        val operation = async { fixture.cleanup.remove(false) }
        runCurrent()
        operation.cancel()
        finishPass.complete(Unit)
        runCurrent()
        assertTrue(operation.isCancelled)
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.owner)
        assertTrue(fixture.localDataPresent)
        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.RETRY))
    }

    @Test fun cancellationAfterSessionCleanupStartsCannotSplitCommittedDataFromOwnerRelease() = runTest {
        val fixture = fixture()
        val finishRevocation = CompletableDeferred<Unit>()
        fixture.revokeBarrier = { finishRevocation.await() }
        val operation = async { fixture.cleanup.remove(false) }
        runCurrent()
        assertEquals(listOf("stop", "revoke-session"), fixture.events)
        operation.cancel()
        finishRevocation.complete(Unit)
        runCurrent()
        assertFalse(fixture.owner)
        assertFalse(fixture.localDataPresent)
        assertEquals(listOf("stop", "revoke-session", "commit-local:false", "release-owner"), fixture.events)
    }

    @Test fun failedLocalCleanupSurvivesCoordinatorAndRunnerReconstruction() = runTest {
        val fixture = fixture().apply { failCommitOnce = true }
        assertFalse(fixture.cleanup.remove(false))
        assertEquals(UnprovisionedRemovalPhase.LOCAL_PENDING, fixture.checkpoints.load(ACCOUNT)?.phase)
        assertFalse(fixture.sessionPresent)
        val freshRunner = AccountSyncRunner(backgroundScope) { SyncPassOutcome.SUCCESS }
        assertTrue(fixture.newCleanup(freshRunner).remove(false))
        assertFalse(fixture.owner)
        assertNull(fixture.checkpoints.load(ACCOUNT))
        assertEquals(1, fixture.events.count { it == "revoke-session" })
        assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, freshRunner.request(SyncTrigger.RETRY))
    }

    @Test fun crashAfterOwnerReleaseOnlyFinalizesMarkerWithoutRepeatingAnyDeletion() = runTest {
        val fixture = fixture().apply { failCheckpointClearOnce = true }
        assertFalse(fixture.cleanup.remove(false))
        assertFalse(fixture.owner)
        assertEquals(UnprovisionedRemovalPhase.OWNER_PENDING, fixture.checkpoints.load(ACCOUNT)?.phase)
        val fresh = fixture.newCleanup(AccountSyncRunner(backgroundScope) { SyncPassOutcome.SUCCESS })
        assertTrue(fresh.remove(false))
        assertNull(fixture.checkpoints.load(ACCOUNT))
        assertEquals(1, fixture.events.count { it == "revoke-session" })
        assertEquals(1, fixture.events.count { it == "commit-local:false" })
        assertEquals(1, fixture.events.count { it == "release-owner" })
    }

    @Test fun finalizationCannotTouchANewOwnerSessionOrLocalData() = runTest {
        for (newState in listOf("owner", "session", "data")) {
            val fixture = fixture().apply { failCheckpointClearOnce = true }
            assertFalse(fixture.cleanup.remove(false))
            when (newState) {
                "owner" -> { fixture.owner = true; fixture.ownerName = "different-owner" }
                "session" -> fixture.sessionPresent = true
                "data" -> fixture.localDataPresent = true
            }
            val eventsBefore = fixture.events.toList()
            assertFalse(fixture.newCleanup(AccountSyncRunner(backgroundScope) { SyncPassOutcome.SUCCESS }).remove(true))
            assertEquals(eventsBefore, fixture.events)
            assertNotNull(fixture.checkpoints.load(ACCOUNT))
        }
    }

    private fun TestScope.fixture(runner: AccountSyncRunner = AccountSyncRunner(backgroundScope) { SyncPassOutcome.SUCCESS }) =
        Fixture(runner)

    private class Fixture(val runner: AccountSyncRunner) {
        var pending = 0
        var owner = true
        var ownerName = "synthetic-owner"
        var noAndroidBinding = true
        var localDataPresent = true
        var sessionPresent = true
        var injectPendingAtCommit = false
        var failCommitOnce = false
        var failCheckpointClearOnce = false
        var bindingProbe: suspend () -> Boolean = { noAndroidBinding }
        var revokeBarrier: suspend () -> Unit = {}
        val events = mutableListOf<String>()
        val checkpoints = object : UnprovisionedRemovalCheckpointStore {
            private val memory = UnprovisionedRemovalCheckpointStore.Memory()
            override fun load(scope: AccountScope) = memory.load(scope)
            override fun save(scope: AccountScope, checkpoint: UnprovisionedRemovalCheckpoint) = memory.save(scope, checkpoint)
            override fun clear(scope: AccountScope) {
                if (failCheckpointClearOnce) { failCheckpointClearOnce = false; error("Synthetic marker failure") }
                memory.clear(scope)
            }
        }
        // There is deliberately no provider or AccountManager fake: this path cannot call one.
        val cleanup = newCleanup(runner)
        fun newCleanup(activeRunner: AccountSyncRunner) = UnprovisionedAccountSignOut(ACCOUNT, activeRunner,
            ownerIsVerified = { owner },
            hasNoAndroidBinding = { bindingProbe() },
            pendingMutations = PendingMutationReader { pending },
            revokeSession = {
                events += "revoke-session"
                revokeBarrier()
                sessionPresent = false
                true
            },
            clearLocalData = { discard ->
                if (injectPendingAtCommit) { pending = 1; injectPendingAtCommit = false }
                if (failCommitOnce) { failCommitOnce = false; false }
                else if (!noAndroidBinding || (!discard && pending != 0)) false
                else { events += "commit-local:$discard"; localDataPresent = false; pending = 0; true }
            },
            releaseOwner = {
                check(!localDataPresent && pending == 0)
                events += "release-owner"
                owner = false
            },
            ownerIdentity = { if (owner) ownerName else null },
            hasNoCoreAccount = { !sessionPresent },
            isLocalDataEmpty = { !localDataPresent && pending == 0 },
            checkpoints = checkpoints,
            stopScheduling = { events += "stop" },
        )
    }
    private companion object { val ACCOUNT = AccountScope("synthetic-owner-scope") }
}
