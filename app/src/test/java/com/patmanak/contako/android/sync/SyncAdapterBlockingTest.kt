package com.patmanak.contako.android.sync

import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncTrigger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SyncAdapterBlockingTest {
    @Test fun interruptionReturnsWithoutCrashingOrCancellingSharedPass() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val passCancelled = AtomicBoolean(false)
        val runner = AccountSyncRunner(scope) {
            started.countDown()
            try {
                release.await()
                SyncPassOutcome.SUCCESS
            } finally {
                if (!release.isCompleted) passCancelled.set(true)
            }
        }
        val escaped = AtomicReference<Throwable?>()
        val interruptedOnReturn = AtomicBoolean(false)
        val outcomePublished = AtomicBoolean(false)
        val waiter = Thread {
            runSyncAdapterBlocking {
                runner.request(SyncTrigger.ANDROID_PERIODIC)
                runner.awaitIdle()
                outcomePublished.set(true)
            }
            interruptedOnReturn.set(Thread.currentThread().isInterrupted)
        }.apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> escaped.set(error) } }
        try {
            waiter.start()
            assertTrue("Shared pass did not start", started.await(5, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(5_000)
            assertFalse("Framework waiter did not stop", waiter.isAlive)
            assertNull(escaped.get())
            assertTrue(interruptedOnReturn.get())
            assertFalse("Cancellation must not publish a result/retry", outcomePublished.get())
            assertFalse(passCancelled.get())
            assertNull(runner.lastCompletion.value)

            release.complete(Unit)
            runBlocking { withTimeout(5_000) { runner.awaitIdle() } }
            assertEquals(SyncPassOutcome.SUCCESS, runner.lastCompletion.value?.outcome)
        } finally {
            release.complete(Unit)
            waiter.interrupt()
            waiter.join(5_000)
            scope.cancel()
        }
    }

    @Test fun normalCompletionStillPublishesOutcome() {
        var completed = false
        runSyncAdapterBlocking { completed = true }
        assertTrue(completed)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun unrelatedFailureIsNotSwallowed() {
        val failure = IllegalStateException("synthetic failure")
        val observed = assertThrows(IllegalStateException::class.java) {
            runSyncAdapterBlocking { throw failure }
        }
        assertSame(failure, observed)
    }
}
