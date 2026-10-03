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
