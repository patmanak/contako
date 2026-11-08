package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated database only, with no Android account, provider access or network. */
@RunWith(AndroidJUnit4::class)
class RemoteGroupAssignmentDependencyDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var dependencies: RoomRemoteGroupAssignmentDependencies

    @Before fun before() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
        dependencies = RoomRemoteGroupAssignmentDependencies(database)
    }

    @After fun after() { database.close(); context.deleteDatabase(DB) }

    @Test fun verifiedGroupProvesIndependenceFromAnUnrelatedBlockedContact() = runBlocking {
        val member = contact("member", "email-member")
        val unrelated = contact("unrelated", "email-unrelated")
        pending(unrelated)
        val group = group("group", listOf(GroupMembership(member.id, member.values.single().id)))
        assertTrue(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id)) // Legacy proof is missing.
        assertTrue(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision,
            RemoteGroupId(group.remoteLabelId!!), setOf(RemoteEmailId("email-member"))))
        assertFalse(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
        pending(member)
        assertTrue(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
        assertTrue(dependencies.hasPendingContactWriteForGroup(AccountScope("foreign-owner"), group.id))
    }

    @Test fun removedEmailBindingSurvivesValueDeletionAndBlocksUntilExactAssignmentAcknowledgement() = runBlocking {
        val member = contact("removed-member", "old-service-email")
        val group = group("removed-group", listOf(GroupMembership(member.id, member.values.single().id)))
        assertTrue(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision,
            RemoteGroupId(group.remoteLabelId!!), setOf(RemoteEmailId("old-service-email"))))
        val next = group.copy(revision = 2, memberships = emptyList(), pendingMutationRevision = 2)
        database.withTransaction {
            dependencies.captureBeforeGroupEdit(ACCOUNT, group.id, group.memberships)
            database.contactGroupDao().upsert(next.toEntity())
            database.contactGroupDao().deleteMemberships(ACCOUNT.value, group.id)
            database.contactDao().deleteValues(ACCOUNT.value, member.id)
        }
        pending(member, deleted = true)
        assertTrue(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
        assertTrue(dependencies.recordVerifiedMembership(ACCOUNT, group.id, next.revision,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), setOf(RemoteEmailId("old-service-email"))))
        assertFalse(dependencies.acknowledgeVerifiedMembership(ACCOUNT, group.id, next.revision,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), emptySet()))
        clearPending(member)
        assertFalse(dependencies.acknowledgeVerifiedMembership(ACCOUNT, group.id, 1,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), emptySet()))
        assertFalse(dependencies.acknowledgeVerifiedMembership(ACCOUNT, group.id, 2,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), setOf(RemoteEmailId("old-service-email"))))
        assertTrue(dependencies.acknowledgeVerifiedMembership(ACCOUNT, group.id, 2,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), emptySet()))
        assertTrue(database.remoteGroupAssignmentDependencyDao().edges(ACCOUNT.value, group.id).isEmpty())
        pending(member)
        assertFalse(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
    }

    @Test fun contactEditCapturesRemoteGroupsBeforeMembershipForeignKeyCascade() = runBlocking {
        val member = contact("edited-member", "remote-email", "remote-group")
        val group = group("group", listOf(GroupMembership(member.id, member.values.single().id)), remote = "remote-group")
        assertTrue(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision,
            RemoteGroupId(group.remoteLabelId!!), setOf(RemoteEmailId("remote-email"))))
        database.withTransaction {
            dependencies.captureContactBeforeEdit(ACCOUNT, member)
            database.contactDao().deleteValues(ACCOUNT.value, member.id)
        }
        assertTrue(database.contactGroupDao().get(ACCOUNT.value, group.id)!!.memberships.isEmpty())
        pending(member)
        assertTrue(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
        val retained = database.remoteGroupAssignmentDependencyDao().edges(ACCOUNT.value, group.id)
        assertTrue(retained.any { it.remoteEmailId == "remote-email" && it.contactId == member.id })
    }

    @Test fun repeatedEmailTextNeedsDistinctRemoteOccurrencesAndUnknownRemoteMembersKeepFallback() = runBlocking {
        val contact = CanonicalContact(ACCOUNT.value, "repeat", displayName = "Synthetic repeat", revision = 1,
            remoteContactId = "remote-repeat", remoteVersion = "v1", values = listOf(
                email("first", "service-first", 0), email("second", "service-second", 1)))
        save(contact)
        val group = group("repeat-group", contact.values.map { GroupMembership(contact.id, it.id) })
        val unrelated = this@RemoteGroupAssignmentDependencyDeviceTest.contact("unrelated", "unrelated-email")
        pending(unrelated)
        assertTrue(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision,
            RemoteGroupId(group.remoteLabelId!!), setOf(RemoteEmailId("service-first"), RemoteEmailId("service-second"))))
        assertFalse(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
        assertFalse(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), setOf(RemoteEmailId("unmapped-peer-email"))))
        assertTrue(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
        save(contact.copy(values = listOf(email("first", "same-service-id", 0), email("second", "same-service-id", 1))))
        assertFalse(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), setOf(RemoteEmailId("same-service-id"))))
        assertTrue(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
    }

    @Test fun verifiedEmptyLegacyGroupIsIndependentAndDependencyStateIsOwnerPurged() = runBlocking {
        pending(contact("unrelated", "unrelated-email"))
        val group = group("empty", emptyList())
        assertTrue(dependencies.requiresVerifiedMembership(ACCOUNT, group.id))
        assertFalse(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision + 1,
            RemoteGroupId(group.remoteLabelId!!), emptySet()))
        assertTrue(dependencies.recordVerifiedMembership(ACCOUNT, group.id, group.revision,
            RemoteGroupId(requireNotNull(group.remoteLabelId)), emptySet()))
        assertFalse(dependencies.requiresVerifiedMembership(ACCOUNT, group.id))
        assertFalse(dependencies.hasPendingContactWriteForGroup(ACCOUNT, group.id))
        database.remoteGroupAssignmentDependencyDao().upsertBaseline(
            RemoteGroupDependencyBaselineEntity("foreign-owner", "foreign-group", "foreign-label", true))
        database.accountRemovalDao().deleteAccount(ACCOUNT.value)
        assertNull(database.remoteGroupAssignmentDependencyDao().baseline(ACCOUNT.value, group.id))
        assertNotNull(database.remoteGroupAssignmentDependencyDao().baseline("foreign-owner", "foreign-group"))
        database.accountRemovalDao().deleteAccount("foreign-owner")
        assertFalse(database.hasAccountScopedData())
    }

    private suspend fun contact(id: String, remoteEmail: String, groupId: String? = null): CanonicalContact {
        val value = email("email-$id", remoteEmail, 0).let { email ->
            if (groupId == null) email else email.copy(metadata = email.metadata + (PROTON_GROUP_IDS_KEY to groupId)) }
        return CanonicalContact(ACCOUNT.value, id, displayName = "Synthetic $id", revision = 1,
            remoteContactId = "remote-$id", remoteVersion = "v1", values = listOf(value)).also { save(it) }
    }

    private fun email(id: String, remoteEmail: String, position: Int) = ContactValue(id, ContactValueKind.EMAIL,
        "same-synthetic@example.test", order = position, metadata = mapOf(PROTON_EMAIL_ID_KEY to remoteEmail))

    private suspend fun save(contact: CanonicalContact) {
        database.contactDao().upsert(contact.toEntity())
        database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
    }

    private suspend fun pending(contact: CanonicalContact, deleted: Boolean = false) {
        database.contactDao().upsert(contact.copy(pendingMutationRevision = contact.revision, isDeleted = deleted).toEntity())
        database.outboxDao().upsert(OutboxMutationEntity(ACCOUNT.value, "CONTACT", contact.id,
            if (deleted) "DELETE" else "UPSERT", contact.revision, 100, 100, blockedReason = "VALIDATION_REJECTED",
            idempotencyKey = "synthetic-${contact.id}", state = "ACTION_REQUIRED"))
    }

    private suspend fun clearPending(contact: CanonicalContact) {
        database.contactDao().upsert(contact.copy(pendingMutationRevision = null).toEntity())
        database.outboxDao().deleteRevision(ACCOUNT.value, "CONTACT", contact.id, contact.revision)
    }

    private suspend fun group(id: String, memberships: List<GroupMembership>, remote: String = "remote-$id"): ContactGroup {
        val group = ContactGroup(ACCOUNT.value, id, "Synthetic $id", revision = 1,
            remoteLabelId = remote, remoteVersion = "v1", memberships = memberships)
        database.contactGroupDao().upsert(group.toEntity())
        database.contactGroupDao().insertMemberships(group.memberships.map { it.toEntity(group) })
        return group
    }

    companion object {
        private val ACCOUNT = AccountScope("synthetic-group-dependencies")
        private const val DB = "synthetic-group-assignment-dependencies.db"
    }
}
