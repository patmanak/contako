package com.patmanak.contako.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetedRecoveryWakeupTest {
    @Test fun futureDeadlineAndRestartResumeWithoutEarlyCallsOrATightRetryLoop() = runTest {
        val next = MutableStateFlow<Long?>(60_000)
        val stopped = MutableStateFlow(false)
        var calls = 0
        fun start() = backgroundScope.launch {
            watchTargetedRecoveryWakeups(next, stopped, clock = { testScheduler.currentTime }) { calls++ }
        }
        val first = start()
        runCurrent(); advanceTimeBy(30_000); runCurrent(); assertEquals(0, calls)
        first.cancel()
        start()
        runCurrent(); advanceTimeBy(29_999); runCurrent(); assertEquals(0, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(500_000); runCurrent(); assertEquals(1, calls)
    }

    @Test fun invalidationCancelsTheWaitAndReadmissionRechecksTheDurableDeadline() = runTest {
        val next = MutableStateFlow<Long?>(1_000)
        val stopped = MutableStateFlow(false)
        var calls = 0
        backgroundScope.launch {
            watchTargetedRecoveryWakeups(next, stopped, clock = { testScheduler.currentTime }) { calls++ }
        }
        runCurrent(); stopped.value = true; runCurrent()
        advanceTimeBy(2_000); runCurrent(); assertEquals(0, calls)
        stopped.value = false; runCurrent(); assertEquals(1, calls)
        next.value = null; runCurrent(); advanceTimeBy(10_000); runCurrent(); assertEquals(1, calls)
    }

    @Test fun durableProgressCanWakeAnotherBoundedBatchEvenWhenTheMinimumDeadlineIsUnchanged() = runTest {
        val next = MutableSharedFlow<Long?>(replay = 1, extraBufferCapacity = 1)
        next.tryEmit(0)
        var remaining = 25
        var passes = 0
        backgroundScope.launch {
            watchTargetedRecoveryWakeups(next, MutableStateFlow(false), clock = { testScheduler.currentTime }) {
                passes++
                remaining = (remaining - 10).coerceAtLeast(0)
                next.emit(if (remaining == 0) null else 0)
            }
        }
        runCurrent()
        assertEquals(3, passes)
        assertEquals(0, remaining)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, passes)
    }
}
