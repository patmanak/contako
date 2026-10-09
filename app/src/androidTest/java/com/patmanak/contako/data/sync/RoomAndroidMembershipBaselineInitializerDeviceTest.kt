package com.patmanak.contako.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** App-private Room fixtures only; no AccountManager, ContactsProvider or remote mutations. */
@RunWith(AndroidJUnit4::class)
class RoomAndroidMembershipBaselineInitializerDeviceTest {
    private lateinit var database: ContakoDatabase
    private lateinit var androidContext: Context
    private val scope = AccountScope("membership-seed-fixture")
    private val context = AndroidInteroperabilityContext(scope, "fixture-android", 1, 0)
    private val canonical = CanonicalContact(scope.value, "contact", remoteContactId = "fixture-remote")
    private lateinit var ledger: AndroidProjectionLedgerEntity

    @Before fun setUp() = runBlocking {
        androidContext = ApplicationProvider.getApplicationContext()
        androidContext.deleteDatabase(DB)
        database = ContakoDatabase.create(androidContext, DB)
        database.contactDao().upsert(canonical.toEntity())
        val service = RoomAndroidProjectionLedger(database)
        val account = service.ensureAccount(scope)
        service.bindAndroidAccountName(scope, account.revision, context.androidAccountName)
        ledger = AndroidProjectionLedgerEntity(
            scope.value, canonical.id, 0, 0, 10, canonical.remoteContactId,
            null, null, null, null, "REPAIR_REQUIRED", "NONE", "NONE", "SOURCE_ID_PENDING",
        )
        database.androidProjectionLedgerDao().insert(ledger)
        Unit
    }

    @After fun tearDown() {
        database.close()
        androidContext.deleteDatabase(DB)
    }

    @Test fun failedBaselineInsertRollsBackInitialLedgerAndRetryCreatesBoth() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fixture_baseline_failure BEFORE INSERT ON android_group_membership_baselines " +
                "BEGIN SELECT RAISE(ABORT, 'fixture failure'); END",
        )
        try {
            RoomAndroidMembershipBaselineInitializer(database).ensure(context, ledger, canonical)
            throw AssertionError("Expected controlled SQLite failure")
        } catch (_: SQLiteException) {
            assertNull(database.androidGroupProjectionDao().getMembership(scope.value, canonical.id))
            assertNull(database.androidGroupProjectionDao().getMembershipBaseline(scope.value, canonical.id))
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_baseline_failure")
        assertTrue(RoomAndroidMembershipBaselineInitializer(database).ensure(context, ledger, canonical))
        val seed = AndroidMembershipInitializationSeed(context, canonical, 10)
        assertEquals(seed.ledger, database.androidGroupProjectionDao().getMembership(scope.value, canonical.id))
        assertArrayEquals(seed.baseline.encodedSnapshot,
            requireNotNull(database.androidGroupProjectionDao().getMembershipBaseline(scope.value, canonical.id)).encodedSnapshot)
        assertEquals(ledger, database.androidProjectionLedgerDao().get(scope.value, canonical.id))
    }

    @Test fun exactInterruptedLegacySeedRecoversAndRepeatedEnsurePreservesIt() = runBlocking {
        val seed = AndroidMembershipInitializationSeed(context, canonical, 10)
        database.androidGroupProjectionDao().insertMembership(seed.ledger)
        val initializer = RoomAndroidMembershipBaselineInitializer(database)
        repeat(2) { assertTrue(initializer.ensure(context, ledger, canonical)) }
        assertEquals(seed.ledger, database.androidGroupProjectionDao().getMembership(scope.value, canonical.id))
        assertArrayEquals(seed.baseline.encodedSnapshot,
            requireNotNull(database.androidGroupProjectionDao().getMembershipBaseline(scope.value, canonical.id)).encodedSnapshot)
        assertEquals(ledger, database.androidProjectionLedgerDao().get(scope.value, canonical.id))
    }

    @Test fun missingCompletedBaselineIsNotFabricatedAndStaleContextDoesNotSeed() = runBlocking {
        val initializer = RoomAndroidMembershipBaselineInitializer(database)
        assertFalse(initializer.ensure(context.copy(accountRevision = 2), ledger, canonical))
        assertNull(database.androidGroupProjectionDao().getMembership(scope.value, canonical.id))
        val completed = AndroidMembershipInitializationSeed(context, canonical, 10).ledger.copy(revision = 2)
        database.androidGroupProjectionDao().insertMembership(completed)
        assertTrue(initializer.ensure(context, ledger, canonical))
        assertNull(database.androidGroupProjectionDao().getMembershipBaseline(scope.value, canonical.id))
        assertEquals(completed, database.androidGroupProjectionDao().getMembership(scope.value, canonical.id))
    }

    @Test fun photoAggregatePreferenceCompletesProjectionWhileRetainingActualBaselineFingerprint() = runBlocking {
        val photoContact = canonical.copy(values = listOf(
            ContactValue("photo", ContactValueKind.PHOTO, "", order = 0, binaryReference = "fixture-photo"),
        ))
        database.contactDao().upsert(photoContact.toEntity())
        assertTrue(RoomAndroidMembershipBaselineInitializer(database).ensure(context, ledger, photoContact))
        val mapper = CanonicalAndroidContactMapper()
        val desired = mapper.project(photoContact)
        assertTrue(desired.rows.any { it.kind == AndroidRowKind.PHOTO })
        val observed = desired.copy(rows = desired.rows.map { row ->
            if (row.kind == AndroidRowKind.PHOTO) row.copy(isSuperPrimary = true) else row
        })
        val membership = AndroidGroupMembershipSnapshotBinaryCodec.decode(
            AndroidMembershipInitializationSeed(context, photoContact, 10).baseline.encodedSnapshot,
        )
        val writes = RoomAndroidProjectionWriteLedger(database, mapper)
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            writes.prepare(context, canonical.id, ledger.revision, photoContact.revision, 0, desired, membership))
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            writes.complete(context, canonical.id, photoContact.revision, 1, 1, observed, membership))
        val completed = requireNotNull(database.androidProjectionLedgerDao().get(scope.value, canonical.id))
        assertEquals(mapper.fingerprint(observed).sha256Hex, completed.androidBaselineFingerprint)
        assertNull(completed.pendingProjectionFingerprint)
        assertEquals("CLEAN", completed.projectionState)
    }

    private companion object { const val DB = "membership-baseline-initializer-fixture.db" }
}
