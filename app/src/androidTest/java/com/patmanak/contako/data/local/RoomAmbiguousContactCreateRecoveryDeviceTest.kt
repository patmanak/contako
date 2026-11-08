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
import com.patmanak.contako.data.sync.MutationRetryPolicy
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
    private var cardFailures = emptyMap<String, GatewayFailureCategory>()
    private var failInventoryOn = 0
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
        assertEquals(3, cardReads)
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
        assertEquals(GatewayFailureCategory.RECONCILIATION_INCOMPLETE, result.category)
        assertEquals(cardFailure, result.recoveryBlockerCategory)
        assertEquals(before, intent())
        assertEquals(2, inventoryReads)
    }

    @Test fun unreadableCardDoesNotHideAnAlreadyBoundPositiveIdentity() = runBlocking {
        val local = seed()
        val delivered = VerifiedContactCard(RemoteContactId("delivered"), RemoteVersion("full-delivered"),
            local.copy(id = "remote-local", remoteContactId = "delivered", remoteVCardUid = UID,
                pendingMutationRevision = null, preservationEnvelope = payload()))
        // Normal import has already established this exact remote ID, but not unique delivery.
        RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT, delivered)
        remote = listOf(card("unreadable", "other"), delivered)
        cardFailures = mapOf("unreadable" to GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED)
        val helper = recovery()
        var uploads = 0
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway(ACCOUNT, database,
                com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader { _, _ -> error("No group read") },
                createRecovery = helper), MutationUploadGateway { uploads++; error("No duplicate create") })
        runner.drain(ACCOUNT.value, 1_000)
        assertEquals(0, uploads)
        assertEquals(2, cardReads)
        assertEquals(2, inventoryReads)
        assertEquals("delivered", contact().remoteContactId)
        assertNull(contact().pendingMutationRevision)
        assertNull(contact().conflictState)
    }

    @Test fun unboundVisibleUidCannotProveUniquenessWhenAnotherCardIsUnreadable() = runBlocking {
        val initial = seed()
        remote = listOf(card("unreadable", "other"), card("delivered", UID))
        cardFailures = mapOf("unreadable" to GatewayFailureCategory.MALFORMED_RESPONSE)
        val before = intent()
        assertReason(MutationPreparationActionRequiredReason.RECONCILIATION_CREATION_MATCH_UNVERIFIED,
            recovery().prepare(command()))
        assertEquals(2, cardReads)
        assertEquals(before, intent())
        assertEquals(initial, contact())
        assertNull(RoomAmbiguousContactCreateChoiceStore(database).offerRevision(ACCOUNT, LOCAL_ID))
    }

    @Test fun sameDeliveredPartialCreateRetainsInvalidInputWithoutFalseConflict() = runBlocking {
        val initial = seed()
        val local = initial.copy(preservationEnvelope = payload(), actionRequiredReasons = setOf("INVALID_EMAIL"),
            values = initial.values + ContactValue("withheld-email", ContactValueKind.EMAIL, "invalid@", order = 2))
        database.contactDao().upsert(local.toEntity())
        database.contactDao().upsertValues(local.values.map { it.toEntity(local) })
        val overlays = RoomContactUploadOverlays(database)
        overlays.saveLocal(null, local)
        val sent = requireNotNull(overlays.wireProjection(local))
        assertTrue(sent.values.none { it.kind == ContactValueKind.EMAIL })
        remote = listOf(VerifiedContactCard(RemoteContactId("delivered"), RemoteVersion("full-delivered"),
            sent.copy(id = "remote-local", remoteContactId = "delivered", remoteVCardUid = UID,
                pendingMutationRevision = null)))
        assertSame(MutationPreparation.ResolvedWithoutUpload, success(recovery().prepare(command())))
        assertEquals("invalid@", contact().valuesOf(ContactValueKind.EMAIL).single().value)
        assertTrue("INVALID_EMAIL" in contact().actionRequiredReasons)
        assertEquals("delivered", contact().remoteContactId)
        assertNull(contact().pendingMutationRevision)
        assertNull(contact().conflictState)
        assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, LOCAL_ID))
        assertTrue(overlays.has(ACCOUNT.value, LOCAL_ID))
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
        assertEquals(GatewayFailureCategory.RECONCILIATION_INCOMPLETE,
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

    @Test fun rateLimitDuringCreateOrDeleteRecoveryNeverDropsEarlierDeliveryAmbiguity() = runBlocking {
        for (deleted in listOf(false, true)) for (boundary in listOf("first-index", "card", "second-index")) {
            database.clearAllTables()
            seed(deleted)
            inventoryReads = 0; cardReads = 0
            remote = listOf(card("delivered", UID))
            failInventoryOn = when (boundary) { "first-index" -> 1; "second-index" -> 2; else -> 0 }
            cardFailure = if (boundary == "card") GatewayFailureCategory.RATE_LIMITED else null
            var uploads = 0
            fun runner(): DurableMutationOrchestrator {
                val helper = recovery()
                val preparation = com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway(
                    ACCOUNT, database,
                    com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader { _, _ ->
                        error("No group read belongs to this contact recovery")
                    }, createRecovery = helper,
                )
                return DurableMutationOrchestrator(RoomMutationExecutionStore(database), preparation,
                    MutationUploadGateway { uploads++; error("No blind POST or DELETE is allowed") })
            }
            assertEquals(1, runner().drain(ACCOUNT.value, 1_000).retryWaiting)
            assertTrue("$deleted/$boundary", intent().requiresReconciliation)
            assertEquals(0, uploads)
            val next = intent().nextAttemptAtEpochMillis
            assertTrue(next > 1_000)
            database.close(); database = ContakoDatabase.create(context, DATABASE)
            runner().drain(ACCOUNT.value, next - 1)
            assertTrue(intent().requiresReconciliation)
            failInventoryOn = 0; cardFailure = null
            runner().drain(ACCOUNT.value, next)
            assertEquals(0, uploads)
            assertEquals("delivered", contact().remoteContactId)
            assertEquals(deleted, contact().isDeleted)
            assertNotNull(contact().pendingMutationRevision)
        }
    }

    @Test fun rateLimitOnRecoveryInventoryPreservesServerDeadline() = runBlocking {
        seed(); failInventoryOn = 1
        val helper = recovery()
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            MutationPreparationGateway { helper.prepare(it) }, MutationUploadGateway { error("Unexpected upload") })
        runner.drain(ACCOUNT.value, 1_000)
        assertEquals(91_000L, intent().nextAttemptAtEpochMillis)
        assertTrue(intent().requiresReconciliation)
        runner.drain(ACCOUNT.value, 90_999)
        assertEquals(1, inventoryReads)
    }

    @Test fun provedAbsenceThenRefusedPostDoesNotRestoreTheCapturedCommandsOldAmbiguity() = runBlocking {
        seed()
        val helper = recovery()
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            MutationPreparationGateway { helper.prepare(it) }, MutationUploadGateway {
                GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 90_000)
            })
        assertEquals(1, runner.drain(ACCOUNT.value, 1_000).retryWaiting)
        assertFalse(intent().requiresReconciliation)
        assertEquals(91_000L, intent().nextAttemptAtEpochMillis)
        assertNotNull(contact().pendingMutationRevision)
    }

    @Test fun sameDeliveredCreateConvergesWithoutConflictOrAnotherPost() = runBlocking {
        val local = seed()
        remote = listOf(VerifiedContactCard(RemoteContactId("delivered"), RemoteVersion("full-delivered"),
            local.copy(id = "remote-local", remoteContactId = "delivered", remoteVCardUid = UID,
                pendingMutationRevision = null, preservationEnvelope = payload())))
        assertSame(MutationPreparation.ResolvedWithoutUpload, success(recovery().prepare(command())))
        assertEquals("delivered", contact().remoteContactId)
        assertNull(contact().pendingMutationRevision)
        assertNull(contact().conflictState)
        assertNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, LOCAL_ID))
    }

    @Test fun sameDeliveredCreateAlreadyCapturedDuringInventoryCanBeRecheckedWithoutAFalseConflict() = runBlocking {
        val local = seed()
        val delivered = VerifiedContactCard(RemoteContactId("delivered"), RemoteVersion("full-delivered"),
            local.copy(id = "remote-local", remoteContactId = "delivered", remoteVCardUid = UID,
                pendingMutationRevision = null, preservationEnvelope = payload()))
        remote = listOf(delivered)
        // The normal import can find the UID before recovery's unique account scan.
        RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT, delivered)
        assertNotNull(contact().conflictState)
        var uploads = 0
        val helper = recovery()
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway(ACCOUNT, database,
                com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader { _, _ -> error("No group read") },
                createRecovery = helper), MutationUploadGateway { uploads++; error("No duplicate POST") })
        runner.drain(ACCOUNT.value, 1_000)
        assertEquals(0, uploads)
        assertNull(contact().pendingMutationRevision)
        assertNull(contact().conflictState)
        assertEquals("delivered", contact().remoteContactId)
    }

    @Test fun severalCapturedAbsentCreatesShareOneScanWithoutCachingAbsenceForAnotherIntent() = runBlocking {
        val local = seed()
        val first = intent()
        for (i in 1..3) {
            val other = local.copy(id = "$LOCAL_ID-$i", remoteVCardUid = "$UID-$i", preservationEnvelope = payload())
            database.contactDao().upsert(other.toEntity())
            database.contactDao().upsertValues(other.values.map { it.toEntity(other) })
            other.preservationEnvelope?.let { database.contactPayloadDao().upsert(it.toEntity(ACCOUNT.value, other.id)) }
            database.outboxDao().upsert(first.copy(aggregateId = other.id,
                idempotencyKey = "intent-$i", state = DurableMutationState.PENDING.name))
        }
        remote = (1..4).map { card("unrelated-$it", "other-uid-$it") }
        var uploads = 0
        var scanAvailable = true
        val helper = recovery { scanAvailable.also { scanAvailable = false } }
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway(ACCOUNT, database,
                com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader { _, _ -> error("No group read") },
                createRecovery = helper), MutationUploadGateway { mutation ->
                    uploads++
                    remote = remote + card("remote-${mutation.aggregateId}",
                        requireNotNull(database.contactDao().getEntity(ACCOUNT.value, mutation.aggregateId)).remoteVCardUid!!)
                    GatewayOutcome.Success(RemoteMutationAcknowledgement("remote-${mutation.aggregateId}", "version-$uploads"))
                })
        runner.drain(ACCOUNT.value, 1_000)
        assertEquals(4, uploads)
        assertEquals(2, inventoryReads)
        assertEquals(4, cardReads)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        // A new intent after that cut receives no inherited proof or cached absence.
        seed()
        assertSame(MutationPreparation.WaitingForDependencies, success(helper.prepare(command())))
        assertTrue(intent().requiresReconciliation)
        assertEquals(2, inventoryReads)
    }

    @Test fun anotherCapturedIntentChangedDuringScanKeepsItsAmbiguityAndDeadline() = runBlocking {
        val local = seed()
        val other = local.copy(id = "$LOCAL_ID-other", remoteVCardUid = "$UID-other")
        database.contactDao().upsert(other.toEntity())
        val otherIntent = intent().copy(aggregateId = other.id, idempotencyKey = "other-intent",
            state = DurableMutationState.PENDING.name, nextAttemptAtEpochMillis = 87_000)
        database.outboxDao().upsert(otherIntent)
        remote = listOf(card("unrelated", "other-uid"))
        afterCardRead = {
            database.outboxDao().upsert(otherIntent.copy(nextAttemptAtEpochMillis = 99_000))
        }
        assertSame(MutationPreparation.UploadAllowed, success(recovery(clock = { 88_000 }).prepare(command())))
        val untouched = requireNotNull(database.outboxDao().get(ACCOUNT.value, AggregateType.CONTACT.name, other.id))
        assertTrue(untouched.requiresReconciliation)
        assertEquals(99_000L, untouched.nextAttemptAtEpochMillis)
        assertEquals(1, untouched.attemptCount)
    }

    @Test fun explicitCreateChoiceIsRevisionBoundDurableAndKeepsAttemptHistoryAndDeadline() = runBlocking {
        seed()
        val blocked = intent().copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = "REMOTE_ACTION_REQUIRED", errorCategory = "RECONCILIATION_INCOMPLETE",
            attemptCount = 8, nextAttemptAtEpochMillis = 9_000)
        database.outboxDao().upsert(blocked)
        val choices = RoomAmbiguousContactCreateChoiceStore(database)
        assertEquals(3L, choices.offerRevision(ACCOUNT, LOCAL_ID))
        assertFalse(choices.confirm(ACCOUNT, LOCAL_ID, 2))
        assertTrue(choices.confirm(ACCOUNT, LOCAL_ID, 3))
        assertTrue(intent().requiresReconciliation)
        assertEquals(8, intent().attemptCount)
        assertEquals(9_000L, intent().nextAttemptAtEpochMillis)
        database.close(); database = ContakoDatabase.create(context, DATABASE)
        assertFalse(RoomOutboxStore(database).claim(intent(), 8_999))
        assertTrue(RoomOutboxStore(database).claim(intent(), 9_000))
        assertSame(MutationPreparation.UploadAllowed, success(recovery().prepare(command())))
        assertFalse(intent().requiresReconciliation)
        assertEquals(8, intent().attemptCount)
        assertEquals(9_000L, intent().nextAttemptAtEpochMillis)
        assertEquals(0, inventoryReads)
        assertNull(intent().errorCategory)
    }

    @Test fun explicitCreateChoiceIsUnavailableForKnownOrDuplicateIdentityAndOtherFailures() = runBlocking {
        seed()
        val blocked = intent().copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = "REMOTE_ACTION_REQUIRED", errorCategory = "RECONCILIATION_INCOMPLETE", attemptCount = 8)
        val original = requireNotNull(database.contactDao().getEntity(ACCOUNT.value, LOCAL_ID))
        val choices = RoomAmbiguousContactCreateChoiceStore(database)
        for (row in listOf(blocked.copy(errorCategory = "TIMEOUT"), blocked.copy(attemptCount = 7),
                blocked.copy(requiresReconciliation = false), blocked.copy(operation = MutationOperation.DELETE.name),
                blocked.copy(remoteIdentity = "known"), blocked.copy(revision = 2))) {
            database.outboxDao().upsert(row)
            assertNull(choices.offerRevision(ACCOUNT, LOCAL_ID))
            assertFalse(choices.confirm(ACCOUNT, LOCAL_ID, 3))
        }
        database.outboxDao().upsert(blocked)
        database.contactDao().upsert(original.copy(remoteContactId = "known"))
        assertNull(choices.offerRevision(ACCOUNT, LOCAL_ID))
        database.contactDao().upsert(original)
        database.contactDao().upsert(original.copy(id = "duplicate-owner",
            ownerKey = ownerKey(ACCOUNT.value, "duplicate-owner")))
        val owners = database.contactDao().getEntitiesByStableCreateUid(ACCOUNT.value, UID)
        assertEquals(2, owners.size)
        assertEquals(setOf(LOCAL_ID, "duplicate-owner"), owners.map { it.id }.toSet())
        assertTrue(owners.all { it.remoteVCardUid == UID })
        assertNull(choices.offerRevision(ACCOUNT, LOCAL_ID))
    }

    @Test fun localEditInvalidatesConfirmedCreateChoiceWithoutClearingDeliveryAmbiguity() = runBlocking {
        seed()
        database.outboxDao().upsert(intent().copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = "REMOTE_ACTION_REQUIRED", errorCategory = "RECONCILIATION_INCOMPLETE", attemptCount = 8))
        assertTrue(RoomAmbiguousContactCreateChoiceStore(database).confirm(ACCOUNT, LOCAL_ID, 3))
        val changed = contact().copy(displayName = "Edited after duplicate-risk acknowledgement", preservationEnvelope = payload())
        val saved = RoomContactRepository(database).saveContact(changed)
        assertTrue(saved is com.patmanak.contako.domain.repository.SaveResult.Saved)
        val current = intent()
        assertTrue(current.revision > 3)
        assertTrue(current.requiresReconciliation)
        assertNull(current.errorCategory)
        assertNull(current.blockedReason)
        assertFalse(RoomAmbiguousContactCreateChoiceStore(database).confirmedStillCurrent(ACCOUNT, LOCAL_ID, 3))
        assertReason(MutationPreparationActionRequiredReason.REVISION_MISMATCH,
            recovery().prepare(command(revision = 3)))
        assertEquals(0, inventoryReads)
    }

    @Test fun oneStableIncompleteScanChargesEachCapturedCreateOnceAndExhaustsTheBatchTogether() = runBlocking {
        val local = seed()
        database.outboxDao().upsert(intent().copy(state = DurableMutationState.PENDING.name))
        repeat(24) { seedSibling(local, "batch-$it") }
        remote = listOf(card("unreadable", "unrelated-uid"))
        cardFailure = GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED
        var now = 1_000L
        repeat(8) { pass ->
            var available = true
            val helper = recovery(clock = { now }) { available.also { available = false } }
            val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
                MutationPreparationGateway { helper.prepare(it) }, MutationUploadGateway { error("No blind POST") })
            runner.drain(ACCOUNT.value, now)
            val rows = database.outboxDao().getAll(ACCOUNT.value)
            assertEquals(25, rows.size)
            rows.forEach { row ->
                assertEquals((pass + 2).coerceAtMost(8), row.attemptCount)
                assertEquals(now, row.lastAttemptAtEpochMillis)
                assertTrue(row.requiresReconciliation)
                assertEquals(GatewayFailureCategory.RECONCILIATION_INCOMPLETE.name, row.errorCategory)
                assertEquals(if (pass == 7) DurableMutationState.ACTION_REQUIRED.name else DurableMutationState.PENDING.name, row.state)
            }
            assertEquals((pass + 1) * 2, inventoryReads)
            assertEquals(pass + 1, cardReads)
            runner.drain(ACCOUNT.value, now)
            assertEquals(rows, database.outboxDao().getAll(ACCOUNT.value)) // No second charge in the same pass.
            if (pass < 7) {
                val due = rows.maxOf { it.nextAttemptAtEpochMillis }
                assertTrue(due > now)
                database.close()
                database = ContakoDatabase.create(context, DATABASE)
                val beforeDue = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
                    MutationPreparationGateway { error("No scan before the retained deadline") },
                    MutationUploadGateway { error("No blind POST") })
                assertEquals(0, beforeDue.drain(ACCOUNT.value, now).examined)
                now = due
            }
        }
        assertEquals(16, inventoryReads)
        assertEquals(8, cardReads)
        database.outboxDao().getAll(ACCOUNT.value).forEach { row ->
            assertEquals(row.revision, RoomAmbiguousContactCreateChoiceStore(database).offerRevision(ACCOUNT, row.aggregateId))
        }
    }

    @Test fun sharedIncompleteFailureExcludesChangedFutureBlockedNewAndVisibleCandidates() = runBlocking {
        val local = seed()
        database.outboxDao().upsert(intent().copy(lastAttemptAtEpochMillis = 1_000))
        val before = linkedMapOf<String, OutboxMutationEntity>()
        for (id in listOf("unchanged", "canonical", "payload", "intent", "future", "blocked", "action", "visible")) {
            val row = seedSibling(local, id).copy(
                nextAttemptAtEpochMillis = if (id == "future") 2_000 else 0,
                state = if (id == "action") DurableMutationState.ACTION_REQUIRED.name else DurableMutationState.PENDING.name,
                blockedReason = if (id == "blocked" || id == "action") "REMOTE_ACTION_REQUIRED" else null)
            database.outboxDao().upsert(row)
            before[id] = row
        }
        remote = listOf(card("unreadable", "unrelated-uid"), card("visible-match", "$UID-visible"))
        cardFailures = mapOf("unreadable" to GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED)
        var mutated = false
        afterCardRead = {
            if (!mutated) {
                mutated = true
                val canonical = requireNotNull(database.contactDao().getEntity(ACCOUNT.value, "canonical"))
                database.contactDao().upsert(canonical.copy(displayName = "Changed without revision bump"))
                database.contactPayloadDao().upsert(PreservationEnvelope(mapOf("X-OPAQUE" to "Changed preserved payload"))
                    .toEntity(ACCOUNT.value, "payload"))
                database.outboxDao().upsert(before.getValue("intent").copy(nextAttemptAtEpochMillis = 99_000))
                seedSibling(local, "new-after-cut")
            }
        }
        val outcome = recovery(clock = { 90_000 }).prepare(command()) as GatewayOutcome.Failure
        assertEquals(GatewayFailureCategory.RECONCILIATION_INCOMPLETE, outcome.category)
        val charged = requireNotNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", "unchanged"))
        assertEquals(2, charged.attemptCount)
        assertEquals(1_000L, charged.lastAttemptAtEpochMillis) // The claimed scan time, not the later wall clock.
        assertTrue(charged.nextAttemptAtEpochMillis > 1_000)
        for (id in listOf("canonical", "payload", "future", "blocked", "action")) {
            assertEquals(before.getValue(id), database.outboxDao().get(ACCOUNT.value, "CONTACT", id))
        }
        assertEquals(before.getValue("intent").copy(nextAttemptAtEpochMillis = 99_000),
            database.outboxDao().get(ACCOUNT.value, "CONTACT", "intent"))
        assertEquals(1, database.outboxDao().get(ACCOUNT.value, "CONTACT", "new-after-cut")!!.attemptCount)
        assertEquals(1, database.outboxDao().get(ACCOUNT.value, "CONTACT", "visible")!!.attemptCount)
        assertTrue(database.outboxDao().get(ACCOUNT.value, "CONTACT", "visible")!!.requiresReconciliation)
        assertNull(database.contactDao().getEntity(ACCOUNT.value, "visible")!!.remoteContactId)
        assertEquals(1, intent().attemptCount) // Current failure belongs to the orchestrator, not the scan helper.
        assertEquals(2, inventoryReads)
        assertEquals(2, cardReads)
    }

    @Test fun oneScanNeverChargesMoreThanTwentyFiveExactCapturedCreates() = runBlocking {
        val local = seed()
        repeat(30) { seedSibling(local, "bounded-$it") }
        remote = listOf(card("unreadable", "unrelated-uid"))
        cardFailure = GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED
        val before = database.outboxDao().getAll(ACCOUNT.value)
        assertEquals(GatewayFailureCategory.RECONCILIATION_INCOMPLETE,
            (recovery().prepare(command()) as GatewayOutcome.Failure).category)
        val after = database.outboxDao().getAll(ACCOUNT.value)
        val changed = after.filter { row -> row != before.single { it.aggregateId == row.aggregateId } }
        assertEquals(24, changed.size) // The 25th target is the current claim, charged only by its orchestrator.
        assertTrue(changed.all { it.attemptCount == 2 && it.requiresReconciliation })
        assertEquals(7, after.count { it.attemptCount == 1 })
        assertEquals(2, inventoryReads)
        assertEquals(1, cardReads)
    }

    @Test fun networkAccountRateLimitAndUnstableInventoryFailuresNeverChargeOtherCapturedCreates() = runBlocking {
        for (category in listOf(GatewayFailureCategory.TIMEOUT, GatewayFailureCategory.NETWORK_UNAVAILABLE,
                GatewayFailureCategory.AUTHENTICATION_REQUIRED, GatewayFailureCategory.RATE_LIMITED,
                GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED)) {
            database.clearAllTables()
            val local = seed()
            val untouched = seedSibling(local, "untouched")
            inventoryReads = 0; cardReads = 0; failInventoryOn = 0
            remote = listOf(card("unreadable", "unrelated-uid"))
            cardFailure = category
            // An incomplete card followed by a rejected second index is not stable scan evidence.
            if (category == GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED) failInventoryOn = 2
            assertEquals(if (category == GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED)
                GatewayFailureCategory.RATE_LIMITED else category, (recovery().prepare(command()) as GatewayOutcome.Failure).category)
            assertEquals(untouched, database.outboxDao().get(ACCOUNT.value, "CONTACT", "untouched"))
            assertEquals(1, intent().attemptCount)
            assertTrue(intent().requiresReconciliation)
        }
    }

    private suspend fun seedSibling(local: CanonicalContact, id: String): OutboxMutationEntity {
        val other = local.copy(id = id, remoteVCardUid = "$UID-$id",
            preservationEnvelope = PreservationEnvelope(mapOf("X-OPAQUE" to "Preserved $id")))
        database.contactDao().upsert(other.toEntity())
        database.contactDao().upsertValues(other.values.map { it.toEntity(other) })
        database.contactPayloadDao().upsert(requireNotNull(other.preservationEnvelope).toEntity(ACCOUNT.value, id))
        val row = intent().copy(aggregateId = id, idempotencyKey = "intent-$id", state = DurableMutationState.PENDING.name)
        database.outboxDao().upsert(row)
        return row
    }

    private fun recovery(clock: () -> Long = { 1_000 }, retryPolicy: MutationRetryPolicy = MutationRetryPolicy(),
        permit: () -> Boolean = { true }) = RoomAmbiguousContactCreateRecovery(ACCOUNT, database,
        ProtonContactInventoryGateway { _, cursor ->
            assertNull(cursor)
            inventoryReads++
            if (inventoryReads == failInventoryOn) return@ProtonContactInventoryGateway GatewayOutcome.Failure(
                GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 90_000)
            GatewayOutcome.Success(ContactInventoryPage(remote.map { card -> ContactInventoryMetadata(
                card.id, null, RemoteVersion("public-${card.id.value}-$inventoryVersion"), null, null,
                emptyList(), emptyList(), ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT,
                ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY,
            ) }, null, null, remote.size, authority))
        }, ProtonVerifiedContactCardGateway { _, id ->
            cardReads++
            val failure = cardFailures[id.value] ?: cardFailure
            if (failure != null) GatewayOutcome.Failure(failure,
                retryAfterMillis = if (failure == GatewayFailureCategory.RATE_LIMITED) 90_000 else null)
            else {
                val card = remote.single { it.id == id }
                afterCardRead()
                GatewayOutcome.Success(card)
            }
        }, scanPermit = permit, clock = clock, retryPolicy = retryPolicy)

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
