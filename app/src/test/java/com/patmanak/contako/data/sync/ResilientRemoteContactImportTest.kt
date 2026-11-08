package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.CanonicalContact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ResilientRemoteContactImportTest {
    @Test fun transientCardFailureDoesNotReplayHealthyCardsOrConsumeItsDeadline() = runTest {
        val fixture = Fixture(1_000)
        fixture.failureAt = "0900"
        fixture.failure = GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
        assertEquals(RemoteContactStageResult.Partial(1, 1), fixture.stage().run(ACCOUNT))
        assertEquals(999, fixture.committed.size)
        assertEquals(1_000, fixture.reads.size)
        assertNotNull(fixture.checkpoint.current)
        fixture.reads.clear()
        assertEquals(RemoteContactStageResult.Partial(1, 1), fixture.stage().run(ACCOUNT))
        assertTrue(fixture.reads.isEmpty())
        fixture.now += 5_000
        fixture.failureAt = null
        assertTrue(fixture.stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(listOf("0900"), fixture.reads)
        assertEquals(1_000, fixture.committed.size)
        assertTrue(fixture.obligations.rows.isEmpty())
    }

    @Test fun rateLimitStopsNewReadWindowsAndDurablyDefersUnreadCards() = runTest {
        val fixture = Fixture(1_000)
        fixture.failureAt = "0050"
        fixture.failure = GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 60_000)
        assertEquals(RemoteContactStageResult.Partial(951, 951), fixture.stage().run(ACCOUNT))
        assertEquals(50, fixture.reads.size)
        assertEquals(49, fixture.committed.size)
        assertEquals(1, fixture.obligations.rows.getValue(RemoteContactId("0050")).attempts)
        assertEquals(0, fixture.obligations.rows.getValue(RemoteContactId("0051")).attempts)
        fixture.now += 59_999
        fixture.reads.clear()
        assertEquals(RemoteContactStageResult.Partial(951, 951), fixture.stage().run(ACCOUNT))
        assertTrue(fixture.reads.isEmpty())
        fixture.now++
        fixture.failureAt = null
        assertTrue(fixture.stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(951, fixture.reads.size)
        assertFalse(fixture.reads.contains("0049"))
    }

    @Test fun attributableEmailRemapRefusalQuarantinesOnlyItsCardAfterAtomicBatchRollback() = runTest {
        val fixture = Fixture(1_000)
        fixture.localRefusalAt = "0030"
        assertEquals(RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
        assertEquals(999, fixture.committed.size)
        assertFalse(fixture.committed.contains(RemoteContactId("0030")))
        assertEquals(GatewayFailureCategory.CONFLICT,
            fixture.obligations.rows.getValue(RemoteContactId("0030")).failure.category)
        fixture.reads.clear()
        assertEquals(RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
        assertTrue(fixture.reads.isEmpty())
        assertEquals(2, fixture.checkpoint.commits)
    }

    @Test fun storageAndOwnershipFailuresRemainGlobalAndDoNotAdvanceCheckpoint() = runTest {
        listOf<Exception>(IllegalStateException("SYNTHETIC_STORAGE_FAILURE"),
            LocalReconciliationInvariantFailure(LocalReconciliationInvariant.CREATION_UID),
            LocalReconciliationInvariantFailure(LocalReconciliationInvariant.INTENT_REVISION)).forEach { failure ->
            val fixture = Fixture(30)
            fixture.globalFailure = failure
            assertEquals(RemoteContactStageResult.LocalPersistenceFailure, fixture.stage().run(ACCOUNT))
            assertNull(fixture.checkpoint.current)
            assertTrue(fixture.obligations.rows.isEmpty())
        }
    }

    @Test fun cancellationDuringCanonicalCommitIsNotConvertedToStorageFailure() = runTest {
        val fixture = Fixture(1)
        fixture.globalFailure = CancellationException("Synthetic cancellation")
        try {
            fixture.stage().run(ACCOUNT)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(fixture.checkpoint.current)
        assertTrue(fixture.obligations.rows.isEmpty())
    }

    @Test fun unknownEmailEventReopensAnIgnoredCardWhosePublicFingerprintDidNotChange() = runTest {
        val fixture = Fixture(1)
        fixture.failureAt = "0001"
        fixture.failure = GatewayOutcome.Failure(GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED)
        assertEquals(RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
        fixture.obligations.ignored += RemoteContactId("0001")
        fixture.reads.clear()
        assertEquals(RemoteContactStageResult.Partial(1, ignoredCount = 1), fixture.stage().run(ACCOUNT))
        assertTrue(fixture.reads.isEmpty())
        fixture.failureAt = null
        fixture.events = ContactEventsDelta("changed", emptySet(), false, setOf(RemoteEmailId("unknown-email-event")))
        assertTrue(fixture.stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(listOf("0001"), fixture.reads)
        assertTrue(fixture.obligations.rows.isEmpty())
    }

    @Test fun transientImportFailureHasAClosedGatewayDiagnosticAndObserverFailureCannotChangeResult() = runTest {
        val fixture = Fixture(1)
        fixture.failureAt = "0001"
        fixture.failure = GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
        val diagnostics = mutableListOf<Pair<GatewayDiagnosticBoundary, GatewayFailureCategory>>()
        val observer = object : SyncPassStageObserver {
            override fun onStage(stage: SyncPassStage) = Unit
            override fun onGatewayFailure(boundary: GatewayDiagnosticBoundary, failure: GatewayOutcome.Failure,
                operation: RemoteMutationOperation?) {
                diagnostics += boundary to failure.category
                error("Synthetic observer failure")
            }
        }
        assertEquals(RemoteContactStageResult.Partial(1, 1), fixture.stage(observer).run(ACCOUNT))
        assertEquals(listOf(GatewayDiagnosticBoundary.CARD_READ to GatewayFailureCategory.TIMEOUT), diagnostics)
        assertNotNull(fixture.checkpoint.current)
    }

    private class Fixture(count: Int) {
        val metadata = (1..count).map { index -> ContactInventoryMetadata(
            RemoteContactId(index.toString().padStart(4, '0')), "Synthetic", RemoteVersion("v1"),
            100, 1, emptyList(), emptyList(), ContactInventoryVersionProvenance.REMOTE_SERVER,
            ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION) }
        var now = 100_000L
        val checkpoint = Checkpoint()
        val obligations = Obligations { now }
        val reads = mutableListOf<String>()
        val committed = mutableSetOf<RemoteContactId>()
        var failureAt: String? = null
        var failure = GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
        var localRefusalAt: String? = null
        var globalFailure: Exception? = null
        var events: ContactEventsDelta? = null

        fun stage(observer: SyncPassStageObserver = SyncPassStageObserver { }) = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { _, _ -> GatewayOutcome.Success(ContactInventoryPage(metadata,
                null, null, metadata.size, ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION)) },
            ProtonVerifiedContactCardGateway { _, id ->
                reads += id.value
                if (id.value == failureAt) failure else GatewayOutcome.Success(VerifiedContactCard(id,
                    RemoteVersion("v1"), CanonicalContact(ACCOUNT.value, id.value, displayName = "Synthetic")))
            }, PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deleted ->
                globalFailure?.let { throw it }
                // Simulates atomic commit: a known refusal happens before any card is written.
                if (cards.any { it.id.value == localRefusalAt }) throw LocalReconciliationInvariantFailure(
                    LocalReconciliationInvariant.PENDING_GROUP_EMAIL_REMAP)
                committed += cards.map { it.id }
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deleted)
            }, obligations = obligations, importObserver = observer,
            eventsGateway = events?.let { delta -> ProtonContactEventsGateway { _, _ -> GatewayOutcome.Success(delta) } })
    }

    private class Checkpoint : ContactInventoryCheckpointStore {
        var current: VersionedContactInventoryCheckpoint? = null
        var commits = 0
        override suspend fun load(account: AccountScope) = current
        override suspend fun compareAndSet(account: AccountScope, expectedGeneration: Long?,
            checkpoint: ContactInventoryCheckpoint): Boolean {
            if (current?.generation != expectedGeneration) return false
            current = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1L) + 1, checkpoint)
            commits++
            return true
        }
    }

    private class Obligations(private val clock: () -> Long) : RemoteHydrationObligationStore {
        data class Row(val failure: GatewayOutcome.Failure, val deadline: Long, val attempts: Int)
        val rows = mutableMapOf<RemoteContactId, Row>()
        val ignored = mutableSetOf<RemoteContactId>()
        override suspend fun pending(account: AccountScope) = rows.keys.toSet()
        override suspend fun defer(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) {
            save(baseline, failure, true)
        }
        override suspend fun postpone(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) {
            save(baseline, failure, false)
        }
        private fun save(baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure, attempted: Boolean) {
            rows[baseline.id] = Row(failure, clock() + maxOf(5_000L, failure.retryAfterMillis ?: 0L),
                (rows[baseline.id]?.attempts ?: 0) + if (attempted) 1 else 0)
        }
        override suspend fun readDisposition(account: AccountScope, baseline: ContactInventoryBaseline,
            sourceChanged: Boolean): RemoteHydrationReadDisposition {
            val row = rows[baseline.id] ?: return RemoteHydrationReadDisposition.READ
            if (row.failure.category.isTransientHydrationFailure && row.deadline > clock())
                return RemoteHydrationReadDisposition.RETRY_WAITING
            if (sourceChanged) return RemoteHydrationReadDisposition.READ
            if (baseline.id in ignored) return RemoteHydrationReadDisposition.IGNORED
            if (!row.failure.category.isTransientHydrationFailure) return RemoteHydrationReadDisposition.ACTION_REQUIRED
            return if (row.deadline > clock()) RemoteHydrationReadDisposition.RETRY_WAITING else RemoteHydrationReadDisposition.READ
        }
        override suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>) {
            ids.forEach(rows::remove)
            ignored -= ids
        }
    }

    companion object { private val ACCOUNT = AccountScope("synthetic-account") }
}
