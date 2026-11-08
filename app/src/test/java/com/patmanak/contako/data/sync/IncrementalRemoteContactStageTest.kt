package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayFailureCategory
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
import com.patmanak.contako.domain.model.CanonicalContact
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalRemoteContactStageTest {
    @Test fun sustainedOutageStopsNewWindowsAndResumesOnlyDurablyDeferredCardsAfterDeadline() = runTest {
        for (category in listOf(GatewayFailureCategory.NETWORK_UNAVAILABLE, GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE, GatewayFailureCategory.UNKNOWN)) {
            val inventory = (1..300).map { metadata(it.toString().padStart(4, '0'), 1) }
            val checkpoint = FakeCheckpointStore()
            var now = 100_000L
            val deferred = OutageObligations { now }
            val reads = mutableListOf<RemoteContactId>()
            val persisted = mutableSetOf<RemoteContactId>()
            var outage = true
            fun candidate() = IncrementalRemoteContactStage(
                pagedGateway { pages(inventory, 100) },
                ProtonVerifiedContactCardGateway { _, id ->
                    reads += id
                    if (outage && id.value.toInt() > 25) GatewayOutcome.Failure(category, retryAfterMillis = 30_000)
                    else success(card(id, 1))
                }, PersistentContactInventoryPlanner(checkpoint),
                RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                    persisted += cards.map { it.id }
                    CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
                }, obligations = deferred,
            )

            assertEquals(category.name, RemoteContactStageResult.Partial(275, 275), candidate().run(ACCOUNT))
            assertEquals(35, reads.size) // 25 committed, then only the already-started ten-request window.
            assertEquals(inventory.take(25).map { it.id }.toSet(), persisted)
            assertEquals(inventory.drop(25).map { it.id }.toSet(), deferred.rows.keys)
            assertEquals(10, deferred.rows.values.sumOf { it.attempts })
            assertTrue(deferred.rows.values.all { it.deadline == 130_000L })
            assertEquals(300, checkpoint.current!!.checkpoint.entries.size)
            assertEquals(inventory.map { it.id }.toSet(), persisted + deferred.rows.keys)

            // A new stage models restart: unread coverage and deadlines are durable inputs.
            reads.clear()
            now = 129_999L
            outage = false
            assertEquals(RemoteContactStageResult.Partial(275, 275), candidate().run(ACCOUNT))
            assertTrue(reads.isEmpty())
            now++
            assertTrue(candidate().run(ACCOUNT) is RemoteContactStageResult.Success)
            assertEquals(inventory.drop(25).map { it.id }, reads)
            assertTrue(deferred.rows.isEmpty())
            assertEquals(inventory.map { it.id }.toSet(), persisted)
            reads.clear()
            assertTrue(candidate().run(ACCOUNT) is RemoteContactStageResult.Success)
            assertTrue(reads.isEmpty())
        }
    }

    @Test fun serialOutageIsBoundedButIsolatedTimeoutsAndMalformedCardsDoNotStopHealthyReads() = runTest {
        val inventory = (1..30).map { metadata(it.toString().padStart(4, '0'), 1) }
        suspend fun run(failure: (RemoteContactId) -> GatewayFailureCategory?): Pair<Int, Set<RemoteContactId>> {
            var reads = 0
            val persisted = mutableSetOf<RemoteContactId>()
            val result = IncrementalRemoteContactStage(pagedGateway { pages(inventory, 30) },
                ProtonVerifiedContactCardGateway { _, id ->
                    reads++
                    failure(id)?.let { GatewayOutcome.Failure(it) } ?: success(card(id, 1))
                }, PersistentContactInventoryPlanner(FakeCheckpointStore()),
                RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                    persisted += cards.map { it.id }
                    CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
                }, hydrationReadConcurrency = 1, obligations = OutageObligations { 0L }).run(ACCOUNT)
            assertTrue(result is RemoteContactStageResult.Partial)
            return reads to persisted
        }
        assertEquals(3 to emptySet<RemoteContactId>(), run { GatewayFailureCategory.TIMEOUT })
        val (reads, persisted) = run { id -> when (id.value.toInt() % 4) {
            1, 2 -> GatewayFailureCategory.TIMEOUT
            3 -> GatewayFailureCategory.MALFORMED_RESPONSE
            else -> null
        } }
        assertEquals(30, reads)
        assertEquals(inventory.filter { it.id.value.toInt() % 4 == 0 }.map { it.id }.toSet(), persisted)
    }

    @Test fun rateLimitInAnAlreadyStartedOutageWindowKeepsTheLongestServerDelayForUnreadCards() = runTest {
        val inventory = (1..30).map { metadata(it.toString().padStart(4, '0'), 1) }
        var reads = 0
        val deferred = OutageObligations { 100_000L }
        val result = IncrementalRemoteContactStage(pagedGateway { pages(inventory, 30) },
            ProtonVerifiedContactCardGateway { _, id ->
                reads++
                GatewayOutcome.Failure(if (id.value == "0001") GatewayFailureCategory.RATE_LIMITED
                    else GatewayFailureCategory.TIMEOUT, retryAfterMillis = if (id.value == "0001") 90_000L else 5_000L)
            }, PersistentContactInventoryPlanner(FakeCheckpointStore()),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            }, obligations = deferred).run(ACCOUNT)
        assertEquals(RemoteContactStageResult.Partial(30, 30), result)
        assertEquals(10, reads)
        inventory.drop(10).forEach { metadata ->
            assertEquals(GatewayFailureCategory.RATE_LIMITED, deferred.rows.getValue(metadata.id).failure.category)
            assertEquals(190_000L, deferred.rows.getValue(metadata.id).deadline)
            assertEquals(0, deferred.rows.getValue(metadata.id).attempts)
        }
    }

    @Test fun localInvariantFailuresReportOnlyClosedCodesAndNeverAdvanceCheckpoint() = runTest {
        LocalReconciliationInvariant.entries.forEach { invariant ->
            val checkpoint = FakeCheckpointStore()
            val phases = mutableListOf<RemoteImportPhase>()
            val failures = mutableListOf<SyncPassExceptionCategory>()
            val candidate = IncrementalRemoteContactStage(
                pagedGateway { pages(listOf(metadata("synthetic", 1)), 1) },
                ProtonVerifiedContactCardGateway { _, id -> success(card(id, 1)) },
                PersistentContactInventoryPlanner(checkpoint),
                RemoteCanonicalReconciliationStore { _, _, _, _, _ ->
                    checkLocalReconciliationInvariant(false, invariant)
                    error("Unreachable")
                },
                importObserver = object : SyncPassStageObserver {
                    override fun onStage(stage: SyncPassStage) = Unit
                    override fun onImportPhase(phase: RemoteImportPhase) { phases += phase }
                    override fun onException(category: SyncPassExceptionCategory) { failures += category }
                },
            )
            assertEquals(RemoteContactStageResult.LocalPersistenceFailure, candidate.run(ACCOUNT))
            assertEquals(0, checkpoint.commitCount)
            assertTrue(phases.contains(RemoteImportPhase.LOCAL_COMMIT_FAILED))
            assertTrue(phases.contains(invariant.phase))
            assertFalse(phases.contains(RemoteImportPhase.LOCAL_COMMIT_DONE))
            assertEquals(listOf(SyncPassExceptionCategory.INVALID_STATE), failures)
        }
    }

    @Test fun serialInvestigationCommitsEachContactBeforeReadingTheNext() = runTest {
        val inventory = (1..36).map { metadata("serial-$it", 1) }
        var active = 0
        var maximum = 0
        var fetched = 0
        var committed = 0
        val checkpoint = FakeCheckpointStore()
        val phases = mutableListOf<RemoteImportPhase>()
        val candidate = IncrementalRemoteContactStage(
            pagedGateway { pages(inventory, 36) },
            ProtonVerifiedContactCardGateway { _, id ->
                assertEquals(fetched, committed)
                fetched++
                active++
                maximum = maxOf(maximum, active)
                delay(10)
                active--
                success(card(id, 1))
            },
            PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                assertEquals(1, cards.size)
                committed++
                assertEquals(0, checkpoint.commitCount)
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
            hydrationBatchSize = 1,
            hydrationReadConcurrency = 1,
            importObserver = object : SyncPassStageObserver {
                override fun onStage(stage: SyncPassStage) = Unit
                override fun onImportPhase(phase: RemoteImportPhase) { phases += phase }
            },
        )
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(1, maximum)
        assertEquals(36, committed)
        assertEquals(1, checkpoint.commitCount)
        assertEquals(36, phases.count { it == RemoteImportPhase.LOCAL_COMMIT_DONE })
        assertEquals(RemoteImportPhase.CHECKPOINT_COMMIT_DONE, phases.last())
    }

    @Test fun privateChangeEventHydratesUnchangedIndexAndFailedReadKeepsCursorForReplay() = runTest {
        val checkpoint = FakeCheckpointStore()
        var failRead = false
        val fetched = mutableListOf<RemoteContactId>()
        val seenCursors = mutableListOf<String?>()
        var delta = com.patmanak.contako.data.proton.ContactEventsDelta("start", emptySet(), true)
        val candidate = IncrementalRemoteContactStage(
            pagedGateway { pages(listOf(metadata("one", 1), metadata("two", 1)), 2) },
            ProtonVerifiedContactCardGateway { _, id ->
                fetched += id
                if (failRead) GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT) else success(card(id, 2))
            },
            PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
            eventsGateway = com.patmanak.contako.data.proton.ProtonContactEventsGateway { _, cursor ->
                seenCursors += cursor
                success(delta)
            },
        )
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals("start", checkpoint.current?.checkpoint?.eventCursor)
        delta = com.patmanak.contako.data.proton.ContactEventsDelta("changed", setOf(RemoteContactId("one")), false)
        fetched.clear()
        failRead = true
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.RetryWaiting)
        assertEquals("start", checkpoint.current?.checkpoint?.eventCursor)
        failRead = false
        fetched.clear()
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(listOf(RemoteContactId("one")), fetched)
        assertEquals("changed", checkpoint.current?.checkpoint?.eventCursor)
        assertEquals(listOf(null, "start", "start"), seenCursors)
        delta = com.patmanak.contako.data.proton.ContactEventsDelta("changed", emptySet(), false)
        fetched.clear()
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertTrue(fetched.isEmpty())
    }

    @Test fun publicDirectoryAbsenceNeedsTargetedConfirmationBeforeAnyCommit() = runTest {
        val checkpoint = FakeCheckpointStore()
        stage({ pages(listOf(metadata("one", 1)), 1) }, checkpoint, { id -> success(card(id, 1)) }).run(ACCOUNT)
        val generation = checkpoint.current?.generation
        var committed = 0
        var answer: GatewayOutcome<com.patmanak.contako.data.gateway.RemoteContactPresence> =
            success(com.patmanak.contako.data.gateway.RemoteContactPresence.PRESENT)
        val candidate = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { _, _ -> success(ContactInventoryPage(emptyList(), null, null, 0,
                ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY)) },
            ProtonVerifiedContactCardGateway { _, _ -> error("NO_HYDRATION") },
            PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                committed++
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
            existenceGateway = com.patmanak.contako.data.gateway.ProtonContactExistenceGateway { _, id ->
                assertEquals(RemoteContactId("one"), id)
                answer
            },
        )
        assertEquals(RemoteContactStageResult.StalePlan, candidate.run(ACCOUNT))
        answer = GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.RetryWaiting)
        assertEquals(0, committed)
        assertEquals(generation, checkpoint.current?.generation)
        answer = success(com.patmanak.contako.data.gateway.RemoteContactPresence.CONFIRMED_ABSENT)
        assertEquals(1, (candidate.run(ACCOUNT) as RemoteContactStageResult.Success).deletedCount)
        assertEquals(1, committed)
    }
    @Test
    fun `initial hydration bounds concurrent reads and preserves deterministic commit order`() = runTest {
        val inventory = (1..25).map { metadata("contact-$it", 1) }
        var activeReads = 0
        var maximumReads = 0
        val committedIds = mutableListOf<String>()
        val stage = stage(
            inventoryGateway = pagedGateway { pages(inventory, 25) },
            cardGateway = ProtonVerifiedContactCardGateway { _, id ->
                activeReads++
                maximumReads = maxOf(maximumReads, activeReads)
                delay(10)
                activeReads--
                success(card(id, 1))
            },
            checkpoint = FakeCheckpointStore(),
            canonical = RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                committedIds += cards.map { it.id.value }
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            },
        )

        assertTrue(stage.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(10, maximumReads)
        assertEquals(inventory.map { it.id.value }.sorted(), committedIds)
    }

    @Test
    fun `03-DELTA nominal 300 no-change second pass performs zero hydration`() = runTest {
        val checkpoint = FakeCheckpointStore()
        val inventory = (1..300).map { metadata("contact-$it", 1) }
        var hydrations = 0
        val stage = stage(
            inventory = { pages(inventory, pageSize = 100) },
            checkpoint = checkpoint,
            fetch = { id -> hydrations++; success(card(id, 1)) },
        )

        val first = stage.run(ACCOUNT) as RemoteContactStageResult.Success
        assertEquals(300, first.hydratedCount)
        assertEquals(300, hydrations)
        val second = stage.run(ACCOUNT) as RemoteContactStageResult.Success

        assertEquals(0, second.hydratedCount)
        assertEquals(300, hydrations)
        assertEquals(2, checkpoint.commitCount)
    }

    @Test
    fun `03-DELTA ten changes hydrate ten rather than the directory`() = runTest {
        val checkpoint = FakeCheckpointStore()
        var current = (1..300).map { metadata("contact-$it", 1) }
        var hydrations = 0
        val stage = stage(
            inventory = { pages(current, 75) },
            checkpoint = checkpoint,
            fetch = { id -> hydrations++; success(card(id, if (id.value.removePrefix("contact-").toInt() <= 10) 2 else 1)) },
        )
        stage.run(ACCOUNT)
        hydrations = 0
        current = current.mapIndexed { index, metadata -> if (index < 10) metadata(metadata.id.value, 2) else metadata }

        val delta = stage.run(ACCOUNT) as RemoteContactStageResult.Success

        assertEquals(10, delta.hydratedCount)
        assertEquals(10, hydrations)
    }

    @Test
    fun `03-FAULT failed page proves no deletion and never advances checkpoint`() = runTest {
        val checkpoint = FakeCheckpointStore()
        val firstInventory = listOf(metadata("one", 1), metadata("two", 1))
        val successful = stage({ pages(firstInventory, 1) }, checkpoint, { id -> success(card(id, 1)) })
        successful.run(ACCOUNT)
        val generation = checkpoint.current?.generation
        val failingPages = pages(listOf(metadata("one", 1)), 1)
        val failing = stage(
            inventoryGateway = ProtonContactInventoryGateway { _, cursor ->
                if (cursor == null) success(failingPages.first().copyForNext(InventoryCursor("next")))
                else GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
            },
            cardGateway = ProtonVerifiedContactCardGateway { _, _ -> error("NO_HYDRATION") },
            checkpoint = checkpoint,
            canonical = RemoteCanonicalReconciliationStore { _, _, _, _, _ -> error("NO_COMMIT") },
        )

        val result = failing.run(ACCOUNT)

        assertTrue(result is RemoteContactStageResult.RetryWaiting)
        assertEquals(generation, checkpoint.current?.generation)
        assertEquals(2, checkpoint.current?.checkpoint?.entries?.size)
    }

    @Test
    fun `03-CANCEL cancellation before canonical commit leaves checkpoint unchanged`() = runTest {
        val checkpoint = FakeCheckpointStore()
        var cancel = false
        val stage = stage(
            inventory = { pages(listOf(metadata("one", 1)), 1) },
            checkpoint = checkpoint,
            fetch = { id -> cancel = true; success(card(id, 1)) },
        )

        val result = stage.run(ACCOUNT) { cancel }

        assertEquals(RemoteContactStageResult.Cancelled, result)
        assertNull(checkpoint.current)
        assertEquals(0, checkpoint.commitCount)
    }

    @Test
    fun `07-CANCEL import commits bounded batches but checkpoint advances only after complete replay`() = runTest {
        val checkpoint = FakeCheckpointStore()
        val inventory = (1..60).map { metadata("contact-$it", 1) }
        var cancel = false
        var commits = 0
        var largestBatch = 0
        val canonical = RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
            commits++
            largestBatch = maxOf(largestBatch, cards.size)
            CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
        }
        fun stage(observer: RemoteContactBatchObserver = RemoteContactBatchObserver.NONE) = stage(
            pagedGateway { pages(inventory, 60) },
            ProtonVerifiedContactCardGateway { _, id -> success(card(id, 1)) },
            checkpoint,
            canonical,
            observer,
        )

        assertEquals(
            RemoteContactStageResult.Cancelled,
            stage(RemoteContactBatchObserver { _, _, _ -> cancel = true }).run(ACCOUNT) { cancel },
        )
        assertNull(checkpoint.current)
        assertEquals(1, commits)
        assertEquals(25, largestBatch)

        cancel = false
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(1, checkpoint.commitCount)
        assertEquals(4, commits)
        assertEquals(25, largestBatch)
    }

    @Test
    fun `03-FAULT canonical failure and dishonest completion never advance checkpoint`() = runTest {
        listOf<RemoteCanonicalReconciliationStore>(
            RemoteCanonicalReconciliationStore { _, _, _, _, _ -> error("LOCAL_DB_FAILURE") },
            RemoteCanonicalReconciliationStore { _, _, _, _, _ -> CanonicalReconciliationReceipt(emptySet(), emptySet(), emptySet()) },
        ).forEach { canonical ->
            val checkpoint = FakeCheckpointStore()
            val stage = stage(
                inventoryGateway = pagedGateway { pages(listOf(metadata("one", 1)), 1) },
                cardGateway = ProtonVerifiedContactCardGateway { _, id -> success(card(id, 1)) },
                checkpoint = checkpoint,
                canonical = canonical,
            )

            assertEquals(RemoteContactStageResult.LocalPersistenceFailure, stage.run(ACCOUNT))
            assertNull(checkpoint.current)
        }
    }

    @Test
    fun `03-DELTA malformed continuity or untrusted authority fails closed`() = runTest {
        val reasons = mutableListOf<Triple<RemoteContactActionRequiredBoundary, GatewayFailureCategory?, GatewayContactHydrationCategory?>>()
        val first = ContactInventoryPage(
            contacts = listOf(metadata("one", 1)),
            requestedCursor = null,
            nextCursor = InventoryCursor("unexpected"),
            totalCount = 2,
            snapshotAuthority = AUTHORITY,
        )
        val wrongContinuation = ContactInventoryPage(
            contacts = listOf(metadata("two", 1)),
            requestedCursor = InventoryCursor("different"),
            nextCursor = null,
            totalCount = 2,
            snapshotAuthority = AUTHORITY,
        )
        val malformed = stage(
            inventoryGateway = ProtonContactInventoryGateway { _, cursor ->
                success(if (cursor == null) first else wrongContinuation)
            },
            cardGateway = ProtonVerifiedContactCardGateway { _, _ -> error("NO_HYDRATION") },
            checkpoint = FakeCheckpointStore(),
            canonical = RemoteCanonicalReconciliationStore { _, _, _, _, _ -> error("NO_COMMIT") },
            actionRequiredObserver = RemoteContactActionRequiredObserver { boundary, category, hydration ->
                reasons += Triple(boundary, category, hydration)
            },
        )
        val untrusted = stage(
            inventory = {
                listOf(
                    ContactInventoryPage(
                        emptyList(), null, null, 0, ContactInventorySnapshotAuthority.UNATTESTED,
                    ),
                )
            },
            checkpoint = FakeCheckpointStore(),
            fetch = { error("NO_HYDRATION") },
        )

        assertEquals(RemoteContactStageResult.ActionRequired, malformed.run(ACCOUNT))
        assertEquals(RemoteContactStageResult.ActionRequired, untrusted.run(ACCOUNT))
        assertEquals(
            listOf(
                Triple(
                    RemoteContactActionRequiredBoundary.INVENTORY,
                    GatewayFailureCategory.MALFORMED_RESPONSE,
                    null,
                ),
            ),
            reasons,
        )
    }

    @Test
    fun `03-FAULT hydration action exposes only its fixed payload-free category`() = runTest {
        val reasons = mutableListOf<Triple<RemoteContactActionRequiredBoundary, GatewayFailureCategory?, GatewayContactHydrationCategory?>>()
        val stage = stage(
            inventoryGateway = pagedGateway { pages(listOf(metadata("one", 1)), 1) },
            cardGateway = ProtonVerifiedContactCardGateway { _, _ ->
                GatewayOutcome.Failure(
                    category = GatewayFailureCategory.MALFORMED_RESPONSE,
                    contactHydrationCategory = GatewayContactHydrationCategory.VCARD_PARSE,
                )
            },
            checkpoint = FakeCheckpointStore(),
            canonical = RemoteCanonicalReconciliationStore { _, _, _, _, _ -> error("NO_COMMIT") },
            actionRequiredObserver = RemoteContactActionRequiredObserver { boundary, category, hydration ->
                reasons += Triple(boundary, category, hydration)
            },
        )

        assertEquals(RemoteContactStageResult.ActionRequired, stage.run(ACCOUNT))
        assertEquals(
            listOf(
                Triple(
                    RemoteContactActionRequiredBoundary.HYDRATION,
                    GatewayFailureCategory.MALFORMED_RESPONSE,
                    GatewayContactHydrationCategory.VCARD_PARSE,
                ),
            ),
            reasons,
        )
    }

    @Test fun `unchanged inventory selectively rehydrates identity repair then returns to no change`() = runTest {
        val checkpoint = FakeCheckpointStore()
        val reads = mutableListOf<RemoteContactId>()
        var repairs = emptySet<RemoteContactId>()
        val canonical = object : RemoteCanonicalReconciliationStore {
            override suspend fun identityRepairContacts(account: AccountScope) = repairs
            override suspend fun commit(account: AccountScope, plan: com.patmanak.contako.data.proton.ContactInventoryPlan,
                hydratedCards: List<VerifiedContactCard>, labelsToReconcile: Set<RemoteContactId>,
                deletionsToReconcile: Set<RemoteContactId>): CanonicalReconciliationReceipt {
                repairs -= hydratedCards.map { it.id }.toSet()
                return CanonicalReconciliationReceipt(hydratedCards.map { it.id }.toSet(), labelsToReconcile, deletionsToReconcile)
            }
        }
        val candidate = stage(pagedGateway { pages(listOf(metadata("one", 1), metadata("two", 1)), 2) },
            ProtonVerifiedContactCardGateway { _, id -> reads += id; success(card(id, 1)) }, checkpoint, canonical)
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.Success)
        reads.clear()
        repairs = setOf(RemoteContactId("one"))
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(listOf(RemoteContactId("one")), reads)
        reads.clear()
        assertTrue(candidate.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertTrue(reads.isEmpty())
    }

    private fun stage(
        inventory: (InventoryCursor?) -> List<ContactInventoryPage>,
        checkpoint: FakeCheckpointStore,
        fetch: (RemoteContactId) -> GatewayOutcome<VerifiedContactCard>,
    ): IncrementalRemoteContactStage = stage(
        inventoryGateway = pagedGateway(inventory),
        cardGateway = ProtonVerifiedContactCardGateway { _, id -> fetch(id) },
        checkpoint = checkpoint,
        canonical = RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
            CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
        },
    )

    private fun stage(
        inventoryGateway: ProtonContactInventoryGateway,
        cardGateway: ProtonVerifiedContactCardGateway,
        checkpoint: FakeCheckpointStore,
        canonical: RemoteCanonicalReconciliationStore,
        observer: RemoteContactBatchObserver = RemoteContactBatchObserver.NONE,
        actionRequiredObserver: RemoteContactActionRequiredObserver =
            RemoteContactActionRequiredObserver { _, _, _ -> },
    ) = IncrementalRemoteContactStage(
        inventoryGateway,
        cardGateway,
        PersistentContactInventoryPlanner(checkpoint),
        canonical,
        observer,
        actionRequiredObserver,
    )

    private fun pagedGateway(
        pages: (InventoryCursor?) -> List<ContactInventoryPage>,
    ) = ProtonContactInventoryGateway { _, cursor ->
        val available = pages(cursor)
        success(available.first { it.requestedCursor == cursor })
    }

    private fun pages(metadata: List<ContactInventoryMetadata>, pageSize: Int): List<ContactInventoryPage> {
        if (metadata.isEmpty()) return listOf(ContactInventoryPage(emptyList(), null, null, 0, AUTHORITY))
        val chunks = metadata.chunked(pageSize)
        return chunks.mapIndexed { index, contacts ->
            ContactInventoryPage(
                contacts = contacts,
                requestedCursor = if (index == 0) null else InventoryCursor("cursor-$index"),
                nextCursor = if (index == chunks.lastIndex) null else InventoryCursor("cursor-${index + 1}"),
                totalCount = metadata.size,
                snapshotAuthority = AUTHORITY,
            )
        }
    }

    private fun metadata(id: String, version: Int) = ContactInventoryMetadata(
        id = RemoteContactId(id),
        displayName = "Contact",
        version = RemoteVersion("version-$version"),
        sizeBytes = 100,
        modifiedAtEpochSeconds = version.toLong(),
        emailIds = emptyList(),
        groupIds = emptyList(),
        versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
        coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
    )

    private fun card(id: RemoteContactId, version: Int) = VerifiedContactCard(
        id,
        RemoteVersion("version-$version"),
        CanonicalContact(accountId = "account", id = id.value, displayName = "Contact"),
    )

    private fun ContactInventoryPage.copyForNext(next: InventoryCursor) = ContactInventoryPage(
        contacts, requestedCursor, next, totalCount, snapshotAuthority,
    )

    private fun <T> success(value: T): GatewayOutcome<T> = GatewayOutcome.Success(value)

    private companion object {
        val ACCOUNT = AccountScope("account")
        val AUTHORITY = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION
    }

    private class OutageObligations(private val clock: () -> Long) : RemoteHydrationObligationStore {
        data class Row(val failure: GatewayOutcome.Failure, val deadline: Long, val attempts: Int)
        val rows = mutableMapOf<RemoteContactId, Row>()
        override suspend fun pending(account: AccountScope) = rows.keys.toSet()
        override suspend fun defer(account: AccountScope, baseline: com.patmanak.contako.data.proton.ContactInventoryBaseline,
            failure: GatewayOutcome.Failure) = save(baseline, failure, attempted = true)
        override suspend fun postpone(account: AccountScope, baseline: com.patmanak.contako.data.proton.ContactInventoryBaseline,
            failure: GatewayOutcome.Failure) = save(baseline, failure, attempted = false)
        private fun save(baseline: com.patmanak.contako.data.proton.ContactInventoryBaseline,
            failure: GatewayOutcome.Failure, attempted: Boolean) {
            val previous = rows[baseline.id]
            rows[baseline.id] = Row(failure, maxOf(previous?.deadline ?: 0L,
                clock() + maxOf(5_000L, failure.retryAfterMillis ?: 0L)),
                (previous?.attempts ?: 0) + if (attempted) 1 else 0)
        }
        override suspend fun readDisposition(account: AccountScope,
            baseline: com.patmanak.contako.data.proton.ContactInventoryBaseline,
            sourceChanged: Boolean): RemoteHydrationReadDisposition = when {
            rows[baseline.id]?.deadline?.let { it > clock() } == true -> RemoteHydrationReadDisposition.RETRY_WAITING
            else -> RemoteHydrationReadDisposition.READ
        }
        override suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>) { ids.forEach(rows::remove) }
    }
}

private class FakeCheckpointStore : ContactInventoryCheckpointStore {
    var current: VersionedContactInventoryCheckpoint? = null
    var commitCount = 0

    override suspend fun load(account: AccountScope): VersionedContactInventoryCheckpoint? = current

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
