package com.patmanak.contako.data.local

import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.mapping.AndroidCompleteGroupCatalog
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidDurablePhotoCapture
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodec
import com.patmanak.contako.data.android.provider.GroupMembershipRows
import com.patmanak.contako.data.android.provider.RoomAndroidProviderIdentityResolver
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
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
class RoomAndroidCreatedContactCommitterDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var ledger: RoomAndroidProjectionLedger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        ledger = RoomAndroidProjectionLedger(database)
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun firstCommitPersistsCanonicalOutboxLedgerLocatorAndFullBaselineAtomically() = runBlocking {
        val observed = observedSnapshot()

        val result = committer().commit(CONTACT_ID, LOCATOR, decodedContact(), observed)
            as AndroidCreatedContactCommitResult.Applied

        assertEquals(1L, result.canonicalRevision)
        assertEquals(AndroidIngestionState.BASELINED, result.ledger.ingestionState)
        assertEquals(AndroidAdoptionState.AWAITING_REMOTE_ID, result.ledger.adoptionState)
        assertEquals(LOCATOR, result.ledger.rawContactLocator)
        val stored = requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).toDomain()
        assertFalse(stored.isDeleted)
        assertEquals("Created on Android", stored.displayName)
        assertEquals(1L, stored.revision)
        assertNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, CONTACT_ID))
        assertEquals(observed, ledger.loadBaseline(ACCOUNT, CONTACT_ID))
    }

    @Test
    fun compositeCreatedAdoptionInitializesMembershipLedgerReceiptAndReplaysByLocator() = runBlocking {
        ledger.ensureAccount(ACCOUNT)
        val repository = RoomContactRepository(database)
        val unified = RoomAndroidUnifiedObservationCommitter(
            database,
            RoomAndroidObservationCommitter(
                ACCOUNT,
                ledger,
                RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository),
            ),
            RoomAndroidGroupMembershipObservationCommitter(database, repository),
        )
        val authorization = RoomAndroidCreatedRawContactAuthorization(
            accountId = ACCOUNT.value,
            providerEpoch = LOCATOR.providerEpoch,
            rawContactLocator = LOCATOR.localRowHandle,
            rawContactVersion = 5,
            canonicalContactIdClaim = null,
            sourceIdentity = null,
            deleted = false,
            dirty = true,
        )

        val result = unified.commitCreated(
            CONTACT_ID,
            authorization,
            observedSnapshot(),
            committer(),
            emptyMembershipObservation(),
        ) as RoomAndroidCreatedUnifiedObservationResult.Applied

        assertEquals(1L, result.canonicalRevision)
        val membership = requireNotNull(database.androidGroupProjectionDao().getMembership(ACCOUNT.value, CONTACT_ID))
        assertEquals(EMAIL_ID, membership.preferredEmailValueId)
        assertEquals(1L, membership.revision)
        val membershipBaseline = requireNotNull(
            database.androidGroupProjectionDao().getMembershipBaseline(ACCOUNT.value, CONTACT_ID),
        )
        assertEquals(
            emptyList<String>(),
            AndroidGroupMembershipSnapshotBinaryCodec.decode(membershipBaseline.encodedSnapshot).canonicalGroupIds,
        )
        assertNotNull(database.androidGroupProjectionDao().getMembershipCommitReceipt(ACCOUNT.value, CONTACT_ID))
        assertNotNull(database.androidProjectionLedgerDao().getUnifiedObservationCommitReceipt(ACCOUNT.value, CONTACT_ID))
        assertEquals(
            RoomAndroidCreatedUnifiedObservationResult.Replayed,
            unified.commitCreated("replacement", authorization, observedSnapshot().copy(canonicalContactId = "replacement"),
                RoomAndroidCreatedContactCommitter(database, ACCOUNT, ledger), emptyMembershipObservation()),
        )
        assertNull(database.contactDao().get(ACCOUNT.value, "replacement"))
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
    }

    @Test
    fun compositeCreatedAdoptionAllocatesProviderIdentityAfterStagingCreatedLocator() = runBlocking {
        ledger.ensureAccount(ACCOUNT)
        val repository = RoomContactRepository(database)
        val unified = RoomAndroidUnifiedObservationCommitter(
            database,
            RoomAndroidObservationCommitter(
                ACCOUNT,
                ledger,
                RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository),
            ),
            RoomAndroidGroupMembershipObservationCommitter(database, repository),
        )
        val authorization = RoomAndroidCreatedRawContactAuthorization(
            accountId = ACCOUNT.value,
            providerEpoch = LOCATOR.providerEpoch,
            rawContactLocator = LOCATOR.localRowHandle,
            rawContactVersion = 5,
            canonicalContactIdClaim = null,
            sourceIdentity = null,
            deleted = false,
            dirty = true,
        )
        val providerAccount = AndroidProviderAccountName("android-account")
        val providerRows = listOf(
            AndroidOwnedDataRow(
                dataRowId = 700,
                rawContactId = LOCATOR.localRowHandle,
                mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                canonicalValueId = null,
                canonicalOrder = null,
                linkedValueIdsEncoding = null,
                isPrimary = true,
                isSuperPrimary = true,
                stringSlots = listOf("Created on Android", "Created", "on Android") + List(11) { null },
                binarySlot = null,
            ),
        )

        val result = unified.commitCreated(
            canonicalContactId = CONTACT_ID,
            authorization = authorization,
            createdCommitter = committer(),
            membershipObservation = emptyMembershipObservation(),
            decode = {
                AndroidProviderRowCodec(
                    RoomAndroidProviderIdentityResolver(database, ACCOUNT, LOCATOR.providerEpoch),
                    AndroidDurablePhotoCapture { _, _, _, _ -> error("PHOTO_NOT_EXPECTED") },
                ).decode(providerAccount, CONTACT_ID, LOCATOR.localRowHandle, providerRows)
            },
        )

        assertTrue(result is RoomAndroidCreatedUnifiedObservationResult.Applied)
        assertEquals(LOCATOR, requireNotNull(ledger.load(ACCOUNT, CONTACT_ID)).rawContactLocator)
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
    }

    @Test
    fun compositeCreatedAdoptionRejectsNonPristineProviderProofWithoutDurableState() = runBlocking {
        ledger.ensureAccount(ACCOUNT)
        val repository = RoomContactRepository(database)
        val unified = RoomAndroidUnifiedObservationCommitter(
            database,
            RoomAndroidObservationCommitter(ACCOUNT, ledger, RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository)),
            RoomAndroidGroupMembershipObservationCommitter(database, repository),
        )
        val invalid = RoomAndroidCreatedRawContactAuthorization(
            ACCOUNT.value, LOCATOR.providerEpoch, LOCATOR.localRowHandle, 5,
            canonicalContactIdClaim = "claimed", sourceIdentity = null, deleted = false, dirty = true,
        )

        assertEquals(
            RoomAndroidCreatedUnifiedObservationResult.ReplanRequired,
            unified.commitCreated(CONTACT_ID, invalid, observedSnapshot(), committer(), emptyMembershipObservation()),
        )
        assertNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID))
        assertNull(ledger.load(ACCOUNT, CONTACT_ID))
    }

    @Test
    fun locatorReplayRecoversTheCommittedIdentityWithoutASecondMutation() = runBlocking {
        val observed = observedSnapshot()
        val first = committer().commit(CONTACT_ID, LOCATOR, decodedContact(), observed)
            as AndroidCreatedContactCommitResult.Applied
        reopenDatabase()

        val replacementCandidate = "replacement-after-process-death"
        val recovered = committer().commit(
            replacementCandidate,
            LOCATOR,
            decodedContact().copy(id = replacementCandidate),
            observed.copy(canonicalContactId = replacementCandidate),
        )
            as AndroidCreatedContactCommitResult.RecoveredIdentity
        assertEquals(first.ledger, recovered.ledger)
        assertEquals(1L, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.revision)
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
    }

    @Test
    fun locatorRecoveryFailsClosedWhenTheDurableCommitSetIsIncomplete() = runBlocking {
        committer().commit(CONTACT_ID, LOCATOR, decodedContact(), observedSnapshot())
        database.androidProjectionLedgerDao().deleteBaseline(ACCOUNT.value, CONTACT_ID)

        assertEquals(
            AndroidCreatedContactCommitResult.LocatorConflict,
            committer().commit(CONTACT_ID, LOCATOR, decodedContact(), observedSnapshot()),
        )
        assertEquals(1L, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.revision)
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
    }

    @Test
    fun rejectedCanonicalDeltaRollsBackShellLedgerAndBaseline() = runBlocking {
        val duplicate = decodedContact().copy(
            values = listOf(email(), email().copy(value = "duplicate@example.test")),
        )

        val result = committer().commit(CONTACT_ID, LOCATOR, duplicate, observedSnapshot())

        assertTrue(result is AndroidCreatedContactCommitResult.Rejected)
        assertNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID))
        assertNull(ledger.load(ACCOUNT, CONTACT_ID))
        assertNull(ledger.loadBaseline(ACCOUNT, CONTACT_ID))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun processDeathInsideCanonicalMutationRollsBackEveryAdoptionRecord() = runBlocking {
        val repository = RoomContactRepository(
            database = database,
            checkpointHook = LocalMutationCheckpointHook { checkpoint ->
                if (checkpoint == LocalMutationCheckpoint.CONTACT_SAVE_AFTER_OUTBOX) {
                    throw SimulatedProcessDeath()
                }
            },
        )
        val committer = RoomAndroidCreatedContactCommitter(
            database,
            ACCOUNT,
            ledger,
            RoomAndroidCanonicalMutationStore(database, ACCOUNT, repository),
        )

        assertTrue(
            runCatching {
                committer.commit(CONTACT_ID, LOCATOR, decodedContact(), observedSnapshot())
            }.exceptionOrNull() is SimulatedProcessDeath,
        )
        assertNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID))
        assertNull(ledger.load(ACCOUNT, CONTACT_ID))
        assertNull(ledger.loadBaseline(ACCOUNT, CONTACT_ID))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun staleProviderEpochAndInvalidScopeCreateNoDurableState() = runBlocking {
        ledger.ensureAccount(ACCOUNT)
        ledger.advanceProviderEpoch(ACCOUNT, expectedAccountRevision = 0)

        assertEquals(
            AndroidCreatedContactCommitResult.StaleProviderEpoch,
            committer().commit(CONTACT_ID, LOCATOR, decodedContact(), observedSnapshot()),
        )
        assertTrue(
            committer().commit(
                CONTACT_ID,
                AndroidRawContactLocator(1, LOCATOR.localRowHandle),
                decodedContact().copy(accountId = "foreign"),
                observedSnapshot(),
            ) is AndroidCreatedContactCommitResult.InvalidInput,
        )
        assertNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID))
        assertNull(ledger.load(ACCOUNT, CONTACT_ID))
    }

    @Test
    fun preexistingCanonicalOrRemoteStateCannotBeAdoptedAsANewAndroidContact() = runBlocking {
        database.contactDao().upsert(decodedContact().copy(displayName = "Existing", revision = 3).toEntity())

        assertEquals(
            AndroidCreatedContactCommitResult.Stale,
            committer().commit(CONTACT_ID, LOCATOR, decodedContact(), observedSnapshot()),
        )
        val freshId = "fresh-contact"
        assertTrue(
            committer().commit(
                freshId,
                LOCATOR,
                decodedContact().copy(id = freshId, remoteVersion = "unexpected"),
                observedSnapshot().copy(canonicalContactId = freshId),
            ) is AndroidCreatedContactCommitResult.InvalidInput,
        )
        assertNull(database.contactDao().get(ACCOUNT.value, freshId))
        assertNull(ledger.load(ACCOUNT, freshId))
        assertEquals(3L, requireNotNull(database.contactDao().get(ACCOUNT.value, CONTACT_ID)).contact.revision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun resultDiagnosticsDoNotExposeContactPayloadOrStableIdentity() = runBlocking {
        val result = committer().commit(CONTACT_ID, LOCATOR, decodedContact(), observedSnapshot())
        val text = result.toString()

        assertTrue(text.contains("REDACTED"))
        assertFalse(text.contains(CONTACT_ID))
        assertFalse(text.contains("Created on Android"))
        assertFalse(text.contains("created@example.test"))
    }

    private fun committer() = RoomAndroidCreatedContactCommitter(database, ACCOUNT, ledger)

    private fun emptyMembershipObservation() = RoomAndroidCreatedMembershipObservation(
        AndroidCompleteGroupCatalog.fromExhaustivePages(
            ACCOUNT,
            LOCATOR.providerEpoch,
            listOf(AndroidOwnedGroupRowPage(AndroidProviderAccountName("android-account"), 0, emptyList(), null)),
        ),
        GroupMembershipRows(emptyList()),
        emptyList(),
    )

    private fun reopenDatabase() {
        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        ledger = RoomAndroidProjectionLedger(database)
    }

    private fun decodedContact() = CanonicalContact(
        accountId = ACCOUNT.value,
        id = CONTACT_ID,
        firstName = "Created",
        lastName = "on Android",
        displayName = "Created on Android",
        values = listOf(email()),
    )

    private fun email() = ContactValue(
        id = EMAIL_ID,
        kind = ContactValueKind.EMAIL,
        value = "created@example.test",
        order = 0,
        isPrimary = true,
    )

    private fun observedSnapshot(): AndroidContactSnapshot = MAPPER.project(decodedContact()).let { snapshot ->
        snapshot.copy(
            rows = snapshot.rows.mapIndexed { index, row ->
                row.copy(identity = row.identity.copy(providerRowId = 700L + index))
            },
        )
    }

    private class SimulatedProcessDeath : RuntimeException()

    private companion object {
        const val DATABASE_NAME = "android-created-contact-committer.db"
        const val CONTACT_ID = "android-created-contact"
        const val EMAIL_ID = "created-email"
        val ACCOUNT = AccountScope("account")
        val LOCATOR = AndroidRawContactLocator(0, 81)
        val MAPPER = CanonicalAndroidContactMapper()
    }
}
