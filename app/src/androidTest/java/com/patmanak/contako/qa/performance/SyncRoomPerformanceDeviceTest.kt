package com.patmanak.contako.qa.performance

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactInventoryCheckpointStore
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomRemoteCanonicalReconciliationStore
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.sync.IncrementalRemoteContactStage
import com.patmanak.contako.data.sync.RemoteContactBatchObserver
import com.patmanak.contako.data.sync.RemoteContactStageResult
import com.patmanak.contako.data.sync.RemoteImportPhase
import com.patmanak.contako.data.sync.SyncPassStage
import com.patmanak.contako.data.sync.SyncPassStageObserver
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRoomPerformanceDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val evidence by lazy { context.performanceEvidence() }

    @Test
    fun deltaAndImportUseRealRoomAndKeepHeartbeatResponsive() = runBlocking {
        listOf(Scenario.NONE, Scenario.TEN, Scenario.IMPORT).forEach { scenario ->
            val environment = environment(scenario)
            val warmups = mutableListOf<PerformanceSample>()
            val samples = mutableListOf<PerformanceSample>()
            fun snapshot() = PerformanceReport(
                environment, warmups.size, samples.toList(), scenario.ceilings, warmups.toList(),
            )
            repeat(PerformanceRun.REQUIRED_WARMUPS) {
                val sample = execute(scenario, it + 1)
                warmups += sample
                emit(snapshot())
                assertTrue("${scenario.caseId}: ${sample.outcome}", sample.outcome == PerformanceSampleOutcome.SUCCESS)
            }
            repeat(PerformanceRun.REQUIRED_SAMPLES) {
                val sample = execute(scenario, it + 1)
                samples += sample
                emit(snapshot())
                assertTrue("${scenario.caseId}: ${sample.outcome}", sample.outcome == PerformanceSampleOutcome.SUCCESS)
            }
            val report = snapshot()
            assertTrue("${scenario.caseId}: ${report.violations()}", report.violations().isEmpty())
        }
    }

    private suspend fun execute(scenario: Scenario, ordinal: Int): PerformanceSample {
        val databaseName = "sync-${scenario.name.lowercase()}-${SystemClock.elapsedRealtimeNanos()}.db"
        var database: ContakoDatabase? = null
        val initial = inventory(1)
        val requests = AtomicInteger()
        val hydrations = AtomicInteger()
        val heartbeatCount = AtomicInteger()
        val running = AtomicBoolean(false)
        val counter = PerformanceCounter(SystemClock::elapsedRealtimeNanos)
        counter.start()
        var heartbeat: Job? = null
        var measuredSample: PerformanceSample? = null
        fun sample(outcome: PerformanceSampleOutcome = PerformanceSampleOutcome.SUCCESS) =
            (measuredSample ?: counter.finish(ordinal)).copy(
                requestCount = requests.get(), fullCardFetchCount = hydrations.get(),
                heartbeatCount = heartbeatCount.get(), outcome = outcome,
            )
        suspend fun stopHeartbeat() {
            running.set(false)
            heartbeat?.join()
        }
        try {
            val opened = ContakoDatabase.create(context, databaseName)
            database = opened
            val checkpoint = RoomContactInventoryCheckpointStore(opened)
            val repository = RoomContactRepository(opened)
            if (scenario != Scenario.IMPORT) {
                val seed = stage(opened, checkpoint, initial, {}, {}, {})
                check(withContext(Dispatchers.IO) { seed.run(ACCOUNT) } is RemoteContactStageResult.Success) { "SEED_FAILED" }
            }
            val before = repository.observeContacts(ACCOUNT.value).first().associateBy { it.id }
            val current = if (scenario == Scenario.TEN) {
                initial.mapIndexed { index, value -> if (index < 10) metadata(index, 2) else value }
            } else initial
            counter.start()
            running.set(true)
            heartbeat = CoroutineScope(Dispatchers.Main).launch {
                while (running.get()) {
                    heartbeatCount.incrementAndGet()
                    delay(10)
                }
            }
            val measured = stage(
                opened, checkpoint, current,
                onRequest = { requests.incrementAndGet() },
                onHydration = { hydrations.incrementAndGet() },
                onCommit = { counter.commit(); counter.observeHeap() },
                onCheckpoint = { counter.checkpoint() },
            )
            val result = withContext(Dispatchers.IO) { measured.run(ACCOUNT) }
            measuredSample = sample()
            stopHeartbeat()
            if (result == RemoteContactStageResult.Cancelled) return sample(PerformanceSampleOutcome.CANCELLED)
            check(result is RemoteContactStageResult.Success) { "STAGE_FAILED" }
            check(scenario.hydrations == hydrations.get() && scenario.requests == requests.get()) { "REQUEST_ORACLE_FAILED" }
            if (scenario == Scenario.IMPORT) check(heartbeatCount.get() > 0) { "HEARTBEAT_MISSING" }
            val after = repository.observeContacts(ACCOUNT.value).first().associateBy { it.id }
            check(after.keys == current.map { it.id.value }.toSet()) { "IDENTITY_ORACLE_FAILED" }
            current.forEachIndexed { index, metadata ->
                val actual = after.getValue(metadata.id.value)
                val expected = canonical(metadata)
                check(actual.values.associateBy { it.id } == expected.values.associateBy { it.id } &&
                    actual.displayName == expected.displayName &&
                    actual.firstName == expected.firstName && actual.lastName == expected.lastName &&
                    actual.remoteVersion == metadata.version.value) { "FIELD_ORACLE_FAILED" }
                if (scenario == Scenario.NONE || (scenario == Scenario.TEN && index >= 10)) {
                    check(actual == before.getValue(actual.id)) { "UNCHANGED_ORACLE_FAILED" }
                }
            }
            return sample()
        } catch (_: TimeoutCancellationException) {
            return sample(PerformanceSampleOutcome.TIMEOUT)
        } catch (_: CancellationException) {
            return sample(PerformanceSampleOutcome.CANCELLED)
        } catch (_: Exception) {
            return sample(PerformanceSampleOutcome.LOCAL_FAILURE)
        } finally {
            running.set(false)
            withContext(NonCancellable) {
                try {
                    stopHeartbeat()
                    database?.close()
                } finally {
                    context.deleteDatabase(databaseName)
                }
            }
        }
    }

    private fun stage(
        database: ContakoDatabase,
        checkpoint: RoomContactInventoryCheckpointStore,
        inventory: List<ContactInventoryMetadata>,
        onRequest: () -> Unit,
        onHydration: () -> Unit,
        onCommit: () -> Unit,
        onCheckpoint: () -> Unit = {},
    ) = IncrementalRemoteContactStage(
        ProtonContactInventoryGateway { _, cursor ->
            require(cursor == null)
            onRequest()
            GatewayOutcome.Success(ContactInventoryPage(inventory, null, null, inventory.size, AUTHORITY))
        },
        ProtonVerifiedContactCardGateway { _, id ->
            onRequest()
            onHydration()
            val metadata = inventory.first { it.id == id }
            GatewayOutcome.Success(VerifiedContactCard(
                id, metadata.version, canonical(metadata),
            ))
        },
        PersistentContactInventoryPlanner(checkpoint),
        RoomRemoteCanonicalReconciliationStore(database),
        RemoteContactBatchObserver { _, _, _ -> onCommit() },
        importObserver = object : SyncPassStageObserver {
            override fun onStage(stage: SyncPassStage) = Unit
            override fun onImportPhase(phase: RemoteImportPhase) {
                if (phase == RemoteImportPhase.CHECKPOINT_COMMIT_DONE) onCheckpoint()
            }
        },
    )

    private fun canonical(metadata: ContactInventoryMetadata) = CanonicalContact(
        ACCOUNT.value, metadata.id.value, firstName = "Synthetic", lastName = metadata.id.value,
        displayName = metadata.displayName.orEmpty(),
        values = listOf(
            ContactValue("email", ContactValueKind.EMAIL, "${metadata.id.value}@example.invalid", order = 0),
            ContactValue("phone", ContactValueKind.PHONE, "+15550000000", order = 0),
            ContactValue("note", ContactValueKind.NOTE, "Synthetic private note ${metadata.version.value}", order = 0),
        ),
    )

    private fun emit(run: PerformanceReport) {
        context.writePerformanceArtifact(
            "${run.environment.runId}.json",
            PerformanceMetricCollector.toJson(run),
        )
        context.writePerformanceArtifact(
            "${run.environment.runId}.csv",
            PerformanceMetricCollector.toCsv(run),
        )
    }

    private fun environment(scenario: Scenario) = PerformanceEnvironment(
        "${scenario.caseId.lowercase()}-room-api${android.os.Build.VERSION.SDK_INT}", scenario.caseId, "FX-P-300",
        PerformanceFixtureGenerator.DEFAULT_SEED, PerformanceBuildType.BENCHMARK, "reference-phone",
        android.os.Build.VERSION.SDK_INT, evidence.thermalState, evidence.batteryPercent, "fake-shaped", "warm",
        evidence.chargingState, evidence.sourceRevision, evidence.buildIdentifier, evidence.benchmarkCommand,
        evidence.applicationApkSha256, evidence.testApkSha256,
    )

    private fun inventory(version: Int) = List(300) { metadata(it, version) }
    private fun metadata(index: Int, version: Int) = ContactInventoryMetadata(
        RemoteContactId("remote-${index.toString().padStart(3, '0')}"), "Synthetic", RemoteVersion("v$version"),
        100, version.toLong(), emptyList(), emptyList(), ContactInventoryVersionProvenance.REMOTE_SERVER,
        ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
    )

    private enum class Scenario(val caseId: String, val hydrations: Int, val durationMs: Long) {
        NONE("07-DELTA-NONE", 0, 5_000),
        TEN("07-DELTA-TEN", 10, 15_000),
        IMPORT("07-IMPORT-300", 300, 90_000);

        val requests get() = 1 + hydrations
        val ceilings get() = PerformanceCeilings(
            durationMs, requests, 0, 0, if (this == IMPORT) 96L shl 20 else 64L shl 20, 100,
        )
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-account")
        val AUTHORITY = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION
    }
}
