package com.patmanak.contako.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservation
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.android.provider.AndroidStableRawContactPageResult
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.TargetedRecoveryPrerequisite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room plus controlled provider seams only. No Android account/provider or Proton operation. */
@RunWith(AndroidJUnit4::class)
class TargetedAndroidProjectionDeviceTest {
    private val application: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private val context = AndroidInteroperabilityContext(ACCOUNT, "synthetic-android", 1, 0)

    @Before fun setup() = runBlocking<Unit> {
        application.deleteDatabase(NAME)
        database = ContakoDatabase.create(application, NAME)
        val ledger = RoomAndroidProjectionLedger(database)
        val account = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, account.revision, context.androidAccountName)
    }

    @After fun cleanup() {
        database.close()
        application.deleteDatabase(NAME)
    }

    @Test fun onlyOneOfThreeHundredContactsIsExecutedAndItsIndependentIssueIsCleared() = runBlocking {
        repeat(300) { seed("synthetic-$it", it + 1L) }
        val target = "synthetic-150"
        issue(target)
        issue("synthetic-151")
        val before = database.androidProjectionLedgerDao().getAll(ACCOUNT.value).filter { it.canonicalContactId != target }
        val witness = database.contactDao().get(ACCOUNT.value, "synthetic-151")
        val calls = mutableListOf<String>()
        val coordinator = coordinator { _, entry ->
            calls += entry.canonicalContactId
            writeReceipt(entry.canonicalContactId)
            AndroidBoundedPageResult.Applied
        }
        assertEquals(TargetedRecoveryWorkResult.Resolved(1), coordinator.projectContact(context, target, 1))
        assertEquals(listOf(target), calls)
        assertEquals(before, database.androidProjectionLedgerDao().getAll(ACCOUNT.value).filter { it.canonicalContactId != target })
        assertEquals(witness, database.contactDao().get(ACCOUNT.value, "synthetic-151"))
        assertNull(database.androidContactIssueDao().get(ACCOUNT.value, target, "PROJECTION"))
        assertNotNull(database.androidContactIssueDao().get(ACCOUNT.value, "synthetic-151", "PROJECTION"))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test fun appliedWithoutTheCurrentContactAndMembershipReceiptsDoesNotClearOrResolveAnything() = runBlocking {
        seed("target", 1)
        issue("target")
        val before = database.contactDao().get(ACCOUNT.value, "target")
        val coordinator = coordinator { _, _ -> AndroidBoundedPageResult.Applied }
        assertTrue(coordinator.projectContact(context, "target", 1) is TargetedRecoveryWorkResult.Stale)
        assertNotNull(database.androidContactIssueDao().get(ACCOUNT.value, "target", "PROJECTION"))
        assertEquals(before, database.contactDao().get(ACCOUNT.value, "target"))
    }

    @Test fun aRetainedCurrentProjectionIssuePreventsResolvedAfterBestEffortClearing() = runBlocking {
        seed("target", 1)
        issue("target")
        // Suppress only this isolated database's presentation DELETE. The write receipts can
        // commit, but a best-effort clear that made no change cannot justify a resolved result.
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER retain_synthetic_projection_issue BEFORE DELETE ON android_contact_issues
            WHEN OLD.origin='PROJECTION' BEGIN SELECT RAISE(IGNORE); END
        """.trimIndent())
        val coordinator = coordinator { _, _ -> writeReceipt("target"); AndroidBoundedPageResult.Applied }
        val result = coordinator.projectContact(context, "target", 1)
        assertEquals(TargetedRecoveryPrerequisite.UNAVAILABLE, (result as TargetedRecoveryWorkResult.Failed).prerequisite)
        assertNotNull(database.androidContactIssueDao().get(ACCOUNT.value, "target", "PROJECTION"))
    }

    @Test fun staleRevisionEpochOrOwnerCannotReachTheItemExecutor() = runBlocking {
        seed("target", 1)
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; AndroidBoundedPageResult.Applied }
        assertTrue(coordinator.projectContact(context, "target", 2) is TargetedRecoveryWorkResult.Stale)
        assertTrue(coordinator.projectContact(context.copy(providerEpoch = 1), "target", 1) is TargetedRecoveryWorkResult.Stale)
        assertTrue(coordinator.projectContact(context.copy(account = AccountScope("foreign-owner")), "target", 1) is TargetedRecoveryWorkResult.Stale)
        assertEquals(0, calls)
    }

    @Test fun normalProjectionAttributesOnlyFailuresWhoseExactIssueWasRetained() = runBlocking {
        seed("target", 1)
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database,
            { _, _ -> AndroidBoundedPageResult.RepairRequired })
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, coordinator.projectPage(context, null).second)
        assertTrue(coordinator.lastFailureIsFullyScoped)
        assertNotNull(database.androidContactIssueDao().get(ACCOUNT.value, "target", "PROJECTION"))
        assertEquals(AndroidBoundedPageResult.ReplanRequired,
            coordinator.projectPage(context.copy(accountRevision = context.accountRevision + 1), null).second)
        assertFalse(coordinator.lastFailureIsFullyScoped)
    }

    @Test fun aRefusedIssueRecordLeavesPartialFailureUnscopedEvenIfAnotherIssueExists() = runBlocking {
        seed("target", 1)
        seed("other", 2)
        issue("other")
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database, { _, entry ->
            if (entry.canonicalContactId == "target") {
                database.androidProjectionLedgerDao().update(entry.copy(sourceIdentity = "contradictory-source"))
            }
            AndroidBoundedPageResult.RepairRequired
        })
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, coordinator.projectPage(context, null).second)
        assertFalse(coordinator.lastFailureIsFullyScoped)
        assertNull(database.androidContactIssueDao().get(ACCOUNT.value, "target", "PROJECTION"))
        assertNotNull(database.androidContactIssueDao().get(ACCOUNT.value, "other", "PROJECTION"))
    }

    @Test fun anUnscopedGroupFailureCannotReuseEarlierProjectionAttribution() = runBlocking {
        seed("target", 1)
        var groupResult: AndroidBoundedPageResult = AndroidBoundedPageResult.Applied
        val coordinator = RoomBoundedAndroidProjectionCoordinator(database,
            { _, _ -> AndroidBoundedPageResult.RepairRequired },
            groupProjectionCoordinator = { groupResult })
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, coordinator.projectPage(context, null).second)
        assertTrue(coordinator.lastFailureIsFullyScoped)
        groupResult = AndroidBoundedPageResult.RepairRequired
        assertEquals(AndroidBoundedPageResult.RepairRequired, coordinator.projectPage(context, null).second)
        assertFalse(coordinator.lastFailureIsFullyScoped)
    }

    @Test fun nativePartialAttributionRequiresTheExactIngestIssueToBeDurablyRecorded() = runBlocking {
        seed("target", 42)
        val coordinator = ProductionAndroidContactObservationCoordinator(database, RoomContactRepository(database),
            { _, _, _, _, _, _ -> error("A rejected baseline cannot acknowledge native intent") })
        coordinator.acceptGroupCatalog(context, listOf(AndroidOwnedGroupRowPage(
            com.patmanak.contako.data.android.provider.AndroidProviderAccountName(context.androidAccountName),
            0, emptyList(), null)))
        val observed = AndroidStableRawContactObservationPage(listOf(AndroidStableRawContactObservation(
            AndroidOwnedRawContact(42, "target", "remote-target", dirty = true, deleted = false, version = 7),
            emptyList())), null)
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, coordinator.ingest(context, observed))
        assertTrue(coordinator.lastFailureIsFullyScoped)
        assertNotNull(database.androidContactIssueDao().get(ACCOUNT.value, "target", "INGEST"))
        database.androidContactIssueDao().delete(ACCOUNT.value, "target", "INGEST", 0)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_synthetic_ingest_issue BEFORE INSERT ON android_contact_issues
            WHEN NEW.origin='INGEST' BEGIN SELECT RAISE(ABORT, 'synthetic_issue_storage_failure'); END
        """.trimIndent())
        // The existing partial-result and no-acknowledgement policy stays unchanged even when
        // presentation storage fails. Only its ability to attribute every failure changes.
        assertEquals(AndroidBoundedPageResult.PartiallyApplied, coordinator.ingest(context, observed))
        assertFalse(coordinator.lastFailureIsFullyScoped)
        assertNull(database.androidContactIssueDao().get(ACCOUNT.value, "target", "INGEST"))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test fun aNewCanonicalRevisionDuringProjectionKeepsTheIssueAndInvalidatesTheOldReceipt() = runBlocking {
        seed("target", 1)
        issue("target")
        val coordinator = coordinator { _, _ ->
            writeReceipt("target")
            val current = requireNotNull(database.contactDao().getEntity(ACCOUNT.value, "target"))
            database.contactDao().upsert(current.copy(revision = current.revision + 1))
            AndroidBoundedPageResult.Applied
        }
        assertTrue(coordinator.projectContact(context, "target", 1) is TargetedRecoveryWorkResult.Stale)
        assertNotNull(database.androidContactIssueDao().get(ACCOUNT.value, "target", "PROJECTION"))
        assertEquals(2L, database.contactDao().getEntity(ACCOUNT.value, "target")?.revision)
    }

    @Test fun theExactNativeEditIsDurableBeforeProjectionUsesItsNewRevisionWithoutDrainingUpload() = runBlocking {
        seed("target", 42)
        seed("witness", 43)
        val witness = database.contactDao().get(ACCOUNT.value, "witness")
        var nativeCommitted = false
        val observed = AndroidStableRawContactObservationPage(listOf(AndroidStableRawContactObservation(
            AndroidOwnedRawContact(42, "target", "remote-target", dirty = true, deleted = false, version = 7),
            emptyList())), null)
        val projection = coordinator { _, entry ->
            assertEquals("target", entry.canonicalContactId)
            assertTrue(nativeCommitted)
            val canonical = requireNotNull(database.contactDao().get(ACCOUNT.value, "target")).toDomain()
            assertEquals("Native note ß", canonical.valuesOf(ContactValueKind.NOTE).single().value)
            assertNotNull(canonical.pendingMutationRevision)
            assertEquals(canonical.revision, database.outboxDao().get(ACCOUNT.value, "CONTACT", "target")?.revision)
            writeReceipt("target")
            AndroidBoundedPageResult.Applied
        }
        val stage = BoundedAndroidInteroperabilityStage(
            contactsReader = { _, _ -> error("No directory traversal") },
            groupsReader = { account, after -> AndroidOwnedGroupRowPage(account, after, emptyList(), null) },
            observationCoordinator = object : AndroidBoundedObservationCoordinator {
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                    pages: List<AndroidOwnedGroupRowPage>) = AndroidBoundedPageResult.Applied
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                    page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult {
                    assertEquals(listOf(42L), page.observations.map { it.rawContact.rawContactId })
                    val original = requireNotNull(database.contactDao().get(ACCOUNT.value, "target")).toDomain()
                    val saved = RoomContactRepository(database).saveContact(original.copy(values = original.values.map {
                        if (it.kind == ContactValueKind.NOTE) it.copy(value = "Native note ß") else it
                    }))
                    assertTrue(saved is SaveResult.Saved)
                    nativeCommitted = true
                    return AndroidBoundedPageResult.Applied
                }
            },
            projectionCoordinator = projection,
            exactContactsReader = { _, id ->
                assertEquals(42L, id)
                AndroidStableRawContactPageResult.Stable(observed)
            },
        )
        val preflight = AndroidInteroperabilityPreflight { _, _ ->
            val account = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
            AndroidInteroperabilityPreflightResult.Ready(context.copy(accountRevision = account.revision))
        }
        val result = ProductionTargetedAndroidRecovery(database, preflight, stage, projection)
            .recover(ACCOUNT, "target", 1, 0) { false }
        assertTrue(result is TargetedRecoveryWorkResult.Resolved)
        val current = requireNotNull(database.contactDao().get(ACCOUNT.value, "target")).toDomain()
        assertEquals(current.revision, (result as TargetedRecoveryWorkResult.Resolved).canonicalRevision)
        assertEquals("Native note ß", current.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals("X-SYNTHETIC-OPAQUE:retained", current.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY).single().value)
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
        assertEquals(witness, database.contactDao().get(ACCOUNT.value, "witness"))
    }

    @Test fun permissionOrProviderRepairPrerequisitesCannotStartIngestionOrGlobalRepair() = runBlocking {
        seed("target", 42)
        val stage = BoundedAndroidInteroperabilityStage(
            contactsReader = { _, _ -> error("No provider work without preflight") },
            groupsReader = { _, _ -> error("No provider work without preflight") },
            observationCoordinator = object : AndroidBoundedObservationCoordinator {
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                    pages: List<AndroidOwnedGroupRowPage>): AndroidBoundedPageResult = error("No ingestion")
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                    page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult = error("No ingestion")
            }, projectionCoordinator = { _, _ -> error("No projection") },
        )
        for ((preflight, expected) in listOf(
            AndroidInteroperabilityPreflightResult.PermissionDenied to TargetedRecoveryPrerequisite.ANDROID_PERMISSION,
            AndroidInteroperabilityPreflightResult.RepairRequired(AndroidInteroperabilityRepairReason.PROVIDER_STATE_DIVERGED)
                to TargetedRecoveryPrerequisite.ANDROID_CONTEXT,
        )) {
            val recovery = ProductionTargetedAndroidRecovery(database, { _, _ -> preflight }, stage,
                coordinator { _, _ -> error("No projection") })
            val result = recovery.recover(ACCOUNT, "target", 1, 0) { false }
            assertEquals(expected, (result as TargetedRecoveryWorkResult.Failed).prerequisite)
        }
        assertEquals(0L, database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)?.providerEpoch)
    }

    @Test fun aNativeCreationWithoutRemoteIdentityCannotBeAdoptedByCopyRecovery() = runBlocking {
        seed("target", 42)
        val canonical = requireNotNull(database.contactDao().getEntity(ACCOUNT.value, "target"))
        database.contactDao().upsert(canonical.copy(remoteContactId = null))
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, "target"))
        database.androidProjectionLedgerDao().update(ledger.copy(sourceIdentity = null, adoptionState = "AWAITING_REMOTE_ID"))
        val stage = BoundedAndroidInteroperabilityStage(
            contactsReader = { _, _ -> error("An unbound native creation cannot start ingestion") },
            groupsReader = { _, _ -> error("An unbound native creation cannot start ingestion") },
            observationCoordinator = object : AndroidBoundedObservationCoordinator {
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                    pages: List<AndroidOwnedGroupRowPage>): AndroidBoundedPageResult = error("No ingestion")
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                    page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult = error("No ingestion")
            }, projectionCoordinator = { _, _ -> error("No projection") },
        )
        val recovery = ProductionTargetedAndroidRecovery(database,
            { _, _ -> AndroidInteroperabilityPreflightResult.Ready(context) }, stage,
            coordinator { _, _ -> error("No projection") })
        val result = recovery.recover(ACCOUNT, "target", 1, 0) { false }
        assertEquals(TargetedRecoveryPrerequisite.REMOTE_IDENTITY, (result as TargetedRecoveryWorkResult.Failed).prerequisite)
        assertNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, "target")?.sourceIdentity)
    }

    private fun coordinator(executor: AndroidProjectionItemExecutor) = RoomBoundedAndroidProjectionCoordinator(database,
        executor, groupProjectionCoordinator = { error("A targeted copy cannot project unrelated groups") },
        presenceVerifier = { _, _ -> error("Targeted repair must not bypass item verification for CLEAN rows") })

    private suspend fun seed(id: String, rawId: Long) {
        val canonical = CanonicalContact(ACCOUNT.value, id, displayName = "Synthetic $id",
            revision = 1, remoteContactId = "remote-$id", values = listOf(
            ContactValue("note-$id", ContactValueKind.NOTE, "Original note", order = 0),
            ContactValue("opaque-$id", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-SYNTHETIC-OPAQUE:retained", order = 0),
        ))
        database.contactDao().upsert(canonical.toEntity())
        database.contactDao().upsertValues(canonical.values.map { it.toEntity(canonical) })
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(ACCOUNT.value, id, 0, 0,
            rawId, "remote-$id", null, null, null, null, "REPAIR_REQUIRED", "NONE", "NONE", "ADOPTED"))
    }

    private suspend fun issue(id: String) = database.androidContactIssueDao().upsert(AndroidContactIssueEntity(
        ACCOUNT.value, id, "PROJECTION", 0, 1, "REPAIR_REQUIRED"))

    /** Simulated item-executor receipts, never evidence about a real provider write. */
    private suspend fun writeReceipt(id: String) {
        val canonical = requireNotNull(database.contactDao().get(ACCOUNT.value, id)).toDomain()
        val mapper = CanonicalAndroidContactMapper()
        val fingerprint = mapper.fingerprint(mapper.project(canonical)).sha256Hex
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, id))
        database.androidProjectionLedgerDao().update(ledger.copy(projectionState = "CLEAN",
            canonicalProjectionFingerprint = fingerprint, androidBaselineFingerprint = fingerprint,
            pendingProjectionFingerprint = null, adoptionState = "ADOPTED"))
        val snapshot = AndroidGroupMembershipSnapshot.create(ACCOUNT.value, id, null,
            AndroidGroupMembershipAvailability.NO_EMAIL, emptyList())
        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(snapshot)
        val groupFingerprint = snapshot.semanticFingerprint().sha256Hex
        database.androidGroupProjectionDao().insertMembership(AndroidGroupMembershipProjectionLedgerEntity(
            ACCOUNT.value, id, 0, 0, ledger.rawContactLocator, null, groupFingerprint, groupFingerprint, null,
            "CLEAN", "NONE"))
        database.androidGroupProjectionDao().upsertMembershipBaseline(AndroidGroupMembershipBaselineEntity(
            ACCOUNT.value, id, AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex, encoded))
    }

    private companion object {
        const val NAME = "targeted-android-projection.db"
        val ACCOUNT = AccountScope("targeted-android-synthetic-owner")
    }
}
