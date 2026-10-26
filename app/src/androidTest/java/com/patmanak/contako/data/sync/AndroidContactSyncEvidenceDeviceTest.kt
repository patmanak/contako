package com.patmanak.contako.data.sync

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidContactsProviderReader
import com.patmanak.contako.data.android.provider.AndroidContactsProviderWriter
import com.patmanak.contako.data.android.provider.AndroidGroupsProviderReader
import com.patmanak.contako.data.android.provider.AndroidProjectionBinaryLoader
import com.patmanak.contako.data.android.provider.AndroidRawContactLifecycleGateway
import com.patmanak.contako.data.android.provider.RoomAndroidPhotoProviderWriteCoordinator
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidGroupMembershipProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.data.local.toDomain
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room and an in-process empty provider; never an Android account or real contacts. */
@RunWith(AndroidJUnit4::class)
class AndroidContactSyncEvidenceDeviceTest {
    private val application: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private val scope = AccountScope("android-evidence-fixture")
    private val context = AndroidInteroperabilityContext(scope, "synthetic-android", 1, 0)
    private val mapper = CanonicalAndroidContactMapper()
    private val canonical = CanonicalContact(scope.value, "synthetic-contact", revision = 7,
        displayName = "Synthetic contact", remoteContactId = "synthetic-remote", values = listOf(
            ContactValue("note", ContactValueKind.NOTE, "Synthetic note", order = 0),
            ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-SYNTHETIC:retained", order = 0),
        ))
    private val snapshot get() = mapper.project(canonical)
    private val memberships get() = AndroidGroupMembershipSnapshot.create(scope.value, canonical.id,
        null, AndroidGroupMembershipAvailability.NO_EMAIL, emptyList())

