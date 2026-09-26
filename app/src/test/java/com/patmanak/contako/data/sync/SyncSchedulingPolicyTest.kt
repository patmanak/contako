package com.patmanak.contako.data.sync

import com.patmanak.contako.domain.sync.SyncTrigger
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulingPolicyTest {
    @Test
    fun failedPeriodicRegistrationIsRetriedOnResume() = runTest {
        val fixture = fixture(lastSuccess = 1_000_000)
        fixture.scheduler.failPeriodic = true
        fixture.policy.initialize()
        advanceUntilIdle()
        assertTrue(fixture.scheduler.periodic.isEmpty())
        fixture.scheduler.failPeriodic = false
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertEquals(1, fixture.scheduler.periodic.size)
        assertEquals(1, fixture.policy.metrics().periodicWorkRequests)
    }

    @Test
    fun failedSubmissionRetainsTriggersUntilTheNextNetworkEvent() = runTest {
        val fixture = fixture(lastSuccess = 1_000_000)
        fixture.policy.initialize()
        advanceUntilIdle()
        fixture.scheduler.failRequests = true
        fixture.policy.onMutationCommitted()
        advanceUntilIdle()
        assertEquals(1, fixture.scheduler.requestAttempts)
        assertTrue(fixture.scheduler.requests.isEmpty())
        fixture.scheduler.failRequests = false
        fixture.network.emit(SchedulingNetworkState.OFFLINE)
        fixture.network.emit(SchedulingNetworkState.ONLINE)
        advanceUntilIdle()
        assertEquals(2, fixture.scheduler.requestAttempts)
        assertEquals(setOf(SyncTrigger.MUTATION_COMMITTED, SyncTrigger.CONNECTIVITY_RETURNED),
            fixture.scheduler.requests.single().second)
        assertEquals(1, fixture.policy.metrics().oneShotWorkRequests)
    }

    @Test
    fun failedPendingReadOnResumeDoesNotCrashAndCanRecover() = runTest {
        var failRead = true
        val fixture = fixture(lastSuccess = 1_000_000, hasPendingMutations = {
            if (failRead) throw java.io.IOException("synthetic storage failure")
            true
        })
        fixture.policy.initialize()
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertTrue(fixture.scheduler.requests.isEmpty())
        failRead = false
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertEquals(setOf(SyncTrigger.MUTATION_COMMITTED), fixture.scheduler.requests.single().second)
    }

    @Test
    fun `native dirty intent wakes foreground sync despite fresh success and empty outbox`() = runTest {
        var dirty = false
        val dispatched = mutableListOf<Set<SyncTrigger>>()
        val fixture = fixture(lastSuccess = 1_000_000, foregroundSync = { dispatched += it },
            hasPendingAndroidChanges = { accountName ->
                assertEquals("REDACTED", accountName)
                dirty
            })
        fixture.policy.initialize()
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertTrue(fixture.scheduler.requests.isEmpty())
        dirty = true
        fixture.policy.onForegroundChanged(false)
        repeat(3) { fixture.policy.onForegroundChanged(true) }
        advanceUntilIdle()
        assertEquals(listOf(setOf(SyncTrigger.ANDROID_UPLOAD)), dispatched)
        assertEquals(setOf(SyncTrigger.ANDROID_UPLOAD), fixture.scheduler.requests.single().second)
    }

    @Test
    fun `native dirty probe respects eligibility and retries read failures on later resume`() = runTest {
        var enabled = false
        var reads = 0
        var fail = true
        val dispatched = mutableListOf<Set<SyncTrigger>>()
        val fixture = fixture(lastSuccess = 1_000_000,
            eligibility = { EligibleSyncAccount("REDACTED", enabled) },
            foregroundSync = { dispatched += it },
            hasPendingAndroidChanges = {
                reads++
                if (fail) throw SecurityException("permission unavailable")
                true
            })
        fixture.policy.initialize()
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertEquals(0, reads)
        enabled = true
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertTrue(dispatched.isEmpty())
        fail = false
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertEquals(listOf(setOf(SyncTrigger.ANDROID_UPLOAD)), dispatched)
    }

    @Test
    fun `visible committed edits coalesce and keep durable platform scheduling`() = runTest {
        val dispatched = mutableListOf<Set<SyncTrigger>>()
        val fixture = fixture(foregroundSync = { dispatched += it })
        fixture.policy.initialize()
        fixture.policy.onForegroundChanged(true)
        repeat(20) { fixture.policy.onMutationCommitted() }
        advanceUntilIdle()
        assertEquals(listOf(setOf(SyncTrigger.MUTATION_COMMITTED)), dispatched)
        assertEquals(1, fixture.scheduler.requests.size)
        fixture.policy.onForegroundChanged(false)
        fixture.policy.onMutationCommitted()
        advanceUntilIdle()
        assertEquals(1, dispatched.size)
        assertEquals(2, fixture.scheduler.requests.size)
    }

    @Test
    fun `visible dispatch respects disable offline stop and resumes durable intent`() = runTest {
        var enabled = false
        var dispatched = 0
        val fixture = fixture(eligibility = { EligibleSyncAccount("REDACTED", enabled) },
            network = SchedulingNetworkState.OFFLINE, foregroundSync = { dispatched++ }, hasPendingMutations = { true })
        fixture.policy.initialize()
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertEquals(0, dispatched)
        enabled = true
        fixture.policy.onMutationCommitted()
        advanceUntilIdle()
        assertEquals(0, dispatched)
        fixture.network.emit(SchedulingNetworkState.ONLINE)
        advanceUntilIdle()
        assertEquals(1, dispatched)
        fixture.policy.onForegroundChanged(false)
        fixture.policy.onForegroundChanged(true)
        advanceUntilIdle()
        assertEquals(2, dispatched)
        fixture.policy.onMutationCommitted()
        runCurrent()
        fixture.policy.close()
        advanceUntilIdle()
        assertEquals(2, dispatched)
    }

    @Test
    fun `D129 foreground initial import does not depend on Android automatic sync`() = runTest {
        var starts = 0
        val first = fixture(
            eligibility = { EligibleSyncAccount("REDACTED", false) },
            foregroundFirstImport = { starts++ },
        )
        first.policy.onAccountReady()
        advanceUntilIdle()
        assertEquals(1, starts)
        assertTrue(first.scheduler.requests.isEmpty())
        assertTrue(first.scheduler.periodic.isEmpty())

        val retained = fixture(lastSuccess = 999_999, foregroundFirstImport = { starts++ })
        retained.policy.onAccountReady()
        advanceUntilIdle()
        assertEquals(1, starts)
        assertEquals(1, retained.scheduler.periodic.size)
    }

    @Test
    fun `07-BATTERY 100 edit triggers coalesce into one delayed work request`() = runTest {
        val fixture = fixture()
        fixture.policy.initialize()
        runCurrent()
        List(100) { async { fixture.policy.onMutationCommitted() } }.awaitAll()
        runCurrent()
        advanceTimeBy(1_999)
        assertTrue(fixture.scheduler.requests.isEmpty())
        advanceUntilIdle()

        assertEquals(1, fixture.scheduler.requests.size)
        assertEquals(setOf(SyncTrigger.MUTATION_COMMITTED), fixture.scheduler.requests.single().second)
        val metrics = fixture.policy.metrics()
        assertEquals(1, metrics.periodicWorkRequests)
        assertEquals(1, metrics.oneShotWorkRequests)
        assertEquals(99, metrics.coalescedTriggers)
        assertEquals(1, metrics.delayedRequests)
    }

    @Test
    fun `07-NET startup requests only when last success is older than fifteen minutes`() = runTest {
        val fresh = fixture(now = 1_000_000, lastSuccess = 100_001)
        fresh.policy.onAppStartup()
        fresh.policy.onAppStartup()
        advanceUntilIdle()
        assertEquals(0, fresh.scheduler.requests.size)
        assertEquals(listOf("REDACTED" to 3_600L), fresh.scheduler.periodic)

        val stale = fixture(now = 1_000_002, lastSuccess = 100_001)
        stale.policy.onAppStartup()
        advanceUntilIdle()
        assertEquals(setOf(SyncTrigger.STARTUP_STALE_CHECK), stale.scheduler.requests.single().second)
    }

    @Test
    fun `07-NET first import is reconsidered after account provisioning`() = runTest {
        var eligibility: EligibleSyncAccount? = null
        val fixture = fixture(eligibility = { eligibility })

        fixture.policy.onAppStartup()
        advanceUntilIdle()
        assertTrue(fixture.scheduler.requests.isEmpty())

        eligibility = EligibleSyncAccount("REDACTED", true)
        fixture.policy.onAccountReady()
        fixture.policy.onAccountReady()
        advanceUntilIdle()

        assertEquals(listOf("REDACTED" to 3_600L), fixture.scheduler.periodic)
        assertEquals(1, fixture.scheduler.requests.size)
        assertEquals(setOf(SyncTrigger.FIRST_IMPORT), fixture.scheduler.requests.single().second)
    }

    @Test
    fun `07-NET account ready respects recent success and disabled automatic sync`() = runTest {
        val recent = fixture(lastSuccess = 999_999)
        recent.policy.onAccountReady()
        advanceUntilIdle()
        assertTrue(recent.scheduler.requests.isEmpty())

        val disabled = fixture(
            lastSuccess = null,
            eligibility = { EligibleSyncAccount("REDACTED", false) },
        )
        disabled.policy.onAccountReady()
        advanceUntilIdle()
        assertTrue(disabled.scheduler.periodic.isEmpty())
        assertTrue(disabled.scheduler.requests.isEmpty())
    }

    @Test
    fun `07-NET account ready restarts scheduling after account removal`() = runTest {
        val fixture = fixture()
        fixture.policy.initialize()
        runCurrent()
        fixture.policy.onMutationCommitted()
        runCurrent()
        fixture.policy.close()

        fixture.policy.onAccountReady()
        advanceUntilIdle()

        assertEquals(2, fixture.network.observerRegistrations)
        assertEquals(
            listOf("REDACTED" to 3_600L, "REDACTED" to 3_600L),
            fixture.scheduler.periodic,
        )
        assertEquals(setOf(SyncTrigger.FIRST_IMPORT), fixture.scheduler.requests.single().second)
    }

    @Test
    fun `07-NET connectivity return is transition based and rate limited`() = runTest {
        val fixture = fixture(network = SchedulingNetworkState.OFFLINE)
        fixture.policy.initialize()
        runCurrent()
        fixture.network.emit(SchedulingNetworkState.ONLINE)
        runCurrent()
        fixture.network.emit(SchedulingNetworkState.ONLINE)
        fixture.network.emit(SchedulingNetworkState.OFFLINE)
        fixture.network.emit(SchedulingNetworkState.ONLINE)
        runCurrent()
        advanceTimeBy(4_999)
        assertEquals(1, fixture.scheduler.requests.size)
        advanceUntilIdle()

        assertEquals(2, fixture.scheduler.requests.size)
        assertTrue(fixture.scheduler.requests.all { it.second == setOf(SyncTrigger.CONNECTIVITY_RETURNED) })
        assertEquals(3, fixture.policy.metrics().networkTransitions)
        assertEquals(1, fixture.policy.metrics().delayedRequests)
    }

    @Test
    fun `07-NET invalid account or disabled switch submits no automatic work`() = runTest {
        listOf(null, EligibleSyncAccount("REDACTED", false)).forEach { eligibility ->
            val fixture = fixture(eligibility = { eligibility }, network = SchedulingNetworkState.OFFLINE)
            fixture.policy.initialize()
            fixture.policy.onMutationCommitted()
            fixture.network.emit(SchedulingNetworkState.ONLINE)
            advanceUntilIdle()
            assertTrue(fixture.scheduler.periodic.isEmpty())
            assertTrue(fixture.scheduler.requests.isEmpty())
        }
    }

    @Test
    fun `07-NET callback from a closed subscription cannot enqueue into a later lifecycle`() = runTest {
        val fixture = fixture(
            lastSuccess = 1_000_000,
            network = SchedulingNetworkState.OFFLINE,
        )
        fixture.policy.initialize()
        runCurrent()
        fixture.policy.close()
        fixture.policy.onAccountReady()
        runCurrent()

        fixture.network.emitClosedGeneration(SchedulingNetworkState.ONLINE)
        advanceUntilIdle()

        assertTrue(fixture.scheduler.requests.isEmpty())
        assertEquals(0, fixture.policy.metrics().networkTransitions)
    }

    @Test
    fun `07-NET eligibility result from a closed lifecycle cannot schedule the next lifecycle`() = runTest {
        val firstLookupStarted = CompletableDeferred<Unit>()
        val releaseFirstLookup = CompletableDeferred<Unit>()
        val lookups = AtomicInteger()
        val fixture = fixture(
            lastSuccess = 1_000_000,
            eligibility = {
                if (lookups.getAndIncrement() == 0) {
                    firstLookupStarted.complete(Unit)
                    releaseFirstLookup.await()
                    EligibleSyncAccount("REDACTED-OLD", true)
                } else {
                    EligibleSyncAccount("REDACTED-NEW", true)
                }
            },
        )

        fixture.policy.initialize()
        firstLookupStarted.await()
        fixture.policy.close()
        fixture.policy.onAccountReady()
        runCurrent()
        releaseFirstLookup.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("REDACTED-NEW" to 3_600L), fixture.scheduler.periodic)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        now: Long = 1_000_000,
        lastSuccess: Long? = null,
        eligibility: suspend () -> EligibleSyncAccount? = { EligibleSyncAccount("REDACTED", true) },
        network: SchedulingNetworkState = SchedulingNetworkState.ONLINE,
        foregroundFirstImport: (suspend () -> Unit)? = null,
        foregroundSync: (suspend (Set<SyncTrigger>) -> Unit)? = null,
        hasPendingMutations: suspend () -> Boolean = { false },
        hasPendingAndroidChanges: suspend (String) -> Boolean = { false },
    ): Fixture {
        val clock = FakeClock(now)
        val scheduler = FakeScheduler()
        val networkMonitor = FakeNetworkMonitor(network)
        return Fixture(
            SyncSchedulingPolicy(
                scope = this,
                clock = clock,
                accountEligibility = SyncAccountEligibility { eligibility() },
                lastSuccessfulSyncReader = LastSuccessfulSyncReader { lastSuccess },
                scheduler = scheduler,
                networkMonitor = networkMonitor,
                foregroundFirstImport = foregroundFirstImport,
                foregroundSync = foregroundSync,
                hasPendingMutations = hasPendingMutations,
                hasPendingAndroidChanges = hasPendingAndroidChanges,
                delayMillis = {
                    kotlinx.coroutines.delay(it)
                    clock.now += it
                },
            ),
            scheduler,
            networkMonitor,
        )
    }

    private data class Fixture(
        val policy: SyncSchedulingPolicy,
        val scheduler: FakeScheduler,
        val network: FakeNetworkMonitor,
    )

    private class FakeClock(var now: Long) : SchedulingClock {
        override fun nowEpochMillis(): Long = now
    }

    private class FakeScheduler : SyncWorkScheduler {
        var failPeriodic = false
        var failRequests = false
        var requestAttempts = 0
        val periodic = mutableListOf<Pair<String, Long>>()
        val requests = mutableListOf<Pair<String, Set<SyncTrigger>>>()
        override fun ensurePeriodic(accountName: String, intervalSeconds: Long) {
            if (failPeriodic) throw java.io.IOException("synthetic scheduler failure")
            periodic += accountName to intervalSeconds
        }
        override fun request(accountName: String, triggers: Set<SyncTrigger>) {
            requestAttempts++
            if (failRequests) throw java.io.IOException("synthetic scheduler failure")
            requests += accountName to triggers
        }
    }

    private class FakeNetworkMonitor(private var state: SchedulingNetworkState) : SchedulingNetworkMonitor {
        private var listener: ((SchedulingNetworkState) -> Unit)? = null
        private val registeredListeners = mutableListOf<(SchedulingNetworkState) -> Unit>()
        var observerRegistrations = 0
            private set
        override fun current(): SchedulingNetworkState = state
        override fun observe(listener: (SchedulingNetworkState) -> Unit): Closeable {
            observerRegistrations++
            this.listener = listener
            registeredListeners += listener
            return Closeable { this.listener = null }
        }
        fun emit(next: SchedulingNetworkState) {
            state = next
            listener?.invoke(next)
        }
        fun emitClosedGeneration(next: SchedulingNetworkState) {
            state = next
            registeredListeners.firstOrNull()?.invoke(next)
        }
    }
}
