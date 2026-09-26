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
import com.patmanak.contako.data.local.RoomRemoteCanonicalReconciliationStore
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.sync.IncrementalRemoteContactStage
import com.patmanak.contako.data.sync.RemoteContactBatchObserver
import com.patmanak.contako.data.sync.RemoteContactStageResult
import com.patmanak.contako.domain.model.CanonicalContact
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRoomPerformanceDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val evidence by lazy { context.performanceEvidence() }
    private val databaseNames = mutableListOf<String>()

    @After
    fun tearDown() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun deltaAndImportUseRealRoomAndKeepHeartbeatResponsive() = runBlocking {
        listOf(Scenario.NONE, Scenario.TEN, Scenario.IMPORT).forEach { scenario ->
            val warmups = (1..PerformanceRun.REQUIRED_WARMUPS).map { execute(scenario, it) }
            val samples = (1..PerformanceRun.REQUIRED_SAMPLES).map { execute(scenario, it) }
            val run = PerformanceRun(
                environment(scenario),
                PerformanceRun.REQUIRED_WARMUPS,
                samples,
                scenario.ceilings,
                warmups,
            )
            assertTrue("${scenario.caseId}: ${run.violations()}", run.violations().isEmpty())
            emit(run)
        }
    }

    private suspend fun execute(scenario: Scenario, ordinal: Int): PerformanceSample {
        val databaseName = "sync-${scenario.name.lowercase()}-${SystemClock.elapsedRealtimeNanos()}.db"
        databaseNames += databaseName
        val database = ContakoDatabase.create(context, databaseName)
        val checkpoint = RoomContactInventoryCheckpointStore(database)
        val initial = inventory(1)
        var requests = 0
        var hydrations = 0
        var commits = 0
        val heartbeatCount = AtomicInteger()
        val running = AtomicBoolean(true)
        val heartbeat = CoroutineScope(Dispatchers.Main).launch {
            while (running.get()) {
                heartbeatCount.incrementAndGet()
                delay(10)
            }
        }
        try {
            if (scenario != Scenario.IMPORT) {
                val seed = stage(database, checkpoint, initial, { requests++ }, { hydrations++ }) { commits++ }
                assertTrue(withContext(Dispatchers.IO) { seed.run(ACCOUNT) } is RemoteContactStageResult.Success)
                requests = 0
                hydrations = 0
                commits = 0
            }
            val current = if (scenario == Scenario.TEN) {
                initial.mapIndexed { index, value -> if (index < 10) metadata(index, 2) else value }
            } else initial
            val counter = PerformanceCounter(SystemClock::elapsedRealtimeNanos)
            counter.start()
            val measured = stage(database, checkpoint, current, { requests++ }, { hydrations++ }) {
                commits++
                counter.commit()
                counter.checkpoint()
                counter.observeHeap()
            }
            val result = withContext(Dispatchers.IO) { measured.run(ACCOUNT) }
            counter.request(requests)
            repeat(hydrations) { counter.providerBatch(1) }
            assertTrue(result is RemoteContactStageResult.Success)
            assertEquals(scenario.hydrations, hydrations)
            assertEquals(scenario.requests, requests)
            if (scenario == Scenario.IMPORT) assertTrue("UI heartbeat did not run", heartbeatCount.get() > 0)
            return counter.finish(ordinal).also { assertEquals(commits, it.commitCount) }
        } finally {
            running.set(false)
            heartbeat.join()
            database.close()
        }
    }

    private fun stage(
        database: ContakoDatabase,
        checkpoint: RoomContactInventoryCheckpointStore,
        inventory: List<ContactInventoryMetadata>,
        onRequest: () -> Unit,
        onHydration: () -> Unit,
        onCommit: () -> Unit,
    ) = IncrementalRemoteContactStage(
        ProtonContactInventoryGateway { _, cursor ->
            require(cursor == null)
            onRequest()
            GatewayOutcome.Success(ContactInventoryPage(inventory, null, null, inventory.size, AUTHORITY))
        },
        ProtonVerifiedContactCardGateway { _, id ->
            onRequest()
            onHydration()
            val version = inventory.first { it.id == id }.version
            GatewayOutcome.Success(VerifiedContactCard(
                id, version, CanonicalContact(ACCOUNT.value, id.value, displayName = "Synthetic"),
            ))
        },
        PersistentContactInventoryPlanner(checkpoint),
        RoomRemoteCanonicalReconciliationStore(database),
        RemoteContactBatchObserver { _, _, _ -> onCommit() },
    )

    private fun emit(run: PerformanceRun) {
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
            durationMs, requests, hydrations, hydrations, if (this == IMPORT) 96L shl 20 else 64L shl 20, 100,
        )
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-account")
        val AUTHORITY = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION
    }
}