    @Before fun setup() = runBlocking<Unit> {
        application.deleteDatabase(DB)
        database = ContakoDatabase.create(application, DB)
        val service = RoomAndroidProjectionLedger(database)
        val account = service.ensureAccount(scope)
        service.bindAndroidAccountName(scope, account.revision, context.androidAccountName)
        database.contactDao().upsert(canonical.toEntity())
        database.contactDao().upsertValues(canonical.values.map { it.toEntity(canonical) })
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(
            scope.value, canonical.id, 0, 0, RAW, canonical.remoteContactId,
            null, null, null, null, "REPAIR_REQUIRED", "NONE", "NONE", "ADOPTED"))
        database.androidGroupProjectionDao().insertMembership(AndroidGroupMembershipProjectionLedgerEntity(
            scope.value, canonical.id, 0, 0, RAW, null, null, null, null, "REPAIR_REQUIRED", "NONE"))
    }

    @After fun cleanup() {
        database.close()
        application.deleteDatabase(DB)
    }

    @Test fun verifiedCompletionRecordsTheCoveredCanonicalRevisionAndActualCommitTime() = runBlocking {
        prepare()
        assertNull(evidence())
        val earliest = System.currentTimeMillis()
        assertEquals(AndroidProjectionWriteLedgerResult.Applied, complete())
        val latest = System.currentTimeMillis()
        val proof = requireNotNull(evidence())
        assertEquals(canonical.revision, proof.canonicalRevision)
        assertTrue(proof.confirmedAtEpochMillis in earliest..latest)
        assertEquals(context.providerEpoch, proof.providerEpoch)
        assertEquals(context.androidAccountName, proof.androidAccountName)
        assertEquals(canonical.remoteContactId, proof.sourceIdentity)
        assertEquals(RAW, proof.rawContactLocator)
        assertEquals(mapper.fingerprint(snapshot).sha256Hex, proof.canonicalFingerprint)
        assertEquals(memberships.semanticFingerprint().sha256Hex, proof.membershipFingerprint)
        assertNull(proof.photoReceiptKey)
        assertEquals("CLEAN", database.androidProjectionLedgerDao().get(scope.value, canonical.id)?.projectionState)
        assertNotNull(database.androidProjectionLedgerDao().getBaseline(scope.value, canonical.id))
        assertNotNull(database.androidGroupProjectionDao().getMembershipBaseline(scope.value, canonical.id))
        // Preserved fields are still canonical. This proof only covers the Android projection.
        assertEquals(canonical.values.toSet(), database.contactDao().get(scope.value, canonical.id)?.toDomain()?.values?.toSet())
    }

    @Test fun failedReadbackDoesNotCreateEvidenceOrCleanThePreparedWrite() = runBlocking {
        prepare()
        val changed = snapshot.copy(rows = snapshot.rows.map { row ->
            if (row.identity.canonicalValueId == "note") row.copy(value = "Different observation") else row
        })
        val result = RoomAndroidProjectionWriteLedger(database).complete(context, canonical.id,
            canonical.revision, 1, 1, changed, memberships)
        assertEquals(AndroidProjectionWriteLedgerResult.RepairRequired, result)
        assertNull(evidence())
        assertEquals("WRITE_PENDING", database.androidProjectionLedgerDao().get(scope.value, canonical.id)?.projectionState)
    }

    @Test fun evidenceInsertFailureRollsBackBothBaselinesAndCleanStates() = runBlocking {
        prepare()
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_synthetic_sync_evidence BEFORE INSERT ON contact_sync_evidence
            BEGIN SELECT RAISE(ABORT, 'synthetic_evidence_failure'); END
        """.trimIndent())
        try {
            complete()
            fail("Expected controlled SQLite failure")
        } catch (_: SQLiteException) {
            assertNull(evidence())
            assertNull(database.androidProjectionLedgerDao().getBaseline(scope.value, canonical.id))
            assertNull(database.androidGroupProjectionDao().getMembershipBaseline(scope.value, canonical.id))
            assertEquals("WRITE_PENDING", currentLedger().projectionState)
            assertEquals(1L, currentLedger().revision)
            assertEquals("WRITE_PENDING", database.androidGroupProjectionDao()
                .getMembership(scope.value, canonical.id)?.projectionState)
        }
    }

    @Test fun anOldCanonicalRevisionCannotAcquireAConfirmation() = runBlocking {
        prepare()
        database.contactDao().upsert(canonical.copy(revision = canonical.revision + 1).toEntity())
        assertEquals(AndroidProjectionWriteLedgerResult.Stale, complete())
        assertNull(evidence())
    }

    @Test fun aPhotoWithoutItsExactProviderReceiptCannotCreateWholeProjectionEvidence() = runBlocking {
        val photoContact = canonical.copy(values = canonical.values + ContactValue(
            "photo", ContactValueKind.PHOTO, "synthetic-photo", order = 0, binaryReference = "synthetic-offline-photo"))
        database.contactDao().upsertValues(photoContact.values.map { it.toEntity(photoContact) })
        val observed = mapper.project(photoContact)
        val ledger = RoomAndroidProjectionWriteLedger(database)
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            ledger.prepare(context, canonical.id, 0, canonical.revision, 0, observed, memberships))
        // This controlled snapshot deliberately has no photo-provider receipt. Existing domain
        // completion semantics stay unchanged; optional presentation evidence must stay absent.
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            ledger.complete(context, canonical.id, canonical.revision, 1, 1, observed, memberships))
        assertNull(evidence())
    }

    @Test fun aCanonicalPhotoOmittedByOfflineProjectionCannotAcquireWholeProjectionEvidence() = runBlocking {
        val photoContact = canonical.copy(values = canonical.values + ContactValue(
            "photo", ContactValueKind.PHOTO, "synthetic-photo", order = 0, binaryReference = "unavailable-offline-photo"))
        database.contactDao().upsertValues(photoContact.values.map { it.toEntity(photoContact) })
        val offlineMapper = CanonicalAndroidContactMapper(photoBytesAvailable = { false })
        val observed = offlineMapper.project(photoContact)
        assertFalse(observed.rows.any { it.kind == com.patmanak.contako.data.android.mapping.AndroidRowKind.PHOTO })
        val ledger = RoomAndroidProjectionWriteLedger(database, offlineMapper)
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            ledger.prepare(context, canonical.id, 0, canonical.revision, 0, observed, memberships))
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            ledger.complete(context, canonical.id, canonical.revision, 1, 1, observed, memberships))
        assertEquals("CLEAN", currentLedger().projectionState)
        assertNull(evidence())
        assertEquals(context.providerEpoch, currentLedger().providerEpoch)
        assertEquals(canonical.remoteContactId, currentLedger().sourceIdentity)
    }

    @Test fun anOldEpochOrWrongAccountNameCannotAcquireAConfirmation() = runBlocking {
        prepare()
        assertEquals(AndroidProjectionWriteLedgerResult.Stale, complete(context.copy(providerEpoch = 1)))
        assertEquals(AndroidProjectionWriteLedgerResult.Stale, complete(context.copy(androidAccountName = "foreign-account")))
        assertNull(evidence())
    }

    @Test fun pendingDeletionAppliedDoesNotRecordAbsenceOrReadAProvider() = runBlocking {
        database.contactDao().upsert(canonical.copy(isDeleted = true, pendingMutationRevision = 7).toEntity())
        val provider = EmptyProvider()
        val executor = deletionExecutor(provider)
        assertEquals(AndroidBoundedPageResult.Applied, executor.project(context, currentLedger()))
        assertEquals(0, provider.queries)
        assertNull(evidence())
        assertEquals("NONE", currentLedger().tombstoneState)
    }

    @Test fun onlyReconciledDurableDeletionRecordsAbsenceAndRepeatedSkipKeepsItsTime() = runBlocking {
        database.contactDao().upsert(canonical.copy(isDeleted = true).toEntity())
        val provider = EmptyProvider()
        val executor = deletionExecutor(provider)
        assertEquals(AndroidBoundedPageResult.Applied, executor.project(context, currentLedger()))
        val proof = requireNotNull(evidence())
        assertEquals(canonical.revision, proof.canonicalRevision)
        assertTrue(proof.confirmedAtEpochMillis > 0)
        assertEquals("DETACHED", currentLedger().projectionState)
        assertEquals("REMOTE_CONVERGED", currentLedger().tombstoneState)
        assertEquals(1, provider.queries)
        assertEquals(AndroidBoundedPageResult.Applied, executor.project(context, currentLedger()))
        assertEquals(proof, evidence())
        assertEquals(1, provider.queries)
    }

    @Test fun staleDeletionEpochCannotRecordAbsenceOrReadAProvider() = runBlocking {
        database.contactDao().upsert(canonical.copy(isDeleted = true).toEntity())
        val provider = EmptyProvider()
        assertEquals(AndroidBoundedPageResult.ReplanRequired,
            deletionExecutor(provider).project(context.copy(providerEpoch = 1), currentLedger()))
        assertEquals(0, provider.queries)
        assertNull(evidence())
    }

    @Test fun deletionWithContradictorySourceCannotRecordAbsenceOrReadAProvider() = runBlocking {
        database.contactDao().upsert(canonical.copy(isDeleted = true, remoteContactId = "other-source").toEntity())
        val provider = EmptyProvider()
        assertEquals(AndroidBoundedPageResult.RepairRequired,
            deletionExecutor(provider).project(context, currentLedger()))
        assertEquals(0, provider.queries)
        assertNull(evidence())
    }

    private suspend fun prepare() {
        assertEquals(AndroidProjectionWriteLedgerResult.Applied, RoomAndroidProjectionWriteLedger(database)
            .prepare(context, canonical.id, 0, canonical.revision, 0, snapshot, memberships))
    }

    private suspend fun complete(actual: AndroidInteroperabilityContext = context) =
        RoomAndroidProjectionWriteLedger(database).complete(actual, canonical.id,
            canonical.revision, 1, 1, snapshot, memberships)

    private suspend fun evidence() = database.contactSyncEvidenceDao().get(scope.value, canonical.id, "ANDROID")
    private suspend fun currentLedger() = requireNotNull(database.androidProjectionLedgerDao().get(scope.value, canonical.id))

    private fun deletionExecutor(provider: EmptyProvider): ProductionAndroidProjectionItemExecutor {
        val resolver = ContentResolver.wrap(provider)
        val reader = AndroidContactsProviderReader(resolver)
        val loader = AndroidProjectionBinaryLoader { null }
        return ProductionAndroidProjectionItemExecutor(database, reader, AndroidGroupsProviderReader(resolver),
            AndroidContactsProviderWriter(resolver), AndroidRawContactLifecycleGateway(resolver),
            RoomAndroidPhotoProviderWriteCoordinator(database, resolver, reader, loader), loader)
    }

    private class EmptyProvider : ContentProvider() {
        var queries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            return MatrixCursor(projection ?: emptyArray())
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No provider write expected")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("An already absent owned row must not trigger deletion")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("No provider write expected")
    }

    private companion object {
        const val DB = "android-contact-sync-evidence-fixture.db"
        const val RAW = 42L
    }
}
