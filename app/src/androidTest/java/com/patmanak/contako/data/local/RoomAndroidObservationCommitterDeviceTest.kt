package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidObservationClassification
import com.patmanak.contako.data.android.AndroidProjectionLedgerSnapshot
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidCanonicalDelta
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAndroidObservationCommitterDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var ledger: RoomAndroidProjectionLedger
    private lateinit var committer: RoomAndroidObservationCommitter

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        ledger = RoomAndroidProjectionLedger(database)
        committer = RoomAndroidObservationCommitter(
            ACCOUNT,
            ledger,
            RoomAndroidCanonicalMutationStore(database, ACCOUNT),
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) database.close()
        if (::context.isInitialized) context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun contactDeltaAdvancesCanonicalOutboxAndLedgerAtomically() = runBlocking {
        val baseline = cleanBaseline()
        val edited = contact(displayName = "Android edit")
        val observed = snapshot(edited)
        val delta = delta(edited, observed, changed = setOf(NAME_ID))

        val result = committer.commitContactDelta(baseline, LOCATOR, 0, delta, observed)
            as AndroidObservationCommitResult.Applied

        assertEquals(AndroidObservationClassification.CANONICAL_DELTA_COMMITTED, result.observation.classification)
        val stored = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain()
        assertEquals("Android edit", stored.displayName)
        assertEquals(1L, stored.revision)
        assertNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
        assertEquals(delta.observedFingerprint, requireNotNull(ledger.load(ACCOUNT, CONTACT_ID)).androidBaselineFingerprint)
        assertEquals(observed, ledger.loadBaseline(ACCOUNT, CONTACT_ID))
    }

    @Test
    fun staleCanonicalCasRollsBackTheLedgerRevisionAndBaseline() = runBlocking {
        val baseline = cleanBaseline()
        val result = committer.commitContactDelta(
            baseline,
            LOCATOR,
            expectedCanonicalRevision = 99,
            delta = delta(
                contact(displayName = "Must not commit"),
                snapshot(contact(displayName = "Must not commit")),
                changed = setOf(NAME_ID),
            ),
            observedSnapshot = snapshot(contact(displayName = "Must not commit")),
        )

        assertEquals(AndroidObservationCommitResult.CanonicalStale, result)
        assertEquals(baseline, ledger.load(ACCOUNT, CONTACT_ID))
        val stored = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain()
        assertEquals("Baseline", stored.displayName)
        assertEquals(0L, stored.revision)
        assertEquals(null, database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
    }

    @Test
    fun rejectedCrossAccountDeltaNeverClaimsTheLedger() = runBlocking {
        val baseline = cleanBaseline()
        val foreign = contact(displayName = "Foreign").copy(accountId = "foreign-account")

        assertEquals(
            AndroidObservationCommitResult.CanonicalRejected,
            committer.commitContactDelta(
                baseline,
                LOCATOR,
                expectedCanonicalRevision = 0,
                delta = delta(foreign, snapshot(contact(displayName = "Foreign")), changed = setOf(NAME_ID)),
                observedSnapshot = snapshot(contact(displayName = "Foreign")),
            ),
        )
        assertEquals(baseline, ledger.load(ACCOUNT, CONTACT_ID))
        assertEquals(null, database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
    }

    @Test
    fun noSemanticDeltaReconcilesObservationWithoutCreatingOutbox() = runBlocking {
        val baseline = cleanBaseline()
        val observed = snapshot(contact()).copy(
            rows = snapshot(contact()).rows.map { it.copy(isPrimary = true) },
        )
        val delta = delta(contact(), observed, changed = emptySet())

        val result = committer.commitContactDelta(
            baseline,
            LOCATOR,
            expectedCanonicalRevision = 0,
            delta = delta,
            observedSnapshot = observed,
        ) as AndroidObservationCommitResult.Applied

        assertEquals(AndroidObservationClassification.CANONICAL_DELTA_COMMITTED, result.observation.classification)
        assertEquals(0L, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.revision)
        assertEquals(null, database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
        assertEquals(delta.observedFingerprint, requireNotNull(ledger.load(ACCOUNT, CONTACT_ID)).androidBaselineFingerprint)
        assertEquals(observed, ledger.loadBaseline(ACCOUNT, CONTACT_ID))
    }

    @Test
    fun noSemanticDeltaRejectsAStaleCanonicalRevisionAndRollsBackTheBaseline() = runBlocking {
        val baseline = cleanBaseline()
        val beforeBaseline = requireNotNull(ledger.loadBaseline(ACCOUNT, CONTACT_ID))
        val current = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact
        database.contactDao().upsert(current.copy(revision = 1))
        val observed = snapshot(contact()).copy(
            rows = snapshot(contact()).rows.map { it.copy(isPrimary = true) },
        )

        val result = committer.commitContactDelta(
            baseline,
            LOCATOR,
            expectedCanonicalRevision = 0,
            delta = delta(contact(), observed, changed = emptySet()),
            observedSnapshot = observed,
        )

        assertEquals(AndroidObservationCommitResult.CanonicalStale, result)
        assertEquals(baseline, ledger.load(ACCOUNT, CONTACT_ID))
        assertEquals(beforeBaseline, ledger.loadBaseline(ACCOUNT, CONTACT_ID))
        assertEquals(1L, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.revision)
        assertEquals(null, database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
    }

    @Test
    fun deletionCommitsCanonicalTombstoneOutboxAndLedgerTogether() = runBlocking {
        val baseline = cleanBaseline()

        val result = committer.commitDeletion(baseline, LOCATOR, expectedCanonicalRevision = 0)
            as AndroidObservationCommitResult.Applied

        assertEquals(AndroidObservationClassification.TOMBSTONE_COMMITTED, result.observation.classification)
        val stored = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact
        assertEquals(1L, stored.revision)
        assertEquals(true, stored.isDeleted)
        val outbox = requireNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
        assertEquals(MutationOperation.DELETE.name, outbox.operation)
        assertFalse(requireNotNull(ledger.load(ACCOUNT, CONTACT_ID)).toString().contains(CONTACT_ID))
    }

    @Test
    fun unifiedDeletionDetachesMembershipAndPersistsExactReceiptAtomically() = runBlocking {
        val baseline = cleanBaseline()
        val repository = RoomContactRepository(database)
        database.androidGroupProjectionDao().insertMembership(
            AndroidGroupMembershipProjectionLedgerEntity(
                ACCOUNT.value, CONTACT_ID, 0, LOCATOR.providerEpoch, LOCATOR.localRowHandle,
                preferredEmailValueId = null,
                canonicalProjectionFingerprint = null,
                androidBaselineFingerprint = null,
                pendingProjectionFingerprint = null,
                projectionState = "CLEAN",
                ingestionState = "NONE",
            ),
        )
        val unified = RoomAndroidUnifiedObservationCommitter(
            database,
            RoomAndroidObservationCommitter(
                ACCOUNT,
                ledger,
                RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository),
            ),
            RoomAndroidGroupMembershipObservationCommitter(database, repository),
        )

        val result = unified.commitDeleted(RoomAndroidCreatedRawContactAuthorization(
            ACCOUNT.value, LOCATOR.providerEpoch, LOCATOR.localRowHandle, 17,
            CONTACT_ID, SOURCE_ID, deleted = true, dirty = true,
        ))

        assertTrue(result is RoomAndroidDeletedUnifiedObservationResult.Applied)
        assertEquals(true, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.isDeleted)
        val membership = requireNotNull(database.androidGroupProjectionDao().getMembership(ACCOUNT.value, CONTACT_ID))
        assertEquals("DETACHED", membership.projectionState)
        assertEquals(null, membership.preferredEmailValueId)
        val receipt = requireNotNull(database.androidProjectionLedgerDao().getUnifiedObservationCommitReceipt(
            ACCOUNT.value, CONTACT_ID,
        ))
        assertEquals(LOCATOR.providerEpoch, receipt.providerEpoch)
        assertEquals(LOCATOR.localRowHandle, receipt.rawContactLocator)
        assertEquals(17L, receipt.rawContactVersion)
        assertEquals(baseline.revision + 1, receipt.committedContactLedgerRevision)
    }

    private suspend fun cleanBaseline(): AndroidProjectionLedgerSnapshot {
        val initial = contact()
        database.contactDao().upsert(initial.toEntity())
        database.contactDao().upsertValues(initial.values.map { it.toEntity(initial) })
        ledger.ensureAccount(ACCOUNT)
        val attached = ledger.attachCanonicalContact(ACCOUNT, CONTACT_ID, SOURCE_ID)
        val observed = snapshot(initial)
        val fingerprint = MAPPER.fingerprint(observed)
        return ledger.establishObservedBaseline(
            ACCOUNT,
            CONTACT_ID,
            attached.revision,
            LOCATOR,
            fingerprint,
            fingerprint,
            observed,
        ).let { result ->
            (result as com.patmanak.contako.data.android.AndroidLedgerCasResult.Updated).snapshot
        }
    }

    private fun delta(
        contact: CanonicalContact,
        observed: AndroidContactSnapshot,
        changed: Set<String>,
    ) = AndroidCanonicalDelta(
        contact = contact,
        observedFingerprint = MAPPER.fingerprint(observed),
        resultingCanonicalFingerprint = MAPPER.fingerprint(MAPPER.project(contact)),
        changedValueIds = changed,
    )

    private fun snapshot(contact: CanonicalContact): AndroidContactSnapshot = MAPPER.project(contact).let { projected ->
        projected.copy(
            rows = projected.rows.mapIndexed { index, row ->
                row.copy(identity = row.identity.copy(providerRowId = 900L + index))
            },
        )
    }

    private fun contact(displayName: String = "Baseline") = CanonicalContact(
        accountId = ACCOUNT.value,
        id = CONTACT_ID,
        displayName = displayName,
        values = listOf(
            ContactValue(
                id = NAME_ID,
                kind = ContactValueKind.STRUCTURED_NAME,
                value = displayName,
                order = 0,
                isPrimary = true,
            ),
        ),
        revision = 0,
        updatedAtEpochMillis = 1,
        remoteContactId = SOURCE_ID,
    )

    private companion object {
        const val DATABASE_NAME = "room-android-observation-committer-test.db"
        const val CONTACT_ID = "canonical-contact"
        const val NAME_ID = "name-value"
        const val SOURCE_ID = "remote-contact"
        val ACCOUNT = AccountScope("account")
        val LOCATOR = AndroidRawContactLocator(0, 73)
        val MAPPER = CanonicalAndroidContactMapper()
    }
}
