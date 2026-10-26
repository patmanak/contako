package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeletedContactRecoveryPhaseOrderTest {
    @Test fun recoveryRunsAfterRemoteReconciliationBeforeDeliveryInOrdinaryAndResumedRepair() = runTest {
        for (scope in SyncScope.entries) {
            val events = mutableListOf<String>()
            val executor = executor(events, DeletedContactRecoveryPhase { _, cancelled ->
                assertFalse(cancelled()); events += "recovery"; SyncPassOutcome.SUCCESS
            })
            assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request(scope)))
            assertTrue(events.indexOf("ingest") < events.indexOf("remote" ) || scope == SyncScope.FULL_REPAIR)
            assertTrue(events.indexOf("remote") < events.indexOf("recovery"))
            assertTrue(events.indexOf("recovery") < events.indexOf("upload"))
        }
    }

    @Test fun cancellationAtRecoveryStopsBeforeAnyOutboxDeliveryInBothPassKinds() = runTest {
        for (scope in SyncScope.entries) {
            val events = mutableListOf<String>()
            val executor = executor(events, DeletedContactRecoveryPhase { _, _ -> events += "recovery"; SyncPassOutcome.CANCELLED })
            assertEquals(SyncPassOutcome.CANCELLED, executor.execute(request(scope)))
            assertTrue("recovery" in events)
            assertFalse("upload" in events)
        }
    }

    private fun request(scope: SyncScope) = SyncPassRequest(setOf(SyncTrigger.MANUAL), scope, 0, { 0 }, 0, { 0 })

    private fun executor(events: MutableList<String>, recovery: DeletedContactRecoveryPhase): ContakoSyncPassExecutor {
        val checkpoints = object : ContactInventoryCheckpointStore {
            var current: VersionedContactInventoryCheckpoint? = null
            override suspend fun load(account: AccountScope) = current
            override suspend fun compareAndSet(account: AccountScope, expectedGeneration: Long?, checkpoint: ContactInventoryCheckpoint): Boolean {
                current = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint); return true
            }
        }
        val metadata = ContactInventoryMetadata(RemoteContactId("remote"), "Synthetic", RemoteVersion("version"),
            1, 1, emptyList(), emptyList(), ContactInventoryVersionProvenance.REMOTE_SERVER,
            ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION)
        val remote = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { _, _ -> GatewayOutcome.Success(ContactInventoryPage(listOf(metadata), null, null, 1,
                ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION)) },
            ProtonVerifiedContactCardGateway { _, id -> GatewayOutcome.Success(VerifiedContactCard(id, RemoteVersion("version"),
                CanonicalContact("owner", "remote"))) },
            PersistentContactInventoryPlanner(checkpoints),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                events += "remote"; CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            })
        val store = RecoveryOrderMutationStore()
        val mutations = DurableMutationOrchestrator(store, MutationPreparationGateway { GatewayOutcome.Success(MutationPreparation.UploadAllowed) },
            MutationUploadGateway { events += "upload"; GatewayOutcome.Success(RemoteMutationAcknowledgement("new-remote", "version")) })
        val android = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { events += "ingest" }
            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) = AndroidInteroperabilityStageResult.Success
        }
        val repair = object : FullRepairExecution {
            override var progress = FullRepairProgress(0, FullRepairPhase.ANDROID_PROJECTION, 1, 1, 0, 0, false, false)
            override fun isCancellationRequested() = false
            override suspend fun checkpoint(phase: FullRepairPhase, completedUnits: Long, totalUnits: Long): Boolean {
                progress = progress.copy(revision = progress.revision + 1, phase = phase, completedUnits = completedUnits, totalUnits = totalUnits)
                return true
            }
            override suspend fun clearCancellation() = true
            override suspend fun clearAfterPublished() = true
        }
        return ContakoSyncPassExecutor(AccountScope("owner"), SyncPrerequisiteChecker { SyncPrerequisiteState.READY }, remote,
            mutations, androidPreflight = AndroidInteroperabilityPreflight { account, _ ->
                AndroidInteroperabilityPreflightResult.Ready(AndroidInteroperabilityContext(account, "synthetic", 0, 0))
            }, androidStage = android, fullRepairCoordinator = FullRepairExecutionCoordinator { repair },
            deletedContactRecovery = recovery)
    }
}

private class RecoveryOrderMutationStore : MutationExecutionStore {
    private var pending = true
    private val command = DurableMutationCommand("owner", "CONTACT", "destination", 1, RemoteMutationOperation.CREATE, 0, 0, false)
    override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long) = 0
    override suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int) = if (pending) listOf(command) else emptyList()
    override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long) = pending
    override suspend fun recordFailure(command: DurableMutationCommand, category: GatewayFailureCategory, decision: RetryDecision) = true
    override suspend fun acknowledgeAndFinish(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement): Boolean {
        pending = false; return true
    }
    override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand) = true
    override suspend fun continueAfterPartialProgress(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement, nowEpochMillis: Long) = true
}
