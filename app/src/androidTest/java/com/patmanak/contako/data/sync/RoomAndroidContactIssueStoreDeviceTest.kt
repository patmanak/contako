package com.patmanak.contako.data.sync

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.domain.model.CanonicalContact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Room-only presentation evidence; never creates an Android account or contacts-provider row. */
@RunWith(AndroidJUnit4::class)
class RoomAndroidContactIssueStoreDeviceTest {
    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ContakoDatabase::class.java,
    )
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private val account = AccountScope("issue-synthetic-account")
    private val scope = AndroidInteroperabilityContext(account, "issue-synthetic-provider", 1, 0)
    private val raw = AndroidOwnedRawContact(42, CONTACT, SOURCE, true, false, 9)
    private val store get() = RoomAndroidContactIssueStore(database)

    @Before fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
        val service = RoomAndroidProjectionLedger(database)
        val binding = service.ensureAccount(account)
        service.bindAndroidAccountName(account, binding.revision, scope.androidAccountName)
        database.contactDao().upsert(CanonicalContact(account.value, CONTACT,
            displayName = "Synthetic contact", revision = 4, remoteContactId = SOURCE).toEntity())
        database.androidProjectionLedgerDao().insert(ledger())
    }

    @After fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DB)
    }

    @Test fun failedProjectionWithCleanLedgerSurvivesReopenWithoutChangingIntent() = runBlocking {
        val beforeContact = database.contactDao().get(account.value, CONTACT)
        val beforeLedger = database.androidProjectionLedgerDao().get(account.value, CONTACT)
        assertTrue(store.recordProjectionFailure(scope, requireNotNull(beforeLedger), AndroidBoundedPageResult.RepairRequired))
        assertEquals(beforeContact, database.contactDao().get(account.value, CONTACT))
        assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(account.value, CONTACT))
        assertTrue(database.outboxDao().getAll(account.value).isEmpty())
        database.close()
        database = ContakoDatabase.create(context, DB)
        val issue = database.androidContactIssueDao().observeCurrent(account.value).first().single()
        assertEquals(CONTACT, issue.canonicalContactId)
        assertEquals("PROJECTION", issue.origin)
        assertEquals("REPAIR_REQUIRED", issue.reason)
        assertEquals(4L, issue.canonicalRevision)
        assertFalse(issue.toString().contains(CONTACT))
        assertFalse(issue.toString().contains(account.value))
        assertEquals(beforeContact, database.contactDao().get(account.value, CONTACT))
        assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(account.value, CONTACT))
    }

    @Test fun successfulObservationClearsOnlyItsVerifiedOrigin() = runBlocking {
        assertTrue(store.recordIngestFailure(scope, raw, AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_VENDOR_MIME))
        assertTrue(store.recordProjectionFailure(scope, ledger(), AndroidBoundedPageResult.RepairRequired))
        assertEquals(2, database.androidContactIssueDao().getAll(account.value).size)
        assertTrue(store.clearIngestFailure(scope, raw))
        assertEquals("PROJECTION", database.androidContactIssueDao().getAll(account.value).single().origin)
        assertFalse(store.clearIngestFailure(scope, raw))
        assertTrue(store.clearProjectionFailure(scope, CONTACT))
        assertTrue(database.androidContactIssueDao().getAll(account.value).isEmpty())
    }

    @Test fun staleForeignOrReboundIdentityCannotAttachOrClearAnIssue() = runBlocking {
        assertTrue(store.recordIngestFailure(scope, raw, AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE))
        assertTrue(store.recordProjectionFailure(scope, ledger(), AndroidBoundedPageResult.RepairRequired))
        val expected = database.androidContactIssueDao().getAll(account.value)
        listOf(scope.copy(account = AccountScope("other-account")), scope.copy(accountRevision = 0),
            scope.copy(androidAccountName = "other-provider"), scope.copy(providerEpoch = 1)).forEach { stale ->
            assertFalse(store.recordIngestFailure(stale, raw, AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE))
            assertFalse(store.clearIngestFailure(stale, raw))
            assertFalse(store.recordProjectionFailure(stale, ledger(), AndroidBoundedPageResult.RepairRequired))
            assertFalse(store.clearProjectionFailure(stale, CONTACT))
        }
        listOf(raw.copy(canonicalContactIdClaim = "another-contact"), raw.copy(sourceIdentity = "another-source"),
            raw.copy(rawContactId = 43)).forEach { foreign ->
            assertFalse(store.recordIngestFailure(scope, foreign, AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE))
            assertFalse(store.clearIngestFailure(scope, foreign))
        }
        assertFalse(store.recordProjectionFailure(scope, ledger().copy(rawContactLocator = 43), AndroidBoundedPageResult.RepairRequired))
        assertFalse(store.recordProjectionFailure(scope, ledger().copy(sourceIdentity = "another-source"), AndroidBoundedPageResult.RepairRequired))
        assertEquals(expected, database.androidContactIssueDao().getAll(account.value))
    }

    @Test fun unknownLifecycleShapesDoNotInventAnAffectedContact() = runBlocking {
        listOf(raw.copy(canonicalContactIdClaim = null), raw.copy(sourceIdentity = null), raw.copy(deleted = true)).forEach { unknown ->
            assertFalse(store.recordIngestFailure(scope, unknown, AndroidExistingContactPlanRepairReason.LIFECYCLE_SHAPE))
        }
        database.androidProjectionLedgerDao().update(ledger().copy(adoptionState = AndroidAdoptionState.SOURCE_ID_PENDING.name))
        assertFalse(store.recordIngestFailure(scope, raw, AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_ADOPTION_STATE_MISMATCH))
        assertTrue(database.androidContactIssueDao().getAll(account.value).isEmpty())
    }

    @Test fun ordinaryPendingAndPassWideFailuresAreNotContactErrors() = runBlocking {
        assertTrue(database.androidContactIssueDao().observeCurrent(account.value).first().isEmpty())
        listOf(AndroidBoundedPageResult.Applied, AndroidBoundedPageResult.PartiallyApplied,
            AndroidBoundedPageResult.LocalPersistenceFailure).forEach { ordinary ->
            assertFalse(store.recordProjectionFailure(scope, ledger(), ordinary))
        }
        assertTrue(database.androidContactIssueDao().getAll(account.value).isEmpty())
    }

    @Test fun epochChangesHideOldIssuesAndRefuseStaleClearing() = runBlocking {
        assertTrue(store.recordProjectionFailure(scope, ledger(), AndroidBoundedPageResult.RepairRequired))
        assertEquals(1, database.androidProjectionLedgerDao().advanceProviderEpoch(account.value, scope.accountRevision))
        assertTrue(database.androidContactIssueDao().observeCurrent(account.value).first().isEmpty())
        assertEquals(1, database.androidContactIssueDao().getAll(account.value).size)
        assertFalse(store.clearProjectionFailure(scope, CONTACT))
        assertFalse(store.recordProjectionFailure(scope, ledger(), AndroidBoundedPageResult.RepairRequired))
    }

    @Test fun contactCleanupCascadesWithoutNeedingAnIndependentIssueReset() = runBlocking {
        assertTrue(store.recordProjectionFailure(scope, ledger(), AndroidBoundedPageResult.RepairRequired))
        database.accountRemovalDao().deleteContacts(account.value)
        assertTrue(database.androidContactIssueDao().getAll(account.value).isEmpty())
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun issuePersistenceFailureDoesNotChangeCanonicalOrLedgerState() = runBlocking {
        val beforeContact = database.contactDao().get(account.value, CONTACT)
        val beforeLedger = database.androidProjectionLedgerDao().get(account.value, CONTACT)
        database.openHelper.writableDatabase.execSQL("DROP TABLE android_contact_issues")
        assertFalse(store.recordProjectionFailure(scope, ledger(), AndroidBoundedPageResult.RepairRequired))
        assertFalse(store.recordIngestFailure(scope, raw, AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE))
        assertEquals(beforeContact, database.contactDao().get(account.value, CONTACT))
        assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(account.value, CONTACT))
        assertTrue(database.outboxDao().getAll(account.value).isEmpty())
    }

    @Test fun cancellationIsNotHiddenAsAPresentationFailure() = runBlocking {
        var cancelled = false
        try {
            withContext(Job().also { it.cancel() }) {
                store.recordProjectionFailure(scope, ledger(), AndroidBoundedPageResult.RepairRequired)
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(database.androidContactIssueDao().getAll(account.value).isEmpty())
    }

    @Test fun unifiedCommitFailureKeepsTheKnownIdentityWithoutChangingCanonicalIntent() = runBlocking {
        val before = database.contactDao().get(account.value, CONTACT)
        assertTrue(store.recordIngestFailure(scope, raw, AndroidIngestActionRequiredReason.CONTACT_EXISTING_COMMIT))
        val issue = database.androidContactIssueDao().observeCurrent(account.value).first().single()
        assertEquals(CONTACT, issue.canonicalContactId)
        assertEquals("CONTACT_EXISTING_COMMIT", issue.reason)
        assertEquals(before, database.contactDao().get(account.value, CONTACT))
        assertTrue(database.outboxDao().getAll(account.value).isEmpty())
    }

    @Test fun aDeletedContactRetainsItsReadFailureUntilExactDeletionConverges() = runBlocking {
        assertTrue(store.recordIngestFailure(scope, raw, AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE))
        val contact = requireNotNull(database.contactDao().getEntity(account.value, CONTACT))
        database.contactDao().upsert(contact.copy(isDeleted = true, pendingMutationRevision = 5))
        database.androidProjectionLedgerDao().update(ledger().copy(
            projectionState = AndroidProjectionWriteState.DETACHED.name,
            tombstoneState = AndroidTombstoneState.CANONICAL_COMMITTED.name))
        assertFalse(store.clearProjectionFailure(scope, CONTACT))
        assertEquals(1, database.androidContactIssueDao().observeCurrent(account.value).first().size)
        database.androidProjectionLedgerDao().update(ledger().copy(
            projectionState = AndroidProjectionWriteState.DETACHED.name,
            tombstoneState = AndroidTombstoneState.REMOTE_CONVERGED.name))
        assertFalse(store.clearProjectionFailure(scope, CONTACT))
        database.contactDao().upsert(contact.copy(isDeleted = true, pendingMutationRevision = null))
        assertTrue(store.clearProjectionFailure(scope, CONTACT))
        assertTrue(database.androidContactIssueDao().getAll(account.value).isEmpty())
    }

    @Test fun migrationFrom20AddsAnEmptyIssueTableAndPreservesExistingContact() {
        val name = "migration-android-contact-issues.db"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 20).use { old ->
            old.execSQL("""
                INSERT INTO contacts(account_id,id,owner_key,first_name,last_name,display_name,sort_name,
                    revision,updated_at_epoch_millis,remote_contact_id,remote_vcard_uid,remote_version,
                    action_required_reasons,pending_mutation_revision,conflict_state,is_deleted)
                VALUES('migration-account','migration-contact','migration-owner','','','Synthetic retained',
                    'Synthetic retained',3,0,NULL,NULL,NULL,'',NULL,NULL,0)
            """.trimIndent())
        }
        try {
            migrations.runMigrationsAndValidate(name, 21, true, ContakoDatabase.MIGRATION_20_21).use { upgraded ->
                upgraded.query("SELECT display_name,revision FROM contacts WHERE account_id='migration-account'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Synthetic retained", it.getString(0))
                    assertEquals(3L, it.getLong(1))
                }
                upgraded.query("SELECT COUNT(*) FROM android_contact_issues").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun ledger() = AndroidProjectionLedgerEntity(
        accountId = account.value, canonicalContactId = CONTACT, revision = 2, providerEpoch = 0,
        rawContactLocator = 42, sourceIdentity = SOURCE, canonicalProjectionFingerprint = null,
        androidBaselineFingerprint = null, pendingProjectionFingerprint = null, observedAndroidFingerprint = null,
        projectionState = AndroidProjectionWriteState.CLEAN.name, ingestionState = AndroidIngestionState.BASELINED.name,
        tombstoneState = AndroidTombstoneState.NONE.name, adoptionState = AndroidAdoptionState.ADOPTED.name,
    )

    private companion object {
        const val DB = "android-contact-issues-room.db"
        const val CONTACT = "issue-synthetic-contact"
        const val SOURCE = "issue-synthetic-remote"
    }
}
