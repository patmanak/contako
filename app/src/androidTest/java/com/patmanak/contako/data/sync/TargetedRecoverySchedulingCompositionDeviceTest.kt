package com.patmanak.contako.data.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactInventoryCheckpointStore
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomMutationExecutionStore
import com.patmanak.contako.data.local.RoomRemoteCanonicalReconciliationStore
import com.patmanak.contako.data.local.RoomRemoteHydrationObligationStore
import com.patmanak.contako.data.local.RoomTargetedRecoveryStore
import com.patmanak.contako.data.local.TargetedRecoveryRequestEntity
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncRequestDisposition
import com.patmanak.contako.domain.sync.SyncRunState
import com.patmanak.contako.domain.sync.SyncRunnerObserver
import com.patmanak.contako.domain.sync.SyncTrigger
import com.patmanak.contako.domain.sync.TargetedRecoveryKind
import com.patmanak.contako.domain.sync.TargetedRecoveryQueueResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SW-02/09: real durable deadline flow -> production waiter -> account runner -> actual executor
 * -> targeted recovery -> Room claim/retry/commit, with a controlled clock and remote gateway.
 * No platform scheduler, network, credentials, Android accounts or provider operations are used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TargetedRecoverySchedulingCompositionDeviceTest {
    @Test
    fun serverDeadlineSurvivesRuntimeAndRoomRecreationWithoutEarlyRequestOrWitnessRewrite() = runBlocking {
        withFixture { fixture ->
            val deferred = fixture.runInitialRateLimitedAttempt()
            fixture.advanceBy(30_000)
            fixture.assertRequests(START)
            fixture.stopAndReopen()
            fixture.assertDeferredUnchanged(deferred)
            val runtime = fixture.start()
            fixture.awaitDeadlineObserved(runtime, deferred.nextEligibleAt)

            fixture.advanceBy(SERVER_DELAY - 30_000 - 1)
            fixture.assertRequests(START)
            fixture.assertDeferredUnchanged(deferred)
            assertFalse(fixture.store().hasEligible(ACCOUNT, fixture.now()))

            // An extra caller cannot bypass the durable eligibility guard even after restart.
            assertEquals(SyncRequestDisposition.STARTED, runtime.runner.request(SyncTrigger.TARGETED_RECOVERY))
            fixture.awaitRuntimeIdle(runtime, completedPasses = 1)
            fixture.assertRequests(START)
            fixture.assertDeferredUnchanged(deferred)

            fixture.advanceBy(1)
            fixture.awaitResolved(runtime)
            fixture.assertRequests(START, START + SERVER_DELAY)
            fixture.assertResolvedData(deferred)
            fixture.assertNoFurtherWakeups(runtime)
        }
    }

    @Test
    fun anAlreadyEligibleDeadlineAfterReopenDispatchesOneRetryWithoutResettingAttempts() = runBlocking {
        withFixture { fixture ->
            val deferred = fixture.runInitialRateLimitedAttempt()
            fixture.stopAndReopen()
            // No runtime exists during this interval: startup must use the persisted deadline.
            fixture.advanceBy(SERVER_DELAY + 17_000)
            fixture.assertRequests(START)
            fixture.assertDeferredUnchanged(deferred)
            assertTrue(fixture.store().hasEligible(ACCOUNT, fixture.now()))
            val runtime = fixture.start()
            fixture.awaitResolved(runtime)
            fixture.assertRequests(START, START + SERVER_DELAY + 17_000)
            fixture.assertResolvedData(deferred)
            fixture.assertNoFurtherWakeups(runtime)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { fixture.seed(); block(fixture) }
        finally { fixture.closeAndDelete() }
    }

    private class Runtime(val scope: CoroutineScope, val runner: AccountSyncRunner,
        val observedDeadlines: MutableList<Long?>, val outcomes: MutableList<SyncPassOutcome>)

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "synthetic-targeted-scheduling-${UUID.randomUUID()}.db"
        private var database = ContakoDatabase.create(context, name)
        private val scheduler = TestCoroutineScheduler()
        private var runtime: Runtime? = null
        private val requestTimes = mutableListOf<Long>()
        private var witness: CanonicalContact? = null
        private var foreignRequest: TargetedRecoveryRequestEntity? = null

        fun now() = START + scheduler.currentTime
        fun store() = RoomTargetedRecoveryStore(database)

        suspend fun seed() {
            val contact = CanonicalContact(ACCOUNT.value, WITNESS, displayName = "Synthetic unchanged Chloë Groß",
                revision = 7, remoteContactId = "synthetic-witness-remote", remoteVersion = "synthetic-witness-v1",
                values = listOf(
                    ContactValue("first-email", ContactValueKind.EMAIL, "witness@example.invalid", order = 0),
                    ContactValue("repeat-email", ContactValueKind.EMAIL, "witness@example.invalid", order = 1,
                        metadata = mapOf("syntheticOccurrence" to "second")),
                    ContactValue("note", ContactValueKind.NOTE, "Keep exact: Straße\n東京", order = 0)),
                preservationEnvelope = PreservationEnvelope(mapOf("synthetic-opaque" to "X-SYNTHETIC:retain-exact")))
            database.withTransaction {
                database.contactDao().upsert(contact.toEntity())
                database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
                database.contactPayloadDao().upsert(requireNotNull(contact.preservationEnvelope).toEntity(ACCOUNT.value, WITNESS))
            }
            witness = requireNotNull(RoomContactRepository(database).getContact(ACCOUNT.value, WITNESS))
            defer(ACCOUNT)
            assertEquals(TargetedRecoveryQueueResult.QUEUED, store().queue(ACCOUNT,
                store().preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET), now()))
            defer(FOREIGN)
            assertEquals(TargetedRecoveryQueueResult.QUEUED, store().queue(FOREIGN,
                store().preview(FOREIGN, TargetedRecoveryKind.PROTON_CARD, TARGET), now()))
            foreignRequest = database.targetedRecoveryRequestDao().all(FOREIGN.value).single()
        }

        private suspend fun defer(account: AccountScope) = RoomRemoteHydrationObligationStore(database).defer(account,
            ContactInventoryBaseline(RemoteContactId(TARGET), "Synthetic deferred card", RemoteVersion("synthetic-index"),
                null, null, emptyList(), emptyList()), GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE))

        fun start(): Runtime {
            check(runtime == null)
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            val deadlines = mutableListOf<Long?>()
            val outcomes = mutableListOf<SyncPassOutcome>()
            val recoveryStore = store()
            val cards = ProtonVerifiedContactCardGateway { account, id ->
                assertEquals(ACCOUNT, account)
                assertEquals(RemoteContactId(TARGET), id)
                requestTimes += now()
                // This is an injected structured gateway result, not a real HTTP exchange.
                if (requestTimes.size == 1) GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED,
                    retryAfterMillis = SERVER_DELAY)
                else GatewayOutcome.Success(recoveredCard())
            }
            val unusedPreflight = AndroidInteroperabilityPreflight { _, _ -> error("No Android source exists") }
            val unusedAndroid = object : AndroidInteroperabilityStage {
                override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean):
                    AndroidInteroperabilityStageResult = error("No Android source exists")
                override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean):
                    AndroidInteroperabilityStageResult = error("Targeted card read must not project")
            }
            val recovery = TargetedContactRecovery(recoveryStore, cards,
                TargetedAndroidRecovery { _, _, _, _, _ -> error("Proton-only request") },
                prepareProton = { request, cancelled ->
                    prepareTargetedProtonRead(database, unusedPreflight, unusedAndroid, request, cancelled)
                }, clock = ::now)
            val executor = ContakoSyncPassExecutor(ACCOUNT, SyncPrerequisiteChecker { SyncPrerequisiteState.READY },
                IncrementalRemoteContactStage(ProtonContactInventoryGateway { _, _ -> error("No ordinary inventory crawl") },
                    cards, PersistentContactInventoryPlanner(RoomContactInventoryCheckpointStore(database)),
                    RoomRemoteCanonicalReconciliationStore(database)),
                DurableMutationOrchestrator(RoomMutationExecutionStore(database),
                    MutationPreparationGateway { error("Targeted read must not create intent") },
                    MutationUploadGateway { error("Targeted read must not upload") }),
                wallClock = ::now, androidPreflight = unusedPreflight, androidStage = unusedAndroid,
                targetedRecovery = recovery)
            val runner = AccountSyncRunner(scope, object : SyncRunnerObserver {
                override fun onFinish(outcome: SyncPassOutcome) { outcomes += outcome }
            }, executor)
            return Runtime(scope, runner, deadlines, outcomes).also { created ->
                runtime = created
                scope.launch {
                    watchTargetedRecoveryWakeups(database.targetedRecoveryRequestDao().observeNextEligible(ACCOUNT.value)
                        .onEach { deadlines += it }, runner.isInvalidated, clock = ::now) {
                        runner.request(SyncTrigger.TARGETED_RECOVERY)
                    }
                }
            }
        }

        suspend fun runInitialRateLimitedAttempt(): TargetedRecoveryRequestEntity {
            val runtime = start()
            pumpUntil("Initial structured rate limit did not become a durable deferred request") {
                val request = request()
                request.state == "DEFERRED" && request.attemptCount == 1 &&
                    runtime.runner.state.value is SyncRunState.Idle && runtime.outcomes.size == 1 &&
                    runtime.observedDeadlines.lastOrNull() == request.nextEligibleAt
            }
            assertEquals(listOf(SyncPassOutcome.RETRY_WAITING), runtime.outcomes)
            assertRequests(START)
            return request().also { deferred ->
                assertEquals(START + SERVER_DELAY, deferred.nextEligibleAt)
                assertEquals(GatewayFailureCategory.RATE_LIMITED.name, deferred.failureCategory)
                assertEquals(1, deferred.attemptCount)
                assertNull(deferred.claimToken)
                assertNull(database.contactDao().get(ACCOUNT.value, TARGET))
                assertUnchangedWitnessAndForeign()
            }
        }

        fun advanceBy(millis: Long) { scheduler.advanceTimeBy(millis); scheduler.runCurrent() }

        suspend fun stopAndReopen() {
            stopRuntime()
            database.close()
            database = ContakoDatabase.create(context, name)
        }

        suspend fun awaitDeadlineObserved(runtime: Runtime, deadline: Long) =
            pumpUntil("Restored runtime did not observe its durable deadline") {
                runtime.observedDeadlines.lastOrNull() == deadline
            }

        suspend fun awaitRuntimeIdle(runtime: Runtime, completedPasses: Int) =
            pumpUntil("Account runner did not finish its controlled pass") {
                runtime.runner.state.value is SyncRunState.Idle && runtime.outcomes.size == completedPasses
            }

        suspend fun awaitResolved(runtime: Runtime) = pumpUntil("Eligible retry did not resolve its exact request") {
            request().state == "RESOLVED" && runtime.runner.state.value is SyncRunState.Idle &&
                runtime.outcomes.isNotEmpty() && runtime.observedDeadlines.isNotEmpty() &&
                runtime.observedDeadlines.last() == null
        }

        private suspend fun request() = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()

        suspend fun assertDeferredUnchanged(before: TargetedRecoveryRequestEntity) {
            assertTrue("Deferred request data or attempt budget changed before eligibility", request() == before)
            assertNull(database.contactDao().get(ACCOUNT.value, TARGET))
            assertEquals(1, database.remoteHydrationObligationDao().getAll(ACCOUNT.value).size)
            assertUnchangedWitnessAndForeign()
        }

        suspend fun assertResolvedData(before: TargetedRecoveryRequestEntity) {
            val resolved = request()
            assertEquals("RESOLVED", resolved.state)
            assertEquals(2, resolved.attemptCount)
            assertTrue("Retry changed request identity", resolved.requestGeneration == before.requestGeneration)
            assertTrue("Retry changed source identity", resolved.sourceGeneration == before.sourceGeneration)
            assertEquals(before.createdAt, resolved.createdAt)
            assertEquals(now(), resolved.updatedAt)
            assertNull(resolved.claimToken)
            assertNull(resolved.failureCategory)
            assertTrue(database.remoteHydrationObligationDao().getAll(ACCOUNT.value).isEmpty())
            val actual = requireNotNull(RoomContactRepository(database).getContact(ACCOUNT.value, TARGET))
            val expected = recoveredCard().contact
            assertTrue("Recovered fields differ", actual.values == expected.values)
            assertTrue("Recovered envelope differs", actual.preservationEnvelope == expected.preservationEnvelope)
            assertTrue("Recovered identity differs", actual.id == expected.id && actual.remoteContactId == TARGET)
            assertEquals(1L, actual.revision)
            assertTrue("Recovered display name differs", actual.displayName == expected.displayName)
            assertTrue("Recovered remote version differs", actual.remoteVersion == recoveredCard().version?.value)
            assertUnchangedWitnessAndForeign()
        }

        fun assertRequests(vararg times: Long) = assertEquals(times.toList(), requestTimes.toList())

        suspend fun assertNoFurtherWakeups(runtime: Runtime) {
            val calls = requestTimes.toList()
            val outcomes = runtime.outcomes.toList()
            advanceBy(1_000_000)
            scheduler.runCurrent()
            assertEquals(calls, requestTimes)
            assertEquals(outcomes, runtime.outcomes)
            assertTrue(store().eligible(ACCOUNT, now()).isEmpty())
            assertUnchangedWitnessAndForeign()
        }

        private suspend fun assertUnchangedWitnessAndForeign() {
            assertTrue("Healthy witness was rewritten", RoomContactRepository(database).getContact(ACCOUNT.value, WITNESS) == witness)
            assertTrue("Another account's request was consumed", database.targetedRecoveryRequestDao().all(FOREIGN.value) ==
                listOf(requireNotNull(foreignRequest)))
            assertTrue("Another account's obligation disappeared", database.remoteHydrationObligationDao().getAll(FOREIGN.value).size == 1)
            assertTrue("Read recovery created outbound intent", database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        }

        // Room uses real query executors; virtual time advances only through advanceBy above.
        // Pumping ready coroutine tasks lets actual Room callbacks arrive without advancing timers.
        private suspend fun pumpUntil(message: String, predicate: suspend () -> Boolean) {
            val reached = withTimeoutOrNull(10_000) {
                var ready = false
                while (!ready) {
                    scheduler.runCurrent()
                    ready = predicate()
                    if (!ready) delay(1)
                }
                true
            }
            assertTrue(message, reached == true)
        }

        private suspend fun stopRuntime() {
            val current = runtime ?: return
            current.scope.cancel()
            pumpUntil("Runtime collectors did not stop before Room close") { current.scope.coroutineContext.job.isCompleted }
            runtime = null
        }

        suspend fun closeAndDelete() {
            try { stopRuntime() }
            finally {
                database.close()
                assertTrue("Isolated scheduling database cleanup failed", context.deleteDatabase(name))
            }
        }
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-targeted-scheduling-owner")
        val FOREIGN = AccountScope("synthetic-targeted-scheduling-foreign")
        const val TARGET = "synthetic-scheduled-target"
        const val WITNESS = "synthetic-unchanged-witness"
        const val START = 1_700_000_000_000L
        const val SERVER_DELAY = 90_000L
        fun recoveredCard() = VerifiedContactCard(RemoteContactId(TARGET), RemoteVersion("synthetic-verified-v1"),
            CanonicalContact(ACCOUNT.value, TARGET, displayName = "Synthetic recovered Chloë Groß 東京", remoteContactId = TARGET,
                values = listOf(ContactValue("note", ContactValueKind.NOTE, "Recovered exact: Straße\n東京", order = 0)),
                preservationEnvelope = PreservationEnvelope(mapOf("synthetic-opaque" to "X-SYNTHETIC:retained"))))
    }
}
