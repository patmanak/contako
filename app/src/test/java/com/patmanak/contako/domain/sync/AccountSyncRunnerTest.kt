package com.patmanak.contako.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSyncRunnerTest {
    @Test
    fun `03-SINGLE trigger storm runs one pass and at most one coalesced follow up`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val requests = mutableListOf<SyncPassRequest>()
        val runner = AccountSyncRunner(backgroundScope) { request ->
            requests += request
            if (requests.size == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            SyncPassOutcome.SUCCESS
        }

        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.MANUAL))
        firstStarted.await()
        val dispositions = SyncTrigger.entries.map { trigger ->
            async { runner.request(trigger) }
        }.awaitAll()
        assertTrue(dispositions.all { it == SyncRequestDisposition.COALESCED })
        releaseFirst.complete(Unit)
        runner.awaitIdle()

        assertEquals(2, requests.size)
        assertEquals(setOf(SyncTrigger.MANUAL), requests.first().triggers)
        assertEquals(SyncTrigger.entries.toSet(), requests.last().triggers)
    }

    @Test
    fun `03-SINGLE full repair upgrades only the follow up scope`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<SyncPassRequest>()
        val runner = AccountSyncRunner(backgroundScope) { request ->
            requests += request
            if (requests.size == 1) {
                started.complete(Unit)
                release.await()
            }
            SyncPassOutcome.SUCCESS
        }

        runner.request(SyncTrigger.ANDROID_PERIODIC)
        started.await()
        runner.request(SyncTrigger.MANUAL, SyncScope.FULL_REPAIR)
        runner.request(SyncTrigger.CONNECTIVITY_RETURNED)
        release.complete(Unit)
        runner.awaitIdle()

        assertEquals(SyncScope.INCREMENTAL, requests[0].scope)
        assertEquals(SyncScope.FULL_REPAIR, requests[1].scope)
        assertEquals(setOf(SyncTrigger.MANUAL, SyncTrigger.CONNECTIVITY_RETURNED), requests[1].triggers)
    }

    @Test
    fun `03-CANCEL caller cancellation does not own the pass`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completed = false
        val runner = AccountSyncRunner(backgroundScope) {
            started.complete(Unit)
            release.await()
            completed = true
            SyncPassOutcome.SUCCESS
        }

        val caller = async { runner.request(SyncTrigger.MANUAL) }
        started.await()
        caller.cancel()
        release.complete(Unit)
        runner.awaitIdle()

        assertTrue(completed)
    }

    @Test
    fun `03-CANCEL account removal invalidates active generation and rejects future work`() = runTest {
        val observedCancellation = CompletableDeferred<Boolean>()
        val enterBoundary = CompletableDeferred<Unit>()
        val runner = AccountSyncRunner(backgroundScope) { request ->
            enterBoundary.complete(Unit)
            while (!request.isAccountCancellationRequested()) yield()
            observedCancellation.complete(request.isAccountCancellationRequested())
            SyncPassOutcome.CANCELLED
        }

        runner.request(SyncTrigger.RETRY)
        enterBoundary.await()
        runner.cancelForAccountRemoval()
        assertTrue(observedCancellation.await())
        runner.awaitIdle()
        assertEquals(
            SyncRequestDisposition.ACCOUNT_INVALIDATED,
            runner.request(SyncTrigger.CONNECTIVITY_RETURNED),
        )
    }

    @Test
    fun `03-CANCEL successful reprovisioning opens a fresh runner generation`() = runTest {
        var executions = 0
        val runner = AccountSyncRunner(backgroundScope) {
            executions++
            SyncPassOutcome.SUCCESS
        }

        runner.cancelForAccountRemoval()
        assertEquals(
            SyncRequestDisposition.ACCOUNT_INVALIDATED,
            runner.request(SyncTrigger.FIRST_IMPORT),
        )

        runner.reactivateAfterAccountProvisioning()
        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.FIRST_IMPORT))
        runner.awaitIdle()

        assertEquals(1, executions)
        assertEquals(SyncPassOutcome.SUCCESS, runner.lastCompletion.value?.outcome)
    }

    @Test
    fun `07-CANCEL full repair cancellation is shared without invalidating ordinary sync`() = runTest {
        val repairCancelled = CompletableDeferred<Unit>()
        val requests = mutableListOf<SyncPassRequest>()
        val runner = AccountSyncRunner(backgroundScope) { request ->
            requests += request
            if (request.scope == SyncScope.FULL_REPAIR) {
                while (!request.isCancellationRequested()) yield()
                repairCancelled.complete(Unit)
                SyncPassOutcome.CANCELLED
            } else {
                SyncPassOutcome.SUCCESS
            }
        }

        runner.request(SyncTrigger.MANUAL, SyncScope.FULL_REPAIR)
        while (requests.isEmpty()) yield()
        runner.cancelFullRepair()
        repairCancelled.await()
        runner.awaitIdle()
        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.MANUAL))
        runner.awaitIdle()

        assertEquals(listOf(SyncScope.FULL_REPAIR, SyncScope.INCREMENTAL), requests.map { it.scope })
    }

    @Test
    fun `03-STATUS executor failure is sanitized and runner returns idle`() = runTest {
        val runner = AccountSyncRunner(backgroundScope) { error("PRIVATE_REMOTE_TEXT") }

        runner.request(SyncTrigger.STARTUP_STALE_CHECK)
        runner.awaitIdle()
        val completed = requireNotNull(runner.lastCompletion.value)

        assertEquals(SyncPassOutcome.FAILED, completed.outcome)
        assertFalse(completed.toString().contains("PRIVATE_REMOTE_TEXT"))
    }
}
