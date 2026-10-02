package com.patmanak.contako.data.local

import android.content.Context
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
import com.patmanak.contako.data.sync.RetryDecision
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackedMutationGatewaysDeviceTest {
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
        val local = requireNotNull(database.contactDao().get(ACCOUNT.value, "contact")).toDomain()
        saveContact(local.copy(displayName = "Local choice"))
        val store = RoomMutationExecutionStore(database)
        val groupCommand = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "GROUP" }
        val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote).prepare(groupCommand)
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.WaitingForDependencies,
            (preparation as GatewayOutcome.Success).value)
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
        saveContact(requireNotNull(database.contactDao().get(ACCOUNT.value, "contact")).toDomain()
            .copy(displayName = "Local choice"))
        val pending = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "group"))
        database.outboxDao().upsert(pending.copy(operation = MutationOperation.ASSIGNMENTS.name))
        val store = RoomMutationExecutionStore(database)
        val preparation = RoomBackedMutationPreparationGateway(ACCOUNT, database, remote)
        val groupCommand = store.eligible(ACCOUNT.value, 1_000, 20).single { it.aggregateType == "GROUP" }
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.WaitingForDependencies,
            (preparation.prepare(groupCommand) as GatewayOutcome.Success).value)
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

    private fun orchestrator() = DurableMutationOrchestrator(
        RoomMutationExecutionStore(database),
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
    var memberships = linkedSetOf<RemoteEmailId>()
    private val groups = linkedMapOf<RemoteGroupId, RemoteContactGroup>()
    private val contacts = linkedMapOf<RemoteContactId, com.patmanak.contako.data.gateway.VerifiedContactCard>()

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
        when (mutation) {
            is ContactMutation.Create -> contacts[id] = com.patmanak.contako.data.gateway.VerifiedContactCard(id, version, mutation.contact.copy(remoteContactId = id.value))
            is ContactMutation.Update -> contacts[id] = com.patmanak.contako.data.gateway.VerifiedContactCard(id, version, mutation.contact)
            is ContactMutation.Delete -> contacts.remove(id)
        }
        return GatewayOutcome.Success(ContactMutationReceipt(id, version))
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
    ): GatewayOutcome<AuthoritativeEmailGroupMembership> = GatewayOutcome.Success(
        AuthoritativeEmailGroupMembership(account, groupId, memberships.toList()),
    )

    override suspend fun apply(account: AccountScope, mutation: EmailLabelMutation): GatewayOutcome<Unit> {
        assignmentWrites++
        when (mutation) {
            is EmailLabelMutation.Assign -> memberships += mutation.emailIds
            is EmailLabelMutation.Remove -> memberships -= mutation.emailIds.toSet()
        }
        return GatewayOutcome.Success(Unit)
    }
}
