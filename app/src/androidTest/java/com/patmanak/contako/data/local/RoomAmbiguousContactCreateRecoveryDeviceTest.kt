package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.DurableMutationOrchestrator
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.data.sync.MutationPreparationActionRequiredReason
import com.patmanak.contako.data.sync.MutationPreparationGateway
import com.patmanak.contako.data.sync.MutationUploadGateway
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated Room transactions; no Android account, ContactsProvider or Proton access. */
@RunWith(AndroidJUnit4::class)
class RoomAmbiguousContactCreateRecoveryDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private var remote = emptyList<VerifiedContactCard>()
    private var inventoryReads = 0
    private var cardReads = 0
    private var inventoryVersion = 1
    private var authority = ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY
    private var cardFailure: GatewayFailureCategory? = null
    private var afterCardRead: suspend () -> Unit = {}

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE)
        database = ContakoDatabase.create(context, DATABASE)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE)
    }

    @Test fun noUidMatchReopensOnlyCurrentIntentAndRetainsNewerFields() = runBlocking {
        val initial = seed()
        remote = listOf(card("other", "other-uid"))
        val before = intent()
        assertSame(MutationPreparation.UploadAllowed, success(recovery().prepare(command())))
        assertEquals(before.copy(requiresReconciliation = false), intent())
        assertEquals(initial, contact())
        assertEquals(PreservationEnvelope(mapOf("X-OPAQUE" to "retained opaque value")), payload())
        assertEquals(2, inventoryReads)
        assertEquals(1, cardReads)
    }

    @Test fun emptyAuthoritativeInventoryConfirmsAbsenceBeforeAllowingCreate() = runBlocking {
        seed()
        assertSame(MutationPreparation.UploadAllowed, success(recovery().prepare(command())))
        assertFalse(intent().requiresReconciliation)
        assertEquals(2, inventoryReads)
        assertEquals(0, cardReads)
    }

    @Test fun oneUidMatchBindsSameLocalContactAndPreservesVersionChoice() = runBlocking {
        val initial = seed()
        remote = listOf(card("delivered", UID))
        assertReason(MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT, recovery().prepare(command()))
        val recovered = contact()
        assertEquals(initial.id, recovered.id)
        assertEquals(initial.displayName, recovered.displayName)
        assertEquals(initial.values, recovered.values)
        assertEquals(initial.pendingMutationRevision, recovered.pendingMutationRevision)
        assertEquals("delivered", recovered.remoteContactId)
        assertEquals("REMOTE_UPDATE_CONFLICT", recovered.conflictState)
        assertEquals("delivered", intent().remoteIdentity)
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, intent().state)
        assertTrue(intent().requiresReconciliation)
        assertEquals(listOf(LOCAL_ID), database.contactDao().observeActive(ACCOUNT.value).first().map { it.contact.id })
        val conflict = requireNotNull(RoomContactConflictStore(database).detail(ACCOUNT.value, LOCAL_ID))
        assertEquals(initial.displayName, conflict.local.displayName)
        assertEquals("Remote delivered", conflict.proton.displayName)
    }

    @Test fun twoRemoteUidMatchesNeverBindOrReopenTheCreate() = runBlocking {
        val initial = seed()
        remote = listOf(card("first", UID), card("second", UID), card("unneeded", "other"))
        val before = intent()
        assertReason(MutationPreparationActionRequiredReason.RECONCILIATION_REMOTE_IDENTITY_MISSING,
            recovery().prepare(command()))
        assertEquals(before, intent())
        assertEquals(initial, contact())
        assertEquals(2, cardReads)
        assertEquals(2, inventoryReads)
    }

    @Test fun recoveredDeliveredCreateDoesNotReachTheUploadPortOrDuplicateLocalContact() = runBlocking {
        seed()
        remote = listOf(card("delivered", UID))
        var uploads = 0
        val helper = recovery()
        val orchestrator = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            MutationPreparationGateway { helper.prepare(it) }, MutationUploadGateway {
                uploads++
                GatewayOutcome.Success(RemoteMutationAcknowledgement("unexpected", "unexpected"))
            })
        val result = orchestrator.drain(ACCOUNT.value, 1_000)
        assertEquals(0, uploads)
        assertTrue(result.actionRequired > 0)
        assertEquals("delivered", contact().remoteContactId)
        assertEquals(listOf(LOCAL_ID), database.contactDao().observeActive(ACCOUNT.value).first().map { it.contact.id })
    }

    @Test fun verifiedAbsenceUploadsNewestRevisionOnceThroughTheRealOutbox() = runBlocking {
        val initial = seed()
        var uploads = 0
        val helper = recovery()
        val orchestrator = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            MutationPreparationGateway { helper.prepare(it) }, MutationUploadGateway {
                uploads++
                assertEquals(initial.displayName, contact().displayName)
                assertEquals(initial.values, contact().values)
                GatewayOutcome.Success(RemoteMutationAcknowledgement("new-remote", "new-full-version"))
            })
        assertEquals(1, orchestrator.drain(ACCOUNT.value, 1_000).uploaded)
        orchestrator.drain(ACCOUNT.value, 2_000)
        assertEquals(1, uploads)
        assertEquals("new-remote", contact().remoteContactId)
        assertEquals(initial.values, contact().values)
        assertNull(contact().pendingMutationRevision)
        assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, LOCAL_ID))
    }

    @Test fun unreadableCardCannotProveAnAbsentUid() = runBlocking {
        seed()
        remote = listOf(card("unreadable", "other"))
        cardFailure = GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED
        val before = intent()
        val result = recovery().prepare(command()) as GatewayOutcome.Failure
        assertEquals(cardFailure, result.category)
        assertEquals(before, intent())
        assertEquals(1, inventoryReads)
    }

    @Test fun inventoryChangeDuringCardReadsDoesNotReopenTheCreate() = runBlocking {
        seed()
        remote = listOf(card("other", "other"))
        afterCardRead = { inventoryVersion++ }
        val before = intent()
        assertEquals(GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            (recovery().prepare(command()) as GatewayOutcome.Failure).category)
        assertEquals(before, intent())
        assertEquals(2, inventoryReads)
    }

    @Test fun editWhileReadingCardsKeepsItsRevisionAndAmbiguity() = runBlocking {
        seed()
        remote = listOf(card("other", "other"))
        val originalCommand = command()
        afterCardRead = {
            val row = requireNotNull(database.contactDao().getEntity(ACCOUNT.value, LOCAL_ID))
            database.contactDao().upsert(row.copy(displayName = "Changed during verification",
                revision = row.revision + 1, pendingMutationRevision = row.revision + 1))
            database.outboxDao().upsert(intent().copy(revision = row.revision + 1))
        }
        assertReason(MutationPreparationActionRequiredReason.REVISION_MISMATCH, recovery().prepare(originalCommand))
        assertEquals(4L, contact().revision)
        assertEquals("Changed during verification", contact().displayName)
        assertTrue(intent().requiresReconciliation)
    }

    @Test fun changedPayloadWithoutRevisionBumpStillInvalidatesAbsenceProof() = runBlocking {
        seed()
        remote = listOf(card("other", "other"))
        afterCardRead = {
            database.contactPayloadDao().upsert(PreservationEnvelope(mapOf("X-OPAQUE" to "new preserved value"))
                .toEntity(ACCOUNT.value, LOCAL_ID))
        }
        assertReason(MutationPreparationActionRequiredReason.REVISION_MISMATCH, recovery().prepare(command()))
        assertTrue(intent().requiresReconciliation)
        assertEquals("new preserved value", payload()?.rawProperties?.get("X-OPAQUE"))
    }

    @Test fun untrustedEmptyInventoryCannotVacuouslyProveAbsence() = runBlocking {
        seed()
        authority = ContactInventorySnapshotAuthority.UNATTESTED
        val before = intent()
        assertEquals(GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            (recovery().prepare(command()) as GatewayOutcome.Failure).category)
        assertEquals(before, intent())
        assertEquals(1, inventoryReads)
    }

    @Test fun absentDeliveredCreateAllowsPendingDeleteToFinishWithoutRecreation() = runBlocking {
        val initial = seed(deleted = true)
        val result = success(recovery().prepare(command())) as MutationPreparation.AlreadyApplied
        assertNull(result.acknowledgement.remoteIdentity)
        assertNull(result.acknowledgement.remoteVersion)
        assertEquals(initial, contact())
        assertFalse(intent().requiresReconciliation)
        assertEquals(2, inventoryReads)
    }

    @Test fun deliveredCreateWithPendingDeleteBindsIdentityAndKeepsDeletion() = runBlocking {
        val initial = seed(deleted = true)
        remote = listOf(card("delivered", UID))
        assertReason(MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT, recovery().prepare(command()))
        assertEquals("delivered", contact().remoteContactId)
        assertTrue(contact().isDeleted)
        assertEquals(initial.pendingMutationRevision, contact().pendingMutationRevision)
        assertEquals(MutationOperation.DELETE.name, intent().operation)
    }

    @Test fun anotherLocalIdentityWithSameUidPreventsReplayOrBinding() = runBlocking {
        seed()
        database.contactDao().upsert(CanonicalContact(ACCOUNT.value, "other-local", displayName = "Other",
            revision = 1, remoteVCardUid = UID, remoteContactId = "already-owned", isDeleted = true).toEntity())
        remote = listOf(card("delivered", UID))
        assertReason(MutationPreparationActionRequiredReason.RECONCILIATION_REMOTE_IDENTITY_MISSING,
            recovery().prepare(command()))
        assertTrue(intent().requiresReconciliation)
        assertNull(contact().remoteContactId)
    }

    @Test fun generatedCreateUidFallsBackToTheStableLocalId() = runBlocking {
        seed()
        val row = requireNotNull(database.contactDao().getEntity(ACCOUNT.value, LOCAL_ID))
        database.contactDao().upsert(row.copy(remoteVCardUid = null))
        remote = listOf(card("delivered", LOCAL_ID))
        assertReason(MutationPreparationActionRequiredReason.REMOTE_UPDATE_CONFLICT, recovery().prepare(command()))
        assertEquals("delivered", contact().remoteContactId)
    }

    @Test fun cardWithoutVerifiedVersionCannotAuthorizeReplay() = runBlocking {
        seed()
        remote = listOf(card("other", "other").copy(version = null))
        assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE,
            (recovery().prepare(command()) as GatewayOutcome.Failure).category)
        assertTrue(intent().requiresReconciliation)
    }

    @Test fun staleOrForeignCommandsAreRefusedBeforeAnyPeerRead() = runBlocking {
        seed()
        assertReason(MutationPreparationActionRequiredReason.REVISION_MISMATCH,
            recovery().prepare(command(revision = 2)))
        assertEquals(GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            (recovery().prepare(command(account = "another-owner")) as GatewayOutcome.Failure).category)
        database.outboxDao().upsert(intent().copy(requiresReconciliation = false))
        assertReason(MutationPreparationActionRequiredReason.REVISION_MISMATCH, recovery().prepare(command()))
        assertEquals(0, inventoryReads)
        assertEquals(0, cardReads)
    }

    @Test fun cancellationPropagatesWithoutReopeningIntent() = runBlocking {
        seed()
        remote = listOf(card("other", "other"))
        afterCardRead = { throw CancellationException("synthetic cancellation") }
        val before = intent()
        try {
            recovery().prepare(command())
            fail("Cancellation expected")
        } catch (_: CancellationException) { }
        assertEquals(before, intent())
    }

    private fun recovery() = RoomAmbiguousContactCreateRecovery(ACCOUNT, database,
        ProtonContactInventoryGateway { _, cursor ->
            assertNull(cursor)
            inventoryReads++
            GatewayOutcome.Success(ContactInventoryPage(remote.map { card -> ContactInventoryMetadata(
                card.id, null, RemoteVersion("public-${card.id.value}-$inventoryVersion"), null, null,
                emptyList(), emptyList(), ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT,
                ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY,
            ) }, null, null, remote.size, authority))
        }, ProtonVerifiedContactCardGateway { _, id ->
            cardReads++
            val failure = cardFailure
            if (failure != null) GatewayOutcome.Failure(failure)
            else {
                val card = remote.single { it.id == id }
                afterCardRead()
                GatewayOutcome.Success(card)
            }
        })

    private suspend fun seed(deleted: Boolean = false): CanonicalContact {
        val contact = CanonicalContact(ACCOUNT.value, LOCAL_ID, displayName = "Newer local name",
            revision = 3, pendingMutationRevision = 3, remoteVCardUid = UID, isDeleted = deleted,
            values = listOf(ContactValue("phone", ContactValueKind.PHONE, "+12025550123", order = 0),
                ContactValue("note", ContactValueKind.NOTE, "Retained newer note", order = 1)),
            preservationEnvelope = PreservationEnvelope(mapOf("X-OPAQUE" to "retained opaque value")))
        database.contactDao().upsert(contact.toEntity())
        database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
        database.contactPayloadDao().upsert(requireNotNull(contact.preservationEnvelope).toEntity(ACCOUNT.value, LOCAL_ID))
        database.outboxDao().upsert(OutboxMutationEntity(ACCOUNT.value, AggregateType.CONTACT.name, LOCAL_ID,
            if (deleted) MutationOperation.DELETE.name else MutationOperation.UPSERT.name, 3, 10, 20,
            attemptCount = 1, idempotencyKey = "synthetic-create-intent", state = DurableMutationState.IN_FLIGHT.name,
            requiresReconciliation = true))
        // Reading without payload is intentional in the unchanged row oracle below.
        return contact.copy(preservationEnvelope = null)
    }

    private suspend fun intent() = requireNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, LOCAL_ID))
    private suspend fun contact() = requireNotNull(database.contactDao().get(ACCOUNT.value, LOCAL_ID)).toDomain()
    private suspend fun payload() = database.contactPayloadDao().get(requireNotNull(database.contactDao()
        .getEntity(ACCOUNT.value, LOCAL_ID)).ownerKey)?.toDomain()

    private suspend fun command(account: String = ACCOUNT.value, revision: Long = 3) = intent().let { row ->
        DurableMutationCommand(account, AggregateType.CONTACT.name, LOCAL_ID, revision, row.remoteOperation(),
            row.attemptCount, row.nextAttemptAtEpochMillis, requiresReconciliation = true)
    }

    private fun card(id: String, uid: String) = VerifiedContactCard(RemoteContactId(id), RemoteVersion("full-$id"),
        CanonicalContact(ACCOUNT.value, id, displayName = "Remote $id", remoteContactId = id, remoteVCardUid = uid))

    private fun success(result: GatewayOutcome<MutationPreparation>) = (result as GatewayOutcome.Success).value
    private fun assertReason(expected: MutationPreparationActionRequiredReason, result: GatewayOutcome<MutationPreparation>) {
        assertEquals(expected, (success(result) as MutationPreparation.ActionRequired).reason)
    }

    private companion object {
        const val DATABASE = "ambiguous-contact-create-recovery.db"
        const val LOCAL_ID = "synthetic-local-create"
        const val UID = "synthetic-stable-uid"
        val ACCOUNT = AccountScope("synthetic-create-owner")
    }
}
