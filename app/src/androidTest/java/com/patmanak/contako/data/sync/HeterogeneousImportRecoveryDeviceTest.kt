package com.patmanak.contako.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RemoteHydrationObligationEntity
import com.patmanak.contako.data.local.RoomContactInventoryCheckpointStore
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomRemoteCanonicalReconciliationStore
import com.patmanak.contako.data.local.RoomRemoteHydrationObligationStore
import com.patmanak.contako.data.proton.ContactEventsDelta
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.ProtonContactEventsGateway
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SW-02/10: production import, checkpoints and obligations over a private file-backed Room database.
 * Gateways are controlled; no network, image decoding, Android account or ContactsProvider access.
 */
@RunWith(AndroidJUnit4::class)
class HeterogeneousImportRecoveryDeviceTest {
    @Test
    fun rejectedCardBeforeBatchBoundaryRetainsHealthyValuesAndRetriesOnlyItsOwner() = runBlocking {
        rejectedCardRecovery(24)
    }

    @Test
    fun rejectedCardOnBatchBoundaryRetainsHealthyValuesAndRetriesOnlyItsOwner() = runBlocking {
        rejectedCardRecovery(25)
    }

    @Test
    fun rejectedCardAfterBatchBoundaryRetainsHealthyValuesAndRetriesOnlyItsOwner() = runBlocking {
        rejectedCardRecovery(26)
    }

    @Test
    fun interruptionAfterTwentyFiveDurableCardsReopensAndReplaysBeforeGlobalCheckpoint() = runBlocking {
        withFixture(26) { fixture ->
            var cancel = false
            assertEquals(RemoteContactStageResult.Cancelled, fixture.run(
                observer = RemoteContactBatchObserver { hydrated, labels, deletions ->
                    assertEquals(25, hydrated)
                    assertEquals(0, labels)
                    assertEquals(0, deletions)
                    cancel = true
                },
                isCancelled = { cancel },
            ))
            fixture.assertReads((1..25).toList())
            fixture.assertContents((1..25).toSet())
            assertNull(fixture.checkpoints().load(ACCOUNT))
            assertTrue(fixture.obligations().pending(ACCOUNT).isEmpty())
            fixture.assertOtherOwnerUntouched()
            val committed = fixture.contacts()

            fixture.reopen()
            fixture.assertUnchanged(committed)
            assertNull(fixture.checkpoints().load(ACCOUNT))
            assertTrue(fixture.obligations().pending(ACCOUNT).isEmpty())
            fixture.assertOtherOwnerUntouched()

            assertEquals(RemoteContactStageResult.Partial(1), fixture.run())
            // No global checkpoint exists: all 51 reads MUST replay, including the first 25.
            fixture.assertReads((1..51).toList())
            fixture.assertContents((1..51).toSet() - 26, replayed = (1..25).toSet())
            fixture.assertCheckpoint(generation = 0, cursor = "synthetic-events-2")
            fixture.assertDeferred()
            fixture.assertOtherOwnerUntouched()
            fixture.correctAndCheckNoChange(replayed = (1..25).toSet(), generation = 0)
            assertEquals(listOf(null, null, "synthetic-events-2", "synthetic-events-3", "synthetic-events-4"),
                fixture.eventCursors)
        }
    }

    private suspend fun rejectedCardRecovery(position: Int) = withFixture(position) { fixture ->
        assertEquals(RemoteContactStageResult.Partial(1), fixture.run())
        fixture.assertReads((1..51).toList())
        fixture.assertContents((1..51).toSet() - position)
        fixture.assertCheckpoint(generation = 0, cursor = "synthetic-events-1")
        val obligation = fixture.assertDeferred()
        val healthy = fixture.contacts()
        fixture.assertOtherOwnerUntouched()

        fixture.reopen()
        fixture.assertUnchanged(healthy)
        assertTrue("Deferred source changed across reopen", fixture.assertDeferred() == obligation)
        fixture.assertCheckpoint(generation = 0, cursor = "synthetic-events-1")
        fixture.assertOtherOwnerUntouched()

        // A still-unreadable card retries alone; healthy cards MUST NOT be read or rewritten.
        assertEquals(RemoteContactStageResult.Partial(1), fixture.run())
        fixture.assertReads(listOf(position))
        fixture.assertUnchanged(healthy)
        assertTrue("Repeated refusal changed its source generation", fixture.assertDeferred() == obligation)
        fixture.assertCheckpoint(generation = 1, cursor = "synthetic-events-2")
        fixture.assertOtherOwnerUntouched()
        fixture.correctAndCheckNoChange(generation = 1)
        assertEquals(listOf(null, "synthetic-events-1", "synthetic-events-2", "synthetic-events-3",
            "synthetic-events-4"), fixture.eventCursors)
    }

