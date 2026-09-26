package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidTombstoneState
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotBinaryCodec
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.ContactGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAndroidGroupRowObservationCommitterDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
    }

    @After fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DB)
    }

    @Test fun androidEditCommitsCanonicalOutboxLedgerBaselineAndReplayAtomically() = runBlocking {
        seed("Before")
        val command = command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "After", false))

        val first = committer().commit(command) as RoomAndroidGroupRowObservationResult.Applied
        assertEquals(RoomAndroidGroupRowObservationClassification.ANDROID_EDIT_COMMITTED, first.classification)
        val canonical = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).toDomain()
        assertEquals("After", canonical.name)
        assertEquals(false, canonical.isVisible)
        assertEquals(2, canonical.revision)
        assertEquals(MutationOperation.UPSERT.name, requireNotNull(database.outboxDao().get(
            ACCOUNT.value, AggregateType.GROUP.name, GROUP,
        )).operation)
        val ledger = requireNotNull(database.androidGroupProjectionDao().getGroup(ACCOUNT.value, GROUP))
        assertEquals(VERSION, ledger.providerVersion)
        assertNotNull(database.androidGroupProjectionDao().getGroupBaseline(ACCOUNT.value, GROUP))
        assertEquals(RoomAndroidGroupRowObservationResult.AlreadyCommitted, committer().commit(command))
    }

    @Test fun staleAccountAndProviderEpochWriteNothing() = runBlocking {
        seed("Before")
        val before = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).toDomain()

        assertEquals(RoomAndroidGroupRowObservationResult.StaleAccount, committer().commit(
            command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "After", true)).copy(expectedAccountRevision = 3),
        ))
        assertEquals(RoomAndroidGroupRowObservationResult.StaleProviderEpoch, committer().commit(
            command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "After", true)).copy(providerEpoch = 4),
        ))
        assertEquals(before, requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).toDomain())
        assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP))
    }

    @Test fun androidDeleteCommitsTombstoneAndRemovesBaseline() = runBlocking {
        seed("Before")
        val result = committer().commit(command(null, deleted = true)) as RoomAndroidGroupRowObservationResult.Applied

        assertEquals(RoomAndroidGroupRowObservationClassification.ANDROID_DELETE_COMMITTED, result.classification)
        val canonical = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).toDomain()
        assertEquals(true, canonical.isDeleted)
        assertEquals(MutationOperation.DELETE.name, requireNotNull(database.outboxDao().get(
            ACCOUNT.value, AggregateType.GROUP.name, GROUP,
        )).operation)
        assertNull(database.androidGroupProjectionDao().getGroupBaseline(ACCOUNT.value, GROUP))
    }

    @Test fun androidCreateUsesStableIdentityAndDoesNotMergeDuplicateTitle() = runBlocking {
        val ledger = com.patmanak.contako.data.android.RoomAndroidProjectionLedger(database)
        val account = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, account.revision, ANDROID_ACCOUNT)
        database.contactGroupDao().upsert(ContactGroup(
            accountId = ACCOUNT.value, id = "existing", name = "Duplicate", revision = 1,
        ).toEntity())
        val snapshot = AndroidGroupSnapshot(ACCOUNT.value, GROUP, "Duplicate", true)
        val create = command(snapshot).copy(
            expectedCanonicalGroupRevision = null,
            expectedGroupLedgerRevision = null,
            sourceIdentity = null,
        )

        val result = committer().commit(create) as RoomAndroidGroupRowObservationResult.Applied

        assertEquals(RoomAndroidGroupRowObservationClassification.ANDROID_CREATED, result.classification)
        assertNotNull(database.contactGroupDao().get(ACCOUNT.value, "existing"))
        assertEquals("Duplicate", requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).group.name)
        assertEquals(AndroidAdoptionState.AWAITING_REMOTE_ID.name,
            requireNotNull(database.androidGroupProjectionDao().getGroup(ACCOUNT.value, GROUP)).adoptionState)
    }

    @Test fun replayFailsClosedAfterCanonicalStateChanges() = runBlocking {
        seed("Before")
        val command = command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "After", false))
        committer().commit(command)
        repository.saveGroup(requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).toDomain().copy(
            name = "Later",
        ))

        assertEquals(RoomAndroidGroupRowObservationResult.StaleAccount, committer().commit(command))
    }

    @Test fun crossAccountSnapshotIsRejectedWithoutWrites() = runBlocking {
        seed("Before")
        val result = committer().commit(command(
            AndroidGroupSnapshot("foreign", GROUP, "After", true),
        ))

        assertEquals(RoomAndroidGroupRowObservationResult.StaleCanonicalGroup, result)
        assertEquals("Before", requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).group.name)
        assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP))
    }

    @Test fun sameProviderVersionCannotCarryDifferentObservation() = runBlocking {
        seed("Before")
        val first = command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "First", true))
        committer().commit(first)
        val account = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        val canonical = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).group
        val ledger = requireNotNull(database.androidGroupProjectionDao().getGroup(ACCOUNT.value, GROUP))
        val second = command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "Second", true)).copy(
            expectedAccountRevision = account.revision,
            expectedCanonicalGroupRevision = canonical.revision,
            expectedGroupLedgerRevision = ledger.revision,
        )

        assertEquals(RoomAndroidGroupRowObservationResult.StaleProviderRow, committer().commit(second))
        assertEquals("First", requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).group.name)
    }

    @Test fun repairObserverClassifiesEveryClosedInvariantAndCannotChangeOutcome() = runBlocking {
        suspend fun assertReason(
            expected: RoomAndroidGroupCommitRepairReason,
            arrange: suspend (AndroidGroupProjectionDao, AndroidGroupProjectionLedgerEntity) -> Unit,
        ) {
            resetAndSeed()
            val dao = database.androidGroupProjectionDao()
            arrange(dao, requireNotNull(dao.getGroup(ACCOUNT.value, GROUP)))
            val observed = mutableListOf<RoomAndroidGroupCommitRepairReason>()
            val result = RoomAndroidGroupRowObservationCommitter(
                database,
                repository,
                repairObserver = RoomAndroidGroupCommitRepairObserver(observed::add),
            ).commit(command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "After", false)))

            assertEquals(RoomAndroidGroupRowObservationResult.RepairRequired, result)
            assertEquals(listOf(expected), observed)
        }

        assertReason(RoomAndroidGroupCommitRepairReason.UNFINISHED_PROVIDER_WRITE_JOURNAL) { dao, _ ->
            dao.upsertGroupProviderWriteJournal(unfinishedProviderWriteJournal())
        }
        assertReason(RoomAndroidGroupCommitRepairReason.LEDGER_PROJECTION_REPAIR_REQUIRED) { dao, ledger ->
            assertEquals(1, dao.updateGroup(ledger.copy(
                projectionState = AndroidProjectionWriteState.REPAIR_REQUIRED.name,
            )))
        }
        assertReason(RoomAndroidGroupCommitRepairReason.BASELINE_PRESENT_WITHOUT_LEDGER_FINGERPRINT) { dao, _ ->
            dao.upsertGroupBaseline(baseline(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "Before", true)))
        }
        assertReason(RoomAndroidGroupCommitRepairReason.BASELINE_MISSING) { dao, ledger ->
            assertEquals(1, dao.updateGroup(ledger.copy(androidBaselineFingerprint = "a".repeat(64))))
        }
        assertReason(RoomAndroidGroupCommitRepairReason.BASELINE_INTEGRITY_FAILURE) { dao, ledger ->
            val snapshot = AndroidGroupSnapshot(ACCOUNT.value, GROUP, "Before", true)
            assertEquals(1, dao.updateGroup(ledger.copy(
                androidBaselineFingerprint = snapshot.semanticFingerprint().sha256Hex,
            )))
            val valid = baseline(snapshot)
            dao.upsertGroupBaseline(valid.copy(fingerprint = "b".repeat(64)))
        }
        assertReason(RoomAndroidGroupCommitRepairReason.BASELINE_IDENTITY_FAILURE) { dao, ledger ->
            assertEquals(1, dao.updateGroup(ledger.copy(androidBaselineFingerprint = "c".repeat(64))))
            dao.upsertGroupBaseline(baseline(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "Before", true)))
        }

        resetAndSeed()
        val dao = database.androidGroupProjectionDao()
        val ledger = requireNotNull(dao.getGroup(ACCOUNT.value, GROUP))
        assertEquals(1, dao.updateGroup(ledger.copy(androidBaselineFingerprint = "a".repeat(64))))
        val inert = RoomAndroidGroupRowObservationCommitter(
            database,
            repository,
            repairObserver = RoomAndroidGroupCommitRepairObserver { error("PRIVATE_DATABASE_TEXT") },
        ).commit(command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "After", false)))
        assertEquals(RoomAndroidGroupRowObservationResult.RepairRequired, inert)
    }

    @Test fun completedProviderWriteSurvivesLaterMonotonicRevisions() = runBlocking {
        seed("Before")
        val dao = database.androidGroupProjectionDao()
        val snapshot = AndroidGroupSnapshot(ACCOUNT.value, GROUP, "Before", true)
        val semantic = snapshot.semanticFingerprint().sha256Hex
        val ledger = requireNotNull(dao.getGroup(ACCOUNT.value, GROUP))
        assertEquals(1, dao.updateGroup(ledger.copy(
            revision = 1,
            providerVersion = VERSION,
            canonicalProjectionFingerprint = semantic,
            androidBaselineFingerprint = semantic,
            projectionState = AndroidProjectionWriteState.CLEAN.name,
            ingestionState = AndroidIngestionState.BASELINED.name,
        )))
        dao.upsertGroupBaseline(baseline(snapshot))
        dao.upsertGroupProviderWriteJournal(completedProviderWriteJournal(snapshot))
        val accountDao = database.androidProjectionLedgerDao()
        assertEquals(1, accountDao.compareAndSetAccountRevisionAtProviderEpoch(ACCOUNT.value, 1, 0))
        assertEquals(1, accountDao.compareAndSetAccountRevisionAtProviderEpoch(ACCOUNT.value, 2, 0))

        val first = committer().commit(command(snapshot).copy(
            expectedAccountRevision = 3,
            expectedGroupLedgerRevision = 1,
        )) as RoomAndroidGroupRowObservationResult.Applied
        assertEquals(RoomAndroidGroupRowObservationClassification.NO_CHANGE, first.classification)

        val second = committer().commit(command(snapshot).copy(
            expectedAccountRevision = first.committedAccountRevision,
            expectedGroupLedgerRevision = first.committedGroupLedgerRevision,
        )) as RoomAndroidGroupRowObservationResult.Applied
        assertEquals(RoomAndroidGroupRowObservationClassification.NO_CHANGE, second.classification)
    }

    @Test fun everyTransactionCheckpointRollsBackAllDurableEffects() = runBlocking {
        RoomAndroidGroupRowObservationCheckpoint.entries.forEach { failurePoint ->
            if (database.isOpen) database.close()
            context.deleteDatabase(DB)
            database = ContakoDatabase.create(context, DB)
            repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
            seed("Before")
            val beforeAccount = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
            val beforeCanonical = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).toDomain()
            val beforeLedger = requireNotNull(database.androidGroupProjectionDao().getGroup(ACCOUNT.value, GROUP))
            val failing = RoomAndroidGroupRowObservationCommitter(
                database,
                repository,
                RoomAndroidGroupRowObservationCheckpointHook { checkpoint ->
                    if (checkpoint == failurePoint) error("INJECTED")
                },
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    failing.commit(command(AndroidGroupSnapshot(ACCOUNT.value, GROUP, "After", false)))
                }
            }
            assertEquals(beforeAccount, database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
            assertEquals(beforeCanonical, requireNotNull(database.contactGroupDao().get(ACCOUNT.value, GROUP)).toDomain())
            assertEquals(beforeLedger, database.androidGroupProjectionDao().getGroup(ACCOUNT.value, GROUP))
            assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.GROUP.name, GROUP))
            assertNull(database.androidGroupProjectionDao().getGroupBaseline(ACCOUNT.value, GROUP))
            assertNull(database.androidGroupProjectionDao().getGroupObservationCommitReceipt(ACCOUNT.value, GROUP))
        }
    }

    private suspend fun seed(name: String) {
        val ledger = com.patmanak.contako.data.android.RoomAndroidProjectionLedger(database)
        val account = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, account.revision, ANDROID_ACCOUNT)
        database.contactGroupDao().upsert(ContactGroup(
            accountId = ACCOUNT.value, id = GROUP, name = name, revision = 1,
            remoteLabelId = SOURCE,
        ).toEntity())
        database.androidGroupProjectionDao().insertGroup(AndroidGroupProjectionLedgerEntity(
            accountId = ACCOUNT.value, canonicalGroupId = GROUP, revision = 0, providerEpoch = 0,
            groupRowLocator = ROW, providerVersion = null, sourceIdentity = SOURCE,
            canonicalProjectionFingerprint = null, androidBaselineFingerprint = null,
            pendingProjectionFingerprint = null, projectionState = AndroidProjectionWriteState.DETACHED.name,
            ingestionState = AndroidIngestionState.NONE.name, tombstoneState = AndroidTombstoneState.NONE.name,
            adoptionState = AndroidAdoptionState.ADOPTED.name,
        ))
    }

    private suspend fun resetAndSeed() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
        seed("Before")
    }

    private fun baseline(snapshot: AndroidGroupSnapshot): AndroidGroupProjectionBaselineEntity {
        val encoded = AndroidGroupSnapshotBinaryCodec.encode(snapshot)
        return AndroidGroupProjectionBaselineEntity(
            ACCOUNT.value,
            GROUP,
            AndroidGroupSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex,
            encoded,
        )
    }

    private fun unfinishedProviderWriteJournal() = AndroidGroupProviderWriteJournalEntity(
        accountId = ACCOUNT.value,
        canonicalGroupId = GROUP,
        androidAccountName = ANDROID_ACCOUNT,
        providerEpoch = 0,
        operation = "UPDATE",
        state = "PREPARED",
        expectedAccountRevision = 1,
        expectedCanonicalGroupRevision = 1,
        expectedGroupLedgerRevision = 0,
        expectedGroupRowLocator = null,
        expectedProviderVersion = null,
        expectedSourceIdentity = SOURCE,
        sourceIdentityAfterWrite = SOURCE,
        expectedDeleted = false,
        desiredSemanticFingerprint = null,
        desiredSnapshotIntegrityFingerprint = null,
        desiredSnapshot = null,
        commandFingerprint = "d".repeat(64),
        resultGroupRowLocator = null,
        resultProviderVersion = null,
        resultSourceIdentity = null,
        resultDeleted = null,
        resultProviderStateFingerprint = null,
        completedAccountRevision = null,
        completedGroupLedgerRevision = null,
    )

    private fun completedProviderWriteJournal(snapshot: AndroidGroupSnapshot): AndroidGroupProviderWriteJournalEntity {
        val encoded = AndroidGroupSnapshotBinaryCodec.encode(snapshot)
        return AndroidGroupProviderWriteJournalEntity(
            accountId = ACCOUNT.value,
            canonicalGroupId = GROUP,
            androidAccountName = ANDROID_ACCOUNT,
            providerEpoch = 0,
            operation = "UPDATE",
            state = "COMMITTED",
            expectedAccountRevision = 1,
            expectedCanonicalGroupRevision = 1,
            expectedGroupLedgerRevision = 0,
            expectedGroupRowLocator = ROW,
            expectedProviderVersion = VERSION - 1,
            expectedSourceIdentity = SOURCE,
            sourceIdentityAfterWrite = SOURCE,
            expectedDeleted = false,
            desiredSemanticFingerprint = snapshot.semanticFingerprint().sha256Hex,
            desiredSnapshotIntegrityFingerprint =
                AndroidGroupSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex,
            desiredSnapshot = encoded,
            commandFingerprint = "e".repeat(64),
            resultGroupRowLocator = ROW,
            resultProviderVersion = VERSION,
            resultSourceIdentity = SOURCE,
            resultDeleted = false,
            resultProviderStateFingerprint = "f".repeat(64),
            completedAccountRevision = 2,
            completedGroupLedgerRevision = 1,
        )
    }

    private fun command(snapshot: AndroidGroupSnapshot?, deleted: Boolean = false) =
        RoomAndroidGroupRowObservationCommand(
            ACCOUNT, ANDROID_ACCOUNT, 1, 0, GROUP, 1, 0, ROW, VERSION, SOURCE, deleted, snapshot,
        )

    private fun committer() = RoomAndroidGroupRowObservationCommitter(database, repository)

    private companion object {
        const val DB = "group-row-observation.db"
        val ACCOUNT = AccountScope("account")
        const val ANDROID_ACCOUNT = "android-account"
        const val GROUP = "group"
        const val SOURCE = "source"
        const val ROW = 41L
        const val VERSION = 7L
    }
}
