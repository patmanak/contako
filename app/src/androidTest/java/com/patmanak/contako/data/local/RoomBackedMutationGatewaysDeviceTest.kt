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

    private fun orchestrator() = DurableMutationOrchestrator(
        RoomMutationExecutionStore(database),
        RoomBackedMutationPreparationGateway(ACCOUNT, database, remote),
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

    override suspend fun apply(account: AccountScope, mutation: ContactMutation): GatewayOutcome<ContactMutationReceipt> {
        contactWrites++
        contactMutations += mutation
        val id = when (mutation) {
            is ContactMutation.Create -> RemoteContactId("remote-contact-$contactWrites")
            is ContactMutation.Update -> mutation.id
            is ContactMutation.Delete -> mutation.id
        }
        return GatewayOutcome.Success(ContactMutationReceipt(id, RemoteVersion("version-$contactWrites")))
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