    private suspend fun withFixture(position: Int, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(position)
        try {
            fixture.seedOtherOwner()
            block(fixture)
        } finally {
            fixture.closeAndDelete()
        }
    }

    private class Fixture(val rejectedPosition: Int) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "synthetic-heterogeneous-import-${UUID.randomUUID()}.db"
        private var database = ContakoDatabase.create(context, name)
        private val readIds = Collections.synchronizedList(mutableListOf<RemoteContactId>())
        private val inventoryRequests = AtomicInteger()
        private val eventRequests = AtomicInteger()
        val eventCursors = mutableListOf<String?>()
        private val cards = (1..51).associateWith(::card)
        private var rejected = true
        private var otherObligation: RemoteHydrationObligationEntity? = null

        fun checkpoints() = RoomContactInventoryCheckpointStore(database)
        fun obligations() = RoomRemoteHydrationObligationStore(database)

        suspend fun seedOtherOwner() {
            // The same remote ID under another owner MUST survive resolution of our card.
            val baseline = ContactInventoryBaseline(id(rejectedPosition), "Synthetic other owner", VERSION,
                128, MODIFIED_AT, emptyList(), emptyList())
            obligations().defer(OTHER, baseline, REFUSAL)
            otherObligation = database.remoteHydrationObligationDao().get(OTHER.value, baseline.id.value)
            assertTrue("Other-owner fixture was not durable", otherObligation != null)
        }

        suspend fun run(
            observer: RemoteContactBatchObserver = RemoteContactBatchObserver.NONE,
            isCancelled: () -> Boolean = { false },
        ): RemoteContactStageResult {
            readIds.clear()
            inventoryRequests.set(0)
            val stage = IncrementalRemoteContactStage(
                ProtonContactInventoryGateway { account, cursor ->
                    assertEquals(ACCOUNT, account)
                    assertNull(cursor)
                    inventoryRequests.incrementAndGet()
                    // Deliberately reverse the directory: refusal positions refer to sorted IDs.
                    GatewayOutcome.Success(ContactInventoryPage((51 downTo 1).map(::metadata), null,
                        null, 51, ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION))
                },
                ProtonVerifiedContactCardGateway { account, remoteId ->
                    assertEquals(ACCOUNT, account)
                    readIds += remoteId
                    val position = (1..51).single { id(it) == remoteId }
                    if (position == rejectedPosition && rejected) REFUSAL
                    else GatewayOutcome.Success(VerifiedContactCard(remoteId, VERSION, cards.getValue(position)))
                },
                PersistentContactInventoryPlanner(checkpoints()),
                RoomRemoteCanonicalReconciliationStore(database),
                batchObserver = observer,
                eventsGateway = ProtonContactEventsGateway { account, cursor ->
                    assertEquals(ACCOUNT, account)
                    assertEquals(checkpoints().load(ACCOUNT)?.checkpoint?.eventCursor, cursor)
                    eventCursors += cursor
                    GatewayOutcome.Success(ContactEventsDelta("synthetic-events-${eventRequests.incrementAndGet()}",
                        emptySet(), refreshAll = cursor == null))
                },
                obligations = obligations(),
            )
            return withContext(Dispatchers.IO) { stage.run(ACCOUNT, isCancellationRequested = isCancelled) }
        }

        fun assertReads(positions: List<Int>) {
            val actual = synchronized(readIds) { readIds.toList() }
            assertEquals(1, inventoryRequests.get())
            assertEquals(positions.size, actual.size)
            // Count every occurrence: a set alone would conceal duplicate full-card requests.
            assertEquals(positions.map(::id).groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount())
        }

