package com.patmanak.contako.android.account

import android.accounts.Account
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSignOutCoordinatorTest {
    @Test fun `sync now observes native work even when the initial count is zero`() = runTest {
        val fixture = fixture(pending = 0)
        assertEquals(SignOutResult.SignedOut, fixture.coordinator.signOut(SignOutChoice.SYNC_NOW))
        assertEquals(1, fixture.syncCalls)
    }

    @Test fun `unavailable observation blocks confirmation but explicit discard remains possible`() = runTest {
        val fixture = fixture(pending = 0, observationFails = true)
        assertEquals(SignOutResult.PendingChanges, fixture.coordinator.signOut(SignOutChoice.CONFIRM))
        assertTrue(fixture.events.isEmpty())
        assertEquals(SignOutResult.SignedOut, fixture.coordinator.signOut(SignOutChoice.DISCARD))
        assertEquals(0, fixture.syncCalls)
    }

    @Test fun `pending confirmation and cancel path do not mutate anything`() = runTest {
        val fixture = fixture(pending = 1)

        assertEquals(SignOutResult.PendingChanges, fixture.coordinator.signOut(SignOutChoice.CONFIRM))
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `sync failure stays connected and preserves local state`() = runTest {
        val fixture = fixture(pending = 1, syncOutcome = SyncPassOutcome.FAILED)

        assertEquals(SignOutResult.SyncFailed, fixture.coordinator.signOut(SignOutChoice.SYNC_NOW))
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `sync success and no pending run scoped cleanup without remote delete`() = runTest {
        val fixture = fixture(pending = 1, clearPendingAfterSync = true)

        assertEquals(SignOutResult.SignedOut, fixture.coordinator.signOut(SignOutChoice.SYNC_NOW))
        assertEquals(listOf("remove"), fixture.events)
        assertFalse(fixture.events.any { it.contains("remote", ignoreCase = true) })
    }

    @Test fun `discard skips remote sync and requires the explicit destructive choice`() = runTest {
        val fixture = fixture(pending = 2)

        assertEquals(SignOutResult.SignedOut, fixture.coordinator.signOut(SignOutChoice.DISCARD))
        assertEquals(0, fixture.syncCalls)
    }

    @Test fun `partial cleanup resumes after checkpoint without repeating session cleanup`() = runTest {
        val fixture = fixture(pending = 0, failProviderOnce = true)

        assertEquals(SignOutResult.CleanupFailed, fixture.coordinator.signOut(SignOutChoice.CONFIRM))
        assertEquals(SignOutResult.SignedOut, fixture.coordinator.signOut(SignOutChoice.CONFIRM))
        assertEquals(2, fixture.events.count { it == "remove" })
    }

    private fun TestScope.fixture(
        pending: Int,
        syncOutcome: SyncPassOutcome = SyncPassOutcome.SUCCESS,
        clearPendingAfterSync: Boolean = false,
        failProviderOnce: Boolean = false,
        observationFails: Boolean = false,
    ): Fixture {
        val scope = AccountScope("scope")
        val account = Account("synthetic", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        val events = mutableListOf<String>()
        var pendingCount = pending
        var syncCalls = 0
        var removalCalls = 0
        val runner = AccountSyncRunner(backgroundScope) {
            syncCalls++
            if (clearPendingAfterSync) pendingCount = 0
            syncOutcome
        }
        val removal = InAppAccountRemoval { _, discard ->
            if (observationFails) assertTrue(discard)
            events += "remove"
            removalCalls++
            !failProviderOnce || removalCalls > 1
        }
        val coordinator = AccountSignOutCoordinator(
            scope,
            PendingMutationReader { if (observationFails) error("Provider unavailable") else pendingCount },
            BoundAndroidAccountReader { account },
            runner,
            removal,
        )
        return Fixture(coordinator, events) { syncCalls }
    }

    private class Fixture(
        val coordinator: AccountSignOutCoordinator,
        val events: MutableList<String>,
        private val syncCallsReader: () -> Int,
    ) {
        val syncCalls get() = syncCallsReader()
    }
}
