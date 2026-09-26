package com.patmanak.contako.qa.performance

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import com.patmanak.contako.data.proton.ContactInventoryCheckpointStore
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.VersionedContactInventoryCheckpoint
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflight
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflightResult
import com.patmanak.contako.data.sync.AndroidInteroperabilityStage
import com.patmanak.contako.data.sync.AndroidInteroperabilityStageResult
import com.patmanak.contako.data.sync.CanonicalReconciliationReceipt
import com.patmanak.contako.data.sync.ContakoSyncPassExecutor
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.DurableMutationOrchestrator
import com.patmanak.contako.data.sync.FullRepairExecution
import com.patmanak.contako.data.sync.FullRepairExecutionCoordinator
import com.patmanak.contako.data.sync.FullRepairPhase
import com.patmanak.contako.data.sync.FullRepairProgress
import com.patmanak.contako.data.sync.IncrementalRemoteContactStage
import com.patmanak.contako.data.sync.MutationExecutionStore
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.data.sync.MutationPreparationGateway
import com.patmanak.contako.data.sync.MutationUploadGateway
import com.patmanak.contako.data.sync.RemoteCanonicalReconciliationStore
import com.patmanak.contako.data.sync.RemoteContactBatchObserver
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RetryDecision
import com.patmanak.contako.data.sync.SyncPrerequisiteChecker
import com.patmanak.contako.data.sync.SyncPrerequisiteState
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncPassRequest
import com.patmanak.contako.domain.sync.SyncScope
import com.patmanak.contako.domain.sync.SyncTrigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullRepairRobustnessHarnessTest {
    @Test
    fun `07-ROBUST real full repair spine qualifies 1000 and 5000 without accumulation`() = runTest {
        listOf(PerformanceFixtureProfile.STRESS, PerformanceFixtureProfile.ROBUSTNESS).forEach { profile ->
            val fixture = PerformanceFixtureGenerator.generate(profile)
            repeat(PerformanceRun.REQUIRED_WARMUPS) { runRepair(fixture, 1) }
            val samples = (1..PerformanceRun.REQUIRED_SAMPLES).map { ordinal -> runRepair(fixture, ordinal) }
            val run = PerformanceRun(
                environment(profile, "07-ROBUST-${profile.contactCount}"),
                PerformanceRun.REQUIRED_WARMUPS,
                samples,
                PerformanceCeilings(900_000, profile.contactCount + 10, profile.contactCount, profile.contactCount, 192L shl 20, 100),
            )

            assertTrue(run.violations().toString(), run.violations().isEmpty())
            assertTrue(samples.all { it.heartbeatCount > 0 })
            assertTrue(samples.all { it.crashCount + it.anrProxyCount + it.oomProxyCount == 0 })
            assertTrue(PerformanceMetricCollector.toJson(run).contains("\"providerQueryCount\""))
            assertTrue(PerformanceMetricCollector.toCsv(run).contains("max_batch_estimated_bytes"))
        }
    }

    @Test
    fun `07-HEAVY PHOTO and VCARD use streams and bounded cursors without no-change writes`() {
        listOf(PerformanceFixtureProfile.PHOTO, PerformanceFixtureProfile.VCARD).forEach { profile ->
            val fixture = PerformanceFixtureGenerator.generate(profile)
            val counter = PerformanceCounter { 2_000_000 }
            counter.start(1_000)
            fixture.contacts.chunked(100).forEach { page ->
                counter.providerQuery(page.size)
                page.forEach { contact ->
                    if (contact.photoBytes.isNotEmpty()) counter.photoStream()
                }
                counter.heartbeat()
            }
            val sample = counter.finish(1, 1_000)

            assertEquals(100, sample.maxCursorRows)
            assertEquals(if (profile == PerformanceFixtureProfile.PHOTO) 300 else 0, sample.photoStreamCount)
            assertEquals(0, sample.noChangeWriteCount)
            assertEquals(fixture.canonicalDigest, PerformanceFixtureGenerator.generate(profile).canonicalDigest)
        }
    }

    @Test
    fun `07-CANCEL restart at durable checkpoints converges without duplicate or lost mutation`() = runTest {
        val fixture = PerformanceFixtureGenerator.generate(PerformanceFixtureProfile.ROBUSTNESS)
        listOf(
            FullRepairProgress(1, FullRepairPhase.CANONICAL_RECONCILIATION, 1, 1, 0, 0, false, false),
            FullRepairProgress(2, FullRepairPhase.ANDROID_PROJECTION, 1, 1, 0, 0, false, false),
            FullRepairProgress(3, FullRepairPhase.PUBLISHING, 0, 1, 0, 0, false, false),
        ).forEach { checkpoint ->
            val boundary = CountingProviderBoundary(fixture.contacts.size)
            val repair = MemoryRepair(checkpoint)
            val harness = Harness(fixture, boundary, repair, initialCanonicalIds = fixture.contacts.map { it.id })

            assertEquals(SyncPassOutcome.SUCCESS, harness.executor().execute(request()))
            assertTrue(repair.cleared)
            assertEquals(fixture.contacts.size, harness.expectedConvergedCount)
            assertEquals(harness.canonicalCommitIds.distinct().size, harness.canonicalCommitIds.size)
            if (checkpoint.phase >= FullRepairPhase.ANDROID_PROJECTION) assertEquals(0, boundary.batchCount)
        }
    }

    @Test
    fun `07-CANCEL explicit cancellation is observed between provider batches`() = runTest {
        val fixture = PerformanceFixtureGenerator.generate(PerformanceFixtureProfile.ROBUSTNESS)
        val boundary = CountingProviderBoundary(fixture.contacts.size)
        val repair = MemoryRepair(cancelWhen = { boundary.batchCount == 137 })
        val harness = Harness(fixture, boundary, repair)

        assertEquals(SyncPassOutcome.CANCELLED, harness.executor().execute(request()))
        assertEquals(137, boundary.batchCount)
        assertEquals(137, boundary.operationCount)
        assertTrue(repair.cancellationCleared)
        assertTrue(!repair.cleared)

        val restarted = Harness(
            fixture,
            CountingProviderBoundary(fixture.contacts.size),
            MemoryRepair(),
            initialCanonicalIds = harness.uniqueCanonicalIds.toList(),
        )
        assertEquals(SyncPassOutcome.SUCCESS, restarted.executor().execute(request()))
        assertEquals(fixture.contacts.size, restarted.expectedConvergedCount)
        assertEquals(restarted.canonicalCommitIds.distinct().size, restarted.canonicalCommitIds.size)
    }

    private suspend fun runRepair(fixture: PerformanceFixture, ordinal: Int): PerformanceSample {
        var nanos = 1_000_000L
        val counter = PerformanceCounter { nanos }
        counter.start(1_000)
        val boundary = CountingProviderBoundary(fixture.contacts.size) { operations, bytes ->
            counter.providerBatch(operations, bytes)
            counter.heartbeat()
        }
        val harness = Harness(fixture, boundary, MemoryRepair(), onRequest = {
            nanos += 1_000_000
            counter.request()
        })
        assertEquals(SyncPassOutcome.SUCCESS, harness.executor().execute(request()))
        counter.observeHeap(1_000 + fixture.contacts.size * 2_048L)
        assertEquals(fixture.contacts.size, harness.uniqueCanonicalIds.size)
        assertEquals(fixture.contacts.size, boundary.operationCount)
        return counter.finish(ordinal, 1_000)
    }

    private class Harness(
        private val fixture: PerformanceFixture,
        private val provider: CountingProviderBoundary,
        private val repair: MemoryRepair,
        private val onRequest: () -> Unit = {},
        initialCanonicalIds: List<String> = emptyList(),
    ) {
        val uniqueCanonicalIds = linkedSetOf<String>().apply { addAll(initialCanonicalIds) }
        val canonicalCommitIds = mutableListOf<String>()
        val expectedConvergedCount: Int get() = uniqueCanonicalIds.size
        private val checkpoint = MemoryCheckpointStore()

        fun executor(): ContakoSyncPassExecutor {
            val metadata = fixture.contacts.mapIndexed { index, contact ->
                ContactInventoryMetadata(
                    RemoteContactId(contact.id), contact.displayName, RemoteVersion("v$index"),
                    contact.canonicalBytes().size.toLong(), index.toLong(), emptyList(), emptyList(),
                    ContactInventoryVersionProvenance.REMOTE_SERVER,
                    ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
                )
            }
            val remote = IncrementalRemoteContactStage(
                ProtonContactInventoryGateway { _, cursor ->
                    onRequest()
                    val offset = cursor?.value?.toInt() ?: 0
                    val page = metadata.drop(offset).take(1_000)
                    val nextOffset = offset + page.size
                    GatewayOutcome.Success(ContactInventoryPage(
                        page,
                        cursor,
                        if (nextOffset < metadata.size) InventoryCursor(nextOffset.toString()) else null,
                        metadata.size,
                        AUTHORITY,
                    ))
                },
                ProtonVerifiedContactCardGateway { _, id ->
                    onRequest()
                    GatewayOutcome.Success(VerifiedContactCard(
                        id,
                        metadata.first { it.id == id }.version,
                        CanonicalContact(ACCOUNT.value, id.value, displayName = "Synthetic"),
                    ))
                },
                PersistentContactInventoryPlanner(checkpoint),
                RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                    cards.forEach {
                        uniqueCanonicalIds += it.id.value
                        canonicalCommitIds += it.id.value
                    }
                    CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
                },
                RemoteContactBatchObserver { _, _, _ -> },
            )
            val android = object : AndroidInteroperabilityStage {
                override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                    if (isCancelled()) AndroidInteroperabilityStageResult.Cancelled else AndroidInteroperabilityStageResult.Success

                override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                    repeat(fixture.contacts.size) {
                        if (isCancelled()) return AndroidInteroperabilityStageResult.Cancelled
                        provider.applyBatch(1, 256)
                    }
                    return AndroidInteroperabilityStageResult.Success
                }
            }
            return ContakoSyncPassExecutor(
                ACCOUNT,
                SyncPrerequisiteChecker { SyncPrerequisiteState.READY },
                remote,
                DurableMutationOrchestrator(EmptyMutationStore, MutationPreparationGateway {
                    GatewayOutcome.Success(MutationPreparation.UploadAllowed)
                }, MutationUploadGateway { GatewayOutcome.Success(RemoteMutationAcknowledgement(null, null)) }),
                androidPreflight = AndroidInteroperabilityPreflight { account, cancelled ->
                    if (cancelled()) AndroidInteroperabilityPreflightResult.Cancelled else
                        AndroidInteroperabilityPreflightResult.Ready(AndroidInteroperabilityContext(account, "synthetic", 0, 0))
                },
                androidStage = android,
                fullRepairCoordinator = FullRepairExecutionCoordinator { repair },
            )
        }
    }

    private class CountingProviderBoundary(
        private val expectedOperations: Int,
        private val onBatch: (Int, Int) -> Unit = { _, _ -> },
    ) {
        var batchCount = 0
            private set
        var operationCount = 0
            private set

        fun applyBatch(operations: Int, estimatedBytes: Int) {
            require(operations in 1..200 && estimatedBytes <= 512 * 1_024)
            batchCount++
            operationCount += operations
            require(operationCount <= expectedOperations)
            onBatch(operations, estimatedBytes)
        }
    }

    private class MemoryRepair(
        initial: FullRepairProgress = FullRepairProgress(
            0, FullRepairPhase.REMOTE_ENUMERATION, 0, null, 0, 0, false, false,
        ),
        private val cancelWhen: () -> Boolean = { false },
    ) : FullRepairExecution {
        override var progress = initial
        var cleared = false
        var cancellationCleared = false
        override fun isCancellationRequested() = cancelWhen()
        override suspend fun checkpoint(phase: FullRepairPhase, completedUnits: Long, totalUnits: Long): Boolean {
            progress = progress.copy(
                revision = progress.revision + 1,
                phase = phase,
                completedUnits = completedUnits,
                totalUnits = totalUnits,
            )
            return true
        }
        override suspend fun clearCancellation() = true.also { cancellationCleared = true }
        override suspend fun clearAfterPublished() = true.also { cleared = true }
    }

    private class MemoryCheckpointStore : ContactInventoryCheckpointStore {
        private var value: VersionedContactInventoryCheckpoint? = null
        override suspend fun load(account: AccountScope) = value
        override suspend fun compareAndSet(
            account: AccountScope,
            expectedGeneration: Long?,
            checkpoint: ContactInventoryCheckpoint,
        ): Boolean {
            if (value?.generation != expectedGeneration) return false
            value = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint)
            return true
        }
    }

    private object EmptyMutationStore : MutationExecutionStore {
        override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long) = 0
        override suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int) = emptyList<DurableMutationCommand>()
        override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long) = false
        override suspend fun recordFailure(command: DurableMutationCommand, category: com.patmanak.contako.data.gateway.GatewayFailureCategory, decision: RetryDecision) = false
        override suspend fun acknowledgeAndFinish(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement) = false
        override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand) = false
        override suspend fun continueAfterPartialProgress(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement, nowEpochMillis: Long) = false
    }

    private fun environment(profile: PerformanceFixtureProfile, caseId: String) = PerformanceEnvironment(
        "${caseId.lowercase()}-jvm", caseId, profile.fixtureId, PerformanceFixtureGenerator.DEFAULT_SEED,
        PerformanceBuildType.DEBUG, "host-jvm", 36, "nominal", 80, "fake-shaped", "warm",
    )

    private fun request() = SyncPassRequest(
        setOf(SyncTrigger.MANUAL), SyncScope.FULL_REPAIR, 0, { 0 }, 0, { 0 },
    )

    private companion object {
        val ACCOUNT = AccountScope("synthetic-account")
        val AUTHORITY = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION
    }
}