        suspend fun contacts(): Map<String, CanonicalContact> {
            val repository = RoomContactRepository(database)
            return repository.observeContacts(ACCOUNT.value).first().associate { summary ->
                summary.id to requireNotNull(repository.getContact(ACCOUNT.value, summary.id))
            }
        }

        suspend fun assertContents(positions: Set<Int>, replayed: Set<Int> = emptySet()) {
            val actual = contacts()
            assertEquals(positions.map { cards.getValue(it).id }.toSet(), actual.keys)
            assertEquals(positions.size, actual.values.map { it.remoteContactId }.distinct().size)
            positions.forEach { position ->
                val expected = cards.getValue(position).copy(revision = if (position in replayed) 2L else 1L,
                    updatedAtEpochMillis = MODIFIED_AT * 1_000,
                    remoteContactId = id(position).value, remoteVersion = VERSION.value)
                val stored = actual.getValue(expected.id)
                // Boolean comparisons deliberately keep complete synthetic payloads out of failure output.
                assertTrue("Canonical fields or value identities differ", stored == expected)
                assertTrue("Opaque envelope differs", stored.preservationEnvelope == expected.preservationEnvelope)
                val photos = stored.valuesOf(ContactValueKind.PHOTO)
                val expectedPhotos = expected.valuesOf(ContactValueKind.PHOTO)
                assertTrue("Encoded photo representation differs", photos == expectedPhotos)
                photos.zip(expectedPhotos).forEach { (photo, expectedPhoto) ->
                    assertTrue("Encoded photo bytes differ", requireNotNull(photo.binaryReference).toByteArray(Charsets.UTF_8)
                        .contentEquals(requireNotNull(expectedPhoto.binaryReference).toByteArray(Charsets.UTF_8)))
                }
            }
        }

        suspend fun assertUnchanged(before: Map<String, CanonicalContact>) {
            val actual = contacts()
            before.forEach { (localId, expected) ->
                assertTrue("Untouched witness changed", actual[localId] == expected)
            }
            assertTrue("Untouched witness disappeared", actual.keys.containsAll(before.keys))
        }

        suspend fun assertDeferred(): RemoteHydrationObligationEntity {
            assertEquals(setOf(id(rejectedPosition)), obligations().pending(ACCOUNT))
            val rows = database.remoteHydrationObligationDao().getAll(ACCOUNT.value)
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(ACCOUNT.value, row.accountId)
            assertEquals(id(rejectedPosition).value, row.remoteContactId)
            assertTrue("Deferred name differs", row.displayName == cards.getValue(rejectedPosition).displayName)
            assertEquals(REFUSAL.category.name, row.category)
            assertEquals(REFUSAL.contactHydrationCategory?.name, row.hydrationCategory)
            assertEquals(VERSION.value, row.baselineVersion)
            assertFalse(row.generation.isBlank())
            assertFalse(contacts().containsKey(cards.getValue(rejectedPosition).id))
            return row
        }

        suspend fun assertOtherOwnerUntouched() {
            assertTrue("Foreign owner obligation changed", database.remoteHydrationObligationDao()
                .getAll(OTHER.value) == listOf(requireNotNull(otherObligation)))
            assertEquals(setOf(id(rejectedPosition)), obligations().pending(OTHER))
            assertNull(checkpoints().load(OTHER))
            assertTrue(RoomContactRepository(database).observeContacts(OTHER.value).first().isEmpty())
        }

        suspend fun assertCheckpoint(generation: Long, cursor: String) {
            val checkpoint = requireNotNull(checkpoints().load(ACCOUNT))
            assertEquals(generation, checkpoint.generation)
            assertEquals(cursor, checkpoint.checkpoint.eventCursor)
            assertEquals((1..51).map(::id), checkpoint.checkpoint.entries.map { it.id })
            checkpoint.checkpoint.entries.forEach { entry ->
                assertEquals(VERSION, entry.version)
                assertEquals(128L, entry.sizeBytes)
                assertEquals(MODIFIED_AT, entry.modifiedAtEpochSeconds)
                assertTrue(entry.groupIds.isEmpty() && entry.emailGroupMemberships.isEmpty())
            }
        }

