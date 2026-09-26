package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.sync.IncrementalRemoteGroupStage
import com.patmanak.contako.data.sync.RemoteGroupStageResult
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.SaveResult
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
class RoomRemoteGroupReconciliationStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private var remote = listOf(remoteGroup("remote-group", "Friends", "#123456"))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(database, clock = { 1_000 })
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun remoteGroupImportAndCleanUpdateRemainCanonical() = runBlocking {
        assertTrue(stage().run(ACCOUNT) is RemoteGroupStageResult.Success)
        var group = repository.observeGroups(ACCOUNT.value).first().single()
        assertEquals("Friends", group.name)
        assertNull(group.pendingMutationRevision)

        remote = listOf(remoteGroup("remote-group", "Close friends", "#654321"))
        stage().run(ACCOUNT)
        group = repository.observeGroups(ACCOUNT.value).first().single()
        assertEquals("Close friends", group.name)
        assertEquals("#654321", group.color)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun localGroupUpdateWinsWhenRemoteTimeIsUnavailable() = runBlocking {
        stage().run(ACCOUNT)
        val imported = repository.observeGroups(ACCOUNT.value).first().single()
        save(imported.copy(name = "Local name"))
        remote = listOf(remoteGroup("remote-group", "Remote name", "#654321"))

        stage().run(ACCOUNT)

        val retained = repository.observeGroups(ACCOUNT.value).first().single()
        assertEquals("Local name", retained.name)
        assertEquals(remote.single().fingerprint(), retained.remoteVersion)
        val outbox = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(DurableMutationState.PENDING.name, outbox.state)
        assertEquals(remote.single().fingerprint(), outbox.remoteVersion)
    }

    @Test
    fun localDeleteAgainstRemoteEditRequiresExplicitRecovery() = runBlocking {
        stage().run(ACCOUNT)
        repository.deleteGroup(ACCOUNT.value, "remote-group")
        remote = listOf(remoteGroup("remote-group", "Remote edit", "#654321"))

        stage().run(ACCOUNT)

        val blocked = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, "remote-group")).toDomain()
        assertEquals("GROUP_EDIT_DELETE_RECOVERY_REQUIRED", blocked.conflictState)
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, database.outboxDao().getAll(ACCOUNT.value).single().state)
    }

    @Test
    fun remoteAbsenceConvergesLocalDeleteButBlocksLocalEdit() = runBlocking {
        stage().run(ACCOUNT)
        repository.deleteGroup(ACCOUNT.value, "remote-group")
        remote = emptyList()
        stage().run(ACCOUNT)
        val deleted = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, "remote-group")).toDomain()
        assertTrue(deleted.isDeleted)
        assertNull(deleted.pendingMutationRevision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())

        database.close()
        context.deleteDatabase(DATABASE_NAME)
        setUp()
        remote = listOf(remoteGroup("remote-group", "Friends", "#123456"))
        stage().run(ACCOUNT)
        val imported = repository.observeGroups(ACCOUNT.value).first().single()
        save(imported.copy(name = "Pending edit"))
        remote = emptyList()
        stage().run(ACCOUNT)
        val blocked = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, "remote-group")).toDomain()
        assertFalse(blocked.isDeleted)
        assertEquals("REMOTE_GROUP_DELETION_RECOVERY_REQUIRED", blocked.conflictState)
    }

    private fun stage() = IncrementalRemoteGroupStage(
        object : ProtonContactGroupGateway {
            override fun capabilities() = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE
            override suspend fun list(account: AccountScope) = GatewayOutcome.Success(AvailableContactGroups(remote))
            override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create) =
                error("NO_MUTATION")
            override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update) =
                error("NO_MUTATION")
            override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete) =
                error("NO_MUTATION")
        },
        RoomRemoteGroupReconciliationStore(database),
    )

    private suspend fun save(group: ContactGroup): ContactGroup = when (val result = repository.saveGroup(group)) {
        is SaveResult.Saved -> result.value
        is SaveResult.Rejected -> error("UNEXPECTED_REJECTION")
    }

    private fun remoteGroup(id: String, name: String, color: String) =
        RemoteContactGroup(RemoteGroupId(id), name, color)

    private companion object {
        const val DATABASE_NAME = "v03-remote-groups.db"
        val ACCOUNT = AccountScope("v03-account")
    }
}
