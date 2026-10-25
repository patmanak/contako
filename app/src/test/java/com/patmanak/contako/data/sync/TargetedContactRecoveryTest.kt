package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.local.TargetedRecoveryRequestEntity
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TargetedContactRecoveryTest {
    @Test fun oneRejectedCardAmong300ReadsOnlyItsVerifiedBodyAndNoAndroidCopyOrGlobalStatus() = runTest {
        val witnesses = (1..300).associate { "witness-$it" to CanonicalContact(ACCOUNT.value, "witness-$it", displayName = "Synthetic witness $it") }
        val before = witnesses.toMap()
        val store = MemoryStore()
        val calls = mutableListOf<String>()
        val recovery = TargetedContactRecovery(store, ProtonVerifiedContactCardGateway { account, id ->
            assertEquals(ACCOUNT, account); assertEquals("rejected", id.value)
            assertEquals("RUNNING", store.row.state)
            calls += "card"
            GatewayOutcome.Success(card())
        }, TargetedAndroidRecovery { _, _, _, _, _ -> error("No Android projection for Proton recheck") },
            prepareProton = { _, _ -> calls += "prepare"; TargetedRecoveryWorkResult.Resolved() }, clock = { 1_000 })
        val (executor, counters) = executor(recovery)
        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request(setOf(SyncTrigger.TARGETED_RECOVERY))))
        assertEquals(listOf("prepare", "card"), calls)
        assertEquals("RESOLVED", store.row.state)
        assertEquals(0, counters.inventory)
        assertEquals(0, counters.android)
        assertEquals(0, counters.published)
        assertEquals(42L, counters.lastSuccess)
        assertEquals(1, counters.mutationRecovery)
        assertEquals(before, witnesses)
    }

    @Test fun coalescedOrdinaryPassKeepsItsWorkAndOnlySettlesScopedRequestsWithoutAnotherFetch() = runTest {
        val store = MemoryStore()
        val recovery = TargetedContactRecovery(store,
            ProtonVerifiedContactCardGateway { _, _ -> error("Coalesced pass must not reread the target") },
            TargetedAndroidRecovery { _, _, _, _, _ -> error("No additional targeted projection") },
            prepareProton = { _, _ -> error("No second targeted pass") })
        val (executor, counters) = executor(recovery)
        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request(setOf(SyncTrigger.MANUAL, SyncTrigger.TARGETED_RECOVERY))))
        assertEquals(1, counters.inventory)
        assertEquals(1, counters.published)
        assertEquals(1, store.settled)
    }

    @Test fun lostReturnAfterAtomicCompletionReopensWithoutFetchingAgain() = runTest {
        val store = MemoryStore().apply { loseCompletionReturn = true }
        var reads = 0
        fun recovery() = TargetedContactRecovery(store, ProtonVerifiedContactCardGateway { _, _ ->
            reads++; GatewayOutcome.Success(card()) }, TargetedAndroidRecovery { _, _, _, _, _ -> error("Not Android") },
            prepareProton = { _, _ -> TargetedRecoveryWorkResult.Resolved() }, clock = { 1_000 })
        try { recovery().run(ACCOUNT) { false }; fail("Synthetic lost return expected") } catch (_: CancellationException) { }
        assertEquals("RESOLVED", store.row.state)
        assertEquals(SyncPassOutcome.SUCCESS, recovery().run(ACCOUNT) { false })
        assertEquals(1, reads)
    }

    @Test fun obsoleteSourceAndCancellationCannotReadOrAdoptACard() = runTest {
        for (cancelled in listOf(false, true)) {
            val store = MemoryStore().apply { obsolete = true }
            val recovery = TargetedContactRecovery(store,
                ProtonVerifiedContactCardGateway { _, _ -> error("No stale or cancelled read") },
                TargetedAndroidRecovery { _, _, _, _, _ -> error("No stale or cancelled projection") },
                prepareProton = { _, _ -> error("No stale preparation") })
            val result = recovery.run(ACCOUNT) { cancelled }
            assertEquals(if (cancelled) SyncPassOutcome.CANCELLED else SyncPassOutcome.ACTION_REQUIRED, result)
            assertEquals(if (cancelled) "REQUESTED" else "STALE", store.row.state)
        }
    }

    @Test fun cryptoAndUnscoped404RemainFailuresWithoutDeletionOrResurrection() = runTest {
        for (category in listOf(GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED, GatewayFailureCategory.NOT_FOUND)) {
            val store = MemoryStore()
            val recovery = TargetedContactRecovery(store, ProtonVerifiedContactCardGateway { _, _ -> GatewayOutcome.Failure(category) },
                TargetedAndroidRecovery { _, _, _, _, _ -> error("Not Android") },
                prepareProton = { _, _ -> TargetedRecoveryWorkResult.Resolved() })
            assertEquals(SyncPassOutcome.ACTION_REQUIRED, recovery.run(ACCOUNT) { false })
            assertEquals("ACTION_REQUIRED", store.row.state)
            assertEquals(0, store.commits)
        }
    }

    @Test fun targetedReadRetainsItsCurrentFailureAndHydrationBoundaryWithoutAdoptingAnUnverifiedCard() = runTest {
        val cases = listOf(
            GatewayOutcome.Failure(GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED,
                contactHydrationCategory = GatewayContactHydrationCategory.SIGNATURE_VERIFICATION),
            GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE,
                contactHydrationCategory = GatewayContactHydrationCategory.VCARD_PARSE),
            GatewayOutcome.Failure(GatewayFailureCategory.AUTHENTICATION_REQUIRED),
            GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED),
            GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN),
        )
        for (failure in cases) {
            val store = MemoryStore()
            val recovery = TargetedContactRecovery(store, ProtonVerifiedContactCardGateway { _, _ -> failure },
                TargetedAndroidRecovery { _, _, _, _, _ -> error("No Android write") },
                prepareProton = { _, _ -> TargetedRecoveryWorkResult.Resolved() })
            recovery.run(ACCOUNT) { false }
            val observed = store.finished as TargetedRecoveryWorkResult.Failed
            assertEquals(failure.category, observed.category)
            assertEquals(failure.contactHydrationCategory, observed.hydrationCategory)
            assertEquals(0, store.commits)
        }
    }

    @Test fun offlinePrerequisiteDefersTheCommandAndNeverPublishesAccountSuccess() = runTest {
        val store = MemoryStore()
        val recovery = TargetedContactRecovery(store, ProtonVerifiedContactCardGateway { _, _ -> error("Offline") },
            TargetedAndroidRecovery { _, _, _, _, _ -> error("Offline") }, prepareProton = { _, _ -> error("Offline") })
        val (executor, counters) = executor(recovery, SyncPrerequisiteState.OFFLINE)
        assertEquals(SyncPassOutcome.RETRY_WAITING, executor.execute(request(setOf(SyncTrigger.TARGETED_RECOVERY))))
        assertEquals(0, counters.published)
        assertEquals(42L, counters.lastSuccess)
        assertEquals("DEFERRED", store.row.state)
    }

    @Test fun androidRecoveryUsesExpectedIdentityRevisionEpochAndDoesNotFetchProton() = runTest {
        val store = MemoryStore(kind = TargetedRecoveryKind.ANDROID_COPY)
        var invoked = false
        val recovery = TargetedContactRecovery(store,
            ProtonVerifiedContactCardGateway { _, _ -> error("Android repair must not fetch Proton") },
            TargetedAndroidRecovery { account, target, revision, epoch, cancelled ->
                assertEquals(ACCOUNT, account); assertEquals("rejected", target)
                assertEquals(7L, revision); assertEquals(3L, epoch); assertFalse(cancelled())
                invoked = true; TargetedRecoveryWorkResult.Resolved(8)
            }, prepareProton = { _, _ -> error("No Proton preparation") })
        assertEquals(SyncPassOutcome.SUCCESS, recovery.run(ACCOUNT) { false })
        assertTrue(invoked)
        assertEquals(8L, (store.finished as TargetedRecoveryWorkResult.Resolved).canonicalRevision)
    }

    @Test fun androidOnlyRecoveryDoesNotRequireANetworkOrProtonSessionCheck() = runTest {
        val store = MemoryStore(kind = TargetedRecoveryKind.ANDROID_COPY)
        var projected = false
        val recovery = TargetedContactRecovery(store, ProtonVerifiedContactCardGateway { _, _ -> error("No Proton") },
            TargetedAndroidRecovery { _, _, _, _, _ -> projected = true; TargetedRecoveryWorkResult.Resolved(7) },
            prepareProton = { _, _ -> error("No remote preparation") })
        assertEquals(SyncPassOutcome.SUCCESS, recovery.run(ACCOUNT,
            remotePrerequisites = { error("Offline Android work must not check remote prerequisites") },
            beforeRemote = { error("No remote mutation recovery required") }) { false })
        assertTrue(projected)
        val (executor, counters) = executor(TargetedContactRecovery(MemoryStore(kind = TargetedRecoveryKind.ANDROID_COPY),
            ProtonVerifiedContactCardGateway { _, _ -> error("No Proton") },
            TargetedAndroidRecovery { _, _, _, _, _ -> TargetedRecoveryWorkResult.Resolved(7) },
            prepareProton = { _, _ -> error("No Proton") }), SyncPrerequisiteState.OFFLINE)
        assertEquals(SyncPassOutcome.SUCCESS, executor.execute(request(setOf(SyncTrigger.TARGETED_RECOVERY))))
        assertEquals(0, counters.published)
        assertEquals(0, counters.mutationRecovery)
    }

    private class Counters(var inventory: Int = 0, var android: Int = 0, var published: Int = 0,
        var lastSuccess: Long = 42, var mutationRecovery: Int = 0)

    private fun executor(recovery: TargetedContactRecovery, prerequisite: SyncPrerequisiteState = SyncPrerequisiteState.READY): Pair<ContakoSyncPassExecutor, Counters> {
        val counters = Counters()
        val checkpoint = object : ContactInventoryCheckpointStore {
            private var stored: VersionedContactInventoryCheckpoint? = null
            override suspend fun load(account: AccountScope) = stored
            override suspend fun compareAndSet(account: AccountScope, expectedGeneration: Long?, checkpoint: ContactInventoryCheckpoint): Boolean {
                stored = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint); return true
            }
        }
        val remote = IncrementalRemoteContactStage(ProtonContactInventoryGateway { _, _ ->
            counters.inventory++
            GatewayOutcome.Success(ContactInventoryPage(emptyList(), null, null, 0, ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY))
        }, ProtonVerifiedContactCardGateway { _, _ -> error("No normal card") }, PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            })
        val mutations = object : MutationExecutionStore {
            override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long): Int { counters.mutationRecovery++; return 0 }
            override suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int) = emptyList<DurableMutationCommand>()
            override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long) = error("No outbox")
            override suspend fun recordFailure(command: DurableMutationCommand, category: GatewayFailureCategory, decision: RetryDecision) = error("No outbox")
            override suspend fun acknowledgeAndFinish(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement) = error("No outbox")
            override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand) = error("No outbox")
            override suspend fun continueAfterPartialProgress(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement, nowEpochMillis: Long) = error("No outbox")
        }
        val executor = ContakoSyncPassExecutor(ACCOUNT, SyncPrerequisiteChecker { prerequisite }, remote,
            DurableMutationOrchestrator(mutations, { error("No upload") }, { error("No upload") }),
            statusPublisher = SyncPassStatusPublisher { counters.published++; if (it == SyncPassOutcome.SUCCESS) counters.lastSuccess = 99 },
            androidPreflight = AndroidInteroperabilityPreflight { account, _ ->
                AndroidInteroperabilityPreflightResult.Ready(AndroidInteroperabilityContext(account, "synthetic", 0, 0))
            }, androidStage = object : AndroidInteroperabilityStage {
                override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                    counters.android++; return AndroidInteroperabilityStageResult.Success
                }
                override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean): AndroidInteroperabilityStageResult {
                    counters.android++; return AndroidInteroperabilityStageResult.Success
                }
            }, targetedRecovery = recovery)
        return executor to counters
    }

    private class MemoryStore(kind: TargetedRecoveryKind = TargetedRecoveryKind.PROTON_CARD) : TargetedRecoveryExecutionStore {
        var row = TargetedRecoveryRequestEntity(ACCOUNT.value, kind.name, "rejected", "request", "source",
            if (kind == TargetedRecoveryKind.ANDROID_COPY) 7 else null, if (kind == TargetedRecoveryKind.ANDROID_COPY) 3 else null,
            "REQUESTED", 0, 0, 0, 0, null, null)
        var obsolete = false
        var loseCompletionReturn = false
        var commits = 0
        var settled = 0
        var finished: TargetedRecoveryWorkResult? = null
        override suspend fun recoverInterrupted(account: AccountScope, now: Long) {
            if (row.state == "RUNNING") row = row.copy(state = "DEFERRED", claimToken = null)
        }
        override suspend fun eligible(account: AccountScope, now: Long) =
            if (row.accountId == account.value && row.state in setOf("REQUESTED", "DEFERRED") && row.nextEligibleAt <= now) listOf(row) else emptyList()
        override suspend fun claim(request: TargetedRecoveryRequestEntity, now: Long) =
            row.copy(state = "RUNNING", claimToken = "claim", attemptCount = row.attemptCount + 1).also { row = it }
        override suspend fun validate(request: TargetedRecoveryRequestEntity) = if (obsolete) TargetedRecoveryWorkResult.Stale() else null
        override suspend fun rebaseAfterIngestion(request: TargetedRecoveryRequestEntity, revision: Long?) = request.copy(expectedRevision = revision)
        override suspend fun completeVerified(request: TargetedRecoveryRequestEntity, card: VerifiedContactCard, now: Long,
            isCancelled: () -> Boolean): TargetedRecoveryWorkResult {
            commits++; row = row.copy(state = "RESOLVED", claimToken = null)
            if (loseCompletionReturn) throw CancellationException()
            return TargetedRecoveryWorkResult.Resolved()
        }
        override suspend fun finish(request: TargetedRecoveryRequestEntity, result: TargetedRecoveryWorkResult, now: Long, isCancelled: () -> Boolean) {
            finished = result
            if (row.state != "RUNNING" || isCancelled()) return
            row = row.copy(state = when (result) {
                is TargetedRecoveryWorkResult.Resolved -> "RESOLVED"
                is TargetedRecoveryWorkResult.Stale -> "STALE"
                is TargetedRecoveryWorkResult.Failed -> if (result.category == GatewayFailureCategory.NETWORK_UNAVAILABLE) "DEFERRED" else "ACTION_REQUIRED"
                TargetedRecoveryWorkResult.Cancelled -> "DEFERRED"
            }, claimToken = null)
        }
        override suspend fun settleAfterOrdinaryPass(account: AccountScope, now: Long, isCancelled: () -> Boolean) { settled++ }
    }

    private companion object {
        val ACCOUNT = AccountScope("targeted-synthetic-owner")
        fun card() = VerifiedContactCard(RemoteContactId("rejected"), RemoteVersion("verified-version"),
            CanonicalContact(ACCOUNT.value, "rejected", displayName = "Synthetic recovered", remoteContactId = "rejected"))
        fun request(triggers: Set<SyncTrigger>) = SyncPassRequest(triggers, SyncScope.INCREMENTAL, 0, { 0 }, 0, { 0 })
    }
}
