package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import com.patmanak.contako.data.sync.AndroidProjectionWriteLedgerResult
import com.patmanak.contako.data.sync.RoomAndroidProjectionWriteLedger
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room and production acknowledgement/adoption only; no account, gateway or provider. */
@RunWith(AndroidJUnit4::class)
class ContactSyncDetailStoreDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private val details get() = RoomContactSyncDetailStore(database)
    private val proofs get() = RoomContactSyncEvidenceStore(database)
    private val repository get() = RoomContactRepository(database)
    @Before fun setup() { context.deleteDatabase(NAME); database = ContakoDatabase.create(context, NAME) }
    @After fun cleanup() { if (database.isOpen) database.close(); context.deleteDatabase(NAME) }

    @Test fun legacyVersionAndCleanLedgerNeverInventDatesOrConfirmationAndTenViewsAreReadOnly() = runBlocking {
        seed(contact())
        val binding = RoomAndroidProjectionLedger(database).ensureAccount(AccountScope(OWNER))
        database.androidProjectionLedgerDao().bindAndroidAccountName(OWNER, binding.revision, "synthetic-android")
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(OWNER, CONTACT, 1, 0, 42,
            REMOTE, "a".repeat(64), "a".repeat(64), null, null, "CLEAN", "BASELINED", "NONE", "ADOPTED"))
        val original = database.contactDao().get(OWNER, CONTACT)
        val ledger = database.androidProjectionLedgerDao().get(OWNER, CONTACT)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER forbid_evidence_write BEFORE INSERT ON contact_sync_evidence BEGIN SELECT RAISE(ABORT,'UNEXPECTED_WRITE'); END")
        repeat(10) {
            val detail = requireNotNull(details.observe(OWNER, CONTACT).first())
            assertEquals(ContactPeerSyncState.UNKNOWN, detail.proton.state)
            assertEquals(ContactPeerSyncState.UNKNOWN, detail.android.state)
            assertNull(detail.proton.evidence); assertNull(detail.android.evidence)
        }
        assertEquals(original, database.contactDao().get(OWNER, CONTACT))
        assertEquals(ledger, database.androidProjectionLedgerDao().get(OWNER, CONTACT))
        assertNull(database.contactSyncEvidenceDao().get(OWNER, CONTACT, "PROTON"))
        assertNull(database.contactSyncEvidenceDao().get(OWNER, CONTACT, "ANDROID"))
        assertTrue(database.outboxDao().getAll(OWNER).isEmpty())
    }

    @Test fun exactWriteAcknowledgementRecordsCurrentRevisionAndOfflineEditRetainsItAsAnOlderConfirmation() = runBlocking {
        val saved = save(CanonicalContact(OWNER, CONTACT, displayName = "Synthetic saved"))
        val mutation = RoomMutationExecutionStore(database, evidenceClock = { 1234 })
        val command = mutation.eligible(OWNER, Long.MAX_VALUE, 1).single()
        assertTrue(mutation.claim(command, Long.MAX_VALUE))
        assertTrue(mutation.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(REMOTE, "opaque-not-a-date")))
        val confirmed = requireNotNull(details.load(OWNER, CONTACT))
        assertEquals(ContactPeerSyncState.CONFIRMED, confirmed.proton.state)
        assertEquals(ContactSyncEvidenceKind.WRITE_ACKNOWLEDGEMENT, confirmed.proton.evidence?.kind)
        assertEquals(1234L, confirmed.proton.evidence?.confirmedAtEpochMillis)
        assertEquals(saved.revision, confirmed.proton.evidence?.localRevision)
        val local = requireNotNull(database.contactDao().get(OWNER, CONTACT)).toDomain()
        val edited = save(local.copy(values = listOf(ContactValue("note", ContactValueKind.NOTE, "Local offline note", order = 0))))
        val pending = requireNotNull(details.load(OWNER, CONTACT))
        assertEquals(edited.revision, pending.localRevision)
        assertEquals(ContactPeerSyncState.PENDING, pending.proton.state)
        assertEquals(confirmed.proton.evidence, pending.proton.evidence)
        assertEquals("Local offline note", requireNotNull(database.contactDao().get(OWNER, CONTACT)).toDomain().values.single().value)
    }

    @Test fun verifiedAdoptionUsesItsOwnReceiptTimeRatherThanRemoteModificationTimeAndReactsToLaterChanges() = runBlocking {
        val remote = contact().copy(id = REMOTE, remoteVersion = "v1")
        val reconciler = RoomRemoteCanonicalReconciliationStore(database, evidenceClock = { 2_000 })
        database.withTransaction {
            reconciler.reconcileTargetedVerified(AccountScope(OWNER), VerifiedContactCard(RemoteContactId(REMOTE), RemoteVersion("v1"), remote))
        }
        val adopted = requireNotNull(database.contactDao().getByRemoteContactId(OWNER, REMOTE)).contact
        val initial = requireNotNull(details.load(OWNER, adopted.id))
        assertEquals(ContactPeerSyncState.CONFIRMED, initial.proton.state)
        assertEquals(2_000L, initial.proton.evidence?.confirmedAtEpochMillis)
        val updates = Channel<ContactSyncDetail?>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Default) { details.observe(OWNER, adopted.id).collect { updates.send(it) } }
        try {
            withTimeout(10_000) { updates.receive() }
            database.contactDao().upsert(adopted.copy(revision = adopted.revision + 1))
            val changed = withTimeout(10_000) {
                var next = updates.receive()
                while (next?.localRevision != adopted.revision + 1) next = updates.receive()
                requireNotNull(next)
            }
            assertEquals(ContactPeerSyncState.HISTORICAL, changed.proton.state)
            assertEquals(initial.proton.evidence, changed.proton.evidence)
        } finally { observer.cancelAndJoin(); updates.close() }
    }

    @Test fun staleRevisionSourceVersionAndWrongOwnerCannotBorrowOrOverwriteAProof() = runBlocking {
        seed(contact())
        assertTrue(proofs.recordProton(OWNER, CONTACT, 7, REMOTE, "opaque-v1", ContactSyncEvidenceKind.VERIFIED_READ, 3_000))
        assertFalse(proofs.recordProton(OWNER, CONTACT, 6, REMOTE, "opaque-v1", ContactSyncEvidenceKind.VERIFIED_READ, 9_000))
        seed(contact().copy(accountId = OTHER, remoteContactId = "foreign-remote"))
        assertNull(requireNotNull(details.load(OTHER, CONTACT)).proton.evidence)
        database.contactDao().upsert(contact().copy(remoteVersion = "opaque-v2").toEntity())
        val historical = requireNotNull(details.load(OWNER, CONTACT))
        assertEquals(ContactPeerSyncState.HISTORICAL, historical.proton.state)
        assertEquals(3_000L, historical.proton.evidence?.confirmedAtEpochMillis)
        assertFalse(proofs.recordProton(OWNER, CONTACT, 7, "different-source", "opaque-v2", ContactSyncEvidenceKind.VERIFIED_READ, 10_000))
        database.accountRemovalDao().deleteAccount(OWNER)
        assertNull(details.observe(OWNER, CONTACT).first())
        assertNull(database.contactSyncEvidenceDao().get(OWNER, CONTACT, "PROTON"))
        assertNotNull(details.load(OTHER, CONTACT))
    }

    @Test fun pendingDeletionRetainsItsContactSummaryAndRequiresARealRemoteAbsenceReceipt() = runBlocking {
        save(CanonicalContact(OWNER, CONTACT, displayName = "Synthetic deletion"))
        val mutation = RoomMutationExecutionStore(database, evidenceClock = { 1_000 })
        var command = mutation.eligible(OWNER, Long.MAX_VALUE, 1).single()
        mutation.claim(command, Long.MAX_VALUE)
        mutation.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(REMOTE, "v1"))
        repository.deleteContact(OWNER, CONTACT)
        val pending = requireNotNull(details.load(OWNER, CONTACT))
        assertTrue(pending.isDeleted)
        assertEquals(ContactPeerSyncState.PENDING, pending.proton.state)
        assertEquals(ContactSyncEvidenceKind.WRITE_ACKNOWLEDGEMENT, pending.proton.evidence?.kind)
        command = mutation.eligible(OWNER, Long.MAX_VALUE, 1).single()
        mutation.claim(command, Long.MAX_VALUE)
        mutation.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(REMOTE, "v1"))
        val absent = requireNotNull(details.load(OWNER, CONTACT))
        assertTrue(absent.isDeleted)
        assertEquals(ContactPeerSyncState.CONFIRMED, absent.proton.state)
        assertEquals(ContactSyncEvidenceKind.CONFIRMED_ABSENCE, absent.proton.evidence?.kind)
    }

    @Test fun localOnlyComplementsAreNotErrorsAndGroupIntentNeverBecomesAContactCardAcknowledgement() = runBlocking {
        val canonical = contact().copy(values = listOf(
            ContactValue("custom", ContactValueKind.CUSTOM_DATE, "--04-12", order = 0),
            ContactValue("phonetic", ContactValueKind.PHONETIC_NAME, "Synthetic", order = 0),
            ContactValue("url", ContactValueKind.URL, "https://example.test", label = "Local label", order = 0,
                metadata = mapOf("contakoLocalOnlyLabel" to "true")),
            ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-KEEP:opaque", order = 0),
            ContactValue("email", ContactValueKind.EMAIL, "synthetic@example.test", order = 0),
        ))
        seed(canonical)
        proofs.recordProton(OWNER, CONTACT, 7, REMOTE, "opaque-v1", ContactSyncEvidenceKind.VERIFIED_READ, 1_000)
        assertTrue(repository.saveGroup(ContactGroup(OWNER, "group", "Synthetic group", memberships = listOf(
            GroupMembership(CONTACT, "email")))) is SaveResult.Saved)
        val initial = requireNotNull(details.load(OWNER, CONTACT))
        assertEquals(3, initial.localOnlyFieldCount)
        assertTrue(initial.groupChangesPending)
        assertEquals(ContactPeerSyncState.CONFIRMED, initial.proton.state)
        val groupMutation = RoomMutationExecutionStore(database, evidenceClock = { 9_000 })
        val command = groupMutation.eligible(OWNER, Long.MAX_VALUE, 10).single { it.aggregateType == "GROUP" }
        groupMutation.claim(command, Long.MAX_VALUE)
        groupMutation.acknowledgeAndFinish(command, RemoteMutationAcknowledgement("remote-group", "group-version"))
        assertEquals(initial.proton.evidence, requireNotNull(details.load(OWNER, CONTACT)).proton.evidence)
    }

    @Test fun protonConflictDoesNotInvalidateAnIndependentlyConfirmedAndroidProjection() = runBlocking {
        val canonical = contact()
        seed(canonical)
        val scope = AccountScope(OWNER)
        val service = RoomAndroidProjectionLedger(database)
        val binding = service.ensureAccount(scope)
        service.bindAndroidAccountName(scope, binding.revision, "synthetic-android")
        val current = requireNotNull(database.androidProjectionLedgerDao().getAccount(OWNER))
        val projectionContext = AndroidInteroperabilityContext(scope, "synthetic-android", current.revision, current.providerEpoch)
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(OWNER, CONTACT, 0,
            current.providerEpoch, 42, REMOTE, null, null, null, null,
            "REPAIR_REQUIRED", "NONE", "NONE", "ADOPTED"))
        database.androidGroupProjectionDao().insertMembership(AndroidGroupMembershipProjectionLedgerEntity(
            OWNER, CONTACT, 0, current.providerEpoch, 42, null, null, null, null, "REPAIR_REQUIRED", "NONE"))
        val snapshot = CanonicalAndroidContactMapper().project(canonical)
        val memberships = AndroidGroupMembershipSnapshot.create(OWNER, CONTACT, null,
            AndroidGroupMembershipAvailability.NO_EMAIL, emptyList())
        val writer = RoomAndroidProjectionWriteLedger(database)
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            writer.prepare(projectionContext, CONTACT, 0, canonical.revision, 0, snapshot, memberships))
        assertEquals(AndroidProjectionWriteLedgerResult.Applied,
            writer.complete(projectionContext, CONTACT, canonical.revision, 1, 1, snapshot, memberships))
        database.contactDao().upsert(canonical.copy(conflictState = "REMOTE_CHANGED").toEntity())
        val conflicted = requireNotNull(details.load(OWNER, CONTACT))
        assertEquals(ContactPeerSyncState.CONFLICT, conflicted.proton.state)
        assertEquals(ContactPeerSyncState.CONFIRMED, conflicted.android.state)
        assertEquals(canonical.revision, conflicted.android.evidence?.localRevision)

        // A later canonical adoption invalidates Android by its own covered revision, not by
        // borrowing the remote conflict. No projection or network work runs when viewing it.
        database.contactDao().upsert(canonical.copy(revision = canonical.revision + 1).toEntity())
        val adopted = requireNotNull(details.load(OWNER, CONTACT))
        assertEquals(ContactPeerSyncState.HISTORICAL, adopted.android.state)
        assertEquals(conflicted.android.evidence, adopted.android.evidence)
    }

    @Test fun richContactLocalComplementCountSpansAllBoundedPages() = runBlocking {
        seed(contact().copy(values = (0 until 2_050).map { index ->
            ContactValue("phonetic-$index", ContactValueKind.PHONETIC_NAME, "Synthetic $index", order = index)
        }))
        assertEquals(2_050, requireNotNull(details.load(OWNER, CONTACT)).localOnlyFieldCount)
    }

    private suspend fun seed(contact: CanonicalContact) {
        database.contactDao().upsert(contact.toEntity())
        database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
    }
    private suspend fun save(contact: CanonicalContact) = when (val result = repository.saveContact(contact)) {
        is SaveResult.Saved -> result.value
        is SaveResult.Rejected -> error("Synthetic contact rejected")
    }
    private fun contact() = CanonicalContact(OWNER, CONTACT, displayName = "Synthetic contact", revision = 7,
        updatedAtEpochMillis = 999_999, remoteContactId = REMOTE, remoteVersion = "opaque-v1")
    private companion object {
        const val NAME = "contact-sync-detail-room.db"
        const val OWNER = "detail-synthetic-owner"
        const val OTHER = "detail-synthetic-other"
        const val CONTACT = "detail-synthetic-contact"
        const val REMOTE = "detail-synthetic-remote"
    }
}
