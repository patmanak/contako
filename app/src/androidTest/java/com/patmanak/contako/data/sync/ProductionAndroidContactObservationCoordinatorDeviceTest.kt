package com.patmanak.contako.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.provider.*
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AggregateType
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.toDomain
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Exercises production ingestion against Room only; no platform account or contact is created. */
@RunWith(AndroidJUnit4::class)
class ProductionAndroidContactObservationCoordinatorDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private val account = AccountScope("native-creation-fixture")
    private val scope = AndroidInteroperabilityContext(account, "fixture-provider", 1, 0)

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
        val ledger = RoomAndroidProjectionLedger(database)
        val initial = ledger.ensureAccount(account)
        ledger.bindAndroidAccountName(account, initial.revision, scope.androidAccountName)
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DB)
    }

    @Test
    fun photoCreationIsDurableBeforeAcknowledgementAndReplaysWithoutDuplicate() = runBlocking {
        val photo = jpeg()
        var acknowledge = false
        var acknowledged = 0
        val ack = AndroidContactObservationAcknowledger { _, _, _, _, _, canonicalId ->
            val stored = requireNotNull(database.contactDao().get(account.value, canonicalId)).toDomain()
            assertEquals(AndroidProviderPhotoReferenceEncoder.encode(photo), stored.values.single { it.kind == ContactValueKind.PHOTO }.value)
            assertNotNull(database.outboxDao().get(account.value, AggregateType.CONTACT.name, canonicalId))
            assertNotNull(database.androidProjectionLedgerDao().getUnifiedObservationCommitReceipt(account.value, canonicalId))
            if (acknowledge) {
                acknowledged++
                AndroidProviderAcknowledgementResult.Acknowledged
            } else AndroidProviderAcknowledgementResult.Stale
        }
        var coordinator = coordinator(ack)
        val page = page(observation(11, photo))
        assertEquals(AndroidBoundedPageResult.ReplanRequired, coordinator.ingest(scope, page))
        val original = requireNotNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 11)).canonicalContactId
        database.close()
        database = ContakoDatabase.create(context, DB)
        coordinator = coordinator(ack)
        acknowledge = true
        assertEquals(AndroidBoundedPageResult.Applied, coordinator.ingest(scope, page))
        assertEquals(original, requireNotNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 11)).canonicalContactId)
        assertEquals(1, acknowledged)
        assertEquals(1, database.outboxDao().getAll(account.value).size)
    }

    @Test
    fun changedPhotoAtSameVersionCannotReplayOrAcknowledgeOldCreation() = runBlocking {
        var acknowledge = false
        var acknowledgements = 0
        val coordinator = coordinator { _, _, _, _, _, _ ->
            if (acknowledge) acknowledgements++
            AndroidProviderAcknowledgementResult.Stale
        }
        assertEquals(AndroidBoundedPageResult.ReplanRequired, coordinator.ingest(scope, page(observation(11, jpeg()))))
        acknowledge = true
        assertEquals(AndroidBoundedPageResult.ReplanRequired,
            coordinator.ingest(scope, page(observation(11, jpeg(Color.BLUE)))))
        assertEquals(0, acknowledgements)
        assertEquals(1, database.outboxDao().getAll(account.value).size)
    }

    @Test
    fun unsupportedCreatedRowStaysPendingWhileFollowingPhotoCreationCommits() = runBlocking {
        val acknowledged = mutableListOf<Long>()
        val coordinator = coordinator { _, rawId, _, _, _, _ ->
            acknowledged += rawId
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        val invalid = observation(10, null).let { it.copy(dataRows = it.dataRows + row(10, 103, "vnd.android.cursor.item/im", null)) }
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, coordinator.ingest(scope, page(invalid, observation(11, jpeg()))))
        assertEquals(listOf(11L), acknowledged)
        assertNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 10))
        assertNotNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 11))
        assertEquals(1, database.outboxDao().getAll(account.value).size)
    }

    @Test
    fun rejectedPhotoRollsBackShellAndBindingsWithoutStrandingFollowingContact() = runBlocking {
        val acknowledged = mutableListOf<Long>()
        val coordinator = coordinator { _, rawId, _, _, _, _ ->
            acknowledged += rawId
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        assertEquals(AndroidBoundedPageResult.PartiallyApplied,
            coordinator.ingest(scope, page(observation(10, byteArrayOf(1)), observation(11, jpeg()))))
        assertEquals(listOf(11L), acknowledged)
        assertNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 10))
        assertEquals(1, database.outboxDao().getAll(account.value).size)
    }

    @Test
    fun invalidEmailIsDurableAndFlaggedWithoutStrandingFollowingCreation() = runBlocking {
        val acknowledged = mutableListOf<Long>()
        val coordinator = coordinator { _, rawId, _, _, _, _ ->
            acknowledged += rawId
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        val invalid = observation(10, null).let {
            it.copy(dataRows = it.dataRows + row(10, 103, "vnd.android.cursor.item/email_v2", null, "invalid-email"))
        }
        assertEquals(AndroidBoundedPageResult.Applied,
            coordinator.ingest(scope, page(invalid, observation(11, jpeg()))))
        assertEquals(listOf(10L, 11L), acknowledged)
        val contactId = requireNotNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 10)).canonicalContactId
        val stored = requireNotNull(database.contactDao().get(account.value, contactId)).toDomain()
        assertTrue("INVALID_EMAIL" in stored.actionRequiredReasons)
        assertEquals("invalid-email", stored.values.single { it.kind == ContactValueKind.EMAIL }.value)
        assertEquals(2, database.outboxDao().getAll(account.value).size)
    }

    @Test
    fun duplicateValueOrderRollsBackCreationAndPreservesOtherContacts() = runBlocking {
        val acknowledged = mutableListOf<Long>()
        val coordinator = coordinator { _, rawId, _, _, _, _ ->
            acknowledged += rawId
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        val invalid = observation(10, null).let {
            it.copy(dataRows = it.dataRows + listOf(
                row(10, 103, "vnd.android.cursor.item/email_v2", null, "one@example.test").copy(canonicalOrder = 0),
                row(10, 104, "vnd.android.cursor.item/email_v2", null, "two@example.test").copy(canonicalOrder = 0),
            ))
        }
        assertEquals(AndroidBoundedPageResult.PartiallyApplied,
            coordinator.ingest(scope, page(invalid, observation(11, jpeg()))))
        assertEquals(listOf(11L), acknowledged)
        assertNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 10))
        assertEquals(1, database.outboxDao().getAll(account.value).size)
    }

    @Test
    fun invalidOwnershipShapeStillStopsBeforeFollowingContact() = runBlocking {
        var acknowledged = false
        val coordinator = coordinator { _, _, _, _, _, _ ->
            acknowledged = true
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        val invalid = observation(10, null).let {
            it.copy(rawContact = it.rawContact.copy(canonicalContactIdClaim = "untrusted-claim"))
        }
        assertEquals(AndroidBoundedPageResult.RepairRequired,
            coordinator.ingest(scope, page(invalid, observation(11, jpeg()))))
        assertFalse(acknowledged)
        assertTrue(database.outboxDao().getAll(account.value).isEmpty())
    }

    @Test
    fun oversizedCreationRouteStopsBeforePayloadIsolationOrFollowingContact() = runBlocking {
        var acknowledged = false
        val coordinator = coordinator { _, _, _, _, _, _ ->
            acknowledged = true
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        val oversized = observation(10, null).copy(dataRows = (0..128).map { index ->
            row(10, 1000L + index, "vnd.fixture/unsupported", if (index == 0) byteArrayOf(1) else null)
        })
        assertEquals(AndroidBoundedPageResult.RepairRequired,
            coordinator.ingest(scope, page(oversized, observation(11, jpeg()))))
        assertFalse(acknowledged)
        assertTrue(database.outboxDao().getAll(account.value).isEmpty())
    }

    private fun coordinator(ack: AndroidContactObservationAcknowledger): ProductionAndroidContactObservationCoordinator {
        val coordinator = ProductionAndroidContactObservationCoordinator(database, RoomContactRepository(database), ack)
        coordinator.acceptGroupCatalog(scope, listOf(AndroidOwnedGroupRowPage(AndroidProviderAccountName(scope.androidAccountName), 0, emptyList(), null)))
        return coordinator
    }

    private fun observation(rawId: Long, photo: ByteArray?): AndroidStableRawContactObservation {
        val rows = mutableListOf(row(rawId, rawId * 10, "vnd.android.cursor.item/name", null, "Élodie Straße"))
        if (photo != null) rows += row(rawId, rawId * 10 + 1, "vnd.android.cursor.item/photo", photo)
        return AndroidStableRawContactObservation(AndroidOwnedRawContact(rawId, null, null, true, false, 1), rows)
    }

    private fun row(raw: Long, id: Long, mime: String, photo: ByteArray?, text: String? = null) = AndroidOwnedDataRow(
        dataRowId = id, rawContactId = raw, mimeType = mime,
        canonicalValueId = null, canonicalOrder = null, linkedValueIdsEncoding = null,
        isPrimary = false, isSuperPrimary = false, stringSlots = listOf(text) + List(13) { null }, binarySlot = photo,
    )
    private fun page(vararg observations: AndroidStableRawContactObservation) = AndroidStableRawContactObservationPage(observations.toList(), null)
    private fun jpeg(color: Int = Color.RED): ByteArray {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            image.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            image.recycle()
        }
    }
    private companion object { const val DB = "native-contact-creation-regression.db" }
}
