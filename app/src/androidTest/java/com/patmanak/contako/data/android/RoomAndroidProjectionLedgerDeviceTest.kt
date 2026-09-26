package com.patmanak.contako.data.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.local.AggregateType
import com.patmanak.contako.data.local.AndroidGroupMembershipBaselineEntity
import com.patmanak.contako.data.local.AndroidGroupMembershipProjectionLedgerEntity
import com.patmanak.contako.data.local.AndroidGroupProjectionBaselineEntity
import com.patmanak.contako.data.local.AndroidGroupProjectionLedgerEntity
import com.patmanak.contako.data.local.ContactEntity
import com.patmanak.contako.data.local.ContactGroupEntity
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.MutationOperation
import com.patmanak.contako.data.local.OutboxMutationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAndroidProjectionLedgerDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var ledger: RoomAndroidProjectionLedger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun pendingProjectionSurvivesRestartAndMatchingObservationReconcilesLostAcknowledgement() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        ledger.ensureAccount(ACCOUNT)
        val attached = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, sourceIdentity = "source-identity")
        val prepared = ledger.prepareProjection(ACCOUNT, CONTACT_ID, attached.revision, FP_A)
            .updated()
        assertEquals(AndroidProjectionWriteState.WRITE_PENDING, prepared.projectionState)

        reopenDatabase()
        val durablePending = requireNotNull(ledger.load(ACCOUNT, CONTACT_ID))
        assertEquals(FP_A, durablePending.pendingProjectionFingerprint)
        var canonicalCallbacks = 0
        val reconciled = ledger.ingestObservation(
            ACCOUNT,
            CONTACT_ID,
            durablePending.revision,
            AndroidRawContactLocator(0, 41),
            observedFingerprint = FP_A,
            deleted = false,
            resultingCanonicalProjectionFingerprint = null,
            observedSnapshot = SNAPSHOT_A,
        ) { canonicalCallbacks += 1 }.applied()

        assertEquals(AndroidObservationClassification.SELF_WRITE_RECONCILED, reconciled.classification)
        assertEquals(0, canonicalCallbacks)
        assertEquals(FP_A, reconciled.snapshot.androidBaselineFingerprint)
        assertNull(reconciled.snapshot.pendingProjectionFingerprint)
        assertEquals(AndroidProjectionWriteState.CLEAN, reconciled.snapshot.projectionState)

        val secondPass = ledger.ingestObservation(
            ACCOUNT,
            CONTACT_ID,
            reconciled.snapshot.revision,
            AndroidRawContactLocator(0, 41),
            observedFingerprint = FP_A,
            deleted = false,
            resultingCanonicalProjectionFingerprint = null,
            observedSnapshot = SNAPSHOT_A,
        ) { canonicalCallbacks += 1 }.applied()
        assertEquals(AndroidObservationClassification.NO_CHANGE, secondPass.classification)
        assertEquals(0, canonicalCallbacks)
    }

    @Test
    fun canonicalAndOutboxCallbackSharesTheLedgerTransactionAndRollsBackOnFailure() = runBlocking {
        val clean = prepareCleanBaseline()
        val before = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact

        assertTrue(
            runCatching {
                ledger.ingestObservation(
                    ACCOUNT,
                    CONTACT_ID,
                    clean.revision,
                    AndroidRawContactLocator(0, 7),
                    observedFingerprint = FP_B,
                    deleted = false,
                    resultingCanonicalProjectionFingerprint = FP_B,
                    observedSnapshot = SNAPSHOT_B,
                ) {
                    database.contactDao().upsert(before.copy(displayName = "must-roll-back", revision = 1))
                    database.outboxDao().upsert(outbox(revision = 1))
                    throw SimulatedProcessDeath()
                }
            }.exceptionOrNull() is SimulatedProcessDeath,
        )
        assertEquals(before, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact)
        assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
        assertEquals(clean, ledger.load(ACCOUNT, CONTACT_ID))

        val committed = ledger.ingestObservation(
            ACCOUNT,
            CONTACT_ID,
            clean.revision,
            AndroidRawContactLocator(0, 7),
            observedFingerprint = FP_B,
            deleted = false,
            resultingCanonicalProjectionFingerprint = FP_B,
            observedSnapshot = SNAPSHOT_B,
        ) {
            database.contactDao().upsert(before.copy(displayName = "committed", revision = 1))
            database.outboxDao().upsert(outbox(revision = 1))
        }.applied()
        assertEquals(AndroidObservationClassification.CANONICAL_DELTA_COMMITTED, committed.classification)
        assertEquals(FP_B, committed.snapshot.androidBaselineFingerprint)
        assertEquals(
            "committed",
            requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.displayName,
        )
        assertEquals(1L, requireNotNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", CONTACT_ID)).revision)
    }

    @Test
    fun tombstoneAndAdoptionTransitionsAreDurableRevisionGuardedAndAccountScoped() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        seedContact(FOREIGN_ACCOUNT, CONTACT_ID)
        ledger.ensureAccount(ACCOUNT)
        ledger.ensureAccount(FOREIGN_ACCOUNT)
        val waiting = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID)
        ledger.attachCanonicalContact(FOREIGN_ACCOUNT, CONTACT_ID)
        val sourcePending = ledger.attachSourceIdentity(
            ACCOUNT,
            CONTACT_ID,
            waiting.revision,
            "stable-source-identity",
        ).updated()
        val adopted = ledger.acknowledgeAdoption(
            ACCOUNT,
            CONTACT_ID,
            sourcePending.revision,
            AndroidRawContactLocator(0, 91),
        ).updated()
        assertEquals(AndroidAdoptionState.ADOPTED, adopted.adoptionState)
        assertEquals(adopted, ledger.loadBySourceIdentity(ACCOUNT, "stable-source-identity"))
        assertEquals(adopted, ledger.loadByRawContactLocator(ACCOUNT, AndroidRawContactLocator(0, 91)))
        assertNull(ledger.loadByRawContactLocator(FOREIGN_ACCOUNT, AndroidRawContactLocator(0, 91)))
        assertTrue(
            ledger.acknowledgeAdoption(
                ACCOUNT,
                CONTACT_ID,
                sourcePending.revision,
                AndroidRawContactLocator(0, 92),
            ) is AndroidLedgerCasResult.Stale,
        )

        val beforeDelete = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact
        val deleted = ledger.ingestObservation(
            ACCOUNT,
            CONTACT_ID,
            adopted.revision,
            AndroidRawContactLocator(0, 91),
            observedFingerprint = null,
            deleted = true,
            resultingCanonicalProjectionFingerprint = null,
            observedSnapshot = null,
        ) {
            database.contactDao().upsert(beforeDelete.copy(isDeleted = true, revision = 1))
            database.outboxDao().upsert(outbox(revision = 1, operation = MutationOperation.DELETE))
        }.applied().snapshot
        assertEquals(AndroidTombstoneState.CANONICAL_COMMITTED, deleted.tombstoneState)

        reopenDatabase()
        val durableDelete = requireNotNull(ledger.load(ACCOUNT, CONTACT_ID))
        assertEquals(AndroidTombstoneState.CANONICAL_COMMITTED, durableDelete.tombstoneState)
        val converged = ledger.markTombstoneRemoteConverged(
            ACCOUNT,
            CONTACT_ID,
            durableDelete.revision,
        ).updated()
        assertEquals(AndroidTombstoneState.REMOTE_CONVERGED, converged.tombstoneState)
        assertEquals(AndroidTombstoneState.NONE, requireNotNull(ledger.load(FOREIGN_ACCOUNT, CONTACT_ID)).tombstoneState)
    }

    @Test
    fun providerEpochCasInvalidatesOnlyLocatorsAndBaselinesForTheSelectedAccount() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        seedContact(FOREIGN_ACCOUNT, CONTACT_ID)
        val primaryAccount = ledger.ensureAccount(ACCOUNT)
        ledger.ensureAccount(FOREIGN_ACCOUNT)
        val primary = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "primary-source")
        val foreign = ledger.attachCanonicalContact(FOREIGN_ACCOUNT, CONTACT_ID, "foreign-source")
        val primaryAdopted = ledger.acknowledgeAdoption(
            ACCOUNT,
            CONTACT_ID,
            primary.revision,
            AndroidRawContactLocator(0, 31),
        ).updated()
        ledger.acknowledgeAdoption(
            FOREIGN_ACCOUNT,
            CONTACT_ID,
            foreign.revision,
            AndroidRawContactLocator(0, 31),
        ).updated()

        val advanced = ledger.advanceProviderEpoch(ACCOUNT, primaryAccount.revision)
            as AndroidProviderEpochResult.Advanced
        assertEquals(1L, advanced.account.providerEpoch)
        assertEquals(1, advanced.repairedEntries)
        assertTrue(ledger.advanceProviderEpoch(ACCOUNT, primaryAccount.revision) is AndroidProviderEpochResult.Stale)

        val repaired = requireNotNull(ledger.load(ACCOUNT, CONTACT_ID))
        assertEquals(primaryAdopted.canonicalContactId, repaired.canonicalContactId)
        assertNull(repaired.rawContactLocator)
        assertEquals(AndroidProjectionWriteState.REPAIR_REQUIRED, repaired.projectionState)
        assertEquals(AndroidAdoptionState.SOURCE_ID_PENDING, repaired.adoptionState)
        val untouched = requireNotNull(ledger.load(FOREIGN_ACCOUNT, CONTACT_ID))
        assertEquals(0L, untouched.providerEpoch)
        assertEquals(31L, requireNotNull(untouched.rawContactLocator).localRowHandle)
    }

    @Test
    fun providerEpochAtomicallyInvalidatesGroupAndMembershipLocatorsAndBaselines() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        seedGroup(ACCOUNT, GROUP_ID)
        val accountState = ledger.ensureAccount(ACCOUNT)
        ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "contact-source")
        val groupDao = database.androidGroupProjectionDao()
        assertTrue(
            groupDao.insertGroup(
                AndroidGroupProjectionLedgerEntity(
                    accountId = ACCOUNT.value,
                    canonicalGroupId = GROUP_ID,
                    revision = 0,
                    providerEpoch = 0,
                    groupRowLocator = 301,
                    sourceIdentity = "group-source",
                    canonicalProjectionFingerprint = FP_A.sha256Hex,
                    androidBaselineFingerprint = FP_A.sha256Hex,
                    pendingProjectionFingerprint = null,
                    projectionState = AndroidProjectionWriteState.CLEAN.name,
                    ingestionState = AndroidIngestionState.BASELINED.name,
                    tombstoneState = AndroidTombstoneState.NONE.name,
                    adoptionState = AndroidAdoptionState.ADOPTED.name,
                ),
            ) != -1L,
        )
        assertTrue(
            groupDao.insertMembership(
                AndroidGroupMembershipProjectionLedgerEntity(
                    accountId = ACCOUNT.value,
                    canonicalContactId = CONTACT_ID,
                    revision = 0,
                    providerEpoch = 0,
                    rawContactLocator = 201,
                    preferredEmailValueId = "preferred-email",
                    canonicalProjectionFingerprint = FP_A.sha256Hex,
                    androidBaselineFingerprint = FP_A.sha256Hex,
                    pendingProjectionFingerprint = null,
                    projectionState = AndroidProjectionWriteState.CLEAN.name,
                    ingestionState = AndroidIngestionState.BASELINED.name,
                ),
            ) != -1L,
        )
        groupDao.upsertGroupBaseline(
            AndroidGroupProjectionBaselineEntity(ACCOUNT.value, GROUP_ID, FP_A.sha256Hex, byteArrayOf(1)),
        )
        groupDao.upsertMembershipBaseline(
            AndroidGroupMembershipBaselineEntity(ACCOUNT.value, CONTACT_ID, FP_A.sha256Hex, byteArrayOf(2)),
        )

        val advanced = ledger.advanceProviderEpoch(ACCOUNT, accountState.revision)
            as AndroidProviderEpochResult.Advanced

        assertEquals(3, advanced.repairedEntries)
        val repairedGroup = requireNotNull(groupDao.getGroup(ACCOUNT.value, GROUP_ID))
        assertEquals(1L, repairedGroup.providerEpoch)
        assertNull(repairedGroup.groupRowLocator)
        assertNull(repairedGroup.androidBaselineFingerprint)
        assertEquals(AndroidProjectionWriteState.REPAIR_REQUIRED.name, repairedGroup.projectionState)
        assertEquals(AndroidAdoptionState.SOURCE_ID_PENDING.name, repairedGroup.adoptionState)
        assertNull(groupDao.getGroupBaseline(ACCOUNT.value, GROUP_ID))
        val repairedMembership = requireNotNull(groupDao.getMembership(ACCOUNT.value, CONTACT_ID))
        assertEquals(1L, repairedMembership.providerEpoch)
        assertNull(repairedMembership.rawContactLocator)
        assertNull(repairedMembership.preferredEmailValueId)
        assertNull(repairedMembership.androidBaselineFingerprint)
        assertEquals(AndroidProjectionWriteState.REPAIR_REQUIRED.name, repairedMembership.projectionState)
        assertNull(groupDao.getMembershipBaseline(ACCOUNT.value, CONTACT_ID))
    }

    @Test
    fun repeatedAttachIsIdempotentButRejectsAConflictingSourceIdentity() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        ledger.ensureAccount(ACCOUNT)

        val first = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "stable-source")
        val repeated = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "stable-source")

        assertEquals(first, repeated)
        assertTrue(
            runCatching {
                ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "different-source")
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(first, ledger.load(ACCOUNT, CONTACT_ID))
    }

    @Test
    fun firstObservedBaselineBindsLocatorWithoutCreatingACanonicalMutation() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        ledger.ensureAccount(ACCOUNT)
        val attached = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "source")

        val baseline = ledger.establishObservedBaseline(
            ACCOUNT,
            CONTACT_ID,
            attached.revision,
            AndroidRawContactLocator(0, 55),
            FP_A,
            FP_A,
            SNAPSHOT_A,
        ).updated()

        assertEquals(AndroidProjectionWriteState.CLEAN, baseline.projectionState)
        assertEquals(AndroidIngestionState.BASELINED, baseline.ingestionState)
        assertEquals(AndroidAdoptionState.ADOPTED, baseline.adoptionState)
        assertEquals(FP_A, baseline.canonicalProjectionFingerprint)
        assertEquals(FP_A, baseline.androidBaselineFingerprint)
        assertEquals(55L, requireNotNull(baseline.rawContactLocator).localRowHandle)
        assertTrue(
            ledger.establishObservedBaseline(
                ACCOUNT,
                CONTACT_ID,
                attached.revision,
                AndroidRawContactLocator(0, 55),
                FP_A,
                FP_A,
                SNAPSHOT_A,
            ) is AndroidLedgerCasResult.Stale,
        )
    }

    @Test
    fun fullProviderNeutralBaselineSurvivesRestartAndProviderEpochClearsIt() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        val accountState = ledger.ensureAccount(ACCOUNT)
        val attached = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "source")
        val snapshot = providerSnapshot()
        val fingerprint = CanonicalAndroidContactMapper().fingerprint(snapshot)

        ledger.establishObservedBaseline(
            ACCOUNT,
            CONTACT_ID,
            attached.revision,
            AndroidRawContactLocator(0, 55),
            fingerprint,
            fingerprint,
            snapshot,
        ).updated()
        reopenDatabase()

        assertEquals(snapshot, ledger.loadBaseline(ACCOUNT, CONTACT_ID))
        assertFalse(requireNotNull(ledger.loadBaseline(ACCOUNT, CONTACT_ID)).toString().contains("Private baseline"))
        ledger.advanceProviderEpoch(ACCOUNT, accountState.revision)
        assertNull(ledger.loadBaseline(ACCOUNT, CONTACT_ID))
    }

    @Test
    fun malformedPersistedBaselineFailsClosedWithoutPayloadDiagnostics() = runBlocking {
        seedContact(ACCOUNT, CONTACT_ID)
        ledger.ensureAccount(ACCOUNT)
        val attached = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "source")
        val snapshot = providerSnapshot()
        val fingerprint = CanonicalAndroidContactMapper().fingerprint(snapshot)
        ledger.establishObservedBaseline(
            ACCOUNT,
            CONTACT_ID,
            attached.revision,
            AndroidRawContactLocator(0, 55),
            fingerprint,
            fingerprint,
            snapshot,
        ).updated()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE android_projection_baselines SET encoded_snapshot = X'00'",
        )

        val failure = runCatching { ledger.loadBaseline(ACCOUNT, CONTACT_ID) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure.toString().contains(CONTACT_ID))
        assertFalse(failure.toString().contains("Private baseline"))
    }

    private fun providerSnapshot() = AndroidContactSnapshot(
        CONTACT_ID,
        listOf(
            AndroidContactRow(
                identity = AndroidValueIdentity("baseline-name", providerRowId = 901),
                kind = AndroidRowKind.STRUCTURED_NAME,
                value = "Private baseline",
            ),
        ),
    )

    private suspend fun prepareCleanBaseline(): AndroidProjectionLedgerSnapshot {
        seedContact(ACCOUNT, CONTACT_ID)
        ledger.ensureAccount(ACCOUNT)
        val attached = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, "source")
        val prepared = ledger.prepareProjection(ACCOUNT, CONTACT_ID, attached.revision, FP_A).updated()
        return ledger.ingestObservation(
            ACCOUNT,
            CONTACT_ID,
            prepared.revision,
            AndroidRawContactLocator(0, 7),
            FP_A,
            deleted = false,
            resultingCanonicalProjectionFingerprint = null,
            observedSnapshot = SNAPSHOT_A,
        ) { error("SELF_WRITE_MUST_NOT_INGEST") }.applied().snapshot
    }

    private suspend fun seedContact(account: AccountScope, contactId: String) {
        database.contactDao().upsert(
            ContactEntity(
                accountId = account.value,
                id = contactId,
                ownerKey = "${account.value}:$contactId",
                firstName = "Synthetic",
                lastName = "Contact",
                displayName = "Synthetic Contact",
                sortName = "Synthetic Contact",
                revision = 0,
                updatedAtEpochMillis = 1,
                remoteContactId = null,
                remoteVCardUid = null,
                remoteVersion = null,
                actionRequiredReasonsEncoding = "",
                pendingMutationRevision = null,
                conflictState = null,
                isDeleted = false,
            ),
        )
    }

    private suspend fun seedGroup(account: AccountScope, groupId: String) {
        database.contactGroupDao().upsert(
            ContactGroupEntity(
                accountId = account.value,
                id = groupId,
                ownerKey = "${account.value}:$groupId",
                name = "Synthetic group",
                color = "#6D4AFF",
                displayOrder = 0,
                isVisible = true,
                revision = 0,
                updatedAtEpochMillis = 1,
                remoteLabelId = null,
                remoteVersion = null,
                pendingMutationRevision = null,
                conflictState = null,
                isDeleted = false,
            ),
        )
    }

    private fun outbox(
        revision: Long,
        operation: MutationOperation = MutationOperation.UPSERT,
    ) = OutboxMutationEntity(
        accountId = ACCOUNT.value,
        aggregateType = AggregateType.CONTACT.name,
        aggregateId = CONTACT_ID,
        operation = operation.name,
        revision = revision,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        idempotencyKey = "synthetic-idempotency-key-$revision",
    )

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun openDatabase() {
        database = ContakoDatabase.create(context, DATABASE_NAME)
        ledger = RoomAndroidProjectionLedger(database)
    }

    private fun AndroidLedgerCasResult.updated(): AndroidProjectionLedgerSnapshot =
        (this as AndroidLedgerCasResult.Updated).snapshot

    private fun AndroidObservationResult.applied(): AndroidObservationResult.Applied =
        this as AndroidObservationResult.Applied

    private class SimulatedProcessDeath : RuntimeException()

    private companion object {
        const val DATABASE_NAME = "v04-android-projection-ledger.db"
        const val CONTACT_ID = "canonical-contact"
        const val GROUP_ID = "canonical-group"
        val ACCOUNT = AccountScope("projection-account")
        val FOREIGN_ACCOUNT = AccountScope("foreign-account")
        val SNAPSHOT_A = AndroidContactSnapshot(
            CONTACT_ID,
            listOf(
                AndroidContactRow(
                    identity = AndroidValueIdentity("baseline-name", providerRowId = 901),
                    kind = AndroidRowKind.STRUCTURED_NAME,
                    value = "Synthetic A",
                ),
            ),
        )
        val SNAPSHOT_B = SNAPSHOT_A.copy(
            rows = SNAPSHOT_A.rows.map { it.copy(value = "Synthetic B") },
        )
        val FP_A = CanonicalAndroidContactMapper().fingerprint(SNAPSHOT_A)
        val FP_B = CanonicalAndroidContactMapper().fingerprint(SNAPSHOT_B)
    }
}
