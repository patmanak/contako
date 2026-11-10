package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.ContactMutation
import com.patmanak.contako.data.gateway.ContactMutationReceipt
import com.patmanak.contako.data.gateway.EmailLabelMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonContactMutationGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.data.proton.parseSingleCompleteVCard
import com.patmanak.contako.data.proton.AuthoritativeEmailGroupMembership
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader
import com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway
import com.patmanak.contako.data.sync.RoomBackedMutationUploadGateway
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.data.sync.DurableMutationOrchestrator
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.data.sync.RetryDecision
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import com.patmanak.contako.data.proton.ContactInventoryCheckpointStore
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.VersionedContactInventoryCheckpoint
import com.patmanak.contako.domain.sync.*
import com.patmanak.contako.android.sync.AndroidSyncCompletionClassification
import com.patmanak.contako.android.sync.classifyAndroidSyncCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackedMutationGatewaysDeviceTest {
    @Test fun delete404KeepsTheSameDurableIntentUntilTargetedAbsenceIsConfirmed() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "delete-404", displayName = "Disposable"))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        val created = requireNotNull(repository.getContact(ACCOUNT.value, "delete-404"))
        repository.deleteContact(ACCOUNT.value, created.id)
        val intent = database.outboxDao().getAll(ACCOUNT.value).single()
        var absenceConfirmed = false
        val existence = com.patmanak.contako.data.gateway.ProtonContactExistenceGateway { account, id ->
            assertEquals(ACCOUNT, account)
            assertEquals(created.remoteContactId, id.value)
            GatewayOutcome.Success(if (absenceConfirmed)
                com.patmanak.contako.data.gateway.RemoteContactPresence.CONFIRMED_ABSENT
            else com.patmanak.contako.data.gateway.RemoteContactPresence.PRESENT)
        }
        var deleteAttempts = 0
        val upload = RoomBackedMutationUploadGateway(ACCOUNT, database,
            object : ProtonContactMutationGateway {
                override suspend fun apply(account: AccountScope, mutation: ContactMutation): GatewayOutcome<ContactMutationReceipt> {
                    assertTrue(mutation is ContactMutation.Delete)
                    deleteAttempts++
                    return GatewayOutcome.Failure(GatewayFailureCategory.NOT_FOUND)
                }
            }, remote, remote, remote)
        fun runner() = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            RoomBackedMutationPreparationGateway(ACCOUNT, database, remote, remote, existence), upload)

        assertEquals(1, runner().drain(ACCOUNT.value, 1_000).retryWaiting)
        val pending = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(intent.revision, pending.revision)
        assertEquals(intent.remoteIdentity, pending.remoteIdentity)
        assertEquals("DELETE", pending.operation)
        assertTrue(pending.requiresReconciliation)
        assertEquals(intent.revision, database.contactDao().get(ACCOUNT.value, created.id)?.contact?.pendingMutationRevision)

        // Persistence and a new orchestrator cannot turn the generic 404 into acknowledgement.
        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        absenceConfirmed = true
        assertEquals(1, runner().drain(ACCOUNT.value, pending.nextAttemptAtEpochMillis).reconciledWithoutUpload)
        assertEquals(1, deleteAttempts)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        val tombstone = requireNotNull(database.contactDao().get(ACCOUNT.value, created.id)).contact
        assertTrue(tombstone.isDeleted)
        assertNull(tombstone.pendingMutationRevision)
    }

    @Test fun deleteAfterRemoteDeletionRequiresFreshAbsenceProof() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "deleted-remote", displayName = "Disposable"))
        cleanContact("deleted-remote", "absent-remote")
        val conflicted = requireNotNull(database.contactDao().get(ACCOUNT.value, "deleted-remote")).contact
        database.contactDao().upsert(conflicted.copy(conflictState = "REMOTE_DELETION_RECOVERY_REQUIRED"))
        repository.deleteContact(ACCOUNT.value, "deleted-remote")
        assertNull(database.contactDao().get(ACCOUNT.value, "deleted-remote")?.contact?.conflictState)
        val store = RoomMutationExecutionStore(database)
        val command = store.eligible(ACCOUNT.value, 1_000, 20).single()
        var reads = 0
        var presence: GatewayOutcome<com.patmanak.contako.data.gateway.RemoteContactPresence> =
            GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE)
        val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote, remote,
            com.patmanak.contako.data.gateway.ProtonContactExistenceGateway { _, id ->
                assertEquals("absent-remote", id.value)
                reads++
                presence
            })
        assertEquals(presence, preparation.prepare(command))
        assertEquals(command.revision, database.outboxDao().getAll(ACCOUNT.value).single().revision)
        presence = GatewayOutcome.Success(com.patmanak.contako.data.gateway.RemoteContactPresence.PRESENT)
        // The full card is absent in this fake: presence alone MUST NOT acknowledge.
        assertTrue(preparation.prepare(command) is GatewayOutcome.Failure)
        presence = GatewayOutcome.Success(com.patmanak.contako.data.gateway.RemoteContactPresence.CONFIRMED_ABSENT)
        val result = preparation.prepare(command) as GatewayOutcome.Success
        val applied = result.value as com.patmanak.contako.data.sync.MutationPreparation.AlreadyApplied
        assertEquals(3, reads)
        assertTrue(store.claim(command, 1_000))
        assertTrue(store.acknowledgeAndFinish(command, applied.acknowledgement))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        val tombstone = requireNotNull(database.contactDao().get(ACCOUNT.value, "deleted-remote")).contact
        assertTrue(tombstone.isDeleted)
        assertNull(tombstone.pendingMutationRevision)
        assertNull(tombstone.conflictState)
        assertEquals(0, remote.contactWrites)
    }
    @Test fun blockedDeleteResumesOnlyAfterFreshAbsenceProof() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "blocked-delete", displayName = "Disposable"))
        cleanContact("blocked-delete", "absent-remote")
        repository.deleteContact(ACCOUNT.value, "blocked-delete")
        val intent = database.outboxDao().getAll(ACCOUNT.value).single()
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = "REMOTE_ACTION_REQUIRED", errorCategory = "VALIDATION_REJECTED"))
        var presence: GatewayOutcome<com.patmanak.contako.data.gateway.RemoteContactPresence> =
            GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE)
        val store = RoomMutationExecutionStore(database,
            com.patmanak.contako.data.gateway.ProtonContactExistenceGateway { _, _ -> presence })
        assertEquals(0, store.recoverInterrupted(ACCOUNT.value, 1_000))
        assertTrue(store.eligible(ACCOUNT.value, 1_000, 20).isEmpty())
        presence = GatewayOutcome.Success(com.patmanak.contako.data.gateway.RemoteContactPresence.PRESENT)
        assertEquals(0, store.recoverInterrupted(ACCOUNT.value, 1_000))
        presence = GatewayOutcome.Success(com.patmanak.contako.data.gateway.RemoteContactPresence.CONFIRMED_ABSENT)
        assertEquals(1, store.recoverInterrupted(ACCOUNT.value, 1_000))
        assertEquals(intent.revision, store.eligible(ACCOUNT.value, 1_000, 20).single().revision)
        assertEquals(intent.remoteIdentity, database.outboxDao().getAll(ACCOUNT.value).single().remoteIdentity)
    }
    @Test fun groupCreationReceiptSurvivesConcurrentEditAndDelete() = runBlocking {
        for (delete in listOf(false, true)) {
            val id = if (delete) "receipt-delete" else "receipt-edit"
            val draft = ContactGroup(ACCOUNT.value, id, "Original")
            saveGroup(draft)
            val store = RoomMutationExecutionStore(database)
            val command = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateId == id }
            assertTrue(store.claim(command, 1_000))
            if (delete) repository.deleteGroup(ACCOUNT.value, id) else saveGroup(draft.copy(name = "Edited"))
            assertTrue(requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", id)).requiresReconciliation)
            val next = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateId == id }
            val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote).prepare(next)
            assertTrue((preparation as GatewayOutcome.Success).value is com.patmanak.contako.data.sync.MutationPreparation.ActionRequired)
            assertTrue(store.continueAfterPartialProgress(command,
                com.patmanak.contako.data.sync.RemoteMutationAcknowledgement("remote-$id", "created", false,
                    com.patmanak.contako.data.sync.RemoteMutationOperation.ASSIGNMENTS), 1_000))
            val current = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, id)).group
            assertEquals("remote-$id", current.remoteLabelId)
            assertEquals(delete, current.isDeleted)
            val pending = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", id))
            assertEquals(if (delete) "DELETE" else "UPSERT", pending.operation)
            assertEquals("remote-$id", pending.remoteIdentity)
            assertTrue(!pending.requiresReconciliation)
            if (!delete) {
                saveGroup(draft.copy(name = "Older draft saved later"))
                assertEquals("remote-$id", database.contactGroupDao().get(ACCOUNT.value, id)?.group?.remoteLabelId)
            }
        }
    }
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private lateinit var remote: FakeRemoteMutations

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
        remote = FakeRemoteMutations()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun contactCreateUploadsOnceAndAtomicallyCleansCanonicalIntent() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "local-contact", displayName = "Ada"))

        val result = orchestrator().drain(ACCOUNT.value, 1_000)

        assertEquals(1, result.uploaded)
        assertEquals(1, remote.contactWrites)
        val clean = requireNotNull(repository.getContact(ACCOUNT.value, "local-contact"))
        assertEquals("remote-contact-1", clean.remoteContactId)
        assertNull(clean.pendingMutationRevision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun contactCreateUpdateDeleteEachStayDurableUntilProductionGatewayAcknowledges() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "round-trip", displayName = "Create"))
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
        assertTrue(remote.contactMutations.isEmpty())

        val created = orchestrator().drain(ACCOUNT.value, 1_000)
        assertEquals(1, created.uploaded)
        val acknowledged = requireNotNull(repository.getContact(ACCOUNT.value, "round-trip"))
        assertEquals("remote-contact-1", acknowledged.remoteContactId)
        assertNull(acknowledged.pendingMutationRevision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())

        saveContact(acknowledged.copy(displayName = "Update"))
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
        assertEquals(1, remote.contactMutations.size)
        val updated = orchestrator().drain(ACCOUNT.value, 1_000)
        assertEquals(1, updated.uploaded)
        assertEquals("Update", requireNotNull(repository.getContact(ACCOUNT.value, "round-trip")).displayName)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())

        repository.deleteContact(ACCOUNT.value, "round-trip")
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
        assertEquals(2, remote.contactMutations.size)
        val deleted = orchestrator().drain(ACCOUNT.value, 1_000)
        assertEquals(1, deleted.uploaded)
        val tombstone = requireNotNull(repository.getContact(ACCOUNT.value, "round-trip"))
        assertTrue(tombstone.isDeleted)
        assertNull(tombstone.pendingMutationRevision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        assertTrue(remote.contactMutations[0] is ContactMutation.Create)
        assertTrue(remote.contactMutations[1] is ContactMutation.Update)
        assertTrue(remote.contactMutations[2] is ContactMutation.Delete)
    }

    @Test
    fun groupCreateAdvancesDurablyToAssignmentReconciliationWithoutRepeatingCreate() = runBlocking {
        saveGroup(ContactGroup(ACCOUNT.value, "local-group", "Friends"))

        val created = orchestrator().drain(ACCOUNT.value, 1_000)
        assertEquals(1, created.progressPending)
        assertEquals(1, remote.groupCreates)
        var pending = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(MutationOperation.ASSIGNMENTS.name, pending.operation)
        assertEquals("remote-group-1", pending.remoteIdentity)

        val converged = orchestrator().drain(ACCOUNT.value, 1_000)
        // Fresh assignment intent reconciles inside the upload gateway, before any write.
        assertEquals(1, converged.uploaded)
        assertEquals(0, remote.assignmentWrites)
        assertEquals(1, remote.groupCreates)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        assertEquals("remote-group-1", repository.observeGroups(ACCOUNT.value).first().single().remoteLabelId)
    }

    @Test
    fun assignmentReplacementUsesTwoSerialReconciledStepsAndKeepsOneDurableCommand() = runBlocking {
        saveContact(
            CanonicalContact(
                ACCOUNT.value,
                "contact",
                displayName = "Ada",
                values = listOf(
                    ContactValue(
                        "email-value",
                        ContactValueKind.EMAIL,
                        "ada@example.test",
                        order = 0,
                        metadata = mapOf("protonEmailId" to "email-desired"),
                    ),
                ),
            ),
        )
        cleanContact("contact", "remote-contact")
        saveGroup(
            ContactGroup(
                ACCOUNT.value,
                "group",
                "Friends",
                memberships = listOf(GroupMembership("contact", "email-value")),
            ),
        )
        orchestrator().drain(ACCOUNT.value, 1_000)
        remote.memberships = linkedSetOf(RemoteEmailId("email-extra"))

        val add = orchestrator().drain(ACCOUNT.value, 1_000)
        assertEquals(1, add.progressPending)
        assertEquals(1, remote.assignmentWrites)
        // An unmapped remote member keeps the dependency catalog incomplete. With no
        // contact intent left, that conservative state must not forbid replacement.
        assertEquals(false, database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, "group")?.complete)
        assertEquals(
            setOf(RemoteEmailId("email-extra"), RemoteEmailId("email-desired")),
            remote.memberships,
        )
        val remove = orchestrator().drain(ACCOUNT.value, 1_000)

        assertEquals(1, remove.uploaded)
        assertEquals(2, remote.assignmentWrites)
        assertEquals(setOf(RemoteEmailId("email-desired")), remote.memberships)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun ambiguousCreateWithoutRecoveredIdentityBecomesActionRequiredWithoutReplay() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "local-contact", displayName = "Ada"))
        val pending = database.outboxDao().getAll(ACCOUNT.value).single()
        val store = RoomOutboxStore(database)
        assertTrue(store.claim(pending, 1_000))
        assertTrue(
            store.applyRetryDecision(
                pending,
                GatewayFailureCategory.TIMEOUT,
                RetryDecision.RetryAt(1, 1_000, reconcileBeforeReplay = true),
            ),
        )

        val result = orchestrator().drain(ACCOUNT.value, 1_000)

        assertEquals(1, result.actionRequired)
        assertEquals(0, remote.contactWrites)
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, database.outboxDao().getAll(ACCOUNT.value).single().state)
    }

    @Test
    fun assignmentsWaitForContactAndReplacementEmailIdentityRecoversOnlyDependentFailure() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "contact", displayName = "Fixture", values = listOf(
            ContactValue("email-value", ContactValueKind.EMAIL, "fixture@example.test", order = 0,
                metadata = mapOf("protonEmailId" to "removed-email")))))
        cleanContact("contact", "remote-contact")
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Fixture group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("contact", "email-value"))))
        val groupIntent = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        database.outboxDao().upsert(groupIntent.copy(operation = MutationOperation.ASSIGNMENTS.name))
        val store = RoomMutationExecutionStore(database)
        val local = requireNotNull(database.contactDao().get(ACCOUNT.value, "contact")).toDomain()
        saveContact(local.copy(displayName = "Local choice"))
        // A legacy baseline is eligible for the verified GET that qualifies its dependencies.
        assertTrue(store.eligible(ACCOUNT.value, 1_000, 20).any { it.aggregateType == "GROUP" })
        remote.memberships = linkedSetOf(RemoteEmailId("removed-email"))
        val groupCommand = pendingAssignmentCommand("group")
        val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote).prepare(groupCommand)
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.WaitingForDependencies,
            (preparation as GatewayOutcome.Success).value)
        assertTrue(store.eligible(ACCOUNT.value, 1_000, 20).all { it.aggregateType == "CONTACT" })
        assertEquals(0, remote.assignmentWrites)

        // A previous build already attempted an assignment against the deleted service identity.
        assertTrue(store.claim(groupCommand, 1_000))
        assertTrue(store.recordFailure(groupCommand, GatewayFailureCategory.MALFORMED_RESPONSE, RetryDecision.ActionRequired))
        val contactCommand = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "CONTACT" }
        assertTrue(store.claim(contactCommand, 1_000))
        assertTrue(store.acknowledgeAndFinish(contactCommand, com.patmanak.contako.data.sync.RemoteMutationAcknowledgement(
            "remote-contact", "restored", emailIdsByValueId = mapOf("email-value" to "restored-email"))))
        val recovered = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertEquals(DurableMutationState.PENDING.name, recovered.state)
        assertTrue(recovered.requiresReconciliation)
        assertNull(recovered.errorCategory)
        val next = store.eligible(ACCOUNT.value, 1_000, 20).single()
        val allowed = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote).prepare(next)
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.UploadAllowed,
            (allowed as GatewayOutcome.Success).value)
        assertEquals(listOf(RemoteEmailId("restored-email")),
            RoomBackedMutationPreparationGateway(ACCOUNT, database, remote).desiredEmailIds(ACCOUNT.value, "group"))
    }

    @Test
    fun removedAssignmentWaitsForRestoredEmailBeforeAcknowledgingEmptyMembership() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "contact", displayName = "Fixture", values = listOf(
            ContactValue("email-value", ContactValueKind.EMAIL, "fixture@example.test", order = 0,
                metadata = mapOf("protonEmailId" to "removed-email")))))
        cleanContact("contact", "remote-contact")
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Fixture group", remoteLabelId = "remote-group"))
        val pending = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        database.outboxDao().upsert(pending.copy(operation = MutationOperation.ASSIGNMENTS.name))
        val store = RoomMutationExecutionStore(database)
        saveContact(requireNotNull(database.contactDao().get(ACCOUNT.value, "contact")).toDomain()
            .copy(displayName = "Local choice"))
        assertTrue(store.eligible(ACCOUNT.value, 1_000, 20).any { it.aggregateType == "GROUP" })
        // The removed remote edge, not the empty desired set, proves the dependency.
        remote.memberships = linkedSetOf(RemoteEmailId("removed-email"))
        val groupCommand = pendingAssignmentCommand("group")
        val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote)
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.WaitingForDependencies,
            (preparation.prepare(groupCommand) as GatewayOutcome.Success).value)
        assertTrue(store.eligible(ACCOUNT.value, 1_000, 20).all { it.aggregateType == "CONTACT" })
        assertEquals(0, remote.assignmentWrites)
        val contactCommand = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "CONTACT" }
        assertTrue(store.claim(contactCommand, 1_000))
        assertTrue(store.acknowledgeAndFinish(contactCommand, com.patmanak.contako.data.sync.RemoteMutationAcknowledgement(
            "remote-contact", "restored", emailIdsByValueId = mapOf("email-value" to "restored-email"))))
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.UploadAllowed,
            (preparation.prepare(groupCommand) as GatewayOutcome.Success).value)
        assertEquals(emptyList<RemoteEmailId>(), preparation.desiredEmailIds(ACCOUNT.value, "group"))
    }

    @Test
    fun unmappedRemoteGroupMemberWaitsForAllContactWritesThenAllowsVerifiedReplacement() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "unrelated", displayName = "Pending contact"))
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Legacy group", remoteLabelId = "remote-group"))
        val pending = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        database.outboxDao().upsert(pending.copy(operation = MutationOperation.ASSIGNMENTS.name))
        remote.memberships = linkedSetOf(RemoteEmailId("unmapped-email"))
        val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote)
        val command = pendingAssignmentCommand("group")

        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.WaitingForDependencies,
            (preparation.prepare(command) as GatewayOutcome.Success).value)
        assertEquals(false, database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, "group")?.complete)
        assertEquals(0, remote.assignmentWrites)

        cleanContact("unrelated", "unrelated-remote")
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.UploadAllowed,
            (preparation.prepare(command) as GatewayOutcome.Success).value)
        // The fallback is not proof of mapping, nor a premature acknowledgement.
        assertEquals(false, database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, "group")?.complete)
        assertTrue(database.outboxDao().get(ACCOUNT.value, "GROUP", "group") != null)
        val result = orchestrator().drain(ACCOUNT.value, 1_000)
        assertEquals(1, result.uploaded)
        assertEquals(1, remote.assignmentWrites)
        assertTrue(remote.memberships.isEmpty())
        assertEquals(true, database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, "group")?.complete)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun replacementEmailIdentityDoesNotClearUnrelatedAssignmentRejection() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "contact", displayName = "Fixture", values = listOf(
            ContactValue("email-value", ContactValueKind.EMAIL, "fixture@example.test", order = 0,
                metadata = mapOf("protonEmailId" to "old-email")))))
        cleanContact("contact", "remote-contact")
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Fixture group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("contact", "email-value"))))
        saveContact(requireNotNull(database.contactDao().get(ACCOUNT.value, "contact")).toDomain().copy(displayName = "Edited"))
        val intent = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        database.outboxDao().upsert(intent.copy(operation = MutationOperation.ASSIGNMENTS.name,
            state = DurableMutationState.ACTION_REQUIRED.name,
            errorCategory = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED.name, blockedReason = "GROUP_CAPABILITY_REQUIRED"))
        val store = RoomMutationExecutionStore(database)
        val command = store.eligible(ACCOUNT.value, 1_000, 20).single()
        assertTrue(store.claim(command, 1_000))
        assertTrue(store.acknowledgeAndFinish(command, com.patmanak.contako.data.sync.RemoteMutationAcknowledgement(
            "remote-contact", "updated", emailIdsByValueId = mapOf("email-value" to "new-email"))))
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")?.state)
        assertEquals(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED.name,
            database.outboxDao().get(ACCOUNT.value, "GROUP", "group")?.errorCategory)
    }

    @Test
    fun aNewInvalidGroupMemberDoesNotBlockOtherAssignmentsOrLoseItsDurableMembership() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
            ContactValue("unfinished", ContactValueKind.EMAIL, "not-an-address", order = 0))))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        assertNull(database.contactDao().get(ACCOUNT.value, "partial")!!.contact.pendingMutationRevision)
        assertTrue("INVALID_EMAIL" in repository.getContact(ACCOUNT.value, "partial")!!.actionRequiredReasons)
        assertTrue((remote.contactMutations.single() as ContactMutation.Create).contact.values.isEmpty())

        saveContact(CanonicalContact(ACCOUNT.value, "healthy", displayName = "Healthy", values = listOf(
            ContactValue("first", ContactValueKind.EMAIL, "duplicate@example.test", order = 0,
                metadata = mapOf("protonEmailId" to "healthy-first")),
            ContactValue("second", ContactValueKind.EMAIL, "duplicate@example.test", order = 1,
                metadata = mapOf("protonEmailId" to "healthy-second")))))
        cleanContact("healthy", "healthy-remote")
        saveGroup(ContactGroup(ACCOUNT.value, "partial-group", "Partial group", remoteLabelId = "partial-remote",
            memberships = listOf(GroupMembership("partial", "unfinished"), GroupMembership("healthy", "first"))))
        remote.memberships = linkedSetOf(RemoteEmailId("removed-member"))
        repeat(4) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(setOf(RemoteEmailId("healthy-first")), remote.memberships)
        assertEquals(2, remote.assignmentWrites) // Add valid member, then remove the obsolete member.
        var pending = database.outboxDao().get(ACCOUNT.value, "GROUP", "partial-group")!!
        assertEquals(DurableMutationState.PARTIAL.name, pending.state)
        assertNull(pending.blockedReason)
        assertTrue(pending.errorCategory != GatewayFailureCategory.CONFLICT.name)
        assertEquals(0, pending.attemptCount)
        assertEquals(2, database.contactGroupDao().get(ACCOUNT.value, "partial-group")!!.memberships.size)
        assertEquals(1, database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, "partial").size)

        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
        val membershipReads = remote.membershipReads
        orchestrator().drain(ACCOUNT.value, 1_000)
        assertEquals(membershipReads, remote.membershipReads)
        assertEquals(2, remote.assignmentWrites)
        assertEquals(2, database.contactGroupDao().get(ACCOUNT.value, "partial-group")!!.memberships.size)

        val local = repository.getContact(ACCOUNT.value, "partial")!!
        saveContact(local.copy(values = local.values.map { it.copy(value = "corrected@example.test") }))
        val corrected = repository.getContact(ACCOUNT.value, "partial")!!
        assertTrue(database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, "partial").isEmpty())
        assertEquals("corrected@example.test", RoomContactUploadOverlays(database).wireProjection(corrected)!!.values.single().value)
        // The independently accepted contact PUT supplies the previously omitted occurrence's own identity.
        val store = RoomMutationExecutionStore(database)
        val command = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "CONTACT" }
        assertTrue(store.claim(command, 1_000))
        assertTrue(store.acknowledgeAndFinish(command, com.patmanak.contako.data.sync.RemoteMutationAcknowledgement(
            corrected.remoteContactId, "corrected-version", emailIdsByValueId = mapOf("unfinished" to "corrected-remote-email"))))
        repeat(2) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(setOf(RemoteEmailId("healthy-first"), RemoteEmailId("corrected-remote-email")), remote.memberships)
        assertNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "partial-group"))
        assertNull(database.contactGroupDao().get(ACCOUNT.value, "partial-group")!!.group.pendingMutationRevision)
        assertEquals(2, database.contactGroupDao().get(ACCOUNT.value, "partial-group")!!.memberships.size)
        assertEquals("duplicate@example.test", repository.getContact(ACCOUNT.value, "healthy")!!.values[1].value)
        assertEquals("healthy-second", repository.getContact(ACCOUNT.value, "healthy")!!.values[1].metadata["protonEmailId"])
    }

    @Test fun parkedGroupAcceptsRemoteMemberRemovalAndAdditionWithoutRestoringItsOldSnapshot() = runBlocking {
        createDeferredOnlyGroup()
        val parked = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        remote.memberships.clear()
        adoptRemoteMembership("healthy", false)
        remote.memberships += RemoteEmailId("external-email")
        adoptRemoteMembership("external", true)
        val members = database.contactGroupDao().get(ACCOUNT.value, "group")!!.toDomain().memberships.toSet()
        assertEquals(setOf(GroupMembership("partial", "unfinished"), GroupMembership("external", "external-email-value")), members)
        assertEquals(parked, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        val reads = remote.membershipReads
        val writes = remote.assignmentWrites
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(reads, remote.membershipReads)
        assertEquals(writes, remote.assignmentWrites)
        assertEquals(setOf(RemoteEmailId("external-email")), remote.memberships)
        assertEquals("Retained private note", repository.getContact(ACCOUNT.value, "healthy")!!.valuesOf(ContactValueKind.NOTE).single().value)
    }

    @Test fun editingADeliveredMemberBeforeCardRefreshPreservesItsGroupAcrossTwoPasses() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        assertTrue(healthy.valuesOf(ContactValueKind.CATEGORY).isEmpty())
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        saveContact(healthy.copy(values = healthy.values +
            ContactValue("phone", ContactValueKind.PHONE, "+33 1 23 45 67 89", order = 0)))
        repeat(2) { repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) } }
        val remoteHealthy = (remote.fetch(ACCOUNT, RemoteContactId("healthy-remote")) as GatewayOutcome.Success).value
        database.withTransaction {
            RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT, remoteHealthy)
        }
        assertEquals(setOf(RemoteEmailId("healthy-email")), remote.memberships)
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.any { it.contactId == "healthy" })
        assertEquals("+33 1 23 45 67 89", repository.getContact(ACCOUNT.value, "healthy")!!.valuesOf(ContactValueKind.PHONE).single().value)
        assertEquals("Retained private note", repository.getContact(ACCOUNT.value, "healthy")!!.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals(DurableMutationState.PARTIAL.name, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.state)
    }

    @Test fun anEarlierWebRemovalIsNotReassertedByTheEditedContactsPut() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        remote.memberships.clear()
        remote.seedVerifiedContact(healthy.copy(values = healthy.values.map { value ->
            if (value.kind == ContactValueKind.EMAIL) value.copy(metadata = value.metadata + ("protonGroupIds" to "")) else value
        }))
        remote.applyClearCategoriesOnContactPut = true
        saveContact(healthy.copy(values = healthy.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        repeat(2) { repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) } }
        val fresh = (remote.fetch(ACCOUNT, RemoteContactId("healthy-remote")) as GatewayOutcome.Success).value
        database.withTransaction { RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT, fresh) }
        assertTrue(remote.memberships.isEmpty())
        assertEquals(0, remote.assignmentWrites)
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.none { it.contactId == "healthy" })
        assertTrue(database.remoteGroupAssignmentDependencyDao().edges(ACCOUNT.value, "group")
            .none { it.contactId == "healthy" && it.isDeferredIntent })
    }

    @Test fun staleClearCategoryDoesNotUndoWebRemovalBeforeOrAfterTheGroupReturnsClean() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = installLinkedCategorySnapshot()
        remote.memberships = linkedSetOf(RemoteEmailId("external-email"), RemoteEmailId("secondary-email"))
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        saveContact(healthy.copy(values = healthy.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        repeat(4) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(setOf(RemoteEmailId("external-email"), RemoteEmailId("secondary-email")), remote.memberships)
        val pendingCard = repository.getContact(ACCOUNT.value, "healthy")!!
        assertEquals(setOf("Unlisted", "Independent"), pendingCard.valuesOf(ContactValueKind.CATEGORY).map { it.value }.toSet())
        val received = (remote.contactMutations.last() as ContactMutation.Update).contact
        val encoded = ProtonContactVCardCodec().encode(received)
        assertFalse(encoded.clear.contains("CATEGORIES:Group"))
        assertTrue(encoded.clear.contains("ITEM1.CATEGORIES:Unlisted"))
        assertTrue(encoded.clear.contains("ITEM2.CATEGORIES:Independent"))
        assertTrue(encoded.signed.contains("X-UNRELATED:preserved"))
        // drain() covers only the outbox. The following normal verified import models
        // the next full sync pass that projects the acknowledged card's LabelIDs locally.
        val fresh = (remote.fetch(ACCOUNT, RemoteContactId("healthy-remote")) as GatewayOutcome.Success).value
        database.withTransaction { RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT, fresh) }
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.none {
            it.contactId == "healthy" && it.emailValueId == healthy.valuesOf(ContactValueKind.EMAIL).first().id
        })
        // Finish the unrelated invalid member, then repeat an ordinary edit in CLEAN mode.
        adoptRemoteMembership("external", true)
        val partial = repository.getContact(ACCOUNT.value, "partial")!!
        saveContact(partial.copy(values = partial.values.map { it.copy(value = "corrected@example.test") }))
        val execution = RoomMutationExecutionStore(database)
        val command = execution.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "CONTACT" }
        assertTrue(execution.claim(command, 1_000))
        assertTrue(execution.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(partial.remoteContactId,
            "corrected-version", emailIdsByValueId = mapOf("unfinished" to "corrected-email"))))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        val clean = repository.getContact(ACCOUNT.value, "healthy")!!
        saveContact(clean.copy(values = clean.values.map { if (it.id == "phone") it.copy(value = "+33987654321") else it }))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(setOf(RemoteEmailId("external-email"), RemoteEmailId("secondary-email"), RemoteEmailId("corrected-email")), remote.memberships)
        assertEquals("+33987654321", repository.getContact(ACCOUNT.value, "healthy")!!.valuesOf(ContactValueKind.PHONE).single().value)
        assertEquals(setOf("Unlisted", "Independent"), repository.getContact(ACCOUNT.value, "healthy")!!
            .valuesOf(ContactValueKind.CATEGORY).map { it.value }.toSet())
    }

    @Test fun aFreshPositiveLinkedCategorySurvivesThePutWithoutAnAssignmentWrite() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = installLinkedCategorySnapshot()
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        saveContact(healthy.copy(values = healthy.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(setOf(RemoteEmailId("healthy-email")), remote.memberships)
        assertEquals(0, remote.assignmentWrites)
        val encoded = ProtonContactVCardCodec().encode((remote.contactMutations.last() as ContactMutation.Update).contact)
        assertTrue(encoded.clear.contains("ITEM1.CATEGORIES:Group"))
        assertTrue(encoded.clear.contains("ITEM1.CATEGORIES:Unlisted"))
        assertTrue(encoded.clear.contains("ITEM2.CATEGORIES:Independent"))
    }

    @Test fun anExplicitCategoryEditKeepsItsDurableIntentInsteadOfBeingDeletedByNegativeMembershipEvidence() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = installLinkedCategorySnapshot()
        val editedId = healthy.valuesOf(ContactValueKind.CATEGORY).single { it.value == "Unlisted" }.id
        remote.memberships.clear()
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        val values = healthy.values.map { if (it.id == editedId) it.copy(value = "Group") else it } +
            ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)
        saveContact(healthy.copy(values = values))
        val expected = repository.getContact(ACCOUNT.value, "healthy")!!
        val writes = remote.contactWrites
        orchestrator().drain(ACCOUNT.value, 1_000)
        val held = database.outboxDao().get(ACCOUNT.value, "CONTACT", "healthy")!!
        assertEquals(expected.revision, held.revision)
        assertEquals(expected.revision, repository.getContact(ACCOUNT.value, "healthy")!!.pendingMutationRevision)
        assertEquals(expected.values, repository.getContact(ACCOUNT.value, "healthy")!!.values)
        assertEquals("Group", database.contactDao().getValue(ACCOUNT.value, "healthy", editedId)!!.value)
        assertEquals(writes, remote.contactWrites)
        assertTrue(remote.memberships.isEmpty())
        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
        repeat(2) { orchestrator().drain(ACCOUNT.value, 90_000) }
        val retried = database.outboxDao().get(ACCOUNT.value, "CONTACT", "healthy")!!
        // RECONCILIATION_INCOMPLETE is retryable. One due attempt is retained, then
        // the second drain at the same time must respect its new backoff deadline.
        assertTrue(retried.nextAttemptAtEpochMillis > 90_000)
        assertEquals(held.copy(attemptCount = held.attemptCount + 1,
            lastAttemptAtEpochMillis = 90_000, nextAttemptAtEpochMillis = retried.nextAttemptAtEpochMillis), retried)
        assertEquals(expected.revision, repository.getContact(ACCOUNT.value, "healthy")!!.pendingMutationRevision)
        assertEquals(expected.values, repository.getContact(ACCOUNT.value, "healthy")!!.values)
        assertEquals(writes, remote.contactWrites)

        // Only an explicit user correction replaces the held intention and permits this PUT.
        val reviewed = repository.getContact(ACCOUNT.value, "healthy")!!
        saveContact(reviewed.copy(values = reviewed.values.map { if (it.id == editedId) it.copy(value = "Unlisted") else it }))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 90_000) }
        assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", "healthy"))
        assertEquals("Unlisted", database.contactDao().getValue(ACCOUNT.value, "healthy", editedId)!!.value)
        assertEquals("+33123456789", repository.getContact(ACCOUNT.value, "healthy")!!.valuesOf(ContactValueKind.PHONE).single().value)
        assertTrue(remote.memberships.isEmpty())
    }

    @Test fun editedDeliveredMemberReassertsOnlyItsOwnReplacedIdentityAndKeepsWebAdditions() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        remote.memberships += RemoteEmailId("external-email")
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        remote.replaceEmailIdsOnContactPut = true
        // Several edits before sync must preserve the latest durable fields and one restoration.
        saveContact(healthy.copy(displayName = "Latest local name"))
        val edited = repository.getContact(ACCOUNT.value, "healthy")!!
        saveContact(edited.copy(values = edited.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        repeat(4) { orchestrator().drain(ACCOUNT.value, 1_000) }
        val current = repository.getContact(ACCOUNT.value, "healthy")!!
        val replacement = current.valuesOf(ContactValueKind.EMAIL).single().metadata.getValue("protonEmailId")
        assertTrue(replacement != "healthy-email")
        assertEquals(setOf(RemoteEmailId(replacement), RemoteEmailId("external-email")), remote.memberships)
        assertEquals(1, remote.assignmentWrites)
        assertEquals("Latest local name", current.displayName)
        assertEquals("Retained private note", current.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals(setOf(GroupMembership("partial", "unfinished")),
            RoomPartialGroupMutationStore(database).deferredMemberships(ACCOUNT.value, "group"))
    }

    @Test fun lostPutAcknowledgementKeepsTheExactRestorationWitnessAcrossRestartAndRetry() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        remote.seedVerifiedContact(healthy)
        remote.memberships += RemoteEmailId("external-email")
        remote.applyClearCategoriesOnContactPut = true
        remote.replaceEmailIdsOnContactPut = true
        remote.dropNextContactPutAcknowledgement = true
        saveContact(healthy.copy(values = healthy.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        orchestrator().drain(ACCOUNT.value, 1_000)
        val attempt = database.outboxDao().get(ACCOUNT.value, "CONTACT", "healthy")!!
        assertTrue(attempt.requiresReconciliation)
        assertTrue(database.remoteGroupAssignmentDependencyDao().edges(ACCOUNT.value, "group")
            .any { it.contactId == "healthy" && it.isDeferredIntent && it.remoteEmailId == "healthy-email" })
        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
        orchestrator().drain(ACCOUNT.value, attempt.nextAttemptAtEpochMillis)
        val conflicts = RoomContactConflictStore(database)
        val conflict = conflicts.observe(ACCOUNT.value).first().single { it.contactId == "healthy" }
        assertTrue(conflicts.choose(ACCOUNT.value, conflict, ContactConflictChoice.LOCAL))
        repeat(4) { orchestrator().drain(ACCOUNT.value, attempt.nextAttemptAtEpochMillis) }
        val current = repository.getContact(ACCOUNT.value, "healthy")!!
        val identity = current.valuesOf(ContactValueKind.EMAIL).single().metadata.getValue("protonEmailId")
        assertEquals(setOf(RemoteEmailId(identity), RemoteEmailId("external-email")), remote.memberships)
        assertEquals("+33123456789", current.valuesOf(ContactValueKind.PHONE).single().value)
        assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", "healthy"))
    }

    @Test fun choosingProtonAfterAnUncertainPutCancelsOnlyTheDeliveredMembersRestoration() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        remote.dropNextContactPutAcknowledgement = true
        saveContact(healthy.copy(values = healthy.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        orchestrator().drain(ACCOUNT.value, 1_000)
        val attempt = database.outboxDao().get(ACCOUNT.value, "CONTACT", "healthy")!!
        val remoteAfterPut = (remote.fetch(ACCOUNT, RemoteContactId("healthy-remote")) as GatewayOutcome.Success).value.contact
        remote.seedVerifiedContact(remoteAfterPut.copy(remoteVersion = "external-edit-version", values = remoteAfterPut.values.map { value ->
            if (value.kind != ContactValueKind.EMAIL) value else value.copy(value = "remote-replaced@example.test",
                metadata = mapOf("protonEmailId" to "externally-replaced-email", "protonGroupIds" to ""))
        }))
        orchestrator().drain(ACCOUNT.value, attempt.nextAttemptAtEpochMillis)
        val conflicts = RoomContactConflictStore(database)
        assertTrue(conflicts.detail(ACCOUNT.value, "healthy")!!.protonChoiceAvailable)
        assertTrue(conflicts.choose(ACCOUNT.value,
            conflicts.observe(ACCOUNT.value).first().single { it.contactId == "healthy" }, ContactConflictChoice.PROTON))
        repeat(3) { orchestrator().drain(ACCOUNT.value, attempt.nextAttemptAtEpochMillis) }
        assertTrue(remote.memberships.isEmpty())
        assertEquals(setOf(GroupMembership("partial", "unfinished")),
            RoomPartialGroupMutationStore(database).deferredMemberships(ACCOUNT.value, "group"))
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.none { it.contactId == "healthy" })
        assertEquals("remote-replaced@example.test", repository.getContact(ACCOUNT.value, "healthy")!!.valuesOf(ContactValueKind.EMAIL).single().value)
        assertEquals("unfinished", repository.getContact(ACCOUNT.value, "partial")!!.valuesOf(ContactValueKind.EMAIL).single().value)
    }

    @Test fun explicitGroupEditDuringContactWriteInvalidatesTheEarlierRestorationScope() = runBlocking {
        createDeferredOnlyGroup()
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        saveContact(healthy.copy(values = healthy.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        val execution = RoomMutationExecutionStore(database)
        val command = execution.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "CONTACT" }
        assertTrue(execution.claim(command, 1_000))
        val partial = RoomPartialGroupMutationStore(database)
        val scope = partial.contactWriteScopes(command)!!.single()
        assertTrue(partial.retainContactWriteMemberships(command, scope,
            (remote.members(ACCOUNT, RemoteGroupId("remote-group")) as GatewayOutcome.Success).value))
        val group = database.contactGroupDao().get(ACCOUNT.value, "group")!!.toDomain()
        saveGroup(group.copy(memberships = group.memberships.filterNot { it.contactId == "healthy" }))
        val result = RoomBackedMutationUploadGateway(ACCOUNT, database, remote, remote, remote, remote).upload(command)
        assertTrue(result is GatewayOutcome.Success)
        assertTrue(execution.acknowledgeAndFinish(command, (result as GatewayOutcome.Success).value))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertTrue(remote.memberships.isEmpty())
        assertEquals(setOf(GroupMembership("partial", "unfinished")), partial.deferredMemberships(ACCOUNT.value, "group"))
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.none { it.contactId == "healthy" })
    }

    @Test fun rejectedGroupKeepsTheContactWriteRestorationUntilExplicitCapabilityRecovery() = runBlocking {
        createDeferredOnlyGroup()
        var capabilityGeneration = 0L
        val runner = orchestrator(recoveryGeneration = { capabilityGeneration })
        // Production owns one store/runner. Observe the current epoch before injecting
        // its refusal; constructing another store would simulate a new capability epoch.
        runner.drain(ACCOUNT.value, 1_000)
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        remote.seedVerifiedContact(healthy)
        remote.applyClearCategoriesOnContactPut = true
        saveContact(healthy.copy(values = healthy.values + ContactValue("phone", ContactValueKind.PHONE, "+33123456789", order = 0)))
        val group = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        val blocked = group.copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = "GROUP_CAPABILITY_REQUIRED", errorCategory = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED.name,
            attemptCount = 4, nextAttemptAtEpochMillis = 80_000)
        database.outboxDao().upsert(blocked)
        runner.drain(ACCOUNT.value, 1_000)
        assertEquals(blocked, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertTrue(database.remoteGroupAssignmentDependencyDao().edges(ACCOUNT.value, "group")
            .any { it.contactId == "healthy" && it.isDeferredIntent && it.remoteEmailId == "healthy-email" })
        val writes = remote.assignmentWrites
        repeat(3) { runner.drain(ACCOUNT.value, 90_000) }
        assertEquals(blocked, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertEquals(writes, remote.assignmentWrites)
        assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", "healthy"))
        assertEquals("+33123456789", repository.getContact(ACCOUNT.value, "healthy")!!.valuesOf(ContactValueKind.PHONE).single().value)
        capabilityGeneration++ // A real caller's session/capability invalidation permits one fresh probe.
        repeat(3) { runner.drain(ACCOUNT.value, 90_000) }
        assertEquals(setOf(RemoteEmailId("healthy-email")), remote.memberships)
        assertEquals(4, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.attemptCount)
        assertEquals(DurableMutationState.PARTIAL.name, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.state)
        assertEquals(setOf(GroupMembership("partial", "unfinished")),
            RoomPartialGroupMutationStore(database).deferredMemberships(ACCOUNT.value, "group"))
    }

    @Test fun correctedDeferredMemberAddsOnlyItsOwnEdgeAfterRestartAndPreservesNewRemoteChoices() = runBlocking {
        createDeferredOnlyGroup()
        remote.memberships = linkedSetOf(RemoteEmailId("external-email"))
        adoptRemoteMembership("healthy", false)
        adoptRemoteMembership("external", true)
        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
        val partial = repository.getContact(ACCOUNT.value, "partial")!!
        saveContact(partial.copy(values = partial.values.map { it.copy(value = "corrected@example.test") }))
        val execution = RoomMutationExecutionStore(database)
        val contactCommand = execution.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "CONTACT" }
        assertTrue(execution.claim(contactCommand, 1_000))
        assertTrue(execution.acknowledgeAndFinish(contactCommand, RemoteMutationAcknowledgement(
            partial.remoteContactId, "corrected-version", emailIdsByValueId = mapOf("unfinished" to "corrected-email"))))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(setOf(RemoteEmailId("external-email"), RemoteEmailId("corrected-email")), remote.memberships)
        assertNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertNull(database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.pendingMutationRevision)
        assertEquals("corrected@example.test", repository.getContact(ACCOUNT.value, "partial")!!.values.single().value)
    }

    @Test fun explicitGroupEditReplacesDeferredScopeAndCanRemoveTheDeferredMemberPermanently() = runBlocking {
        createDeferredOnlyGroup()
        val group = database.contactGroupDao().get(ACCOUNT.value, "group")!!.toDomain()
        saveGroup(group.copy(memberships = emptyList()))
        assertNull(database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, "group")!!.deferredOnlyRevision)
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertTrue(remote.memberships.isEmpty())
        assertNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.isEmpty())
        val partial = repository.getContact(ACCOUNT.value, "partial")!!
        saveContact(partial.copy(displayName = "Changed unrelated contact name"))
        RoomPartialGroupMutationStore(database).wakeForContact(ACCOUNT.value, "partial")
        assertNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
    }

    @Test fun deferredScopeSurvivesTransportFailureAndDoesNotAuthorizeDeletingRemoteMembers() = runBlocking {
        createDeferredOnlyGroup()
        val intent = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        database.outboxDao().upsert(intent.copy(state = DurableMutationState.PENDING.name,
            errorCategory = GatewayFailureCategory.NETWORK_UNAVAILABLE.name, requiresReconciliation = true))
        remote.memberships = linkedSetOf(RemoteEmailId("external-email"))
        repeat(2) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(setOf(RemoteEmailId("external-email")), remote.memberships)
        assertEquals(DurableMutationState.PARTIAL.name, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.state)
        assertEquals(intent.revision,
            database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, "group")!!.deferredOnlyRevision)
    }

    @Test fun remotelyDeletedDeferredGroupRetainsItsIntentWithoutRecreatingOrAssigningIt() = runBlocking {
        createDeferredOnlyGroup()
        RoomRemoteGroupReconciliationStore(database).commit(ACCOUNT, AvailableContactGroups(emptyList()))
        val writes = remote.assignmentWrites
        repeat(2) { orchestrator().drain(ACCOUNT.value, 1_000) }
        val intent = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, intent.state)
        assertEquals("REMOTE_GROUP_DELETION_RECOVERY_REQUIRED", intent.blockedReason)
        assertEquals(writes, remote.assignmentWrites)
        assertEquals(0, remote.groupCreates)
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.any { it.contactId == "partial" })
    }

    @Test fun aProvedDeferredGroupFollowsRemoteMetadataWithoutReassertingItsDeliveredName() = runBlocking {
        createDeferredOnlyGroup()
        val before = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        val remoteGroup = RemoteContactGroup(RemoteGroupId("remote-group"), "Remote rename", "#654321")
        RoomRemoteGroupReconciliationStore(database).commit(ACCOUNT, AvailableContactGroups(listOf(remoteGroup)))
        val after = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        assertEquals(before.copy(remoteVersion = remoteGroup.fingerprint()), after)
        assertEquals("Remote rename", database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.name)
        assertEquals(before.revision, database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.pendingMutationRevision)
        assertEquals(setOf(GroupMembership("partial", "unfinished")),
            RoomPartialGroupMutationStore(database).deferredMemberships(ACCOUNT.value, "group"))
    }

    @Test fun legacyPartialWithoutRemainingOmissionProofWaitsForExplicitReviewAcrossContactEdits() = runBlocking {
        createDeferredOnlyGroup()
        val dao = database.remoteGroupAssignmentDependencyDao()
        dao.upsertBaseline(dao.baseline(ACCOUNT.value, "group")!!.copy(deferredOnlyRevision = null))
        dao.upsertEdges(dao.edges(ACCOUNT.value, "group").map { it.copy(isDeferredIntent = false) })
        database.contactEmailUploadOverlayDao().deleteContact(ACCOUNT.value, "partial")
        RoomPartialGroupMutationStore(database).wakeForContact(ACCOUNT.value, "partial")
        val blocked = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, blocked.state)
        assertEquals("PARTIAL_GROUP_SCOPE_UNVERIFIED", blocked.blockedReason)
        val healthy = repository.getContact(ACCOUNT.value, "healthy")!!
        saveContact(healthy.copy(displayName = "Local unrelated edit"))
        assertEquals(blocked, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.any { it.contactId == "partial" })
        assertEquals(setOf(RemoteEmailId("healthy-email")), remote.memberships)
    }

    @Test fun scopedGroupMetadataReadPreservesPermanentFailureAndInterruptedAttemptState() = runBlocking {
        for (state in listOf(DurableMutationState.ACTION_REQUIRED, DurableMutationState.IN_FLIGHT)) {
            database.clearAllTables()
            remote = FakeRemoteMutations()
            createDeferredOnlyGroup()
            val original = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.copy(state = state.name,
                errorCategory = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED.name,
                blockedReason = "GROUP_CAPABILITY_REQUIRED", attemptCount = 4, nextAttemptAtEpochMillis = 90_000)
            database.outboxDao().upsert(original)
            val remoteGroup = RemoteContactGroup(RemoteGroupId("remote-group"), "New remote name", "#123456")
            repeat(2) {
                RoomRemoteGroupReconciliationStore(database).commit(ACCOUNT, AvailableContactGroups(listOf(remoteGroup)))
                assertEquals(original.copy(remoteVersion = remoteGroup.fingerprint()),
                    database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
                assertEquals("New remote name", database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.name)
                assertTrue(RoomPartialGroupMutationStore(database).isDeferredOnly(ACCOUNT.value, "group", original.revision))
            }
            assertEquals(0, database.outboxDao().updateDeferredGroupRemoteBaseline(ACCOUNT.value, "group",
                original.revision + 1, state.name, remoteGroup.fingerprint(), "remote-group", "unproved"))
            assertEquals(original.copy(remoteVersion = remoteGroup.fingerprint()),
                database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
            if (state == DurableMutationState.ACTION_REQUIRED) {
                RoomRemoteGroupReconciliationStore(database).commit(ACCOUNT, AvailableContactGroups(emptyList()))
                val deleted = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
                assertEquals(DurableMutationState.ACTION_REQUIRED.name, deleted.state)
                assertEquals("REMOTE_GROUP_DELETION_RECOVERY_REQUIRED", deleted.blockedReason)
                assertEquals(original.revision, deleted.revision)
                assertEquals(original.attemptCount, deleted.attemptCount)
                assertFalse(database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.isDeleted)
            }
        }
    }

    private suspend fun createDeferredOnlyGroup() {
        saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
            ContactValue("unfinished", ContactValueKind.EMAIL, "unfinished", order = 0))))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        saveContact(CanonicalContact(ACCOUNT.value, "healthy", displayName = "Healthy", values = listOf(
            ContactValue("healthy-email-value", ContactValueKind.EMAIL, "healthy@example.test", order = 0,
                metadata = mapOf("protonEmailId" to "healthy-email", "protonGroupIds" to "remote-group")),
            ContactValue("note", ContactValueKind.NOTE, "Retained private note", order = 1))))
        cleanContact("healthy", "healthy-remote")
        saveContact(CanonicalContact(ACCOUNT.value, "external", displayName = "External", values = listOf(
            ContactValue("external-email-value", ContactValueKind.EMAIL, "external@example.test", order = 0,
                metadata = mapOf("protonEmailId" to "external-email")))))
        cleanContact("external", "external-remote")
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("partial", "unfinished"), GroupMembership("healthy", "healthy-email-value"))))
        remote.memberships = linkedSetOf(RemoteEmailId("healthy-email"))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        val parked = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        assertEquals(DurableMutationState.PARTIAL.name, parked.state)
        assertEquals(parked.revision, database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, "group")!!.deferredOnlyRevision)
        assertEquals(setOf(GroupMembership("partial", "unfinished")),
            RoomPartialGroupMutationStore(database).deferredMemberships(ACCOUNT.value, "group"))
    }

    /** Model a previously hydrated card with real per-email clear-category provenance. */
    private suspend fun installLinkedCategorySnapshot(): CanonicalContact {
        val before = repository.getContact(ACCOUNT.value, "healthy")!!
        fun card(body: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:linked-category\r\n$body\r\nEND:VCARD\r\n"
        val decoded = ProtonContactVCardCodec().decode(ACCOUNT.value,
            me.proton.core.contact.domain.entity.Contact(me.proton.core.domain.entity.UserId("synthetic-user"),
                me.proton.core.contact.domain.entity.ContactId("healthy-remote"), "Healthy", emptyList()), listOf(
                ProtonPlainContactCard(me.proton.core.contact.domain.entity.ContactCardType.Signed,
                    card("FN:Healthy\r\nITEM1.EMAIL:healthy@example.test\r\nITEM2.EMAIL:secondary@example.test\r\nX-UNRELATED:preserved")),
                ProtonPlainContactCard(me.proton.core.contact.domain.entity.ContactCardType.ClearText,
                    card("ITEM1.CATEGORIES:Group,Unlisted\r\nITEM2.CATEGORIES:Independent")),
            ))
        val values = decoded.values.map { value ->
            if (value.kind != ContactValueKind.EMAIL) value else value.copy(metadata = value.metadata +
                ("protonEmailId" to if (value.order == 0) "healthy-email" else "secondary-email") +
                ("protonGroupIds" to if (value.order == 0) "remote-group" else ""))
        } + before.valuesOf(ContactValueKind.NOTE)
        val contact = decoded.copy(id = before.id, revision = before.revision, remoteContactId = before.remoteContactId,
            remoteVersion = before.remoteVersion, values = values)
        database.withTransaction {
            val group = database.contactGroupDao().get(ACCOUNT.value, "group")!!.toDomain()
            database.contactGroupDao().deleteMemberships(ACCOUNT.value, "group")
            database.contactDao().upsert(contact.toEntity())
            database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
            database.contactDao().deleteValuesNotIn(ACCOUNT.value, contact.id, contact.values.map { it.id })
            database.contactPayloadDao().upsert(contact.preservationEnvelope!!.toEntity(ACCOUNT.value, contact.id))
            database.contactGroupDao().insertMemberships((group.memberships.filterNot { it.contactId == contact.id } +
                GroupMembership(contact.id, contact.valuesOf(ContactValueKind.EMAIL).first().id)).map { it.toEntity(group) })
        }
        return repository.getContact(ACCOUNT.value, "healthy")!!
    }

    private suspend fun adoptRemoteMembership(contactId: String, included: Boolean) {
        val old = repository.getContact(ACCOUNT.value, contactId)!!
        val changed = old.copy(values = old.values.map { value ->
            if (value.kind != ContactValueKind.EMAIL) value else value.copy(metadata = value.metadata +
                ("protonGroupIds" to if (included) "remote-group" else ""))
        })
        database.withTransaction {
            RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT,
                VerifiedContactCard(RemoteContactId(old.remoteContactId!!), RemoteVersion("remote-edited"), changed))
        }
    }

    @Test
    fun missingEmailIdentityIsDeferredOnlyWithTheExactCurrentOmissionOverlay() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
            ContactValue("unfinished", ContactValueKind.EMAIL, "unfinished", order = 0))))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("partial", "unfinished"))))
        val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote)
        val overlay = database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, "partial").single()
        val projection = preparation.desiredEmailProjection(ACCOUNT.value, "group")!!
        assertTrue(projection.emailIds.isEmpty())
        assertTrue(projection.hasDeferredMembers)
        for (changed in listOf(overlay.copy(canonicalRevision = overlay.canonicalRevision + 1),
            overlay.copy(requiresRemoteIdentity = true), overlay.copy(sourceEmailId = "unverified-source"))) {
            database.contactEmailUploadOverlayDao().upsert(listOf(changed))
            assertNull(preparation.desiredEmailProjection(ACCOUNT.value, "group"))
        }
        database.contactEmailUploadOverlayDao().deleteContact(ACCOUNT.value, "partial")
        assertNull(preparation.desiredEmailProjection(ACCOUNT.value, "group"))
        assertEquals(1, database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.size)
        assertEquals(0, remote.assignmentWrites)
    }

    @Test
    fun anEntirelyDeferredGroupDoesNotBecomeAcknowledgedOrDropItsLocalMember() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
            ContactValue("unfinished", ContactValueKind.EMAIL, "unfinished", order = 0))))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("partial", "unfinished"))))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        assertEquals(0, remote.assignmentWrites)
        assertTrue(remote.memberships.isEmpty())
        val intent = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        assertEquals("ASSIGNMENTS", intent.operation)
        assertEquals(DurableMutationState.PARTIAL.name, intent.state)
        assertEquals(intent.revision, database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.pendingMutationRevision)
        assertEquals(GroupMembership("partial", "unfinished"),
            database.contactGroupDao().get(ACCOUNT.value, "group")!!.toDomain().memberships.single())
    }

    @Test
    fun partialGroupCompletesThePassWithoutIoRetryOrRepeatedReadsAndSurvivesRestart() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
            ContactValue("unfinished", ContactValueKind.EMAIL, "unfinished", order = 0))))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("partial", "unfinished"))))
        val executor = partialGroupPassExecutor()
        val outcome = executor.execute(incrementalRequest())
        assertEquals(SyncPassOutcome.SUCCESS, outcome)
        assertFalse(executor.hasUnscopedFailure)
        assertEquals(AndroidSyncCompletionClassification.SUCCESS,
            classifyAndroidSyncCompletion(SyncRunState.Completed(outcome, 1, executor.completionCause)))
        val parked = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertEquals(DurableMutationState.PARTIAL.name, parked.state)
        assertEquals(parked.revision, database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.pendingMutationRevision)
        assertEquals(0, database.outboxDao().observePendingCount(ACCOUNT.value).first())
        assertEquals(2, database.accountRemovalDao().countPending(ACCOUNT.value)) // Group intent plus invalid contact input.
        val status = requireNotNull(RoomSyncStatusStore(database).observe(ACCOUNT.value).first())
        assertEquals(0, status.pendingMutationCount)
        assertTrue(status.scopedIssuesOnly)
        assertEquals(SyncActionReason.VALIDATION_REJECTED, status.actionReason)
        val reads = remote.membershipReads

        database.close()
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 1_000 }, elapsedRealtimeClock = { 1_000 })
        val replay = partialGroupPassExecutor()
        repeat(3) {
            assertEquals(SyncPassOutcome.SUCCESS, replay.execute(incrementalRequest()))
            assertFalse(replay.hasUnscopedFailure)
        }
        assertEquals(reads, remote.membershipReads)
        assertEquals(parked, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertEquals(GroupMembership("partial", "unfinished"),
            database.contactGroupDao().get(ACCOUNT.value, "group")!!.toDomain().memberships.single())
        assertEquals("unfinished", repository.getContact(ACCOUNT.value, "partial")!!.values.single().value)
        assertEquals(1, database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, "partial").size)

        val recovery = RoomSyncRecoveryDataSource(database, AccountSyncRunner(this, SyncPassExecutor { SyncPassOutcome.SUCCESS }),
            NetworkStateProvider { NetworkState.WIFI }, ACCOUNT, clock = { 1_000 })
        val dashboard = requireNotNull(recovery.observeStatus(ACCOUNT.value).first())
        assertEquals(0, dashboard.pendingMutationCount)
        assertEquals(setOf("partial"), dashboard.partialContactIds)
        assertEquals(SyncDashboardState.PENDING, dashboard.state)
        assertTrue(dashboard.blockedItems.none { it.type == SyncBlockedItemType.GROUP })
    }

    @Test
    fun verifiedDeliveredContactAdoptionDoesNotWakeDeferredGroupOrRestoreARemoteRemoval() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
            ContactValue("unfinished", ContactValueKind.EMAIL, "unfinished", order = 0))))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        saveContact(CanonicalContact(ACCOUNT.value, "healthy", displayName = "Healthy", values = listOf(
            ContactValue("healthy-email", ContactValueKind.EMAIL, "healthy@example.test", order = 0,
                metadata = mapOf("protonEmailId" to "old-healthy-email")),
            ContactValue("unchanged-note", ContactValueKind.NOTE, "Unchanged private note", order = 1))))
        cleanContact("healthy", "healthy-remote")
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("partial", "unfinished"), GroupMembership("healthy", "healthy-email"))))
        remote.memberships = linkedSetOf(RemoteEmailId("old-healthy-email"))
        repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
        val parked = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
            .copy(attemptCount = 4, nextAttemptAtEpochMillis = 80_000)
        assertEquals(DurableMutationState.PARTIAL.name, parked.state)
        database.outboxDao().upsert(parked)
        val healthy = requireNotNull(repository.getContact(ACCOUNT.value, "healthy"))
        val adopted = healthy.copy(id = "remote-side-healthy", remoteContactId = "healthy-remote", remoteVersion = "new-version",
            values = healthy.values.map { if (it.id == "healthy-email")
                it.copy(metadata = mapOf("protonEmailId" to "new-healthy-email")) else it })
        remote.memberships.clear() // The independently read card no longer belongs to this group.

        RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT,
            com.patmanak.contako.data.gateway.VerifiedContactCard(RemoteContactId("healthy-remote"), RemoteVersion("new-version"), adopted))

        assertEquals(parked, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertEquals("new-healthy-email", repository.getContact(ACCOUNT.value, "healthy")!!.values
            .single { it.id == "healthy-email" }.metadata["protonEmailId"])
        assertEquals("Unchanged private note", repository.getContact(ACCOUNT.value, "healthy")!!.values
            .single { it.id == "unchanged-note" }.value)
        assertEquals("unfinished", repository.getContact(ACCOUNT.value, "partial")!!.values.single().value)
        assertEquals(1, database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.size)
        val reads = remote.membershipReads
        assertEquals(0, orchestrator().drain(ACCOUNT.value, 79_999).examined)
        assertEquals(reads, remote.membershipReads)
        repeat(3) { orchestrator().drain(ACCOUNT.value, 80_000) }
        assertTrue(remote.memberships.isEmpty())
        assertEquals(reads, remote.membershipReads)
        assertEquals(DurableMutationState.PARTIAL.name, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.state)
        assertEquals(4, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.attemptCount)
    }

    @Test
    fun unrelatedContactAcknowledgementDoesNotReopenDeliveredGroupAssignments() = runBlocking {
        for (removed in listOf(false, true)) {
            database.clearAllTables()
            remote = FakeRemoteMutations()
            saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
                ContactValue("unfinished", ContactValueKind.EMAIL, "unfinished", order = 0))))
            assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
            saveContact(CanonicalContact(ACCOUNT.value, "healthy", displayName = "Healthy", values = listOf(
                ContactValue("healthy-email", ContactValueKind.EMAIL, "healthy@example.test", order = 0,
                    metadata = mapOf("protonEmailId" to "old-healthy-email")),
                ContactValue("private-note", ContactValueKind.NOTE, "Retained note", order = 1))))
            cleanContact("healthy", "healthy-remote")
            saveGroup(ContactGroup(ACCOUNT.value, "group", "Group", remoteLabelId = "remote-group",
                memberships = listOf(GroupMembership("partial", "unfinished"), GroupMembership("healthy", "healthy-email"))))
            remote.memberships = linkedSetOf(RemoteEmailId("old-healthy-email"))
            repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
            if (removed) {
                val group = database.contactGroupDao().get(ACCOUNT.value, "group")!!.toDomain()
                saveGroup(group.copy(memberships = group.memberships.filterNot { it.contactId == "healthy" }))
                repeat(3) { orchestrator().drain(ACCOUNT.value, 1_000) }
                assertTrue(database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.none { it.contactId == "healthy" })
                assertTrue(database.remoteGroupAssignmentDependencyDao().edges(ACCOUNT.value, "group").any { it.contactId == "healthy" })
            }
            assertEquals(DurableMutationState.PARTIAL.name, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.state)
            val local = repository.getContact(ACCOUNT.value, "healthy")!!
            saveContact(local.copy(displayName = "Edited healthy contact"))
            val store = RoomMutationExecutionStore(database)
            val command = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "CONTACT" }
            assertTrue(store.claim(command, 1_000))
            // Install a retained parked assignment at the current revision to isolate the
            // ACK wake boundary from the repository's earlier current-membership wakeup.
            val parked = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
                .copy(state = DurableMutationState.PARTIAL.name, attemptCount = 4, nextAttemptAtEpochMillis = 80_000)
            database.outboxDao().upsert(parked)
            assertTrue(store.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(
                "healthy-remote", "ack-version", emailIdsByValueId = mapOf("healthy-email" to "new-healthy-email"))))

            assertEquals(parked, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
            assertEquals("new-healthy-email", repository.getContact(ACCOUNT.value, "healthy")!!.values
                .single { it.id == "healthy-email" }.metadata["protonEmailId"])
            assertEquals("Retained note", repository.getContact(ACCOUNT.value, "healthy")!!.values.single { it.id == "private-note" }.value)
            assertEquals("unfinished", repository.getContact(ACCOUNT.value, "partial")!!.values.single().value)
            assertEquals(if (removed) 1 else 2, database.contactGroupDao().get(ACCOUNT.value, "group")!!.memberships.size)
            // Once a full assignment was delivered, a later remote membership is not owned
            // by the still-invalid different occurrence's deferred intention.
            remote.memberships = linkedSetOf(RemoteEmailId("new-healthy-email"))
            repeat(3) { orchestrator().drain(ACCOUNT.value, 80_000) }
            assertEquals(setOf(RemoteEmailId("new-healthy-email")), remote.memberships)
            assertEquals(DurableMutationState.PARTIAL.name, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.state)
            assertEquals(4, database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!.attemptCount)
        }
    }

    @Test
    fun parkingRejectsStaleForeignOrChangedRemoteBindingAndOnlyAcceptsTheClaimedGroup() = runBlocking {
        saveContact(CanonicalContact(ACCOUNT.value, "partial", displayName = "Partial", values = listOf(
            ContactValue("unfinished", ContactValueKind.EMAIL, "unfinished", order = 0))))
        assertEquals(1, orchestrator().drain(ACCOUNT.value, 1_000).uploaded)
        saveGroup(ContactGroup(ACCOUNT.value, "group", "Group", remoteLabelId = "remote-group",
            memberships = listOf(GroupMembership("partial", "unfinished"))))
        orchestrator().drain(ACCOUNT.value, 1_000)
        val assignments = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertEquals(MutationOperation.ASSIGNMENTS.name, assignments.operation)
        assertEquals(DurableMutationState.PENDING.name, assignments.state)
        assertEquals(assignments.revision, database.contactGroupDao().get(ACCOUNT.value, "group")!!.group.pendingMutationRevision)
        val command = pendingAssignmentCommand("group")
        val execution = RoomMutationExecutionStore(database)
        assertTrue(execution.claim(command, 1_000))
        val original = database.outboxDao().get(ACCOUNT.value, "GROUP", "group")!!
        val group = database.contactGroupDao().get(ACCOUNT.value, "group")!!.group
        val partial = RoomPartialGroupMutationStore(database)
        fun candidate(accountId: String = ACCOUNT.value, revision: Long = command.revision,
            identity: String? = command.expectedRemoteIdentity, version: String? = command.expectedRemoteVersion) =
            DurableMutationCommand(accountId, "GROUP", "group", revision, RemoteMutationOperation.ASSIGNMENTS,
                command.attemptCount, command.nextEligibleEpochMillis, command.requiresReconciliation, identity, version)
        for (bad in listOf(candidate(accountId = "foreign-account"), candidate(revision = command.revision + 1),
                candidate(identity = "foreign-source"), candidate(version = "foreign-version"))) {
            assertFalse(partial.park(bad))
            assertEquals(original, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        }
        for (changed in listOf(group.copy(remoteLabelId = "foreign-source"), group.copy(remoteVersion = "foreign-version"),
                group.copy(revision = group.revision + 1), group.copy(conflictState = "GROUP_EDIT_DELETE_RECOVERY_REQUIRED"))) {
            database.contactGroupDao().upsert(changed)
            assertFalse(partial.park(command))
            assertEquals(original, database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        }
        database.contactGroupDao().upsert(group)
        assertTrue(partial.park(command))
        assertEquals(original.copy(state = DurableMutationState.PARTIAL.name, errorCategory = null),
            database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        assertFalse(partial.park(command))
    }

    private fun partialGroupPassExecutor(): ContakoSyncPassExecutor {
        val checkpoint = object : ContactInventoryCheckpointStore {
            private var current: VersionedContactInventoryCheckpoint? = null
            override suspend fun load(account: AccountScope) = current
            override suspend fun compareAndSet(account: AccountScope, expectedGeneration: Long?, checkpoint: ContactInventoryCheckpoint): Boolean {
                if (current?.generation != expectedGeneration) return false
                current = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint)
                return true
            }
        }
        val stage = IncrementalRemoteContactStage(
            com.patmanak.contako.data.gateway.ProtonContactInventoryGateway { _, _ -> GatewayOutcome.Success(
                com.patmanak.contako.data.gateway.ContactInventoryPage(emptyList(), null, null, 0,
                    com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY)) },
            com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway { _, _ -> error("No hydration belongs to this fixture") },
            PersistentContactInventoryPlanner(checkpoint),
            RemoteCanonicalReconciliationStore { _, _, cards, labels, deletions ->
                CanonicalReconciliationReceipt(cards.map { it.id }.toSet(), labels, deletions)
            })
        lateinit var executor: ContakoSyncPassExecutor
        val publisher = RoomSyncPassStatusPublisher(ACCOUNT.value, database, wallClock = { 1_000 },
            hasUnscopedFailure = { executor.hasUnscopedFailure })
        executor = ContakoSyncPassExecutor(ACCOUNT, SyncPrerequisiteChecker { SyncPrerequisiteState.READY }, stage,
            orchestrator(), wallClock = { 1_000 }, statusPublisher = publisher,
            androidPreflight = AndroidInteroperabilityPreflight { _, _ -> AndroidInteroperabilityPreflightResult.Ready(
                AndroidInteroperabilityContext(ACCOUNT, "fixture@example.test", 0, 0)) },
            androidStage = object : AndroidInteroperabilityStage {
                override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) = AndroidInteroperabilityStageResult.Success
                override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) = AndroidInteroperabilityStageResult.Success
            })
        return executor
    }

    private fun incrementalRequest() = SyncPassRequest(setOf(SyncTrigger.MANUAL), SyncScope.INCREMENTAL, 0, { 0 }, 0, { 0 })

    private suspend fun pendingAssignmentCommand(groupId: String): DurableMutationCommand {
        val intent = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", groupId))
        check(intent.operation == MutationOperation.ASSIGNMENTS.name)
        return DurableMutationCommand(intent.accountId, intent.aggregateType, intent.aggregateId,
            intent.revision, RemoteMutationOperation.ASSIGNMENTS, intent.attemptCount,
            intent.nextAttemptAtEpochMillis, intent.requiresReconciliation,
            expectedRemoteIdentity = intent.remoteIdentity, expectedRemoteVersion = intent.remoteVersion)
    }

    private fun orchestrator(recoveryGeneration: () -> Long = { 0 }) = DurableMutationOrchestrator(
        RoomMutationExecutionStore(database, recoveryGeneration = recoveryGeneration),
        RoomBackedMutationPreparationGateway(ACCOUNT, database, remote, remote),
        RoomBackedMutationUploadGateway(ACCOUNT, database, remote, remote, remote, remote),
    )

    private suspend fun saveContact(contact: CanonicalContact) {
        check(repository.saveContact(contact) is SaveResult.Saved)
    }

    private suspend fun saveGroup(group: ContactGroup) {
        check(repository.saveGroup(group) is SaveResult.Saved)
    }

    private suspend fun cleanContact(localId: String, remoteId: String) {
        val pending = database.outboxDao().getAll(ACCOUNT.value).single { it.aggregateId == localId }
        val store = RoomOutboxStore(database)
        check(store.claim(pending, 1_000))
        check(store.acknowledge(pending, remoteId, "version"))
        check(store.finishAcknowledged(requireNotNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", localId))))
    }

    private companion object {
        const val DATABASE_NAME = "v03-mutation-bridge.db"
        val ACCOUNT = AccountScope("v03-account")
    }
}

private class FakeRemoteMutations :
    com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway,
    ProtonContactMutationGateway,
    ProtonContactGroupGateway,
    ProtonEmailGroupMembershipReader,
    ProtonContactEmailLabelGateway {
    var contactWrites = 0
    val contactMutations = mutableListOf<ContactMutation>()
    var groupCreates = 0
    var assignmentWrites = 0
    var membershipReads = 0
    var memberships = linkedSetOf<RemoteEmailId>()
    var applyClearCategoriesOnContactPut = false
    var replaceEmailIdsOnContactPut = false
    var dropNextContactPutAcknowledgement = false
    private val groups = linkedMapOf<RemoteGroupId, RemoteContactGroup>()
    private val contacts = linkedMapOf<RemoteContactId, com.patmanak.contako.data.gateway.VerifiedContactCard>()

    fun seedVerifiedContact(contact: CanonicalContact) {
        val id = RemoteContactId(requireNotNull(contact.remoteContactId))
        contacts[id] = VerifiedContactCard(id, RemoteVersion(requireNotNull(contact.remoteVersion)), contact)
    }

    override suspend fun fetch(account: AccountScope, contactId: RemoteContactId) = contacts[contactId]?.let { GatewayOutcome.Success(it) }
        ?: GatewayOutcome.Failure(com.patmanak.contako.data.gateway.GatewayFailureCategory.NOT_FOUND)

    override suspend fun apply(account: AccountScope, mutation: ContactMutation): GatewayOutcome<ContactMutationReceipt> {
        contactWrites++
        contactMutations += mutation
        val id = when (mutation) {
            is ContactMutation.Create -> RemoteContactId("remote-contact-$contactWrites")
            is ContactMutation.Update -> mutation.id
            is ContactMutation.Delete -> mutation.id
        }
        val version = RemoteVersion("version-$contactWrites")
        var emailIdsByValueId: Map<String, String>? = null
        when (mutation) {
            is ContactMutation.Create -> contacts[id] = com.patmanak.contako.data.gateway.VerifiedContactCard(id, version, mutation.contact.copy(remoteContactId = id.value))
            is ContactMutation.Update -> {
                var received = mutation.contact
                if (applyClearCategoriesOnContactPut) {
                    // This peer models the clear CATEGORIES replacement performed by a PUT,
                    // independently of the per-email LabelIDs retained in a stale local card.
                    val emails = received.valuesOf(ContactValueKind.EMAIL)
                    val ids = emails.mapNotNull { it.metadata["protonEmailId"]?.let(::RemoteEmailId) }.toSet()
                    memberships -= ids
                    if (replaceEmailIdsOnContactPut) {
                        emailIdsByValueId = emails.associate { it.id to "put-email-$contactWrites-${it.id}" }
                        received = received.copy(values = received.values.map { value ->
                            if (value.kind != ContactValueKind.EMAIL) value else value.copy(metadata = value.metadata +
                                ("protonEmailId" to emailIdsByValueId!!.getValue(value.id)))
                        })
                    }
                    val encoded = ProtonContactVCardCodec().encode(received)
                    val groups = parseSingleCompleteVCard(encoded.clear).getProperties(ezvcard.property.Categories::class.java)
                        .filter { "Group" in it.values }.mapNotNull { it.group?.uppercase(java.util.Locale.ROOT) }.toSet()
                    parseSingleCompleteVCard(encoded.signed).getProperties(ezvcard.property.Email::class.java)
                        .forEachIndexed { index, email ->
                            if (email.group?.uppercase(java.util.Locale.ROOT) in groups)
                                received.valuesOf(ContactValueKind.EMAIL).getOrNull(index)?.metadata?.get("protonEmailId")
                                    ?.let { memberships += RemoteEmailId(it) }
                        }
                    received = received.copy(values = received.values.map { value ->
                        if (value.kind != ContactValueKind.EMAIL) value else value.copy(metadata = value.metadata +
                            ("protonGroupIds" to if (value.metadata["protonEmailId"]?.let(::RemoteEmailId) in memberships)
                                "remote-group" else ""))
                    })
                }
                contacts[id] = VerifiedContactCard(id, version, received)
                if (dropNextContactPutAcknowledgement) {
                    dropNextContactPutAcknowledgement = false
                    return GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE)
                }
            }
            is ContactMutation.Delete -> contacts.remove(id)
        }
        return GatewayOutcome.Success(ContactMutationReceipt(id, version, emailIdsByValueId))
    }

    override fun capabilities() = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE
    override suspend fun list(account: AccountScope) = GatewayOutcome.Success(AvailableContactGroups(groups.values.toList()))

    override suspend fun create(
        account: AccountScope,
        mutation: ContactGroupMutation.Create,
    ): GatewayOutcome<RemoteContactGroup> {
        groupCreates++
        val group = RemoteContactGroup(RemoteGroupId("remote-group-$groupCreates"), mutation.name, mutation.color)
        groups[group.id] = group
        return GatewayOutcome.Success(group)
    }

    override suspend fun update(
        account: AccountScope,
        mutation: ContactGroupMutation.Update,
    ): GatewayOutcome<RemoteContactGroup> {
        val group = RemoteContactGroup(mutation.id, mutation.name, mutation.color)
        groups[group.id] = group
        return GatewayOutcome.Success(group)
    }

    override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete): GatewayOutcome<Unit> {
        groups.remove(mutation.id)
        return GatewayOutcome.Success(Unit)
    }

    override suspend fun members(
        account: AccountScope,
        groupId: RemoteGroupId,
    ): GatewayOutcome<AuthoritativeEmailGroupMembership> {
        membershipReads++
        return GatewayOutcome.Success(AuthoritativeEmailGroupMembership(account, groupId, memberships.toList()))
    }

    override suspend fun apply(account: AccountScope, mutation: EmailLabelMutation): GatewayOutcome<Unit> {
        assignmentWrites++
        when (mutation) {
            is EmailLabelMutation.Assign -> memberships += mutation.emailIds
            is EmailLabelMutation.Remove -> memberships -= mutation.emailIds.toSet()
        }
        if (applyClearCategoriesOnContactPut) {
            contacts.replaceAll { _, card -> card.copy(contact = card.contact.copy(values = card.contact.values.map { value ->
                if (value.kind != ContactValueKind.EMAIL) value else value.copy(metadata = value.metadata +
                    ("protonGroupIds" to if (value.metadata["protonEmailId"]?.let(::RemoteEmailId) in memberships)
                        "remote-group" else ""))
            })) }
        }
        return GatewayOutcome.Success(Unit)
    }
}