        suspend fun correctAndCheckNoChange(replayed: Set<Int> = emptySet(), generation: Long) {
            val healthy = contacts()
            rejected = false
            val corrected = run()
            assertEquals(RemoteContactStageResult.Success(51, 1, 0, 0), corrected)
            assertReads(listOf(rejectedPosition))
            assertUnchanged(healthy)
            assertContents((1..51).toSet(), replayed)
            assertTrue(obligations().pending(ACCOUNT).isEmpty())
            assertOtherOwnerUntouched()
            assertCheckpoint(generation + 1, "synthetic-events-${eventRequests.get()}")
            val completed = contacts()
            repeat(2) { pass ->
                assertEquals(RemoteContactStageResult.Success(51, 0, 0, 0), run())
                assertReads(emptyList())
                assertTrue("No-change pass changed a canonical witness", contacts() == completed)
                assertContents((1..51).toSet(), replayed)
                assertTrue(obligations().pending(ACCOUNT).isEmpty())
                assertOtherOwnerUntouched()
                assertCheckpoint(generation + 2 + pass, "synthetic-events-${eventRequests.get()}")
            }
        }

        fun reopen() {
            database.close()
            database = ContakoDatabase.create(context, name)
        }

        fun closeAndDelete() {
            try {
                database.close()
            } finally {
                assertTrue("Isolated fixture database cleanup failed", context.deleteDatabase(name))
            }
        }
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-import-owner")
        val OTHER = AccountScope("synthetic-import-other-owner")
        val VERSION = RemoteVersion("synthetic-revision-1")
        const val MODIFIED_AT = 1_700_000_000L
        val REFUSAL = GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE,
            contactHydrationCategory = GatewayContactHydrationCategory.VCARD_PARSE)
        // Small inline PNG; compare the retained representation without decoding an image.
        const val PHOTO_URI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="

        fun id(position: Int) = RemoteContactId("synthetic-remote-${position.toString().padStart(3, '0')}")

        fun metadata(position: Int) = ContactInventoryMetadata(id(position), card(position).displayName, VERSION,
            128, MODIFIED_AT, emptyList(), emptyList(), ContactInventoryVersionProvenance.REMOTE_SERVER,
            ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION)

        fun card(position: Int): CanonicalContact {
            val suffix = position.toString().padStart(3, '0')
            val values = mutableListOf(ContactValue("email-$suffix", ContactValueKind.EMAIL,
                "fixture-$suffix@example.invalid", order = 0, isPrimary = true))
            if (position % 3 != 0) {
                values += ContactValue("repeat-email-$suffix", ContactValueKind.EMAIL,
                    "fixture-$suffix@example.invalid", label = "Work", order = 1,
                    metadata = mapOf("syntheticOccurrence" to "second"))
                values += ContactValue("note-$suffix", ContactValueKind.NOTE,
                    "Synthetic note $suffix\nStraße 東京 \"quoted\"", order = 2)
                values += ContactValue("address-$suffix", ContactValueKind.POSTAL_ADDRESS, "12 Synthetic Lane, Paris",
                    order = 3, components = mapOf("street" to "12 Synthetic Lane", "locality" to "Paris"))
            }
            if (position % 3 == 2) {
                values += ContactValue("photo-$suffix", ContactValueKind.PHOTO, PHOTO_URI,
                    order = 4, isPrimary = true, binaryReference = PHOTO_URI, preservationKey = "photo-wire-$suffix")
            }
            return CanonicalContact(ACCOUNT.value, "synthetic-local-$suffix", firstName = "Synthetic $suffix",
                lastName = "Straße 東京", displayName = "Synthetic imported $suffix", values = values,
                remoteVCardUid = if (position in 24..26) "synthetic-shared-uid" else "synthetic-uid-$suffix",
                preservationEnvelope = PreservationEnvelope(mapOf(
                    "synthetic-opaque-$suffix" to "X-SYNTHETIC;TYPE=custom:opaque-$suffix\\,preserved",
                ), remoteBaseline = "synthetic-baseline-$suffix"))
        }
    }
}
