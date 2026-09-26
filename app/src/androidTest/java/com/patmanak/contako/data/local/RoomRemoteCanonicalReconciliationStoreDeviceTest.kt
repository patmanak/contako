package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidIngestionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.sync.IncrementalRemoteContactStage
import com.patmanak.contako.data.sync.RemoteContactStageResult
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.ServerClockCalibration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRemoteCanonicalReconciliationStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private var wallClock = 12_000_000L
    private var elapsedClock = 2_001_000L
    private var remote = listOf(remoteFixture("remote", "Remote One", 1, 10_000, "stable-uid"))
    private var hydrationCount = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(
            database,
            clock = { wallClock },
            elapsedRealtimeClock = { elapsedClock },
            calibrationProvider = {
                ServerClockCalibration(
                    observedDeviceWallClockEpochMillis = 10_000_000,
                    observedDeviceElapsedRealtimeMillis = 1_000,
                    serverOffsetMillis = 0,
                    roundTripMillis = 0,
                    serverPrecisionMillis = 1_000,
                )
            },
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun remoteImportIsCanonicalAndSecondNoChangePassDoesNotHydrate() = runBlocking {
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        val imported = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals("Remote One", imported.displayName)
        assertEquals("version-1", imported.remoteVersion)
        assertNull(imported.pendingMutationRevision)
        assertEquals(1, hydrationCount)

        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(1, hydrationCount)
    }

    @Test
    fun remoteHydrationInvalidatesExistingContactAndMembershipProjectionIntents() = runBlocking {
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        val ledgerDao = database.androidProjectionLedgerDao()
        val initialContactLedger = requireNotNull(ledgerDao.get(ACCOUNT.value, "remote"))
        check(ledgerDao.update(initialContactLedger.copy(
            revision = Math.incrementExact(initialContactLedger.revision),
            projectionState = AndroidProjectionWriteState.CLEAN.name,
        )) == 1)
        val groupDao = database.androidGroupProjectionDao()
        val pendingFingerprint = "0".repeat(64)
        check(groupDao.insertMembership(AndroidGroupMembershipProjectionLedgerEntity(
            accountId = ACCOUNT.value,
            canonicalContactId = "remote",
            revision = 0,
            providerEpoch = initialContactLedger.providerEpoch,
            rawContactLocator = null,
            preferredEmailValueId = null,
            canonicalProjectionFingerprint = null,
            androidBaselineFingerprint = null,
            pendingProjectionFingerprint = pendingFingerprint,
            projectionState = AndroidProjectionWriteState.WRITE_PENDING.name,
            ingestionState = AndroidIngestionState.NONE.name,
        )) != -1L)

        remote = listOf(remoteFixture("remote", "Remote Changed", 2, 11_000, "stable-uid"))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)

        val contactLedger = requireNotNull(ledgerDao.get(ACCOUNT.value, "remote"))
        val membershipLedger = requireNotNull(groupDao.getMembership(ACCOUNT.value, "remote"))
        assertEquals(AndroidProjectionWriteState.DETACHED.name, contactLedger.projectionState)
        assertEquals(AndroidProjectionWriteState.DETACHED.name, membershipLedger.projectionState)
        assertNull(contactLedger.pendingProjectionFingerprint)
        assertNull(membershipLedger.pendingProjectionFingerprint)
        assertEquals(Math.incrementExact(initialContactLedger.revision) + 1, contactLedger.revision)
        assertEquals(1, membershipLedger.revision)
    }

    @Test
    fun localLaterUpdateWinsAndAbsorbsRemotePreservationWithoutLosingIntent() = runBlocking {
        stage().run(ACCOUNT)
        save(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).copy(displayName = "Local Later"))
        remote = listOf(remoteFixture("remote", "Remote Earlier", 2, 11_000, "stable-uid", "remote-unknown"))

        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)

        val retained = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals("Local Later", retained.displayName)
        assertEquals("version-2", retained.remoteVersion)
        assertEquals("remote-unknown", retained.preservationEnvelope?.rawProperties?.get("proton-card-9"))
        val outbox = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals("version-2", outbox.remoteVersion)
        assertEquals(DurableMutationState.PENDING.name, outbox.state)
    }

    @Test
    fun remoteStrictlyLaterUpdateWinsAndSupersedesPendingLocalRevision() = runBlocking {
        stage().run(ACCOUNT)
        wallClock = 10_500_000
        elapsedClock = 501_000
        save(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).copy(displayName = "Local Earlier"))
        remote = listOf(remoteFixture("remote", "Remote Later", 2, 12_000, "stable-uid"))

        stage().run(ACCOUNT)

        val adopted = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals("Remote Later", adopted.displayName)
        assertNull(adopted.pendingMutationRevision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun `overlapping local delete and remote edit requires action instead of guessing`() = runBlocking {
        stage().run(ACCOUNT)
        wallClock = 12_000_500
        elapsedClock = 2_001_500
        repository.deleteContact(ACCOUNT.value, "remote")
        remote = listOf(remoteFixture("remote", "Concurrent Remote", 2, 12_000, "stable-uid"))

        stage().run(ACCOUNT)

        val blocked = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertTrue(blocked.isDeleted)
        assertEquals("EDIT_DELETE_RECOVERY_REQUIRED", blocked.conflictState)
        val outbox = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, outbox.state)
        assertEquals("EDIT_DELETE_RECOVERY_REQUIRED", outbox.blockedReason)
    }

    @Test
    fun unchangedHydratedCardDoesNotTurnPendingDeleteIntoConflict() = runBlocking {
        stage().run(ACCOUNT)
        val original = remote.single().card
        wallClock = 12_000_500
        elapsedClock = 2_001_500
        repository.deleteContact(ACCOUNT.value, "remote")
        // The index changes (e.g. independent group writes), but verified content is unchanged.
        remote = listOf(remoteFixture("remote", "Remote One", 2, 12_000, "stable-uid").copy(card = original))
        stage().run(ACCOUNT)
        val pending = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(DurableMutationState.PENDING.name, pending.state)
        assertEquals(MutationOperation.DELETE.name, pending.operation)
        assertEquals("version-1", pending.remoteVersion)
        assertNull(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).conflictState)
        assertTrue(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).isDeleted)
    }

    @Test
    fun verifiedLegacyFullCardAliasCanResumeOnlyTheMatchingDeleteConflict() = runBlocking {
        stage().run(ACCOUNT)
        val original = remote.single().card
        repository.deleteContact(ACCOUNT.value, "remote")
        val pending = database.outboxDao().getAll(ACCOUNT.value).single()
        database.outboxDao().blockConflict(ACCOUNT.value, "CONTACT", "remote", pending.revision, "EDIT_DELETE_RECOVERY_REQUIRED")
        remote = listOf(remoteFixture("remote", "Remote One", 2, 12_000, "stable-uid").copy(
            card = original.copy(version = RemoteVersion("content-v1:new"), compatibleVersions = setOf(checkNotNull(original.version))),
        ))
        stage().run(ACCOUNT)
        val resumed = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(DurableMutationState.PENDING.name, resumed.state)
        assertNull(resumed.blockedReason)
        assertNull(resumed.errorCategory)
        assertEquals(pending.revision, resumed.revision)
    }

    @Test
    fun remoteDeletionWithoutTimestampBlocksPendingEditButDeletesCleanContact() = runBlocking {
        stage().run(ACCOUNT)
        save(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).copy(displayName = "Pending"))
        remote = emptyList()

        stage().run(ACCOUNT)
        val blocked = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertFalse(blocked.isDeleted)
        assertEquals("REMOTE_DELETION_RECOVERY_REQUIRED", blocked.conflictState)

        database.close()
        context.deleteDatabase(DATABASE_NAME)
        setUp()
        remote = listOf(remoteFixture("remote", "Remote One", 1, 10_000, "stable-uid"))
        stage().run(ACCOUNT)
        remote = emptyList()
        stage().run(ACCOUNT)
        assertTrue(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).isDeleted)
    }

    @Test
    fun lostCreateAcknowledgementMatchesStableVCardUidInsteadOfDuplicatingLocalContact() = runBlocking {
        remote = emptyList()
        stage().run(ACCOUNT)
        wallClock = 10_500_000
        elapsedClock = 501_000
        save(CanonicalContact(accountId = ACCOUNT.value, id = "local-uid", displayName = "Pending Create"))
        remote = listOf(remoteFixture("remote-created", "Pending Create", 1, 12_000, "local-uid"))

        stage().run(ACCOUNT)

        val reconciled = requireNotNull(repository.getContact(ACCOUNT.value, "local-uid"))
        assertEquals("remote-created", reconciled.remoteContactId)
        assertNull(reconciled.pendingMutationRevision)
        assertNull(repository.getContact(ACCOUNT.value, "remote-created"))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun lostDeleteAcknowledgementConvergesWhenCompleteInventoryProvesAbsence() = runBlocking {
        stage().run(ACCOUNT)
        repository.deleteContact(ACCOUNT.value, "remote")
        remote = emptyList()

        stage().run(ACCOUNT)

        val tombstone = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertTrue(tombstone.isDeleted)
        assertNull(tombstone.pendingMutationRevision)
        assertNull(tombstone.conflictState)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test
    fun hydrationPreservesPendingGroupIntentAcrossEmailValueIdentityChange() = runBlocking {
        val email = com.patmanak.contako.domain.model.ContactValue(
            "email-old", com.patmanak.contako.domain.model.ContactValueKind.EMAIL,
            "fixture@example.test", order = 0,
        )
        val original = remote.single()
        remote = listOf(original.copy(card = original.card.copy(contact = original.card.contact.copy(values = listOf(email)))))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        repository.saveGroup(com.patmanak.contako.domain.model.ContactGroup(
            ACCOUNT.value, "pending-group", "Pending", "#6D4AFF",
            memberships = listOf(com.patmanak.contako.domain.model.GroupMembership("remote", email.id)),
            remoteLabelId = "remote-group",
        ))
        val changed = remoteFixture("remote", "Remote Changed", 2, 11_000, "stable-uid")
        remote = listOf(changed.copy(card = changed.card.copy(contact = changed.card.contact.copy(values = listOf(email.copy(id = "email-new"))))))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        val pending = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, "pending-group"))
        assertEquals(listOf("email-new"), pending.memberships.map { it.emailValueId })
        assertTrue(pending.group.pendingMutationRevision != null)
        assertEquals(1, database.outboxDao().getAll(ACCOUNT.value).size)
    }

    private fun stage(): IncrementalRemoteContactStage {
        val inventoryGateway = ProtonContactInventoryGateway { _, cursor ->
            check(cursor == null)
            GatewayOutcome.Success(
                ContactInventoryPage(
                    contacts = remote.map(RemoteFixture::metadata),
                    requestedCursor = null,
                    nextCursor = null,
                    totalCount = remote.size,
                    snapshotAuthority = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            )
        }
        val cardGateway = ProtonVerifiedContactCardGateway { _, id ->
            hydrationCount++
            GatewayOutcome.Success(remote.single { it.metadata.id == id }.card)
        }
        return IncrementalRemoteContactStage(
            inventoryGateway,
            cardGateway,
            PersistentContactInventoryPlanner(RoomContactInventoryCheckpointStore(database)),
            RoomRemoteCanonicalReconciliationStore(database),
        )
    }

    private suspend fun save(contact: CanonicalContact): CanonicalContact = when (val result = repository.saveContact(contact)) {
        is SaveResult.Saved -> result.value
        is SaveResult.Rejected -> error("UNEXPECTED_REJECTION")
    }

    private fun remoteFixture(
        id: String,
        displayName: String,
        version: Int,
        modifiedSeconds: Long,
        uid: String,
        unknown: String = "unknown-$version",
    ): RemoteFixture {
        val remoteId = RemoteContactId(id)
        val remoteVersion = RemoteVersion("version-$version")
        return RemoteFixture(
            ContactInventoryMetadata(
                id = remoteId,
                displayName = displayName,
                version = remoteVersion,
                sizeBytes = 100,
                modifiedAtEpochSeconds = modifiedSeconds,
                emailIds = emptyList(),
                groupIds = emptyList(),
                versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
                coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
            ),
            VerifiedContactCard(
                remoteId,
                remoteVersion,
                CanonicalContact(
                    accountId = ACCOUNT.value,
                    id = id,
                    displayName = displayName,
                    remoteContactId = id,
                    remoteVCardUid = uid,
                    preservationEnvelope = PreservationEnvelope(mapOf("proton-card-9" to unknown)),
                ),
            ),
        )
    }

    private data class RemoteFixture(
        val metadata: ContactInventoryMetadata,
        val card: VerifiedContactCard,
    )

    private companion object {
        const val DATABASE_NAME = "v03-remote-reconciliation.db"
        val ACCOUNT = AccountScope("v03-account")
    }
}
