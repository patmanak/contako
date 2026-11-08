package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.AndroidAdoptionState
import com.patmanak.contako.data.android.AndroidProjectionWriteState
import com.patmanak.contako.data.android.AndroidRawContactLocator
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.data.sync.RetryDecision
import com.patmanak.contako.domain.sync.ServerClockCalibration
import com.patmanak.contako.data.sync.DurableMutationOrchestrator
import com.patmanak.contako.data.sync.MutationPreparation
import com.patmanak.contako.data.sync.MutationPreparationGateway
import com.patmanak.contako.data.sync.MutationUploadGateway
import com.patmanak.contako.data.sync.RemoteMutationAcknowledgement
import com.patmanak.contako.data.sync.RoomBackedMutationPreparationGateway
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader
import com.patmanak.contako.data.gateway.GatewayOutcome
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
class RoomOutboxStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private lateinit var store: RoomOutboxStore
    private var wallClock = 1_010_000L
    private var elapsedClock = 20_000L

    @Test
    fun blockedContactObservationUsesOutboxScopeAndClearsOnRetry() = runBlocking {
        save("blocked-contact", "Fixture")
        val dao = database.outboxDao()
        val original = dao.getAll(ACCOUNT).single()
        assertTrue(dao.observeBlockedContactIds(ACCOUNT).first().isEmpty())
        dao.upsert(original.copy(state = "ACTION_REQUIRED", blockedReason = "VALIDATION_REJECTED"))
        assertEquals(listOf(original.aggregateId), dao.observeBlockedContactIds(ACCOUNT).first())
        assertTrue(dao.observeBlockedContactIds("different-account").first().isEmpty())
        dao.upsert(original.copy(aggregateType = "GROUP", aggregateId = "blocked-group", state = "ACTION_REQUIRED"))
        assertEquals(listOf(original.aggregateId), dao.observeBlockedContactIds(ACCOUNT).first())
        dao.upsert(original.copy(state = "PENDING", blockedReason = null))
        assertTrue(dao.observeBlockedContactIds(ACCOUNT).first().isEmpty())
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun durableMutationStoresD024EvidenceAndSurvivesRestart() = runBlocking {
        save("contact", "Ada")

        reopenDatabase()
        val mutation = database.outboxDao().getAll(ACCOUNT).single()

        assertEquals(DurableMutationState.PENDING.name, mutation.state)
        assertEquals(elapsedClock, mutation.deviceElapsedRealtimeMillis)
        assertEquals(500L, mutation.serverOffsetMillis)
        assertEquals(10_000L, mutation.calibrationAgeMillis)
        assertEquals(1_052L, mutation.uncertaintyMillis)
        assertEquals(1_009_448L, mutation.intervalEarliestEpochMillis)
        assertEquals(1_011_552L, mutation.intervalLatestEpochMillis)
        assertFalse(mutation.clockJumpDetected)
        assertFalse(mutation.toString().contains(ACCOUNT))
        assertFalse(mutation.toString().contains("contact"))
    }

    @Test
    fun eligibleClaimIsBoundedAndRevisionCompareAndSetRejectsStaleWorker() = runBlocking {
        save("contact", "Ada")
        val pending = store.eligible(ACCOUNT, wallClock, 1).single()

        assertTrue(store.claim(pending, wallClock))
        assertFalse(store.claim(pending, wallClock))
        assertTrue(store.eligible(ACCOUNT, wallClock, 1).isEmpty())

        wallClock++
        save("contact", "Augusta")
        assertFalse(store.acknowledge(pending, "remote", "version-1"))
        val replacement = database.outboxDao().getAll(ACCOUNT).single()
        assertEquals(2L, replacement.revision)
        assertEquals(DurableMutationState.PENDING.name, replacement.state)
        assertEquals(0, replacement.attemptCount)
    }

    @Test
    fun moreThanOneBatchOfOlderAssignmentsCannotHideTheirPendingContactDependency() = runBlocking {
        save("dependency", "Fixture")
        val contact = database.outboxDao().getAll(ACCOUNT).single()
        val assignments = (0..100).map { index ->
            pendingGroup(contact, "assignment-$index", index, MutationOperation.ASSIGNMENTS)
        }
        assertTrue(assignments.all { it.createdAtEpochMillis < contact.createdAtEpochMillis })
        val uploaded = mutableListOf<String>()
        fun runner() = DurableMutationOrchestrator(
            RoomMutationExecutionStore(database),
            RoomBackedMutationPreparationGateway(
                AccountScope(ACCOUNT), database, ProtonEmailGroupMembershipReader { _, _ ->
                    error("UNEXPECTED_REMOTE_MEMBERSHIP_READ")
                },
            ),
            MutationUploadGateway { command ->
                uploaded += command.aggregateId
                GatewayOutcome.Success(RemoteMutationAcknowledgement(
                    if (command.aggregateType == "CONTACT") "remote-dependency" else "remote-${command.aggregateId}",
                    "verified-version",
                ))
            },
        )

        assertEquals(listOf("dependency"), store.eligible(ACCOUNT, wallClock, 100).map { it.aggregateId })
        val first = runner().drain(ACCOUNT, wallClock)
        assertEquals(1, first.examined)
        assertEquals(1, first.uploaded)
        assertEquals(listOf("dependency"), uploaded)
        // Dependency waiting does not claim a group, consume retries, alter intent or issue a write.
        assertEquals(assignments, database.outboxDao().getAll(ACCOUNT))
        assertEquals(101, first.progressPending)
        reopenDatabase()

        val second = runner().drain(ACCOUNT, wallClock)
        assertEquals(100, second.examined)
        assertEquals(100, second.uploaded)
        assertEquals(1, second.progressPending)
        val third = runner().drain(ACCOUNT, wallClock)
        assertEquals(1, third.examined)
        assertEquals(1, third.uploaded)
        assertEquals(102, uploaded.size)
        assertEquals(102, uploaded.distinct().size)
        assertTrue(database.outboxDao().getAll(ACCOUNT).isEmpty())
    }

    @Test
    fun waitingAssignmentsDoNotBypassContactBackoffOrTurnARepeatedDrainIntoRetryWork() = runBlocking {
        save("dependency", "Fixture")
        val original = database.outboxDao().getAll(ACCOUNT).single()
        val delayed = original.copy(nextAttemptAtEpochMillis = wallClock + 5_000, attemptCount = 2)
        database.outboxDao().upsert(delayed)
        val assignments = (0..100).map { index ->
            pendingGroup(original, "assignment-$index", index, MutationOperation.ASSIGNMENTS)
        }
        val before = database.outboxDao().getAll(ACCOUNT)
        val runner = DurableMutationOrchestrator(
            RoomMutationExecutionStore(database),
            MutationPreparationGateway { error("NO_ELIGIBLE_COMMAND") },
            MutationUploadGateway { error("NO_ELIGIBLE_COMMAND") },
        )

        repeat(3) {
            val result = runner.drain(ACCOUNT, wallClock + 4_999)
            assertEquals(0, result.examined)
            assertEquals(0, result.retryWaiting)
            assertEquals(0, result.actionRequired)
            assertEquals(102, result.progressPending)
            assertEquals(before, database.outboxDao().getAll(ACCOUNT))
        }
        assertEquals(listOf(delayed), store.eligible(ACCOUNT, wallClock + 5_000, 100))
        // A blocked dependency retains all assignment work, without repeated claims or uploads.
        database.outboxDao().upsert(delayed.copy(state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = "VALIDATION_REJECTED", errorCategory = GatewayFailureCategory.VALIDATION_REJECTED.name))
        val blocked = runner.drain(ACCOUNT, wallClock + 5_000)
        assertEquals(0, blocked.examined)
        assertEquals(1, blocked.actionRequired)
        assertEquals(101, blocked.progressPending)
        assertEquals(assignments, database.outboxDao().getAll(ACCOUNT).filter { it.aggregateType == "GROUP" })
    }

    @Test
    fun dependencyFilteringIsAccountScopedAndDoesNotDelayIndependentGroupWrites() = runBlocking {
        save("dependency", "Fixture")
        val contact = database.outboxDao().getAll(ACCOUNT).single()
        val assignment = pendingGroup(contact, "dependent", 0, MutationOperation.ASSIGNMENTS)
        val independent = pendingGroup(contact, "independent", 1, MutationOperation.UPSERT)
        val otherAccountAssignment = pendingGroup(contact.copy(accountId = "other-account"),
            "other-assignment", 2, MutationOperation.ASSIGNMENTS)

        assertEquals(listOf(contact, independent), store.eligible(ACCOUNT, wallClock, 100))
        assertEquals(listOf(otherAccountAssignment), store.eligible("other-account", wallClock, 100))
        assertTrue(store.eligible("unknown-account", wallClock, 100).isEmpty())
        assertEquals(listOf(assignment, independent, contact), database.outboxDao().getAll(ACCOUNT))
    }

    @Test
    fun interruptedInFlightMutationRestartsAsAmbiguousReconciliation() = runBlocking {
        save("contact", "Ada")
        val pending = store.eligible(ACCOUNT, wallClock, 1).single()
        assertTrue(store.claim(pending, wallClock))

        reopenDatabase()
        assertEquals(1, store.recoverInterrupted(ACCOUNT, wallClock + 1))
        val recovered = database.outboxDao().getAll(ACCOUNT).single()

        assertEquals(DurableMutationState.PENDING.name, recovered.state)
        assertTrue(recovered.requiresReconciliation)
        assertEquals(GatewayFailureCategory.UNKNOWN.name, recovered.errorCategory)
        assertEquals(0, recovered.attemptCount)
    }

    @Test
    fun retryAndCancellationTransitionsPreserveDurableIntent() = runBlocking {
        save("contact", "Ada")
        var mutation = store.eligible(ACCOUNT, wallClock, 1).single()
        assertTrue(store.claim(mutation, wallClock))
        assertTrue(
            store.applyRetryDecision(
                mutation,
                GatewayFailureCategory.TIMEOUT,
                RetryDecision.RetryAt(1, wallClock + 5_000, reconcileBeforeReplay = true),
            ),
        )
        mutation = database.outboxDao().getAll(ACCOUNT).single()
        assertEquals(1, mutation.attemptCount)
        assertTrue(mutation.requiresReconciliation)
        assertTrue(store.eligible(ACCOUNT, wallClock + 4_999, 1).isEmpty())
        assertEquals(1, store.eligible(ACCOUNT, wallClock + 5_000, 1).size)

        assertTrue(store.claim(mutation, wallClock + 5_000))
        assertTrue(store.applyRetryDecision(mutation, GatewayFailureCategory.CANCELLED, RetryDecision.Cancelled))
        val cancelled = database.outboxDao().getAll(ACCOUNT).single()
        assertEquals(1, cancelled.attemptCount)
        assertEquals(DurableMutationState.PENDING.name, cancelled.state)
        assertTrue(cancelled.requiresReconciliation)
    }

    @Test
    fun acknowledgementAndCanonicalReconciliationCommitExactlyOnce() = runBlocking {
        save("contact", "Ada")
        val pending = store.eligible(ACCOUNT, wallClock, 1).single()
        assertTrue(store.claim(pending, wallClock))
        assertTrue(store.acknowledge(pending, "remote-contact", "version-1"))
        val acknowledged = requireNotNull(database.outboxDao().get(ACCOUNT, "CONTACT", "contact"))
        assertEquals(DurableMutationState.ACKNOWLEDGED.name, acknowledged.state)

        assertTrue(store.finishAcknowledged(acknowledged))
        assertNull(database.outboxDao().get(ACCOUNT, "CONTACT", "contact"))
        val clean = requireNotNull(repository.getContact(ACCOUNT, "contact"))
        assertEquals("remote-contact", clean.remoteContactId)
        assertEquals("version-1", clean.remoteVersion)
        assertNull(clean.pendingMutationRevision)
        val projection = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT, "contact"))
        assertEquals("remote-contact", projection.sourceIdentity)
        assertEquals(AndroidAdoptionState.SOURCE_ID_PENDING.name, projection.adoptionState)
        assertEquals(AndroidProjectionWriteState.DETACHED.name, projection.projectionState)
        assertNull(projection.rawContactLocator)
        assertTrue(store.finishAcknowledged(acknowledged))
        assertEquals(projection, database.androidProjectionLedgerDao().get(ACCOUNT, "contact"))
    }

    @Test
    fun createAcknowledgementAtomicallyAttachesAndroidSourceIdentity() = runBlocking {
        save("contact", "Ada")
        val projection = RoomAndroidProjectionLedger(database)
        projection.ensureAccount(AccountScope(ACCOUNT))
        projection.attachCanonicalContact(
            AccountScope(ACCOUNT),
            "contact",
            createdRawContactLocator = AndroidRawContactLocator(0, 42),
        )
        val attached = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT, "contact"))
        assertEquals(
            1,
            database.androidProjectionLedgerDao().update(
                attached.copy(projectionState = AndroidProjectionWriteState.CLEAN.name),
            ),
        )
        val pending = store.eligible(ACCOUNT, wallClock, 1).single()
        assertTrue(store.claim(pending, wallClock))
        assertTrue(store.acknowledge(pending, "remote-contact", "version-1"))
        val acknowledged = requireNotNull(database.outboxDao().get(ACCOUNT, "CONTACT", "contact"))

        assertTrue(store.finishAcknowledged(acknowledged))

        val clean = requireNotNull(repository.getContact(ACCOUNT, "contact"))
        val ledger = requireNotNull(projection.load(AccountScope(ACCOUNT), "contact"))
        assertEquals("remote-contact", clean.remoteContactId)
        assertTrue(ledger.hasSourceIdentity)
        assertEquals(AndroidAdoptionState.SOURCE_ID_PENDING, ledger.adoptionState)
        assertEquals(AndroidProjectionWriteState.DETACHED, ledger.projectionState)
        assertNull(ledger.pendingProjectionFingerprint)
        assertTrue(database.outboxDao().getAll(ACCOUNT).isEmpty())
    }

    @Test
    fun actionRequiredRemainsVisibleButIneligible() = runBlocking {
        save("contact", "Ada")
        val pending = store.eligible(ACCOUNT, wallClock, 1).single()
        assertTrue(store.claim(pending, wallClock))
        assertTrue(
            store.applyRetryDecision(
                pending,
                GatewayFailureCategory.VALIDATION_REJECTED,
                RetryDecision.ActionRequired,
            ),
        )

        val blocked = database.outboxDao().getAll(ACCOUNT).single()
        assertEquals(DurableMutationState.ACTION_REQUIRED.name, blocked.state)
        assertEquals("VALIDATION_REJECTED", blocked.blockedReason)
        assertTrue(store.eligible(ACCOUNT, Long.MAX_VALUE, 1).isEmpty())
    }

    @Test
    fun lostRemoteAcknowledgementReconcilesAfterDatabaseRestartWithoutDuplicateWrite() = runBlocking {
        save("contact", "Ada")
        var remoteApplied = false
        var uploads = 0
        fun orchestrator() = DurableMutationOrchestrator(
            RoomMutationExecutionStore(database),
            MutationPreparationGateway {
                if (remoteApplied) {
                    GatewayOutcome.Success(
                        MutationPreparation.AlreadyApplied(
                            RemoteMutationAcknowledgement("remote-contact", "version-1"),
                        ),
                    )
                } else {
                    GatewayOutcome.Success(MutationPreparation.UploadAllowed)
                }
            },
            MutationUploadGateway {
                uploads++
                remoteApplied = true
                GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
            },
        )

        val first = orchestrator().drain(ACCOUNT, wallClock)
        assertEquals(1, first.retryWaiting)
        reopenDatabase()
        val second = orchestrator().drain(ACCOUNT, wallClock + 5_000)

        assertEquals(1, second.reconciledWithoutUpload)
        assertEquals(1, uploads)
        assertTrue(database.outboxDao().getAll(ACCOUNT).isEmpty())
        val clean = requireNotNull(repository.getContact(ACCOUNT, "contact"))
        assertEquals("remote-contact", clean.remoteContactId)
        assertNull(clean.pendingMutationRevision)
    }

    @Test
    fun contactAcknowledgementPersistsNewEmailIdentityBeforeFinishingIntent() = runBlocking {
        val email = com.patmanak.contako.domain.model.ContactValue(
            "canonical-email", com.patmanak.contako.domain.model.ContactValueKind.EMAIL,
            "fixture@example.test", order = 0,
            metadata = mapOf("protonEmailId" to "old-service-email", "preserved" to "retained"),
        )
        repository.saveContact(CanonicalContact(ACCOUNT, "contact", displayName = "Fixture", values = listOf(email)))
        val execution = RoomMutationExecutionStore(database)
        val command = execution.eligible(ACCOUNT, wallClock, 1).single()
        assertTrue(execution.claim(command, wallClock))
        assertTrue(execution.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(
            "remote-contact", "version-2", emailIdsByValueId = mapOf(email.id to "new-service-email"),
        )))
        reopenDatabase()
        val actual = requireNotNull(repository.getContact(ACCOUNT, "contact")).values.single()
        assertEquals(email.id, actual.id)
        assertEquals(email.value, actual.value)
        assertEquals(mapOf("protonEmailId" to "new-service-email", "preserved" to "retained"), actual.metadata)
        assertTrue(database.outboxDao().getAll(ACCOUNT).isEmpty())
    }

    private suspend fun pendingGroup(
        template: OutboxMutationEntity,
        id: String,
        index: Int,
        operation: MutationOperation,
    ): OutboxMutationEntity {
        database.contactGroupDao().upsert(ContactGroupEntity(
            accountId = template.accountId, id = id, ownerKey = "${template.accountId}:$id",
            name = "Fixture group $index", color = "#5E35B1", displayOrder = index,
            isVisible = true, revision = template.revision, updatedAtEpochMillis = wallClock - 1_000 + index,
            remoteLabelId = "remote-$id", remoteVersion = "group-version",
            pendingMutationRevision = template.revision, conflictState = null, isDeleted = false,
        ))
        if (operation == MutationOperation.ASSIGNMENTS) {
            // This scheduling fixture has a proved historical dependency; legacy catalogs
            // without such evidence remain eligible for a verified membership probe.
            database.remoteGroupAssignmentDependencyDao().upsertBaseline(
                RemoteGroupDependencyBaselineEntity(template.accountId, id, "remote-$id", true))
            database.remoteGroupAssignmentDependencyDao().upsertEdges(listOf(RemoteGroupDependencyEdgeEntity(
                template.accountId, id, template.aggregateId, "historical-email", "historical-remote-email", template.revision)))
        }
        val row = template.copy(aggregateType = AggregateType.GROUP.name, aggregateId = id,
            operation = operation.name, createdAtEpochMillis = wallClock - 1_000 + index,
            updatedAtEpochMillis = wallClock - 1_000 + index, attemptCount = 2,
            remoteIdentity = "remote-$id", remoteVersion = "group-version",
            idempotencyKey = "fixture-$id")
        database.outboxDao().upsert(row)
        return row
    }

    private suspend fun save(id: String, firstName: String): CanonicalContact {
        val existing = repository.getContact(ACCOUNT, id)
        val requested = existing?.copy(firstName = firstName) ?: CanonicalContact(
            accountId = ACCOUNT,
            id = id,
            firstName = firstName,
        )
        return when (val result = repository.saveContact(requested)) {
            is SaveResult.Saved -> result.value
            is SaveResult.Rejected -> error("UNEXPECTED_REJECTION")
        }
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun openDatabase() {
        database = ContakoDatabase.create(context, DATABASE_NAME)
        repository = RoomContactRepository(
            database = database,
            clock = { wallClock },
            elapsedRealtimeClock = { elapsedClock },
            calibrationProvider = {
                ServerClockCalibration(
                    observedDeviceWallClockEpochMillis = 1_000_000,
                    observedDeviceElapsedRealtimeMillis = 10_000,
                    serverOffsetMillis = 500,
                    roundTripMillis = 101,
                    serverPrecisionMillis = 1_000,
                )
            },
        )
        store = RoomOutboxStore(database)
    }

    private companion object {
        const val DATABASE_NAME = "v03-room-outbox.db"
        const val ACCOUNT = "v03-account"
    }
}
