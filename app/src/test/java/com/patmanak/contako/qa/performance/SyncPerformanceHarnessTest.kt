package com.patmanak.contako.qa.performance

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
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import com.patmanak.contako.data.proton.ContactInventoryCheckpointStore
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.VersionedContactInventoryCheckpoint
import com.patmanak.contako.data.sync.CanonicalReconciliationReceipt
import com.patmanak.contako.data.sync.IncrementalRemoteContactStage
import com.patmanak.contako.data.sync.RemoteCanonicalReconciliationStore
import com.patmanak.contako.data.sync.RemoteContactBatchObserver
import com.patmanak.contako.data.sync.RemoteContactStageResult
import com.patmanak.contako.domain.model.CanonicalContact
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPerformanceHarnessTest {
    @Test
    fun `07-DELTA and 07-IMPORT deterministic pipeline meets count and boundedness oracles`() = runTest {
        listOf(Scenario.NONE, Scenario.TEN, Scenario.IMPORT).forEach { scenario ->
            val samples = (1..PerformanceRun.REQUIRED_WARMUPS + PerformanceRun.REQUIRED_SAMPLES).map { ordinal ->
                execute(scenario, (ordinal - PerformanceRun.REQUIRED_WARMUPS).coerceAtLeast(0))
            }.drop(PerformanceRun.REQUIRED_WARMUPS)
            val run = PerformanceRun(environment(scenario), PerformanceRun.REQUIRED_WARMUPS, samples, scenario.ceilings)

            assertTrue("${scenario.caseId}: ${run.violations()}", run.violations().isEmpty())
            assertEquals(scenario.requests, samples.maxOf(PerformanceSample::requestCount))
            assertEquals(scenario.hydrations, samples.maxOf(PerformanceSample::providerOperationCount))
            assertTrue(PerformanceMetricCollector.toJson(run).contains("\"commitCount\""))
            assertTrue(PerformanceMetricCollector.toCsv(run).contains("commits,checkpoints"))
        }
    }

    @Test
    fun `07-IMPORT controlled real time remains separate from fake clock qualification`() = runBlocking {
        val startedAt = System.nanoTime()
        val sample = execute(Scenario.IMPORT, 1, fakeClock = false)
        val wallDurationMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(300, sample.providerOperationCount)
        assertEquals(12, sample.commitCount)
        assertTrue(wallDurationMs < 90_000)
    }

    private suspend fun execute(scenario: Scenario, ordinal: Int, fakeClock: Boolean = true): PerformanceSample {
        val checkpoint = MemoryCheckpointStore()
        val baseline = inventory(1)
        if (scenario != Scenario.IMPORT) checkpoint.seed(baseline)
        val current = if (scenario == Scenario.TEN) {
            baseline.mapIndexed { index, value -> if (index < 10) metadata(index, 2) else value }
        } else {
            baseline
        }
        var requests = 0
        var hydrations = 0
        var commits = 0
        var largestBatch = 0
        var nanos = 1_000_000L
        val counter = PerformanceCounter { if (fakeClock) nanos else System.nanoTime() }
        counter.start(1_000)
        val stage = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { _, cursor ->
                requests++
                if (fakeClock) nanos += SHAPED_REQUEST_NANOS
                require(cursor == null)
                GatewayOutcome.Success(ContactInventoryPage(current, null, null, 300, AUTHORITY))
            },
            ProtonVerifiedContactCardGateway { _, id ->
                requests++
                hydrations++
                if (fakeClock) nanos += SHAPED_REQUEST_NANOS
                val version = if (scenario == Scenario.TEN && id.index() < 10) 2 else 1
                GatewayOutcome.Success(card(id, version))
            },
            PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                commits++
                largestBatch = maxOf(largestBatch, cards.size)
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
            RemoteContactBatchObserver { hydrated, _, _ ->
                counter.commit()
                counter.checkpoint()
                counter.observeHeap(1_000L + hydrated * 2_048)
            },
        )

        val result = stage.run(ACCOUNT)
        assertTrue(result is RemoteContactStageResult.Success)
        assertEquals(scenario.hydrations, hydrations)
        assertEquals(scenario.requests, requests)
        assertTrue(largestBatch <= 25)
        counter.request(requests)
        repeat(hydrations) { counter.providerBatch(1) }
        return counter.finish(ordinal, 1_000).also {
            assertEquals(commits, it.commitCount)
            assertEquals(1, checkpoint.commitCount)
        }
    }

    private fun environment(scenario: Scenario) = PerformanceEnvironment(
        "${scenario.caseId.lowercase()}-fake-clock", scenario.caseId, "FX-P-300",
        PerformanceFixtureGenerator.DEFAULT_SEED, PerformanceBuildType.DEBUG, "host-jvm", 36,
        "nominal", 80, "fake-shaped", "warm",
    )

    private fun inventory(version: Int) = List(300) { metadata(it, version) }

    private fun metadata(index: Int, version: Int) = ContactInventoryMetadata(
        RemoteContactId("remote-${index.toString().padStart(3, '0')}"), "Synthetic", RemoteVersion("v$version"),
        100, version.toLong(), emptyList(), emptyList(), ContactInventoryVersionProvenance.REMOTE_SERVER,
        ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
    )

    private fun card(id: RemoteContactId, version: Int) = VerifiedContactCard(
        id, RemoteVersion("v$version"), CanonicalContact(ACCOUNT.value, id.value, displayName = "Synthetic"),
    )

    private fun RemoteContactId.index() = value.removePrefix("remote-").toInt()

    private enum class Scenario(
        val caseId: String,
        val hydrations: Int,
        val durationMs: Long,
    ) {
        NONE("07-DELTA-NONE", 0, 5_000),
        TEN("07-DELTA-TEN", 10, 15_000),
        IMPORT("07-IMPORT-300", 300, 90_000);

        val requests get() = 1 + hydrations
        val ceilings get() = PerformanceCeilings(
            durationMs, requests, hydrations, hydrations, if (this == IMPORT) 96L shl 20 else 64L shl 20, 100,
        )
    }

    private class MemoryCheckpointStore : ContactInventoryCheckpointStore {
        private var current: VersionedContactInventoryCheckpoint? = null
        var commitCount = 0
            private set

        fun seed(metadata: List<ContactInventoryMetadata>) {
            val checkpoint = ContactInventoryCheckpoint(metadata.map {
                com.patmanak.contako.data.proton.ContactInventoryBaseline(
                    it.id, it.displayName, it.version, requireNotNull(it.sizeBytes),
                    requireNotNull(it.modifiedAtEpochSeconds), emptyList(), emptyList(),
                )
            })
            current = VersionedContactInventoryCheckpoint(0, checkpoint)
        }

        override suspend fun load(account: AccountScope) = current

        override suspend fun compareAndSet(
            account: AccountScope,
            expectedGeneration: Long?,
            checkpoint: ContactInventoryCheckpoint,
        ): Boolean {
            if (current?.generation != expectedGeneration) return false
            current = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint)
            commitCount++
            return true
        }
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-account")
        val AUTHORITY = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION
        const val SHAPED_REQUEST_NANOS = 40_000_000L
    }
}
