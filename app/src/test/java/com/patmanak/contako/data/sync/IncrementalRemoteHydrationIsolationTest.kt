package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.CanonicalContact
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Stage/coordinator regressions: an acknowledged event MUST be covered by data or a durable obligation. */
class IncrementalRemoteHydrationIsolationTest {
    @Test fun oneRejectedCardAtEveryBatchBoundaryCannotStarveFiftyHealthyCards() = runTest {
        for (position in listOf(1, 24, 25, 26, 51)) {
            val fixture = Fixture(position)
            assertEquals("position=$position", RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
            assertEquals(50, fixture.canonical.size)
            assertFalse(fixture.canonical.contains(fixture.bad))
            assertEquals(setOf(fixture.bad), fixture.obligations.pending(ACCOUNT))
            assertEquals("events-1", fixture.checkpoints.current!!.checkpoint.eventCursor)
            assertEquals(51, fixture.checkpoints.current!!.checkpoint.entries.size)
            assertEquals(setOf<RemoteContactId>(), fixture.obligations.pending(AccountScope("other-account")))

            // New objects model process restart; only durable stores survive.
            fixture.reads.clear()
            assertEquals(RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
            assertEquals(listOf(fixture.bad), fixture.reads)
            assertEquals(50, fixture.canonical.size)
            assertEquals("events-2", fixture.checkpoints.current!!.checkpoint.eventCursor)

            fixture.failure = null
            fixture.reads.clear()
            val corrected = fixture.stage().run(ACCOUNT) as RemoteContactStageResult.Success
            assertEquals(1, corrected.hydratedCount)
            assertEquals(listOf(fixture.bad), fixture.reads)
            assertEquals(51, fixture.canonical.size)
            assertTrue(fixture.obligations.pending(ACCOUNT).isEmpty())
            fixture.reads.clear()
            assertEquals(0, (fixture.stage().run(ACCOUNT) as RemoteContactStageResult.Success).hydratedCount)
            assertTrue(fixture.reads.isEmpty())
        }
    }

    @Test fun eachCardScopedRefusalRemainsAnObligationRatherThanAnUnverifiedContact() = runTest {
        for (category in listOf(GatewayFailureCategory.MALFORMED_RESPONSE,
            GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED, GatewayFailureCategory.VALIDATION_REJECTED,
            GatewayFailureCategory.NOT_FOUND, GatewayFailureCategory.CONFLICT)) {
            val fixture = Fixture(26).apply { failure = category }
            assertEquals(category.name, RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
            assertEquals(50, fixture.canonical.size)
            assertEquals(category, fixture.obligations.failures.getValue(ACCOUNT).getValue(fixture.bad))
            assertEquals(setOf(fixture.bad), fixture.obligations.pending(ACCOUNT))
        }
    }

    @Test fun accountAuthenticationAndTransientFailuresNeverAdvanceCursorAsPartialSuccess() = runTest {
        for (category in listOf(GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            GatewayFailureCategory.NETWORK_UNAVAILABLE, GatewayFailureCategory.RATE_LIMITED)) {
            val fixture = Fixture(26).apply { failure = category }
            val result = fixture.stage().run(ACCOUNT)
            assertFalse(result is RemoteContactStageResult.Success)
            assertFalse(result is RemoteContactStageResult.Partial)
            assertNull(fixture.checkpoints.current)
            assertTrue(fixture.obligations.pending(ACCOUNT).isEmpty())
            assertEquals(25, fixture.canonical.size)
        }
    }

    @Test fun lostLocalCommitAfterDeferralKeepsObligationAndReplaysWithoutAcknowledgingEvents() = runTest {
        val fixture = Fixture(1).apply { rejectCanonicalCommit = true }
        assertEquals(RemoteContactStageResult.LocalPersistenceFailure, fixture.stage().run(ACCOUNT))
        assertNull(fixture.checkpoints.current)
        assertEquals(setOf(fixture.bad), fixture.obligations.pending(ACCOUNT))
        assertTrue(fixture.canonical.isEmpty())
        fixture.rejectCanonicalCommit = false
        assertEquals(RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
        assertEquals(50, fixture.canonical.size)
        assertEquals(setOf(fixture.bad), fixture.obligations.pending(ACCOUNT))
    }

    @Test fun cancellationAfterLastDurableBatchCannotDiscardRejectedCardOrAcknowledgeCursor() = runTest {
        val fixture = Fixture(1)
        var cancel = false
        val stage = fixture.stage(RemoteContactBatchObserver { _, _, _ -> if (fixture.canonical.size == 50) cancel = true })
        assertEquals(RemoteContactStageResult.Cancelled, stage.run(ACCOUNT, isCancellationRequested = { cancel }))
        assertNull(fixture.checkpoints.current)
        assertEquals(50, fixture.canonical.size)
        assertEquals(setOf(fixture.bad), fixture.obligations.pending(ACCOUNT))
        assertEquals(RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
    }

    @Test fun checkpointAcknowledgementLostAfterCommitStillRetainsFailedCardForRestart() = runTest {
        val fixture = Fixture(51)
        fixture.checkpoints.failAfterCommit = true
        assertEquals(RemoteContactStageResult.LocalPersistenceFailure, fixture.stage().run(ACCOUNT))
        assertEquals("events-1", fixture.checkpoints.current!!.checkpoint.eventCursor)
        assertEquals(setOf(fixture.bad), fixture.obligations.pending(ACCOUNT))
        fixture.checkpoints.failAfterCommit = false
        fixture.reads.clear()
        assertEquals(RemoteContactStageResult.Partial(1), fixture.stage().run(ACCOUNT))
        assertEquals(listOf(fixture.bad), fixture.reads)
        assertEquals(50, fixture.canonical.size)
    }

    @Test fun failureToPersistObligationCannotAdvanceCheckpoint() = runTest {
        val fixture = Fixture(1)
        fixture.obligations.rejectWrites = true
        val attempted = runCatching { fixture.stage().run(ACCOUNT) }
        assertFalse(attempted.getOrNull() is RemoteContactStageResult.Success)
        assertFalse(attempted.getOrNull() is RemoteContactStageResult.Partial)
        assertNull(fixture.checkpoints.current)
        assertTrue(fixture.obligations.pending(ACCOUNT).isEmpty())
        assertTrue(fixture.canonical.isEmpty())
    }

    @Test fun deferredCardDeletedBeforeFirstCheckpointRequiresTargetedAbsenceProofBeforeQueueResolution() = runTest {
        val rejected = id(1)
        val obligations = MemoryObligations()
        val canonical = mutableSetOf<RemoteContactId>()
        val deleted = mutableSetOf<RemoteContactId>()
        val checkpoints = MemoryCheckpoint {
            assertTrue("The orphan obligation must be resolved before acknowledging its removal", obligations.pending(ACCOUNT).isEmpty())
            assertEquals((2..51).map(::id).toSet(), canonical)
            assertEquals(setOf(rejected), deleted)
        }
        var includeRejectedInInventory = true
        var interruptCanonicalCommit = true
        var existence: GatewayOutcome<RemoteContactPresence> = GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN)
        var existenceReads = 0
        fun stage() = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { _, _ ->
                val entries = (1..51).filter { includeRejectedInInventory || it != 1 }.map(::metadata)
                GatewayOutcome.Success(ContactInventoryPage(entries, null, null, entries.size,
                    ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY))
            },
            ProtonVerifiedContactCardGateway { _, contactId ->
                if (contactId == rejected) GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
                else GatewayOutcome.Success(VerifiedContactCard(contactId, RemoteVersion("revision-1"),
                    CanonicalContact(accountId = ACCOUNT.value, id = contactId.value, displayName = "Synthetic contact")))
            },
            PersistentContactInventoryPlanner(checkpoints),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                if (interruptCanonicalCommit) throw IOException("Synthetic interruption before initial checkpoint")
                canonical += cards.map { it.id }
                deleted += deletions
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
            existenceGateway = ProtonContactExistenceGateway { account, contactId ->
                assertEquals(ACCOUNT, account)
                assertEquals(rejected, contactId)
                existenceReads++
                existence
            },
            eventsGateway = ProtonContactEventsGateway { _, previous ->
                assertNull(previous)
                GatewayOutcome.Success(ContactEventsDelta("after-proof", emptySet(), true))
            },
            obligations = obligations,
        )

        assertEquals(RemoteContactStageResult.LocalPersistenceFailure, stage().run(ACCOUNT))
        assertNull(checkpoints.current)
        assertEquals(setOf(rejected), obligations.pending(ACCOUNT))

        // The next complete public directory omits an ID that was never in a checkpoint.
        includeRejectedInInventory = false
        interruptCanonicalCommit = false
        assertEquals(RemoteContactStageResult.RetryWaiting(GatewayFailureCategory.UNKNOWN), stage().run(ACCOUNT))
        assertEquals(setOf(rejected), obligations.pending(ACCOUNT))
        assertNull(checkpoints.current)
        assertTrue(canonical.isEmpty())
        existence = GatewayOutcome.Success(RemoteContactPresence.PRESENT)
        assertEquals(RemoteContactStageResult.StalePlan, stage().run(ACCOUNT))
        assertEquals(setOf(rejected), obligations.pending(ACCOUNT))
        assertNull(checkpoints.current)

        existence = GatewayOutcome.Success(RemoteContactPresence.CONFIRMED_ABSENT)
        val completed = stage().run(ACCOUNT) as RemoteContactStageResult.Success
        assertEquals(50, completed.hydratedCount)
        assertEquals(1, completed.deletedCount)
        assertEquals(3, existenceReads)
        assertTrue(obligations.pending(ACCOUNT).isEmpty())
        assertEquals(50, checkpoints.current!!.checkpoint.entries.size)
        assertEquals("after-proof", checkpoints.current!!.checkpoint.eventCursor)
    }

    private class Fixture(position: Int) {
        val bad = id(position)
        val canonical = mutableSetOf<RemoteContactId>()
        val reads = mutableListOf<RemoteContactId>()
        val obligations = MemoryObligations()
        val checkpoints = MemoryCheckpoint {
            // This assertion runs at the actual checkpoint boundary, not just after the pass.
            val covered = canonical + obligations.pending(ACCOUNT)
            assertEquals((1..51).map(::id).toSet(), covered)
        }
        var failure: GatewayFailureCategory? = GatewayFailureCategory.MALFORMED_RESPONSE
        var rejectCanonicalCommit = false
        private var eventReads = 0

        fun stage(observer: RemoteContactBatchObserver = RemoteContactBatchObserver.NONE) = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { account, cursor ->
                assertEquals(ACCOUNT, account)
                assertNull(cursor)
                GatewayOutcome.Success(ContactInventoryPage((1..51).map { metadata(it) }, null, null, 51,
                    ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION))
            },
            ProtonVerifiedContactCardGateway { account, contactId ->
                assertEquals(ACCOUNT, account)
                reads += contactId
                if (contactId == bad && failure != null) GatewayOutcome.Failure(failure!!)
                else GatewayOutcome.Success(VerifiedContactCard(contactId, RemoteVersion("revision-1"),
                    CanonicalContact(accountId = ACCOUNT.value, id = contactId.value, displayName = "Synthetic contact")))
            },
            PersistentContactInventoryPlanner(checkpoints),
            RemoteCanonicalReconciliationStore { account, _, cards, labels, deletions ->
                assertEquals(ACCOUNT, account)
                if (rejectCanonicalCommit) throw IOException("Synthetic canonical store interruption")
                assertFalse(cards.any { it.id == bad && failure != null })
                canonical += cards.map { it.id }
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
            batchObserver = observer,
            eventsGateway = ProtonContactEventsGateway { _, previous ->
                assertEquals(checkpoints.current?.checkpoint?.eventCursor, previous)
                eventReads++
                GatewayOutcome.Success(ContactEventsDelta("events-$eventReads", emptySet(), previous == null))
            },
            obligations = obligations,
        )
    }

    private class MemoryObligations : RemoteHydrationObligationStore {
        val failures = mutableMapOf<AccountScope, MutableMap<RemoteContactId, GatewayFailureCategory>>()
        var rejectWrites = false
        override suspend fun pending(account: AccountScope) = failures[account]?.keys?.toSet().orEmpty()
        override suspend fun defer(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) {
            if (rejectWrites) throw IOException("Synthetic obligation store interruption")
            failures.getOrPut(account) { mutableMapOf() }[baseline.id] = failure.category
        }
        override suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>) {
            ids.forEach { failures[account]?.remove(it) }
        }
    }

    private class MemoryCheckpoint(private val beforeCommit: suspend () -> Unit) : ContactInventoryCheckpointStore {
        var current: VersionedContactInventoryCheckpoint? = null
        var failAfterCommit = false
        override suspend fun load(account: AccountScope) = current
        override suspend fun compareAndSet(account: AccountScope, expectedGeneration: Long?, checkpoint: ContactInventoryCheckpoint): Boolean {
            if (current?.generation != expectedGeneration) return false
            beforeCommit()
            current = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint)
            if (failAfterCommit) throw IOException("Synthetic lost checkpoint acknowledgement")
            return true
        }
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-account")
        fun id(position: Int) = RemoteContactId("contact-${position.toString().padStart(3, '0')}")
        fun metadata(position: Int) = ContactInventoryMetadata(id(position), "Synthetic contact", RemoteVersion("revision-1"),
            100, 1L, emptyList(), emptyList(),
            ContactInventoryVersionProvenance.REMOTE_SERVER, ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION)
    }
}
