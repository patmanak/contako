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
    @Test fun persistedCleanupClosesAdmissionBeforeAnyStartupCoroutineRuns() = runTest {
        var executions = 0
        val runner = AccountSyncRunner(backgroundScope, initiallyInvalidated = true) {
            executions++
            SyncPassOutcome.SUCCESS
        }
        // No scheduler advancement or asynchronous cancellation is needed to reject the first
        // SyncAdapter/worker request against a newly constructed process runtime.
        for (trigger in SyncTrigger.entries) {
            assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, runner.request(trigger))
        }
        assertEquals(0, executions)
        assertEquals(SyncRunState.Idle, runner.state.value)
        runner.reactivateAfterAccountProvisioning()
        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.FIRST_IMPORT))
        runner.awaitIdle()
        assertEquals(1, executions)
    }

    @Test fun completionRetainsClosedFailureCauseAndSuccessClearsIt() = runTest {
        var outcome = SyncPassOutcome.ACTION_REQUIRED
        val executor = object : SyncPassExecutor, SyncPassCompletionDetailsSource {
            override var completionCause: SyncCompletionCause? = SyncCompletionCause.VALIDATION
            override suspend fun execute(request: SyncPassRequest) = outcome
        }
        val runner = AccountSyncRunner(backgroundScope, executor)
        for (cause in SyncCompletionCause.entries) {
            executor.completionCause = cause
            runner.request(SyncTrigger.MANUAL)
            runner.awaitIdle()
            assertEquals(cause, runner.lastCompletion.value?.cause)
        }
        outcome = SyncPassOutcome.SUCCESS
        // Even a defective executor retaining old metadata cannot contaminate success.
        runner.request(SyncTrigger.MANUAL)
        runner.awaitIdle()
        assertEquals(null, runner.lastCompletion.value?.cause)
    }

    @Test fun thrownIoIsRetryableButUnknownExceptionsAreNotClaimedToBeDatabaseFailures() = runTest {
        for (io in listOf(false, true)) {
            val runner = AccountSyncRunner(backgroundScope) {
                if (io) throw java.io.IOException("PRIVATE_NETWORK_TEXT")
                else error("PRIVATE_UNCLASSIFIED_TEXT")
            }
            runner.request(SyncTrigger.MANUAL)
            runner.awaitIdle()
            val completed = requireNotNull(runner.lastCompletion.value)
            assertEquals(if (io) SyncPassOutcome.RETRY_WAITING else SyncPassOutcome.FAILED, completed.outcome)
            assertEquals(if (io) SyncCompletionCause.TRANSIENT_IO else SyncCompletionCause.UNKNOWN, completed.cause)
            assertFalse(completed.toString().contains("PRIVATE"))
        }
    }

    @Test fun completionDetailsFailureCannotBreakTheRunner() = runTest {
        val runner = AccountSyncRunner(backgroundScope, object : SyncPassExecutor, SyncPassCompletionDetailsSource {
            override val completionCause: SyncCompletionCause? get() = error("PRIVATE")
            override suspend fun execute(request: SyncPassRequest) = SyncPassOutcome.ACTION_REQUIRED
        })
        runner.request(SyncTrigger.MANUAL)
        runner.awaitIdle()
        assertEquals(SyncCompletionCause.UNKNOWN, runner.lastCompletion.value?.cause)
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, runner.lastCompletion.value?.outcome)
    }

    @Test fun diagnosticObserversDescribeRequestsAndCannotChangeScheduling() = runTest {
        val events = mutableListOf<String>()
        val observer = object : SyncRunnerObserver {
            override fun onRequest(trigger: SyncTrigger, scope: SyncScope, disposition: SyncRequestDisposition) {
                events += "${trigger.name}:${disposition.name}"
                error("observer only")
            }
            override fun onStart(scope: SyncScope) { events += "start:${scope.name}"; error("observer only") }
            override fun onFinish(outcome: SyncPassOutcome) { events += "finish:${outcome.name}"; error("observer only") }
        }
        val runner = AccountSyncRunner(backgroundScope, observer) { SyncPassOutcome.SUCCESS }
        assertEquals(SyncRequestDisposition.STARTED, runner.request(SyncTrigger.ANDROID_UPLOAD))
        runner.awaitIdle()
        runner.cancelForAccountRemoval()
        assertEquals(SyncRequestDisposition.ACCOUNT_INVALIDATED, runner.request(SyncTrigger.MANUAL))
        assertEquals(listOf("ANDROID_UPLOAD:STARTED", "start:INCREMENTAL", "finish:SUCCESS", "MANUAL:ACCOUNT_INVALIDATED"), events)
    }

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
