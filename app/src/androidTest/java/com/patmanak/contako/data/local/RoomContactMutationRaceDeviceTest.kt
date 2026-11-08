package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader
import com.patmanak.contako.data.sync.DurableMutationCommand
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.data.sync.RetryDecision
import com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room transactions and reopen; all peer acknowledgements and values are synthetic. */
@RunWith(AndroidJUnit4::class)
class RoomContactMutationRaceDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private var wallClock = 50_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE)
        open()
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun editingAnAmbiguousCreateRetainsItsDeliveryProofAcrossDatabaseReopen() = runBlocking {
        create()
        val command = command()
        val execution = RoomMutationExecutionStore(database)
        assertTrue(execution.claim(command, wallClock))
        assertTrue(execution.recordFailure(command, GatewayFailureCategory.TIMEOUT,
            RetryDecision.RetryAt(1, wallClock + 5_000, reconcileBeforeReplay = true)))
        val ambiguous = intent()
        assertTrue(ambiguous.requiresReconciliation)

        edit("Newer local name")
        reopen()

        val pending = intent()
        assertEquals(2L, pending.revision)
        assertEquals(MutationOperation.UPSERT.name, pending.operation)
        assertTrue(pending.requiresReconciliation)
        assertEquals(ambiguous.lastAttemptAtEpochMillis, pending.lastAttemptAtEpochMillis)
        assertEquals(ambiguous.createdAtEpochMillis, pending.createdAtEpochMillis)
        assertNull(pending.remoteIdentity)
        assertEquals("Newer local name", current().displayName)
        assertEquals(listOf(LOCAL_ID), database.contactDao().getUnboundCreateCandidates(ACCOUNT, LOCAL_ID)
            .map { it.contact.id })
        assertTrue(database.contactDao().getUnboundCreateCandidates("other-account", LOCAL_ID).isEmpty())
    }

    @Test
    fun deletingAnAmbiguousCreateRetainsTheTombstoneAsAnExactUidRecoveryCandidate() = runBlocking {
        create()
        val command = command()
        val execution = RoomMutationExecutionStore(database)
        assertTrue(execution.claim(command, wallClock))
        assertTrue(execution.recordFailure(command, GatewayFailureCategory.NETWORK_UNAVAILABLE,
            RetryDecision.RetryAt(1, wallClock + 5_000, reconcileBeforeReplay = true)))
        val attemptedAt = intent().lastAttemptAtEpochMillis
        wallClock++
        repository.deleteContact(ACCOUNT, LOCAL_ID)
        reopen()

        assertTrue(current().isDeleted)
        assertTrue(repository.observeContacts(ACCOUNT).first().isEmpty())
        assertEquals(MutationOperation.DELETE.name, intent().operation)
        assertTrue(intent().requiresReconciliation)
        assertEquals(attemptedAt, intent().lastAttemptAtEpochMillis)
        val candidates = database.contactDao().getUnboundCreateCandidates(ACCOUNT, LOCAL_ID)
        assertEquals(listOf(LOCAL_ID), candidates.map { it.contact.id })
        assertTrue(candidates.single().contact.isDeleted)
        assertTrue(database.contactDao().getUnboundCreateCandidates(ACCOUNT, "different-uid").isEmpty())
    }

    @Test
    fun createAcknowledgementAfterAnEditBindsIdentityAndKeepsOnlyTheNewerUpdatePending() = runBlocking {
        create()
        val creating = command()
        val execution = RoomMutationExecutionStore(database)
        assertEquals(RemoteMutationOperation.CREATE, creating.operation)
        assertTrue(execution.claim(creating, wallClock))
        edit("Name edited while creating")
        val newer = current()

        assertTrue(execution.acknowledgeAndFinish(creating, acknowledgement("created-version")))
        reopen()

        val actual = current()
        assertEquals(LOCAL_ID, actual.id)
        assertEquals(newer.displayName, actual.displayName)
        assertEquals(newer.revision, actual.revision)
        assertEquals(newer.revision, actual.pendingMutationRevision)
        assertEquals(REMOTE_ID, actual.remoteContactId)
        assertEquals("created-version", actual.remoteVersion)
        assertEquals(listOf(LOCAL_ID), repository.observeContacts(ACCOUNT).first().map { it.id })
        assertEquals(actual.revision, intent().revision)
        assertEquals(REMOTE_ID, intent().remoteIdentity)
        assertEquals("created-version", intent().remoteVersion)
        assertEquals(DurableMutationState.PENDING.name, intent().state)
        assertFalse(intent().requiresReconciliation)
        assertEquals(RemoteMutationOperation.UPDATE, command().operation)
        assertEquals(REMOTE_ID, database.androidProjectionLedgerDao().get(ACCOUNT, LOCAL_ID)?.sourceIdentity)
        assertNull(database.contactConflictDao().get(ACCOUNT, LOCAL_ID))
    }

    @Test
    fun createAcknowledgementAfterDeletionKeepsTheRemoteDeleteDurable() = runBlocking {
        create()
        val creating = command()
        val execution = RoomMutationExecutionStore(database)
        assertTrue(execution.claim(creating, wallClock))
        wallClock++
        repository.deleteContact(ACCOUNT, LOCAL_ID)
        val deleteRevision = current().revision
        assertTrue(intent().requiresReconciliation)

        assertTrue(execution.acknowledgeAndFinish(creating, acknowledgement("created-version")))
        reopen()

        assertTrue(current().isDeleted)
        assertEquals(deleteRevision, current().pendingMutationRevision)
        assertEquals(REMOTE_ID, current().remoteContactId)
        assertEquals(deleteRevision, intent().revision)
        assertEquals(REMOTE_ID, intent().remoteIdentity)
        assertEquals(MutationOperation.DELETE.name, intent().operation)
        assertEquals(DurableMutationState.PENDING.name, intent().state)
        assertEquals(RemoteMutationOperation.DELETE, command().operation)
        assertTrue(repository.observeContacts(ACCOUNT).first().isEmpty())
    }

    @Test
    fun updateAcknowledgementAfterAnEditAdvancesTheBaselineWithoutInventingAConflict() = runBlocking {
        cleanCreatedContact()
        edit("First update sent")
        val sent = current()
        val updating = command()
        val execution = RoomMutationExecutionStore(database)
        assertEquals(RemoteMutationOperation.UPDATE, updating.operation)
        assertTrue(execution.claim(updating, wallClock))
        edit("Second update still local")
        val pendingRevision = current().revision

        assertTrue(execution.acknowledgeAndFinish(updating, acknowledgement("updated-version")))
        reopen()

        assertEquals("Second update still local", current().displayName)
        assertEquals(pendingRevision, current().pendingMutationRevision)
        assertEquals("updated-version", current().remoteVersion)
        assertEquals("updated-version", intent().remoteVersion)
        assertEquals(DurableMutationState.PENDING.name, intent().state)
        val verifiedSent = VerifiedContactCard(RemoteContactId(REMOTE_ID), RemoteVersion("updated-version"),
            sent.copy(remoteContactId = REMOTE_ID, remoteVersion = "updated-version", pendingMutationRevision = null))
        var fullReads = 0
        val preparation = RoomBackedMutationPreparationGateway(AccountScope(ACCOUNT), database,
            ProtonEmailGroupMembershipReader { _, _ -> error("NO_GROUP_READ") },
            ProtonVerifiedContactCardGateway { account, id ->
                assertEquals(AccountScope(ACCOUNT), account)
                assertEquals(RemoteContactId(REMOTE_ID), id)
                fullReads++
                GatewayOutcome.Success(verifiedSent)
            })
        val result = preparation.prepare(command())
        assertEquals(MutationPreparation.UploadAllowed, (result as GatewayOutcome.Success).value)
        assertEquals(1, fullReads)
        assertNull(database.contactConflictDao().get(ACCOUNT, LOCAL_ID))
        assertEquals("Second update still local", current().displayName)
        assertEquals(pendingRevision, current().pendingMutationRevision)
    }

    @Test
    fun supersededUpdateAcknowledgementCannotRebindAContactToAnotherRemoteIdentity() = runBlocking {
        cleanCreatedContact()
        edit("First update sent")
        val updating = command()
        val execution = RoomMutationExecutionStore(database)
        assertTrue(execution.claim(updating, wallClock))
        edit("Second update still local")
        val beforeContact = current()
        val beforeIntent = intent()
        val beforeLedger = database.androidProjectionLedgerDao().get(ACCOUNT, LOCAL_ID)

        assertFalse(execution.acknowledgeAndFinish(updating,
            RemoteMutationAcknowledgement("unrelated-remote", "unexpected-version")))

        assertEquals(beforeContact, current())
        assertEquals(beforeIntent, intent())
        assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(ACCOUNT, LOCAL_ID))
    }

    @Test
    fun supersededCreateAcknowledgementRetainsTheExistingAndroidSourceGuard() = runBlocking {
        create()
        val creating = command()
        val execution = RoomMutationExecutionStore(database)
        assertTrue(execution.claim(creating, wallClock))
        edit("Newer pending name")
        RoomAndroidProjectionLedger(database).attachCanonicalContact(AccountScope(ACCOUNT), LOCAL_ID,
            "already-bound-source")
        val beforeContact = current()
        val beforeIntent = intent()
        val beforeLedger = database.androidProjectionLedgerDao().get(ACCOUNT, LOCAL_ID)

        assertFalse(execution.acknowledgeAndFinish(creating, acknowledgement("created-version")))

        assertEquals(beforeContact, current())
        assertEquals(beforeIntent, intent())
        assertEquals(beforeLedger, database.androidProjectionLedgerDao().get(ACCOUNT, LOCAL_ID))
    }

    private suspend fun create() = save(CanonicalContact(ACCOUNT, LOCAL_ID, displayName = "Initial fixture"))

    private suspend fun edit(name: String) {
        wallClock++
        save(current().copy(displayName = name))
    }

    private suspend fun cleanCreatedContact() {
        create()
        val execution = RoomMutationExecutionStore(database)
        val creating = command()
        check(execution.claim(creating, wallClock))
        check(execution.acknowledgeAndFinish(creating, acknowledgement("initial-version")))
        check(current().pendingMutationRevision == null)
    }

    private suspend fun save(contact: CanonicalContact): CanonicalContact = when (val result = repository.saveContact(contact)) {
        is SaveResult.Saved -> result.value
        is SaveResult.Rejected -> error("UNEXPECTED_CONTACT_REJECTION")
    }

    private suspend fun current(): CanonicalContact = requireNotNull(repository.getContact(ACCOUNT, LOCAL_ID))

    private suspend fun intent(): OutboxMutationEntity =
        requireNotNull(database.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, LOCAL_ID))

    private suspend fun command(): DurableMutationCommand =
        RoomMutationExecutionStore(database).eligible(ACCOUNT, wallClock, 100).single()

    private fun acknowledgement(version: String) = RemoteMutationAcknowledgement(REMOTE_ID, version)

    private fun reopen() {
        database.close()
        open()
    }

    private fun open() {
        database = ContakoDatabase.create(context, DATABASE)
        repository = RoomContactRepository(database, clock = { wallClock })
    }

    private companion object {
        const val DATABASE = "contact-mutation-race-fixtures.db"
        const val ACCOUNT = "mutation-race-fixture-account"
        const val LOCAL_ID = "fixture-local-uid"
        const val REMOTE_ID = "fixture-remote-contact"
    }
}
