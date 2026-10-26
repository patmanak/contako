package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import com.patmanak.contako.data.proton.ContactInventoryPlan
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Isolated Room fixtures only; this class never accesses an account or ContactsProvider. */
@RunWith(AndroidJUnit4::class)
class RoomDeletionRecoveryDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ContakoDatabase
    @Before fun setup() { context.deleteDatabase(NAME); db = ContakoDatabase.create(context, NAME) }
    @After fun cleanup() { db.close(); context.deleteDatabase(NAME) }
    private fun store() = RoomDeletionRecoveryStore(db)
    private suspend fun seed(): DeletionRecoveryDetail {
        val saved = RoomContactRepository(db).saveContact(CanonicalContact(ACCOUNT, "old", displayName = "Synthetic fixture",
            remoteContactId = "remote", remoteVCardUid = "retired", remoteVersion = "baseline", values = listOf(
                ContactValue("note", ContactValueKind.NOTE, "retained local note", order = 0),
                ContactValue("email", ContactValueKind.EMAIL, "fixture@example.test", order = 0),
                ContactValue("photo", ContactValueKind.PHOTO, PHOTO, order = 0,
                    binaryReference = PHOTO)))) as SaveResult.Saved
        val intent = requireNotNull(db.outboxDao().get(ACCOUNT, "CONTACT", saved.value.id))
        db.outboxDao().upsert(intent.copy(state = "ACTION_REQUIRED", blockedReason = RoomDeletionRecoveryStore.REQUIRED))
        store().discover(ACCOUNT)
        return requireNotNull(store().detail(ACCOUNT, "old"))
    }
    private suspend fun ready(): DeletionRecoveryDetail {
        val detail = seed()
        assertTrue(store().verifyAbsence(ACCOUNT, "old", detail.summary.generation, detail.summary.localRevision, "remote"))
        return requireNotNull(store().detail(ACCOUNT, "old"))
    }

    @Test fun candidateNeedsExactAbsenceAndKeepDraftSurvivesRestartWithoutNewOutbox() = runBlocking {
        val candidate = seed()
        assertFalse(candidate.summary.absenceVerified)
        assertFalse(store().queue(ACCOUNT, candidate.summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertFalse(store().verifyAbsence("other", "old", candidate.summary.generation, candidate.summary.localRevision, "remote"))
        assertFalse(store().verifyAbsence(ACCOUNT, "old", candidate.summary.generation, candidate.summary.localRevision, "wrong"))
        assertTrue(store().verifyAbsence(ACCOUNT, "old", candidate.summary.generation, candidate.summary.localRevision, "remote"))
        val expected = requireNotNull(store().detail(ACCOUNT, "old")).summary
        assertTrue(store().queue(ACCOUNT, expected, DeletionRecoveryChoice.KEEP_DRAFT))
        assertFalse(store().queue(ACCOUNT, expected, DeletionRecoveryChoice.RECREATE))
        assertTrue(store().stageDraft(ACCOUNT, "old", expected.generation))
        assertFalse(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
        assertEquals(1, db.outboxDao().getAll(ACCOUNT).size)
        assertTrue(store().complete(ACCOUNT, "old", expected.generation))
        assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
        assertEquals(1, db.accountRemovalDao().countPending(ACCOUNT))
        db.close(); db = ContakoDatabase.create(context, NAME)
        val draft = requireNotNull(store().detail(ACCOUNT, "old"))
        assertEquals(DeletionRecoveryState.DRAFT, draft.summary.state)
        assertNull(draft.summary.choice)
        assertEquals("retained local note", draft.contact.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals(PHOTO, draft.contact.valuesOf(ContactValueKind.PHOTO).single().binaryReference)
        val changed = draft.contact.copy(values = draft.contact.values.map { if (it.kind == ContactValueKind.NOTE) it.copy(value = "new draft note") else it })
        assertTrue(store().saveDraft(ACCOUNT, draft.summary, changed))
        assertFalse(store().saveDraft(ACCOUNT, draft.summary, changed))
        assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
        assertEquals(draft.summary.draftRevision + 1, store().detail(ACCOUNT, "old")?.summary?.draftRevision)
    }

    @Test fun recreateAllocatesOnceAndFinalizesAtomicallyWithFreshIdentity() = runBlocking {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.RECREATE))
        val destination = requireNotNull(store().get(ACCOUNT, "old")?.destinationContactId)
        assertFalse(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.RECREATE))
        db.close(); db = ContakoDatabase.create(context, NAME)
        assertEquals(destination, store().get(ACCOUNT, "old")?.destinationContactId)
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        assertFalse(store().complete(ACCOUNT, "old", ready.summary.generation))
        val created = requireNotNull(db.contactDao().get(ACCOUNT, destination)).toDomain()
        assertNull(created.remoteContactId); assertNull(created.remoteVCardUid); assertNull(created.remoteVersion)
        assertEquals(1, db.outboxDao().getAll(ACCOUNT).size)
        assertEquals(destination, db.outboxDao().getAll(ACCOUNT).single().aggregateId)
        assertTrue(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
    }

    @Test fun newCanonicalIntentPreventsFinalizationAndAccountPurgeRemovesOnlyItsDrafts() = runBlocking {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        RoomContactRepository(db).saveContact(ready.contact.copy(displayName = "New native intent"))
        assertFalse(store().complete(ACCOUNT, "old", ready.summary.generation))
        assertFalse(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
        assertEquals("New native intent", db.contactDao().getEntity(ACCOUNT, "old")?.displayName)
        db.deletionRecoveryDao().upsert(requireNotNull(store().get(ACCOUNT, "old")).copy(accountId = "other"))
        db.accountRemovalDao().deleteAccount(ACCOUNT)
        assertNull(store().get(ACCOUNT, "old"))
        assertNotNull(store().get("other", "old"))
    }

    @Test fun independentGroupIntentInvalidatesConsentBeforeCleanupAndPreservesBothVersions() = runBlocking {
        val first = ready()
        RoomContactRepository(db).saveGroup(ContactGroup(ACCOUNT, "group", "Synthetic group",
            remoteLabelId = "label", memberships = listOf(GroupMembership("old", "email"))))
        assertFalse(store().queue(ACCOUNT, first.summary, DeletionRecoveryChoice.KEEP_DRAFT))
        store().discover(ACCOUNT)
        val refreshed = requireNotNull(store().detail(ACCOUNT, "old"))
        assertNotEquals(first.summary.generation, refreshed.summary.generation)
        assertTrue(store().verifyAbsence(ACCOUNT, "old", refreshed.summary.generation, refreshed.summary.localRevision, "remote"))
        assertTrue(store().queue(ACCOUNT, requireNotNull(store().detail(ACCOUNT, "old")).summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertTrue(store().stageDraft(ACCOUNT, "old", refreshed.summary.generation))
        val group = requireNotNull(db.contactGroupDao().get(ACCOUNT, "group")).toDomain()
        RoomContactRepository(db).saveGroup(group.copy(name = "Changed group intent"))
        assertFalse(store().complete(ACCOUNT, "old", refreshed.summary.generation))
        store().discover(ACCOUNT)
        assertEquals(DeletionRecoveryState.STALE, store().detail(ACCOUNT, "old")?.summary?.state)
        assertEquals("retained local note", store().detail(ACCOUNT, "old")?.contact?.valuesOf(ContactValueKind.NOTE)?.single()?.value)
        assertEquals("Changed group intent", db.contactGroupDao().get(ACCOUNT, "group")?.group?.name)
        assertFalse(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
    }

    @Test fun confirmedDiscardFinalizesOnlyTheSourceWithoutRemoteDeleteOrRecreation() = runBlocking {
        val ready = ready()
        val repository = RoomContactRepository(db)
        assertTrue(repository.saveContact(CanonicalContact("canary-owner", "canary-contact", displayName = "Independent fixture",
            remoteContactId = "canary-remote", remoteVersion = "canary-baseline", values = listOf(
                ContactValue("canary-note", ContactValueKind.NOTE, "Independent account content", order = 0)))) is SaveResult.Saved)
        val canaryBefore = repository.getContact("canary-owner", "canary-contact")
        val canaryOutboxBefore = db.outboxDao().getAll("canary-owner")
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.DISCARD))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        assertFalse(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
        assertEquals("UPSERT", db.outboxDao().getAll(ACCOUNT).single().operation)
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        val terminal = requireNotNull(store().get(ACCOUNT, "old"))
        assertEquals(DeletionRecoveryState.DISCARDED.name, terminal.state)
        assertFalse(terminal.hasDraft)
        assertNull(terminal.destinationContactId)
        assertTrue(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
        assertTrue(db.outboxDao().getAll(ACCOUNT).isEmpty())
        assertTrue(db.deletionRecoveryDao().chunks(ACCOUNT, "old").isEmpty())
        assertEquals(0, db.accountRemovalDao().countPending(ACCOUNT))
        assertEquals(canaryBefore, repository.getContact("canary-owner", "canary-contact"))
        assertEquals(canaryOutboxBefore, db.outboxDao().getAll("canary-owner"))
        assertFalse(store().complete(ACCOUNT, "old", ready.summary.generation))
    }

    @Test fun draftChunkStorageFailureRollsBackDeletionAndRetainsExactSavedContentAndRevision() = runBlocking {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        val before = requireNotNull(store().detail(ACCOUNT, "old"))
        val canonicalBefore = db.contactDao().get(ACCOUNT, "old")
        val outboxBefore = db.outboxDao().getAll(ACCOUNT)
        val changed = before.contact.copy(values = before.contact.values.map {
            if (it.kind == ContactValueKind.NOTE) it.copy(value = "This edit must roll back") else it
        })
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER synthetic_draft_storage_failure BEFORE INSERT ON deletion_recovery_chunks
            WHEN NEW.account_id='synthetic-owner'
            BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END
        """.trimIndent())
        try {
            assertNotNull(runCatching { store().saveDraft(ACCOUNT, before.summary, changed) }.exceptionOrNull())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_draft_storage_failure") }
        assertEquals(before, store().detail(ACCOUNT, "old"))
        assertEquals(canonicalBefore, db.contactDao().get(ACCOUNT, "old"))
        assertEquals(outboxBefore, db.outboxDao().getAll(ACCOUNT))
        db.close(); db = ContakoDatabase.create(context, NAME)
        assertEquals(before, store().detail(ACCOUNT, "old"))
        assertEquals(before.summary.draftRevision, store().get(ACCOUNT, "old")?.draftRevision)
    }

    @Test fun recreationOutboxStorageFailureRollsBackDestinationAndKeepsExactSourceAndRecovery() = runBlocking {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.RECREATE))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        val before = requireNotNull(store().detail(ACCOUNT, "old"))
        val rowBefore = requireNotNull(store().get(ACCOUNT, "old"))
        val destination = requireNotNull(rowBefore.destinationContactId)
        val canonicalBefore = db.contactDao().get(ACCOUNT, "old")
        val outboxBefore = db.outboxDao().getAll(ACCOUNT)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER synthetic_creation_storage_failure BEFORE INSERT ON outbox_mutations
            WHEN NEW.account_id='synthetic-owner' AND NEW.aggregate_type='CONTACT' AND NEW.aggregate_id<>'old'
            BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END
        """.trimIndent())
        try {
            assertNotNull(runCatching { store().complete(ACCOUNT, "old", ready.summary.generation) }.exceptionOrNull())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_creation_storage_failure") }
        assertEquals(rowBefore, store().get(ACCOUNT, "old"))
        assertEquals(before, store().detail(ACCOUNT, "old"))
        assertEquals(canonicalBefore, db.contactDao().get(ACCOUNT, "old"))
        assertEquals(outboxBefore, db.outboxDao().getAll(ACCOUNT))
        assertFalse(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
        assertNull(db.contactDao().getEntity(ACCOUNT, destination))
        // Once storage works, retry the already allocated destination rather than a new CREATE.
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        assertEquals(destination, db.outboxDao().getAll(ACCOUNT).single().aggregateId)
    }

    @Test fun recreatedDestinationResumesTheSameAmbiguousCommandAfterFreshAbsenceAndExplicitChoice() = runBlocking {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.RECREATE))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        val destination = requireNotNull(store().get(ACCOUNT, "old")?.destinationContactId)
        val original = db.outboxDao().getAll(ACCOUNT).single().copy(requiresReconciliation = true,
            attemptCount = 3, lastAttemptAtEpochMillis = 12345, nextAttemptAtEpochMillis = 67890)
        db.outboxDao().upsert(original)
        assertTrue(store().suspendDestination(ACCOUNT, "old", ready.summary.generation))
        // Exercise the actual remote Room adoption/deletion path: the old row's revision
        // advances twice while the already allocated destination remains unchanged.
        val reconciliation = RoomRemoteCanonicalReconciliationStore(db)
        val retired = requireNotNull(db.contactDao().get(ACCOUNT, "old")).toDomain()
        val restored = CanonicalContact(ACCOUNT, "remote", displayName = "Restored remote fixture",
            remoteContactId = "remote", remoteVersion = "restored-version",
            values = listOf(ContactValue("remote-note", ContactValueKind.NOTE, "Restored remote content", order = 0)))
        reconciliation.adoptRemote(retired, restored, VerifiedContactCard(RemoteContactId("remote"), RemoteVersion("restored-version"), restored), null)
        assertFalse(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
        val deleted = setOf(RemoteContactId("remote"))
        reconciliation.commit(AccountScope(ACCOUNT), ContactInventoryPlan.pending(AccountScope(ACCOUNT), null,
            emptySet(), emptySet(), deleted, ContactInventoryCheckpoint(emptyList())), emptyList(), emptySet(), deleted)
        assertTrue(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).revision > ready.summary.localRevision)
        val stale = requireNotNull(store().detail(ACCOUNT, "old"))
        assertFalse(stale.summary.hasDraft)
        assertEquals(DeletionRecoveryState.STALE, stale.summary.state)
        assertFalse(store().queue(ACCOUNT, stale.summary, DeletionRecoveryChoice.RECREATE))
        assertTrue(store().verifyAbsence(ACCOUNT, "old", ready.summary.generation, ready.summary.localRevision, "remote"))
        val reviewed = requireNotNull(store().detail(ACCOUNT, "old"))
        assertEquals(1L, reviewed.summary.destinationRevision)
        assertFalse(store().queue(ACCOUNT, reviewed.summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertFalse(store().queue(ACCOUNT, reviewed.summary, DeletionRecoveryChoice.DISCARD))
        assertTrue(store().queue(ACCOUNT, reviewed.summary, DeletionRecoveryChoice.RECREATE))
        assertFalse(store().queue(ACCOUNT, reviewed.summary, DeletionRecoveryChoice.RECREATE))
        assertEquals(original.copy(state = "PENDING", blockedReason = null, errorCategory = null), db.outboxDao().getAll(ACCOUNT).single())
        assertEquals(destination, store().get(ACCOUNT, "old")?.destinationContactId)
        assertEquals(1L, db.contactDao().getEntity(ACCOUNT, destination)?.revision)
        assertTrue(requireNotNull(db.contactDao().getEntity(ACCOUNT, "old")).isDeleted)
    }

    @Test fun removingDraftEmailPrunesOnlyItsCapturedEdgesAndLeavesNoRecreationDependency() = runBlocking {
        val first = ready()
        RoomContactRepository(db).saveGroup(ContactGroup(ACCOUNT, "group", "Synthetic group", remoteLabelId = "label",
            memberships = listOf(GroupMembership("old", "email"))))
        store().discover(ACCOUNT)
        val captured = requireNotNull(store().detail(ACCOUNT, "old"))
        assertTrue(store().verifyAbsence(ACCOUNT, "old", captured.summary.generation, first.summary.localRevision, "remote"))
        assertTrue(store().queue(ACCOUNT, requireNotNull(store().detail(ACCOUNT, "old")).summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertTrue(store().stageDraft(ACCOUNT, "old", captured.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", captured.summary.generation))
        val draft = requireNotNull(store().detail(ACCOUNT, "old"))
        assertEquals(1, draft.retainedGroupAssignmentCount)
        val canonicalBefore = db.contactDao().get(ACCOUNT, "old")
        val outboxBefore = db.outboxDao().getAll(ACCOUNT)
        assertTrue(store().saveDraft(ACCOUNT, draft.summary, draft.contact.copy(values = draft.contact.values.filterNot { it.id == "email" })))
        val edited = requireNotNull(store().detail(ACCOUNT, "old"))
        assertEquals(0, edited.retainedGroupAssignmentCount)
        assertEquals(canonicalBefore, db.contactDao().get(ACCOUNT, "old"))
        assertEquals(outboxBefore, db.outboxDao().getAll(ACCOUNT))
        assertTrue(store().verifyAbsence(ACCOUNT, "old", edited.summary.generation, edited.summary.localRevision, "remote"))
        assertTrue(store().queue(ACCOUNT, requireNotNull(store().detail(ACCOUNT, "old")).summary, DeletionRecoveryChoice.RECREATE))
    }

    @Test fun draftEmailPruningDoesNotDeleteAnUnrelatedUnknownCapturedEdge() = runBlocking {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        val removedEdge = DeletionRecoveryGroup(ACCOUNT, "old", "synthetic-group", "email", "label", "Synthetic group")
        val unknownEdge = DeletionRecoveryGroup(ACCOUNT, "old", "unknown-group", "unknown-email", null, "Unknown group")
        db.deletionRecoveryDao().insertGroups(listOf(removedEdge, unknownEdge))
        val draft = requireNotNull(store().detail(ACCOUNT, "old"))
        assertTrue(store().saveDraft(ACCOUNT, draft.summary, draft.contact.copy(values = draft.contact.values.filterNot { it.id == "email" })))
        assertEquals(listOf(unknownEdge), db.deletionRecoveryDao().groups(ACCOUNT, "old"))
    }

    @Test fun verificationRotationSurvivesReopenAndDoesNotStarveTheHundredAndFirstCandidate() = runBlocking {
        ready()
        val template = requireNotNull(store().get(ACCOUNT, "old"))
        repeat(101) { index ->
            db.deletionRecoveryDao().upsert(template.copy(contactId = "candidate-${index.toString().padStart(3, '0')}",
                generation = "generation-$index", state = "NEEDS_VERIFICATION", absenceVerified = false))
        }
        val first = store().eligible(ACCOUNT).take(100)
        assertEquals(100, first.size)
        assertFalse(store().markExamined(ACCOUNT, first.first().contactId, "obsolete-generation"))
        first.forEach { assertTrue(store().markExamined(ACCOUNT, it.contactId, it.generation)) }
        db.close(); db = ContakoDatabase.create(context, NAME)
        assertEquals("candidate-100", store().eligible(ACCOUNT).first().contactId)
        assertTrue(requireNotNull(store().get(ACCOUNT, "candidate-000")).verificationOrder > 0)
        assertFalse(store().eligible(ACCOUNT).any { it.contactId == "old" }) // READY, awaiting user choice.
        val chosen = requireNotNull(store().get(ACCOUNT, "candidate-000"))
        db.deletionRecoveryDao().upsert(chosen.copy(state = "QUEUED", choice = "KEEP_DRAFT"))
        assertEquals("candidate-000", store().eligible(ACCOUNT).first().contactId)
    }

    @Test fun retiredDraftRecreationDoesNotTouchAReactivatedSourceOrItsNewNativeIntent() = runBlocking {
        assertRetiredDraftActionPreservesNewSource(DeletionRecoveryChoice.RECREATE)
    }

    @Test fun retiredDraftDiscardDoesNotTouchAReactivatedSourceOrItsNewNativeIntent() = runBlocking {
        assertRetiredDraftActionPreservesNewSource(DeletionRecoveryChoice.DISCARD)
    }

    @Test fun discoveryCannotOverwriteAnEngagedDestinationWhenTheOldSourceAcquiresNewLocalWork() = runBlocking {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.RECREATE))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        val destination = requireNotNull(store().get(ACCOUNT, "old")?.destinationContactId)
        val originalCommand = requireNotNull(db.outboxDao().get(ACCOUNT, "CONTACT", destination))
        assertTrue(store().suspendDestination(ACCOUNT, "old", ready.summary.generation))
        assertTrue(store().block(ACCOUNT, "old", ready.summary.generation, DeletionRecoveryBlocker.LOCAL_CHANGED))
        val old = requireNotNull(db.contactDao().get(ACCOUNT, "old")).toDomain()
        assertTrue(RoomContactRepository(db).saveContact(old.copy(displayName = "Independent old-source edit")) is SaveResult.Saved)
        val newSourceIntent = requireNotNull(db.outboxDao().get(ACCOUNT, "CONTACT", "old")).copy(state = "ACTION_REQUIRED",
            blockedReason = RoomDeletionRecoveryStore.REQUIRED)
        db.outboxDao().upsert(newSourceIntent)
        val sourceBefore = db.contactDao().get(ACCOUNT, "old")
        val rowBefore = store().get(ACCOUNT, "old")
        store().discover(ACCOUNT)
        assertEquals(rowBefore, store().get(ACCOUNT, "old"))
        assertEquals(rowBefore, store().forDestination(ACCOUNT, destination))
        assertTrue(store().verifyAbsence(ACCOUNT, "old", ready.summary.generation, ready.summary.localRevision, "remote"))
        val explicit = requireNotNull(store().detail(ACCOUNT, "old")).summary
        assertEquals(DeletionRecoveryState.READY, explicit.state)
        assertTrue(store().queue(ACCOUNT, explicit, DeletionRecoveryChoice.RECREATE))
        assertEquals(originalCommand, db.outboxDao().get(ACCOUNT, "CONTACT", destination))
        assertEquals(sourceBefore, db.contactDao().get(ACCOUNT, "old"))
        assertEquals(newSourceIntent, db.outboxDao().get(ACCOUNT, "CONTACT", "old"))
        assertEquals(destination, store().get(ACCOUNT, "old")?.destinationContactId)
        assertTrue(requireNotNull(store().get(ACCOUNT, "old")).sourceRetired)
    }

    private suspend fun assertRetiredDraftActionPreservesNewSource(choice: DeletionRecoveryChoice) {
        val ready = ready()
        assertTrue(store().queue(ACCOUNT, ready.summary, DeletionRecoveryChoice.KEEP_DRAFT))
        assertTrue(store().stageDraft(ACCOUNT, "old", ready.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", ready.summary.generation))
        assertTrue(requireNotNull(store().get(ACCOUNT, "old")).sourceRetired)
        val reconciliation = RoomRemoteCanonicalReconciliationStore(db)
        val restored = CanonicalContact(ACCOUNT, "remote", displayName = "Restored fixture", remoteContactId = "remote",
            remoteVersion = "restored-version", values = listOf(ContactValue("restored-note", ContactValueKind.NOTE, "Remote content", order = 0)))
        reconciliation.adoptRemote(requireNotNull(db.contactDao().get(ACCOUNT, "old")).toDomain(), restored,
            VerifiedContactCard(RemoteContactId("remote"), RemoteVersion("restored-version"), restored), null)
        val deleted = setOf(RemoteContactId("remote"))
        reconciliation.commit(AccountScope(ACCOUNT), ContactInventoryPlan.pending(AccountScope(ACCOUNT), null,
            emptySet(), emptySet(), deleted, ContactInventoryCheckpoint(emptyList())), emptyList(), emptySet(), deleted)
        // Native ingestion may now commit another independent local intention on that source.
        val repository = RoomContactRepository(db)
        val source = requireNotNull(db.contactDao().get(ACCOUNT, "old")).toDomain()
        assertTrue(repository.saveContact(source.copy(displayName = "New independent native work", values = listOf(
            ContactValue("new-note", ContactValueKind.NOTE, "Never discard this new work", order = 0),
            ContactValue("new-email", ContactValueKind.EMAIL, "new@example.test", order = 0)))) is SaveResult.Saved)
        assertTrue(repository.saveGroup(ContactGroup(ACCOUNT, "new-group", "New independent group", remoteLabelId = "new-label",
            memberships = listOf(GroupMembership("old", "new-email")))) is SaveResult.Saved)
        val ledger = requireNotNull(db.androidProjectionLedgerDao().get(ACCOUNT, "old"))
        db.androidProjectionLedgerDao().update(ledger.copy(revision = ledger.revision + 1, rawContactLocator = 777,
            adoptionState = "ADOPTED", projectionState = "CLEAN", ingestionState = "BASELINED"))
        val canonicalBefore = db.contactDao().get(ACCOUNT, "old")
        val ledgerBefore = db.androidProjectionLedgerDao().get(ACCOUNT, "old")
        val groupsBefore = db.contactGroupDao().getAll(ACCOUNT)
        val outboxBefore = db.outboxDao().getAll(ACCOUNT).associateBy { it.aggregateType to it.aggregateId }
        val draft = requireNotNull(store().detail(ACCOUNT, "old"))
        assertTrue(store().saveDraft(ACCOUNT, draft.summary, draft.contact.copy(values = draft.contact.values.map {
            if (it.kind == ContactValueKind.NOTE) it.copy(value = "Edited independent draft") else it
        })))
        val edited = requireNotNull(store().detail(ACCOUNT, "old"))
        assertTrue(store().verifyAbsence(ACCOUNT, "old", edited.summary.generation, edited.summary.localRevision, "remote"))
        assertTrue(store().queue(ACCOUNT, requireNotNull(store().detail(ACCOUNT, "old")).summary, choice))
        assertTrue(store().stageDraft(ACCOUNT, "old", edited.summary.generation))
        assertTrue(store().complete(ACCOUNT, "old", edited.summary.generation))
        assertEquals(canonicalBefore, db.contactDao().get(ACCOUNT, "old"))
        assertEquals(ledgerBefore, db.androidProjectionLedgerDao().get(ACCOUNT, "old"))
        assertEquals(groupsBefore, db.contactGroupDao().getAll(ACCOUNT))
        val destination = store().get(ACCOUNT, "old")?.destinationContactId
        assertEquals(outboxBefore, db.outboxDao().getAll(ACCOUNT).filterNot { it.aggregateId == destination }
            .associateBy { it.aggregateType to it.aggregateId })
        if (choice == DeletionRecoveryChoice.RECREATE) {
            val created = requireNotNull(db.contactDao().get(ACCOUNT, requireNotNull(destination))).toDomain()
            assertEquals("Edited independent draft", created.valuesOf(ContactValueKind.NOTE).single().value)
        } else {
            assertNull(destination)
            assertEquals(DeletionRecoveryState.DISCARDED.name, store().get(ACCOUNT, "old")?.state)
        }
    }

    companion object {
        private const val ACCOUNT = "synthetic-owner"
        private const val NAME = "deletion-recovery-test.db"
        private const val PHOTO = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
