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
    fun opaqueCreatedRowsPreserveSupportedFieldsAndDurableIntentAcrossRestartWithoutBlockingFollowingContact() = runBlocking {
        val acknowledged = mutableListOf<Long>()
        val ack = AndroidContactObservationAcknowledger { _, rawId, _, _, _, _ ->
            acknowledged += rawId
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        val opaqueBinary = byteArrayOf(1, 3, 5, 7)
        val opaque = row(10, 103, "vnd.fixture/opaque", opaqueBinary, "Provider-only payload")
            .copy(canonicalValueId = "untrusted-opaque-claim")
        val vendor = row(10, 104, "vnd.sec.cursor.item/profile_card", opaqueBinary, "Provider-local payload")
        val created = observation(10, null).let { it.copy(dataRows = it.dataRows + listOf(
            row(10, 102, "vnd.android.cursor.item/email_v2", null, "retained@example.test"),
            opaque, vendor, row(10, 105, "vnd.android.cursor.item/im", null, "Provider IM"),
        )) }
        val observedPage = page(created, observation(11, jpeg()))
        assertEquals(AndroidBoundedPageResult.Applied, coordinator(ack).ingest(scope, observedPage))
        assertEquals(listOf(10L, 11L), acknowledged)
        val contactId = requireNotNull(database.androidProjectionLedgerDao()
            .getByRawContactLocator(account.value, 0, 10)).canonicalContactId
        val stored = requireNotNull(database.contactDao().get(account.value, contactId)).toDomain()
        assertEquals("Élodie Straße", stored.displayName)
        assertEquals(listOf("retained@example.test"), stored.valuesOf(ContactValueKind.EMAIL).map { it.value })
        assertTrue(stored.values.none { it.value.contains("Provider") || it.id == opaque.canonicalValueId })
        assertNotNull(database.androidProjectionLedgerDao().getByRawContactLocator(account.value, 0, 11))
        val intents = database.outboxDao().getAll(account.value)
        assertEquals(2, intents.size)
        assertNotNull(database.outboxDao().get(account.value, AggregateType.CONTACT.name, contactId))
        assertNotNull(database.androidProjectionLedgerDao().getUnifiedObservationCommitReceipt(account.value, contactId))

        database.close()
        database = ContakoDatabase.create(context, DB)
        // The fake acknowledges without clearing DIRTY or writing the canonical claim.
        // Replaying that pre-acknowledgement page after another contact advanced the
        // account revision must replan, rather than reuse the first contact's stale receipt.
        assertEquals(AndroidBoundedPageResult.ReplanRequired, coordinator(ack).ingest(scope, observedPage))
        assertEquals(listOf(10L, 11L), acknowledged)
        assertEquals(stored, requireNotNull(database.contactDao().get(account.value, contactId)).toDomain())
        assertEquals(intents, database.outboxDao().getAll(account.value))
        assertEquals(contactId, requireNotNull(database.androidProjectionLedgerDao()
            .getByRawContactLocator(account.value, 0, 10)).canonicalContactId)
        assertArrayEquals(byteArrayOf(1, 3, 5, 7), opaque.binarySlot)
        assertArrayEquals(byteArrayOf(1, 3, 5, 7), vendor.binarySlot)
        assertEquals("Provider-only payload", opaque.stringSlots.first())
        assertEquals("Provider-local payload", vendor.stringSlots.first())
    }

    @Test
    fun knownEmailWithUnexpectedBinaryRemainsUnacknowledgedWithoutStrandingFollowingContact() = runBlocking {
        val acknowledged = mutableListOf<Long>()
        val coordinator = coordinator { _, rawId, _, _, _, _ ->
            acknowledged += rawId
            AndroidProviderAcknowledgementResult.Acknowledged
        }
        val malformed = observation(10, null).let { it.copy(dataRows = it.dataRows +
            row(10, 103, "vnd.android.cursor.item/email_v2", byteArrayOf(1, 3), "retained@example.test")) }

        assertEquals(AndroidBoundedPageResult.PartiallyApplied,
            coordinator.ingest(scope, page(malformed, observation(11, jpeg()))))

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
    fun duplicateValueOrderIsNormalizedWithoutDroppingValuesOrStrandingFollowingContact() = runBlocking {
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
        assertEquals(AndroidBoundedPageResult.Applied,
            coordinator.ingest(scope, page(invalid, observation(11, jpeg()))))
        assertEquals(listOf(10L, 11L), acknowledged)
        val createdId = requireNotNull(database.androidProjectionLedgerDao()
            .getByRawContactLocator(account.value, 0, 10)).canonicalContactId
        val emails = requireNotNull(database.contactDao().get(account.value, createdId))
            .toDomain().values.filter { it.kind == ContactValueKind.EMAIL }
        assertEquals(listOf("one@example.test", "two@example.test"), emails.map { it.value })
        assertEquals(listOf(0, 1), emails.map { it.order })
        assertEquals(2, database.outboxDao().getAll(account.value).size)
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

    @Test
    fun missingInitialBaselineProofStaysUnacknowledgedWithoutStrandingLaterRowsOrPages() = runBlocking {
        val reasons = listOf(
            AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_NO_PHOTO_RECOVERY,
            AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_JOURNAL_MISSING,
            AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_RECOVERY_STATE_MISMATCH,
        )
        reasons.forEachIndexed { index, expected ->
            if (index > 0) {
                database.close()
                setUp()
            }
            val repository = RoomContactRepository(database)
            val photo = if (index == 0) null else jpeg()
            val blockedId = "blocked-baseline"
            val source = "blocked-source"
            val saved = repository.saveContact(com.patmanak.contako.domain.model.CanonicalContact(
                accountId = account.value, id = blockedId, displayName = "Baseline fixture",
                remoteContactId = source,
                values = photo?.let { listOf(com.patmanak.contako.domain.model.ContactValue(
                    "photo", ContactValueKind.PHOTO, requireNotNull(AndroidProviderPhotoReferenceEncoder.encode(it)),
                    order = 0, isPrimary = true,
                )) }.orEmpty(),
            )).let {
                check(it is com.patmanak.contako.domain.repository.SaveResult.Saved)
                it.value
            }
            val ledger = RoomAndroidProjectionLedger(database)
            val attached = ledger.attachCanonicalContact(account, blockedId, source)
            ledger.acknowledgeAdoption(account, blockedId, attached.revision,
                com.patmanak.contako.data.android.AndroidRawContactLocator(scope.providerEpoch, 10))
            val beforeLedger = requireNotNull(database.androidProjectionLedgerDao().get(account.value, blockedId))
            if (index == 2) {
                database.androidGroupProjectionDao().upsertPhotoProviderWriteJournal(
                    com.patmanak.contako.data.local.AndroidPhotoProviderWriteJournalEntity(
                        account.value, blockedId, scope.androidAccountName, scope.providerEpoch, 10, 1, source,
                        saved.revision, beforeLedger.revision, "photo",
                        requireNotNull(AndroidProviderPhotoReferenceEncoder.encode(requireNotNull(photo))),
                        photo.size.toLong(), "0".repeat(64), "REPAIR_REQUIRED", null,
                    ),
                )
            }
            val beforeJournal = database.androidGroupProjectionDao().getPhotoProviderWriteJournal(account.value, blockedId)
            val beforeContact = requireNotNull(database.contactDao().get(account.value, blockedId)).toDomain()
            val beforeIntent = database.outboxDao().get(account.value, AggregateType.CONTACT.name, blockedId)
            val acknowledged = mutableListOf<Long>()
            val observedFailures = mutableListOf<AndroidExistingContactPlanRepairReason>()
            val coordinator = ProductionAndroidContactObservationCoordinator(database, repository,
                AndroidContactObservationAcknowledger { _, rawId, _, _, _, _ ->
                    acknowledged += rawId
                    AndroidProviderAcknowledgementResult.Acknowledged
                }, existingPlanRepairObserver = AndroidExistingContactPlanRepairObserver { observedFailures += it })
            coordinator.acceptGroupCatalog(scope, listOf(AndroidOwnedGroupRowPage(
                AndroidProviderAccountName(scope.androidAccountName), scope.providerEpoch, emptyList(), null,
            )))
            val blocked = observation(10, photo).let { it.copy(rawContact = it.rawContact.copy(
                canonicalContactIdClaim = blockedId, sourceIdentity = source,
            )) }
            assertEquals(AndroidBoundedPageResult.PartiallyApplied,
                coordinator.ingest(scope, page(blocked, observation(11, jpeg()))))
            assertEquals(listOf(expected), observedFailures)
            assertEquals(listOf(11L), acknowledged)
            assertEquals(beforeContact, requireNotNull(database.contactDao().get(account.value, blockedId)).toDomain())
            assertEquals(beforeIntent, database.outboxDao().get(account.value, AggregateType.CONTACT.name, blockedId))
            assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(account.value, blockedId))
            assertEquals(beforeJournal, database.androidGroupProjectionDao().getPhotoProviderWriteJournal(account.value, blockedId))
            assertNull(database.androidProjectionLedgerDao().getBaseline(account.value, blockedId))
            assertEquals(AndroidBoundedPageResult.Applied, coordinator.ingest(scope, page(observation(12, jpeg()))))
            assertEquals(listOf(11L, 12L), acknowledged)
        }
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
