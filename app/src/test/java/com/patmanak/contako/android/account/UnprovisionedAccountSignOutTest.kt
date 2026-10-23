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
        assertEquals(2, fixture.events.count { it == "revoke-session" })
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

    private fun TestScope.fixture(runner: AccountSyncRunner = AccountSyncRunner(backgroundScope) { SyncPassOutcome.SUCCESS }) =
        Fixture(runner)

    private class Fixture(val runner: AccountSyncRunner) {
        var pending = 0
        var owner = true
        var noAndroidBinding = true
        var localDataPresent = true
        var sessionPresent = true
        var injectPendingAtCommit = false
        var failCommitOnce = false
        var bindingProbe: suspend () -> Boolean = { noAndroidBinding }
        var revokeBarrier: suspend () -> Unit = {}
        val events = mutableListOf<String>()
        // There is deliberately no provider or AccountManager fake: this path cannot call one.
        val cleanup = UnprovisionedAccountSignOut(AccountScope("synthetic-owner-scope"), runner,
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
            stopScheduling = { events += "stop" },
        )
    }
}
