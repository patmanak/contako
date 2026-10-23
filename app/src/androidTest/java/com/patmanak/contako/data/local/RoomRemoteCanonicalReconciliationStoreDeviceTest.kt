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
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflight
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflightResult
import com.patmanak.contako.data.sync.AndroidInteroperabilityStage
import com.patmanak.contako.data.sync.AndroidInteroperabilityStageResult
import com.patmanak.contako.data.sync.ContakoSyncPassExecutor
import com.patmanak.contako.data.sync.DurableMutationOrchestrator
import com.patmanak.contako.data.sync.FullRepairPhase
import com.patmanak.contako.data.sync.IncrementalRemoteContactStage
import com.patmanak.contako.data.sync.MutationUploadGateway
import com.patmanak.contako.data.sync.RemoteContactStageResult
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway
import com.patmanak.contako.data.sync.RoomFullRepairExecutionCoordinator
import com.patmanak.contako.data.sync.RoomFullRepairProgressStore
import com.patmanak.contako.data.sync.SyncPrerequisiteChecker
import com.patmanak.contako.data.sync.SyncPrerequisiteState
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.ServerClockCalibration
import com.patmanak.contako.domain.sync.ContactConflictChoice
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncPassRequest
import com.patmanak.contako.domain.sync.SyncScope
import com.patmanak.contako.domain.sync.SyncTrigger
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
        openDatabase()
    }

    private fun openDatabase() {
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

    @Test fun remoteAdoptionAndProtonConflictChoiceRetainLocalOnlyDatesAndUrlLabels() = runBlocking {
        val kind = com.patmanak.contako.domain.model.ContactValueKind.URL
        val url = com.patmanak.contako.domain.model.ContactValue("remote-url-1", kind,
            "https://example.test/profile", order = 0)
        remote = remote.map { it.copy(card = it.card.copy(contact = it.card.contact.copy(values = listOf(url)))) }
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        val imported = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        val date = com.patmanak.contako.domain.model.ContactValue("custom-date",
            com.patmanak.contako.domain.model.ContactValueKind.CUSTOM_DATE, "--10-23", "Événement", 0)
        // Local complements have already been acknowledged; Proton cannot represent them.
        database.contactDao().upsertValues(listOf(date, url.copy(label = "Profile",
            metadata = mapOf("androidSemanticType" to "PROFILE"))).map { it.toEntity(imported) })
        val changed = remoteFixture("remote", "Remote note changed", 2, 12_000, "stable-uid")
        remote = listOf(changed.copy(card = changed.card.copy(contact = changed.card.contact.copy(
            values = listOf(url.copy(id = "remote-url-2"))))))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        var adopted = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals(date, adopted.valuesOf(com.patmanak.contako.domain.model.ContactValueKind.CUSTOM_DATE).single())
        assertEquals("Profile", adopted.valuesOf(kind).single().label)
        assertEquals("remote-url-2", adopted.valuesOf(kind).single().id)
        assertNull(adopted.pendingMutationRevision)

        save(adopted.copy(displayName = "Concurrent local change"))
        val newest = remoteFixture("remote", "Remote wins", 3, 13_000, "stable-uid")
        remote = listOf(newest.copy(card = newest.card.copy(contact = newest.card.contact.copy(values = listOf(url)))))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        val conflicts = RoomContactConflictStore(database)
        val detail = requireNotNull(conflicts.detail(ACCOUNT.value, "remote"))
        assertTrue(conflicts.choose(ACCOUNT.value, detail.summary, com.patmanak.contako.domain.sync.ContactConflictChoice.PROTON))
        assertEquals(com.patmanak.contako.data.sync.MutationPreparation.ResolvedWithoutUpload,
            conflicts.prepare(ACCOUNT.value, "remote", detail.summary.localRevision, remote.single().card))
        adopted = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals("Remote wins", adopted.displayName)
        assertEquals(date, adopted.valuesOf(com.patmanak.contako.domain.model.ContactValueKind.CUSTOM_DATE).single())
        assertEquals("Profile", adopted.valuesOf(kind).single().label)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        assertNull(conflicts.detail(ACCOUNT.value, "remote"))
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
    fun localLaterUpdateRetainsBothVersionsWithoutAdvancingUploadBaseline() = runBlocking {
        stage().run(ACCOUNT)
        save(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).copy(displayName = "Local Later"))
        remote = listOf(remoteFixture("remote", "Remote Earlier", 2, 11_000, "stable-uid", "remote-unknown"))

        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)

        val retained = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals("Local Later", retained.displayName)
        assertEquals("version-1", retained.remoteVersion)
        val conflict = requireNotNull(RoomContactConflictStore(database).detail(ACCOUNT.value, retained.id))
        assertEquals("remote-unknown", conflict.proton.preservationEnvelope?.rawProperties?.get("proton-card-9"))
        val outbox = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals("version-1", outbox.remoteVersion)
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, outbox.state)
    }

    @Test
    fun remoteStrictlyLaterUpdateStillRequiresUserChoice() = runBlocking {
        stage().run(ACCOUNT)
        wallClock = 10_500_000
        elapsedClock = 501_000
        save(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).copy(displayName = "Local Earlier"))
        remote = listOf(remoteFixture("remote", "Remote Later", 2, 12_000, "stable-uid"))

        stage().run(ACCOUNT)

        val adopted = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals("Local Earlier", adopted.displayName)
        assertTrue(adopted.pendingMutationRevision != null)
        assertEquals("Remote Later", requireNotNull(RoomContactConflictStore(database).detail(ACCOUNT.value, adopted.id)).proton.displayName)
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
        assertEquals(RoomContactConflictStore.CONFLICT, blocked.conflictState)
        val outbox = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, outbox.state)
        assertEquals(RoomContactConflictStore.CONFLICT, outbox.blockedReason)
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

    @Test fun remoteDeletionInvalidatesQueuedChoiceWithoutLosingDraft() = runBlocking {
        stage().run(ACCOUNT)
        save(requireNotNull(repository.getContact(ACCOUNT.value, "remote")).copy(displayName = "Local draft"))
        remote = listOf(remoteFixture("remote", "Remote change", 2, 12_000, "stable-uid"))
        stage().run(ACCOUNT)
        val conflicts = RoomContactConflictStore(database)
        val detail = requireNotNull(conflicts.detail(ACCOUNT.value, "remote"))
        assertTrue(conflicts.choose(ACCOUNT.value, detail.summary, com.patmanak.contako.domain.sync.ContactConflictChoice.PROTON))
        remote = emptyList()
        stage().run(ACCOUNT)
        val updated = requireNotNull(conflicts.detail(ACCOUNT.value, "remote"))
        assertTrue(updated.summary.remoteDeleted)
        assertNull(updated.summary.choice)
        assertEquals("Local draft", repository.getContact(ACCOUNT.value, "remote")?.displayName)
    }

    @Test
    fun lostCreateAcknowledgementMatchesStableVCardUidInsteadOfDuplicatingLocalContact() = runBlocking {
        recoverInterruptedCreate(SyncScope.INCREMENTAL)
    }

    @Test
    fun fullRepairRecoversInterruptedCreateBeforeHydrationWithoutDuplicatingLocalContact() = runBlocking {
        recoverInterruptedCreate(SyncScope.FULL_REPAIR)
    }

    @Test
    fun advancedFullRepairRetriesRecoveredCreateAfterInventoryFailureWithoutDuplicatingLocalContact() = runBlocking {
        recoverInterruptedCreate(SyncScope.FULL_REPAIR, resumeAfterProjection = true)
    }

    private suspend fun recoverInterruptedCreate(scope: SyncScope, resumeAfterProjection: Boolean = false) {
        remote = emptyList()
        stage().run(ACCOUNT)
        wallClock = 10_500_000
        elapsedClock = 501_000
        save(CanonicalContact(accountId = ACCOUNT.value, id = "local-uid", displayName = "Pending Create"))
        if (scope == SyncScope.FULL_REPAIR) {
            val progress = RoomFullRepairProgressStore(database)
            progress.beginOrResume(ACCOUNT, false, false, wallClock)
            if (resumeAfterProjection) {
                for ((phase, completed) in listOf(
                    FullRepairPhase.REMOTE_ENUMERATION to 1L,
                    FullRepairPhase.CANONICAL_RECONCILIATION to 0L,
                    FullRepairPhase.CANONICAL_RECONCILIATION to 1L,
                    FullRepairPhase.ANDROID_PROJECTION to 0L,
                    FullRepairPhase.ANDROID_PROJECTION to 1L,
                )) {
                    assertTrue(progress.checkpoint(ACCOUNT, requireNotNull(progress.load(ACCOUNT)).revision,
                        phase, completed, 1, wallClock))
                }
            }
        }
        val mutations = RoomMutationExecutionStore(database)
        val command = mutations.eligible(ACCOUNT.value, wallClock, 100).single()
        assertEquals(RemoteMutationOperation.CREATE, command.operation)
        assertFalse(command.requiresReconciliation)
        assertTrue(mutations.claim(command, wallClock))
        val claimed = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals(DurableMutationState.IN_FLIGHT.name, claimed.state)
        assertFalse(claimed.requiresReconciliation)
        // Proton accepted the create, but the process stopped before its acknowledgement.
        remote = listOf(remoteFixture("remote-created", "Pending Create", 1, 12_000, "local-uid"))
        database.close()
        openDatabase()
        if (resumeAfterProjection) {
            val failedResume = passExecutor(inventoryFailure = { GatewayFailureCategory.NETWORK_UNAVAILABLE })
            assertEquals(SyncPassOutcome.RETRY_WAITING, failedResume.execute(passRequest(scope)))
            val recoveredBeforeInventory = database.outboxDao().getAll(ACCOUNT.value).single()
            assertEquals(DurableMutationState.PENDING.name, recoveredBeforeInventory.state)
            assertTrue(recoveredBeforeInventory.requiresReconciliation)
            assertNull(repository.getContact(ACCOUNT.value, "local-uid")?.remoteContactId)
            // The next restart has no IN_FLIGHT row left to recover. Its completed repair
            // phase must still trigger a fresh incremental check and projection traversal.
            database.close()
            openDatabase()
        }
        var projections = 0
        val restarted = passExecutor(onProject = { projections++ })

        assertEquals(SyncPassOutcome.ACTION_REQUIRED, restarted.execute(passRequest(scope)))
        assertTrue(projections > 0)
        if (resumeAfterProjection) {
            val progress = requireNotNull(RoomFullRepairProgressStore(database).load(ACCOUNT))
            assertEquals(FullRepairPhase.ANDROID_PROJECTION, progress.phase)
            assertEquals(1L, progress.completedUnits)
        }

        val reconciled = requireNotNull(repository.getContact(ACCOUNT.value, "local-uid"))
        assertEquals("remote-created", reconciled.remoteContactId)
        assertTrue(reconciled.pendingMutationRevision != null)
        assertEquals(listOf("local-uid"), repository.observeContacts(ACCOUNT.value).first().map { it.id })
        assertNull(repository.getContact(ACCOUNT.value, "remote-created"))
        val recovered = database.outboxDao().getAll(ACCOUNT.value).single()
        assertEquals("remote-created", recovered.remoteIdentity)
        assertEquals(claimed.revision, recovered.revision)
        assertEquals(claimed.idempotencyKey, recovered.idempotencyKey)
        assertTrue(recovered.requiresReconciliation)

        // Matching UID restores identity, not authority to overwrite either version. The
        // normal guarded conflict choice must then be able to finish without another create.
        val conflicts = RoomContactConflictStore(database)
        val detail = requireNotNull(conflicts.detail(ACCOUNT.value, "local-uid"))
        assertTrue(conflicts.choose(ACCOUNT.value, detail.summary, ContactConflictChoice.PROTON))
        assertEquals(SyncPassOutcome.SUCCESS, restarted.execute(passRequest(scope)))
        assertNull(repository.getContact(ACCOUNT.value, "local-uid")?.pendingMutationRevision)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        val hydrated = hydrationCount
        assertEquals(SyncPassOutcome.SUCCESS, restarted.execute(passRequest(SyncScope.INCREMENTAL)))
        assertEquals(hydrated, hydrationCount)
        assertEquals(listOf("local-uid"), repository.observeContacts(ACCOUNT.value).first().map { it.id })
    }

    @Test fun sharedUidDoesNotMergeDistinctRemoteIdsInEitherImportOrder() = runBlocking {
        for (reverse in listOf(false, true)) {
            val prefix = if (reverse) "reverse" else "forward"
            val first = "$prefix-first"
            val second = "$prefix-second"
            val pair = listOf(remoteFixture(first, "First fixture", 1, 12_000, "shared-$prefix"),
                remoteFixture(second, "Second fixture", 1, 12_000, "shared-$prefix"))
            remote = remote + if (reverse) pair.reversed() else pair
            assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
            assertEquals(first, repository.getContact(ACCOUNT.value, first)?.remoteContactId)
            assertEquals(second, repository.getContact(ACCOUNT.value, second)?.remoteContactId)
            assertEquals(first, database.androidProjectionLedgerDao().get(ACCOUNT.value, first)?.sourceIdentity)
            assertEquals(second, database.androidProjectionLedgerDao().get(ACCOUNT.value, second)?.sourceIdentity)
            val hydrated = hydrationCount
            assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
            assertEquals(hydrated, hydrationCount)
            save(requireNotNull(repository.getContact(ACCOUNT.value, first)).copy(displayName = "Edited first"))
            assertEquals("Second fixture", repository.getContact(ACCOUNT.value, second)?.displayName)
            assertNull(repository.getContact(ACCOUNT.value, second)?.pendingMutationRevision)
            repository.deleteContact(ACCOUNT.value, first)
            assertFalse(requireNotNull(repository.getContact(ACCOUNT.value, second)).isDeleted)
        }
    }

    @Test fun uidMatchWithoutAmbiguousCreateDoesNotAdoptAnUnsentDraft() = runBlocking {
        save(CanonicalContact(ACCOUNT.value, "not-sent", displayName = "Local draft"))
        remote = listOf(remoteFixture("remote-imported", "Remote import", 1, 12_000, "not-sent"))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertNull(repository.getContact(ACCOUNT.value, "not-sent")?.remoteContactId)
        assertEquals("Local draft", repository.getContact(ACCOUNT.value, "not-sent")?.displayName)
        assertEquals("remote-imported", repository.getContact(ACCOUNT.value, "remote-imported")?.remoteContactId)
    }

    @Test fun multipleAmbiguousLocalCreatesWithSharedUidAreNotGuessed() = runBlocking {
        for (id in listOf("draft-one", "draft-two")) {
            save(CanonicalContact(ACCOUNT.value, id, displayName = "Local draft", remoteVCardUid = "duplicate-uid"))
            val intent = requireNotNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", id))
            database.outboxDao().upsert(intent.copy(requiresReconciliation = true))
        }
        remote = listOf(remoteFixture("remote-imported", "Remote import", 1, 12_000, "duplicate-uid"))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        for (id in listOf("draft-one", "draft-two")) {
            assertNull(repository.getContact(ACCOUNT.value, id)?.remoteContactId)
            assertTrue(database.outboxDao().get(ACCOUNT.value, "CONTACT", id)?.requiresReconciliation == true)
        }
        assertEquals("remote-imported", repository.getContact(ACCOUNT.value, "remote-imported")?.remoteContactId)
    }

    @Test fun remoteIdCollisionDoesNotOverwriteAnUnrelatedLocalDraft() = runBlocking {
        save(CanonicalContact(ACCOUNT.value, "remote-collision", displayName = "Local draft"))
        remote = listOf(remoteFixture("remote-collision", "Remote import", 1, 12_000, "another-uid"))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals("Local draft", repository.getContact(ACCOUNT.value, "remote-collision")?.displayName)
        assertNull(repository.getContact(ACCOUNT.value, "remote-collision")?.remoteContactId)
        val imported = requireNotNull(database.contactDao().getByRemoteContactId(ACCOUNT.value, "remote-collision"))
        assertEquals("Remote import", imported.contact.displayName)
        assertTrue(imported.contact.id != "remote-collision")
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

    @Test fun recoveredGroupCatalogTargetsOnlyMissingKnownEmailMembershipsAndThenConverges() = runBlocking {
        val email = com.patmanak.contako.domain.model.ContactValue("email-fixture", ContactValueKind.EMAIL,
            "fixture@example.test", order = 0, metadata = mapOf(
                com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY to "label-later,label-pending,label-unknown"))
        val original = remote.single()
        val target = original.copy(card = original.card.copy(contact = original.card.contact.copy(values = listOf(email))))
        val other = remoteFixture("unaffected", "Other fixture", 1, 10_000, "other-uid")
        remote = listOf(target, other)
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(2, hydrationCount)
        val imported = requireNotNull(repository.getContact(ACCOUNT.value, "remote"))
        assertEquals(email.metadata, imported.valuesOf(ContactValueKind.EMAIL).single().metadata)
        val reconciler = RoomRemoteCanonicalReconciliationStore(database)
        assertTrue(reconciler.identityRepairContacts(ACCOUNT).isEmpty())

        val groups = database.contactGroupDao()
        val later = com.patmanak.contako.domain.model.ContactGroup(ACCOUNT.value, "known-group", "Later group",
            revision = 1, remoteLabelId = "label-later")
        groups.upsert(later.toEntity())
        assertTrue(repository.saveGroup(com.patmanak.contako.domain.model.ContactGroup(ACCOUNT.value,
            "pending-group", "Pending group", remoteLabelId = "label-pending")) is SaveResult.Saved)
        val pending = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "pending-group"))
        assertEquals(setOf(RemoteContactId("remote")), reconciler.identityRepairContacts(ACCOUNT))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(3, hydrationCount)
        assertEquals(listOf("email-fixture"), requireNotNull(groups.get(ACCOUNT.value, "known-group"))
            .memberships.map { it.emailValueId })
        assertTrue(requireNotNull(groups.get(ACCOUNT.value, "pending-group")).memberships.isEmpty())
        assertEquals(pending, database.outboxDao().get(ACCOUNT.value, "GROUP", "pending-group"))
        assertEquals(email.metadata, repository.getContact(ACCOUNT.value, "remote")?.valuesOf(ContactValueKind.EMAIL)?.single()?.metadata)
        assertTrue(reconciler.identityRepairContacts(ACCOUNT).isEmpty())
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(3, hydrationCount)

        // An extra canonical edge may be a newer acknowledged local assignment. Stale
        // remote metadata must not trigger a repair merely to remove that edge.
        val extra = later.copy(id = "extra-group", name = "Extra group", remoteLabelId = "label-extra")
        groups.upsert(extra.toEntity())
        groups.insertMemberships(listOf(com.patmanak.contako.domain.model.GroupMembership("remote", email.id).toEntity(extra)))
        assertTrue(reconciler.identityRepairContacts(ACCOUNT).isEmpty())
    }

    @Test
    fun blockedDuplicateAssignmentsRefreshOnlyAffectedCardAndRetainPendingIntent() = runBlocking {
        val kind = com.patmanak.contako.domain.model.ContactValueKind.EMAIL
        val old = listOf(
            com.patmanak.contako.domain.model.ContactValue("home-old", kind, "fixture@example.test", label = "HOME", order = 0),
            com.patmanak.contako.domain.model.ContactValue("work-old", kind, "fixture@example.test", label = "WORK", order = 1),
        )
        val original = remote.single()
        remote = listOf(original.copy(card = original.card.copy(contact = original.card.contact.copy(values = old))))
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        repository.saveGroup(com.patmanak.contako.domain.model.ContactGroup(
            ACCOUNT.value, "duplicate-group", "Duplicate", "#6D4AFF", remoteLabelId = "remote-group",
            memberships = old.map { com.patmanak.contako.domain.model.GroupMembership("remote", it.id) },
        ))
        val queued = database.outboxDao().getAll(ACCOUNT.value).single()
        database.outboxDao().upsert(queued.copy(operation = "ASSIGNMENTS", state = "ACTION_REQUIRED",
            errorCategory = "CONFLICT", blockedReason = "CONFLICT_RECOVERY_REQUIRED"))
        // Hydration precedes outbox drain: an unrelated pending contact must not strand recovery.
        val other = save(CanonicalContact(ACCOUNT.value, "other-local", displayName = "Other fixture"))
        val repaired = old.mapIndexed { index, value -> value.copy(id = "new-${value.id}",
            metadata = mapOf(com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY to "service-$index")) }
        // Same inventory version: only the durable missing-identity obligation forces a read.
        remote = listOf(original.copy(card = original.card.copy(contact = original.card.contact.copy(values = repaired))))
        val before = hydrationCount
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(before + 1, hydrationCount)
        val pending = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "duplicate-group"))
        assertEquals("PENDING", pending.state)
        assertNull(pending.blockedReason)
        assertTrue(pending.requiresReconciliation)
        val group = requireNotNull(database.contactGroupDao().get(ACCOUNT.value, "duplicate-group"))
        assertEquals(repaired.map { it.id }.toSet(), group.memberships.map { it.emailValueId }.toSet())
        assertTrue(group.group.pendingMutationRevision != null)
        val otherIntent = requireNotNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", other.id))
        database.outboxDao().upsert(otherIntent.copy(state = "ACKNOWLEDGED"))
        database.outboxDao().deleteAcknowledged(ACCOUNT.value, "CONTACT", other.id, otherIntent.revision)
        assertTrue(stage().run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(before + 1, hydrationCount)
    }

    private fun passExecutor(
        inventoryFailure: () -> GatewayFailureCategory? = { null },
        onProject: () -> Unit = {},
    ): ContakoSyncPassExecutor = ContakoSyncPassExecutor(
        account = ACCOUNT,
        prerequisites = SyncPrerequisiteChecker { SyncPrerequisiteState.READY },
        remoteStage = stage(inventoryFailure),
        mutationOrchestrator = DurableMutationOrchestrator(
            RoomMutationExecutionStore(database),
            RoomBackedMutationPreparationGateway(
                ACCOUNT, database,
                ProtonEmailGroupMembershipReader { _, _ -> error("No group intent") },
                verifiedCards = ProtonVerifiedContactCardGateway { _, id ->
                    GatewayOutcome.Success(remote.single { it.metadata.id == id }.card)
                },
            ),
            MutationUploadGateway { error("An accepted create must never be replayed") },
        ),
        wallClock = { wallClock },
        androidPreflight = AndroidInteroperabilityPreflight { account, _ ->
            AndroidInteroperabilityPreflightResult.Ready(AndroidInteroperabilityContext(account, "fixture", 0, 0))
        },
        androidStage = object : AndroidInteroperabilityStage {
            override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success
            override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                AndroidInteroperabilityStageResult.Success.also { onProject() }
        },
        fullRepairCoordinator = RoomFullRepairExecutionCoordinator(
            ACCOUNT, RoomFullRepairProgressStore(database), { wallClock },
        ),
    )

    private fun passRequest(scope: SyncScope) = SyncPassRequest(
        setOf(SyncTrigger.MANUAL), scope, 0, { 0 }, 0, { 0 },
    )

    private fun stage(inventoryFailure: () -> GatewayFailureCategory? = { null }): IncrementalRemoteContactStage {
        val inventoryGateway = ProtonContactInventoryGateway { _, cursor ->
            check(cursor == null)
            val failure = inventoryFailure()
            if (failure != null) GatewayOutcome.Failure(failure) else GatewayOutcome.Success(
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
