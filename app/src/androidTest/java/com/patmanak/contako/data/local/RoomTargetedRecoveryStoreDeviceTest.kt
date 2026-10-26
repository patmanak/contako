package com.patmanak.contako.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.sync.TargetedRecoveryWorkResult
import com.patmanak.contako.data.sync.MutationRetryPolicy
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.RoomSyncPassStatusPublisher
import com.patmanak.contako.data.sync.RoomSyncRecoveryDataSource
import com.patmanak.contako.data.sync.NetworkStateProvider
import com.patmanak.contako.data.sync.NetworkState
import com.patmanak.contako.data.sync.SyncStatusUpdate
import com.patmanak.contako.data.sync.SyncActionReason
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Local Room and production reconciliation only. No accounts, network or provider writes. */
@RunWith(AndroidJUnit4::class)
class RoomTargetedRecoveryStoreDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private val store get() = RoomTargetedRecoveryStore(database)

    @Before fun setup() {
        context.deleteDatabase(NAME)
        database = ContakoDatabase.create(context, NAME)
    }
    @After fun cleanup() { if (database.isOpen) database.close(); context.deleteDatabase(NAME) }

    @Test fun repeatedTapsCoalesceWithoutReplacingClaimDelayOrOriginalIntent() = runBlocking {
        defer()
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        assertTrue(preview.canQueue)
        assertEquals(TargetedRecoveryQueueResult.QUEUED, store.queue(ACCOUNT, preview, 1_000))
        val original = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        repeat(10) { assertEquals(TargetedRecoveryQueueResult.ALREADY_PENDING, store.queue(ACCOUNT, preview, 2_000)) }
        assertEquals(original, database.targetedRecoveryRequestDao().all(ACCOUNT.value).single())
        val claim = requireNotNull(store.claim(original, 1_000))
        store.finish(claim, TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.RATE_LIMITED,
            retryAfterMillis = 60_000), 2_000) { false }
        val deferred = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        assertEquals("DEFERRED", deferred.state)
        assertTrue(deferred.nextEligibleAt >= 62_000)
        assertTrue(store.eligible(ACCOUNT, 61_999).isEmpty())
        assertEquals(TargetedRecoveryQueueResult.ALREADY_PENDING, store.queue(ACCOUNT, preview, 3_000))
        assertEquals(deferred, database.targetedRecoveryRequestDao().all(ACCOUNT.value).single())
    }

    @Test fun sourceGenerationIsStableForTheSameRefusalAndChangesWithItsBaseline() = runBlocking {
        defer()
        val first = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        defer()
        assertEquals(first, store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET))
        store.queue(ACCOUNT, first, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        defer(version = "changed-index-version")
        val changed = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        assertNotEquals(first.obligationGeneration, changed.obligationGeneration)
        assertTrue(store.validate(claim) is TargetedRecoveryWorkResult.Stale)
        assertTrue(store.completeVerified(claim, card(), 1_000) { false } is TargetedRecoveryWorkResult.Stale)
        assertNull(database.contactDao().get(ACCOUNT.value, TARGET))
        assertEquals(TargetedRecoveryQueueResult.STALE, store.queue(ACCOUNT, first, 2_000))
    }

    @Test fun atomicVerifiedRecoveryPreserves300WitnessesAndReopensAsResolvedWithoutAnOutboxWrite() = runBlocking {
        database.withTransaction {
            repeat(300) { index ->
                val contact = CanonicalContact(ACCOUNT.value, "witness-$index", displayName = "Synthetic $index", revision = 5,
                    values = listOf(ContactValue("note", ContactValueKind.NOTE, "Keep exact: Chloë ß $index", order = 0)))
                database.contactDao().upsert(contact.toEntity())
                database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
            }
        }
        val witnesses = database.contactDao().observeActive(ACCOUNT.value).first()
        defer()
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        assertNull(store.validate(claim))
        assertTrue(store.completeVerified(claim, card(), 1_000) { false } is TargetedRecoveryWorkResult.Resolved)
        assertEquals("RESOLVED", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        assertEquals(witnesses, database.contactDao().observeActive(ACCOUNT.value).first().filter { it.contact.id.startsWith("witness-") })
        assertTrue(database.remoteHydrationObligationDao().getAll(ACCOUNT.value).isEmpty())
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        val recovered = requireNotNull(database.contactDao().get(ACCOUNT.value, TARGET))
        assertEquals("Synthetic recovered", recovered.contact.displayName)
        assertEquals("Recovered note", recovered.toDomain().valuesOf(ContactValueKind.NOTE).single().value)
        database.close(); database = ContakoDatabase.create(context, NAME)
        store.recoverInterrupted(ACCOUNT, 2_000)
        assertTrue(store.eligible(ACCOUNT, 2_000).isEmpty())
        assertEquals("RESOLVED", store.observe(ACCOUNT).first().single().state.name)
    }

    @Test fun nativeRevisionAndProviderEpochChangesInvalidateQueuedRequestsWithoutMutatingCanonicalValues() = runBlocking {
        val contact = CanonicalContact(ACCOUNT.value, "existing", displayName = "Synthetic", revision = 4, remoteContactId = TARGET)
        database.contactDao().upsert(contact.toEntity())
        RoomAndroidProjectionLedger(database).ensureAccount(ACCOUNT)
        defer()
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        database.contactDao().upsert(contact.copy(revision = 5, displayName = "Newer local intent").toEntity())
        assertTrue(store.validate(claim) is TargetedRecoveryWorkResult.Stale)
        assertTrue(store.completeVerified(claim, card(), 1_000) { false } is TargetedRecoveryWorkResult.Stale)
        assertEquals("Newer local intent", database.contactDao().getEntity(ACCOUNT.value, "existing")?.displayName)
        // Match the serialized executor: persist the first command's terminal result
        // before starting the separate provider-epoch scenario.
        store.finish(claim, TargetedRecoveryWorkResult.Stale(), 1_000) { false }
        assertEquals("STALE", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        val next = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, next, 2_000)
        val nextClaim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 2_000))
        val binding = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        database.androidProjectionLedgerDao().advanceProviderEpoch(ACCOUNT.value, binding.revision)
        assertTrue(store.validate(nextClaim) is TargetedRecoveryWorkResult.Stale)
    }

    @Test fun androidCompletionRebasesOnlyToTheExactReceiptRevisionAndCurrentEpoch() = runBlocking {
        val contact = CanonicalContact(ACCOUNT.value, "android", displayName = "Synthetic", revision = 1)
        database.contactDao().upsert(contact.toEntity())
        bindAndroid()
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, "android")
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        // Controlled DB representation of the helper's guarded, verified projection receipt.
        database.contactDao().upsert(contact.copy(revision = 2).toEntity())
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(ACCOUNT.value, "android", 1, 0,
            null, null, null, null, null, null, "CLEAN", "BASELINED", "NONE", "ADOPTED"))
        store.finish(claim, TargetedRecoveryWorkResult.Resolved(2), 1_000) { false }
        assertEquals("RESOLVED", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
    }

    @Test fun accountCleanupRemovesOnlyItsRequestsAndDefeatsLateClaimCallbacks() = runBlocking {
        defer()
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        val foreign = claim.copy(accountId = "foreign-owner", state = "REQUESTED", claimToken = null)
        database.targetedRecoveryRequestDao().upsert(foreign)
        database.accountRemovalDao().deleteAccount(ACCOUNT.value)
        assertTrue(store.completeVerified(claim, card(), 1_000) { false } is TargetedRecoveryWorkResult.Cancelled)
        assertTrue(database.targetedRecoveryRequestDao().all(ACCOUNT.value).isEmpty())
        assertEquals(listOf(foreign), database.targetedRecoveryRequestDao().all("foreign-owner"))
        assertTrue(database.hasAccountScopedData())
        database.targetedRecoveryRequestDao().deleteAccount("foreign-owner")
        assertFalse(database.hasAccountScopedData())
    }

    @Test fun internalAndroidPlanChangesAndNewLocalRevisionsCannotResetRetryBudgetOrServerDeadline() = runBlocking {
        val contact = CanonicalContact(ACCOUNT.value, "android", displayName = "Synthetic", revision = 1)
        database.contactDao().upsert(contact.toEntity())
        bindAndroid()
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, "android")
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        store.finish(claim, TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.RATE_LIMITED,
            retryAfterMillis = 60_000), 1_000) { false }
        val before = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(ACCOUNT.value, "android", 1, 0,
            null, null, null, null, "a".repeat(64), null, "WRITE_PENDING", "BASELINED", "NONE", "SOURCE_ID_PENDING"))
        val changed = store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, "android")
        assertNotEquals(preview.obligationGeneration, changed.obligationGeneration)
        store.queue(ACCOUNT, changed, 2_000)
        val replanned = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        assertEquals(before.attemptCount, replanned.attemptCount)
        assertEquals(before.nextEligibleAt, replanned.nextEligibleAt)
        database.contactDao().upsert(contact.copy(revision = 2).toEntity())
        store.queue(ACCOUNT, store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, "android"), 3_000)
        val revised = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        assertEquals(before.attemptCount, revised.attemptCount)
        assertEquals(before.nextEligibleAt, revised.nextEligibleAt)
        assertTrue(store.eligible(ACCOUNT, before.nextEligibleAt - 1).isEmpty())
    }

    @Test fun exhaustedBudgetAndDeterministicPrerequisitesStopAutomaticWorkButAllowOneExplicitCooledRetry() = runBlocking {
        defer()
        val limited = RoomTargetedRecoveryStore(database, MutationRetryPolicy(1, 100, maximumAutomaticAttempts = 1))
        val preview = limited.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        limited.queue(ACCOUNT, preview, 0)
        var row = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        var claim = requireNotNull(limited.claim(row, 0))
        limited.finish(claim, TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.UNKNOWN,
            TargetedRecoveryPrerequisite.ANDROID_CONTEXT), 1) { false }
        row = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        claim = requireNotNull(limited.claim(row, row.nextEligibleAt))
        limited.finish(claim, TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.UNKNOWN,
            TargetedRecoveryPrerequisite.ANDROID_CONTEXT), row.nextEligibleAt) { false }
        val stopped = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        assertEquals("ACTION_REQUIRED", stopped.state)
        assertTrue(limited.eligible(ACCOUNT, stopped.nextEligibleAt + 1_000).isEmpty())
        assertEquals(TargetedRecoveryQueueResult.ALREADY_PENDING, limited.queue(ACCOUNT, preview, stopped.nextEligibleAt - 1))
        assertEquals(TargetedRecoveryQueueResult.QUEUED, limited.queue(ACCOUNT, preview, stopped.nextEligibleAt))
        val manual = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
        assertEquals(stopped.attemptCount, manual.attemptCount)
        claim = requireNotNull(limited.claim(manual, stopped.nextEligibleAt))
        assertTrue(limited.completeVerified(claim, card(), stopped.nextEligibleAt + 1) { false } is TargetedRecoveryWorkResult.Resolved)
    }

    @Test fun ingestionOnlyFailureIsAnExplicitAndroidObligationAndUnreadyBindingCannotOfferAnUnsafePreview() = runBlocking {
        val contact = CanonicalContact(ACCOUNT.value, "android", displayName = "Synthetic", revision = 1)
        database.contactDao().upsert(contact.toEntity())
        RoomAndroidProjectionLedger(database).ensureAccount(ACCOUNT)
        assertEquals(TargetedRecoveryPrerequisite.ANDROID_CONTEXT,
            store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, "android").prerequisite)
        bindAndroid()
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(ACCOUNT.value, "android", 1, 0,
            null, null, null, null, null, null, "CLEAN", "BASELINED", "NONE", "ADOPTED"))
        database.androidContactIssueDao().upsert(AndroidContactIssueEntity(ACCOUNT.value, "android", "INGEST", 0, 1, "REPAIR_REQUIRED"))
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, "android")
        assertTrue(preview.canQueue)
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        // A copy alone cannot clear a native observation that has not been durably accepted.
        store.finish(claim, TargetedRecoveryWorkResult.Resolved(1), 1_000) { false }
        assertEquals("STALE", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        assertEquals(1, database.androidContactIssueDao().getAll(ACCOUNT.value).size)
    }

    @Test fun resolvingTheOnlyAttributedIssueClearsItsBadgeWithoutClaimingGlobalSuccessOrHidingNewUnknownFailure() = runBlocking {
        val status = RoomSyncStatusStore(database)
        status.publish(ACCOUNT.value, 500, SyncStatusUpdate(SyncPassOutcome.SUCCESS, 0, 0))
        defer()
        RoomSyncPassStatusPublisher(ACCOUNT.value, database, status, wallClock = { 1_000 },
            hasUnscopedFailure = { false }).publish(SyncPassOutcome.ACTION_REQUIRED)
        assertTrue(requireNotNull(status.load(ACCOUNT.value)).scopedIssuesOnly)
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, preview, 1_001)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 1_001))
        store.completeVerified(claim, card(), 2_000) { false }
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()), SyncPassExecutor { error("Read-only") })
        val recovery = RoomSyncRecoveryDataSource(database, runner, NetworkStateProvider { NetworkState.WIFI }, ACCOUNT)
        val observed = requireNotNull(recovery.observeStatus(ACCOUNT.value).first())
        assertEquals(0, observed.actionRequiredCount)
        assertEquals(SyncDashboardState.PENDING, observed.state)
        assertEquals(500L, observed.lastSuccessAtEpochMillis)
        assertEquals(1_000L, status.load(ACCOUNT.value)?.updatedAtEpochMillis)
        RoomSyncPassStatusPublisher(ACCOUNT.value, database, status, wallClock = { 3_000 },
            externalActionReason = { SyncActionReason.INTERNAL_FAILURE }, hasUnscopedFailure = { true })
            .publish(SyncPassOutcome.ACTION_REQUIRED)
        val unknown = requireNotNull(recovery.observeStatus(ACCOUNT.value).first())
        assertEquals(1, unknown.actionRequiredCount)
        assertEquals(SyncProblem.INTERNAL_FAILURE, unknown.problem)
        assertEquals(500L, unknown.lastSuccessAtEpochMillis)
    }

    @Test fun anotherBlockedGroupRemainsNamedAndCountedAfterOneAttributedImportIsResolved() = runBlocking {
        val status = RoomSyncStatusStore(database)
        status.publish(ACCOUNT.value, 500, SyncStatusUpdate(SyncPassOutcome.SUCCESS, 0, 0))
        val repository = RoomContactRepository(database)
        assertTrue(repository.saveGroup(ContactGroup(ACCOUNT.value, "blocked-group", "Synthetic group",
            remoteLabelId = "synthetic-label", remoteVersion = "baseline")) is SaveResult.Saved)
        val intent = requireNotNull(database.outboxDao().get(ACCOUNT.value, "GROUP", "blocked-group"))
        database.outboxDao().upsert(intent.copy(state = "ACTION_REQUIRED", blockedReason = "GROUP_CAPABILITY_REQUIRED",
            errorCategory = "PERMISSION_OR_PLAN_DENIED"))
        defer()
        RoomSyncPassStatusPublisher(ACCOUNT.value, database, status, wallClock = { 1_000 },
            hasUnscopedFailure = { false }).publish(SyncPassOutcome.ACTION_REQUIRED)
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, preview, 1_001)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 1_001))
        store.completeVerified(claim, card(), 2_000) { false }
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()), SyncPassExecutor { error("Read-only") })
        val observed = requireNotNull(RoomSyncRecoveryDataSource(database, runner,
            NetworkStateProvider { NetworkState.WIFI }, ACCOUNT).observeStatus(ACCOUNT.value).first())
        assertEquals(1, observed.actionRequiredCount)
        assertEquals(SyncProblem.GROUP_CAPABILITY_REQUIRED, observed.problem)
        assertEquals("blocked-group", observed.blockedItems.single().id)
        assertEquals(500L, observed.lastSuccessAtEpochMillis)
        assertEquals(intent.copy(state = "ACTION_REQUIRED", blockedReason = "GROUP_CAPABILITY_REQUIRED",
            errorCategory = "PERMISSION_OR_PLAN_DENIED"), database.outboxDao().get(ACCOUNT.value, "GROUP", "blocked-group"))
    }

    @Test fun aConcurrentLocalIntentBecomesAConflictAndCannotBeSettledAsAResolvedRecovery() = runBlocking {
        val repository = RoomContactRepository(database)
        val saved = when (val result = repository.saveContact(CanonicalContact(ACCOUNT.value, "local", displayName = "Local name",
            remoteContactId = TARGET, remoteVersion = "baseline", values = listOf(
                ContactValue("note", ContactValueKind.NOTE, "Local note", order = 0))))) {
            is SaveResult.Saved -> result.value
            is SaveResult.Rejected -> error("Synthetic intent unexpectedly rejected")
        }
        defer()
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        assertTrue(store.completeVerified(claim, card(), 1_000) { false } is TargetedRecoveryWorkResult.Failed)
        assertNotNull(database.contactConflictDao().get(ACCOUNT.value, saved.id))
        assertEquals("Local note", requireNotNull(database.contactDao().get(ACCOUNT.value, saved.id))
            .toDomain().valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals("ACTION_REQUIRED", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        store.settleAfterOrdinaryPass(ACCOUNT, 2_000) { false }
        assertEquals("ACTION_REQUIRED", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
    }

    @Test fun deletionOfAPendingCopyWithoutAnIssueCannotResolveBeforeItsCurrentDeletionReceipt() = runBlocking {
        val canonical = CanonicalContact(ACCOUNT.value, "pending-copy", displayName = "Synthetic pending",
            remoteContactId = "synthetic-remote", remoteVersion = "version", revision = 2)
        database.contactDao().upsert(canonical.toEntity())
        bindAndroid()
        val ledger = AndroidProjectionLedgerEntity(ACCOUNT.value, canonical.id, 4, 0, 42,
            canonical.remoteContactId, null, null, null, null, "DETACHED", "BASELINED", "NONE", "ADOPTED")
        database.androidProjectionLedgerDao().insert(ledger)
        assertTrue(database.androidContactIssueDao().getAll(ACCOUNT.value).isEmpty())
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, canonical.id)
        assertTrue(preview.canQueue)
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        store.finish(claim, TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.VALIDATION_REJECTED,
            TargetedRecoveryPrerequisite.ANDROID_PERMISSION), 1_000) { false }
        assertEquals("ACTION_REQUIRED", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        RoomContactRepository(database).deleteContact(ACCOUNT.value, canonical.id)
        val deleteIntent = requireNotNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", canonical.id))
        val tombstone = requireNotNull(database.contactDao().getEntity(ACCOUNT.value, canonical.id))
        assertTrue(tombstone.isDeleted)
        assertTrue(database.androidContactIssueDao().getAll(ACCOUNT.value).isEmpty())
        // The ordinary pass did not reach Android because permission was still denied.
        store.settleAfterOrdinaryPass(ACCOUNT, 2_000) { false }
        assertEquals("STALE", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        assertEquals(ledger, database.androidProjectionLedgerDao().get(ACCOUNT.value, canonical.id))
        assertEquals(deleteIntent, database.outboxDao().get(ACCOUNT.value, "CONTACT", canonical.id))
        database.close(); database = ContakoDatabase.create(context, NAME)
        assertEquals(TargetedRecoveryState.STALE, store.observe(ACCOUNT).first().single().state)
        val deletionPreview = store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, canonical.id)
        assertTrue(deletionPreview.canQueue)
        store.queue(ACCOUNT, deletionPreview, 60_000)
        val deletionClaim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 60_000))
        assertNull(store.validate(deletionClaim))
        store.finish(deletionClaim, TargetedRecoveryWorkResult.Resolved(tombstone.revision), 61_000) { false }
        assertEquals("STALE", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        assertEquals(deleteIntent, database.outboxDao().get(ACCOUNT.value, "CONTACT", canonical.id))

        // Controlled representation of the normal projector's proven deletion receipt.
        // This does not qualify a provider delete; the retained locator is historical evidence.
        store.queue(ACCOUNT, deletionPreview, 90_000)
        database.contactDao().upsert(tombstone.copy(pendingMutationRevision = null))
        database.outboxDao().deleteRevision(ACCOUNT.value, "CONTACT", canonical.id, deleteIntent.revision)
        database.androidProjectionLedgerDao().update(ledger.copy(revision = 5, tombstoneState = "REMOTE_CONVERGED"))
        store.settleAfterOrdinaryPass(ACCOUNT, 91_000) { false }
        assertEquals("RESOLVED", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        assertEquals(42L, database.androidProjectionLedgerDao().get(ACCOUNT.value, canonical.id)?.rawContactLocator)
    }

    @Test fun aCurrentIssueOrChangedEpochStillBlocksADeletionReceiptFromResolvingAnOldRequest() = runBlocking {
        val canonical = CanonicalContact(ACCOUNT.value, "deleted", remoteContactId = "synthetic-remote",
            revision = 2, isDeleted = true)
        database.contactDao().upsert(canonical.toEntity())
        bindAndroid()
        val ledger = AndroidProjectionLedgerEntity(ACCOUNT.value, canonical.id, 4, 0, 42,
            canonical.remoteContactId, null, null, null, null, "DETACHED", "BASELINED", "REMOTE_CONVERGED", "ADOPTED")
        database.androidProjectionLedgerDao().insert(ledger)
        database.androidContactIssueDao().upsert(AndroidContactIssueEntity(ACCOUNT.value, canonical.id,
            "INGEST", 0, 2, "REPAIR_REQUIRED"))
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, canonical.id)
        assertTrue(preview.canQueue)
        store.queue(ACCOUNT, preview, 0)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 0))
        assertNull(store.validate(claim))
        store.finish(claim, TargetedRecoveryWorkResult.Resolved(2), 1_000) { false }
        assertEquals("STALE", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
        store.queue(ACCOUNT, preview, 60_000)
        val next = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 60_000))
        val binding = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        database.androidProjectionLedgerDao().advanceProviderEpoch(ACCOUNT.value, binding.revision)
        assertTrue(store.validate(next) is TargetedRecoveryWorkResult.Stale)
        store.settleAfterOrdinaryPass(ACCOUNT, 61_000) { false }
        assertEquals("STALE", database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().state)
    }

    @Test fun theLatestTargetedReadFailureSurvivesReopenWithoutReplacingIndependentImportOrAccountState() = runBlocking {
        val cases = listOf(
            GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED to TargetedRecoveryFailure.CRYPTOGRAPHY,
            GatewayFailureCategory.AUTHENTICATION_REQUIRED to TargetedRecoveryFailure.AUTHENTICATION,
            GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED to TargetedRecoveryFailure.REMOTE_PERMISSION,
            GatewayFailureCategory.UNKNOWN to TargetedRecoveryFailure.UNKNOWN,
        )
        val statusStore = RoomSyncStatusStore(database)
        statusStore.publish(ACCOUNT.value, 42, SyncStatusUpdate(SyncPassOutcome.SUCCESS, 0, 0))
        defer()
        val originalImport = database.remoteHydrationObligationDao().getAll(ACCOUNT.value)
        val originalStatus = statusStore.load(ACCOUNT.value)
        for ((index, pair) in cases.withIndex()) {
            val (category, expected) = pair
            val now = index * 100_000L
            val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
            store.queue(ACCOUNT, preview, now)
            val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), now))
            val hydration = if (category == GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED)
                GatewayContactHydrationCategory.SIGNATURE_VERIFICATION else null
            store.finish(claim, TargetedRecoveryWorkResult.Failed(category, hydrationCategory = hydration), now + 1_000) { false }
            val stored = database.targetedRecoveryRequestDao().all(ACCOUNT.value).single()
            assertEquals(category.name, stored.failureCategory)
            assertEquals(hydration?.name, stored.hydrationCategory)
            assertEquals(expected, store.observe(ACCOUNT).first().single().lastFailure)
            database.close(); database = ContakoDatabase.create(context, NAME)
            assertEquals(stored, database.targetedRecoveryRequestDao().all(ACCOUNT.value).single())
            assertEquals(expected, store.observe(ACCOUNT).first().single().lastFailure)
            assertEquals(originalImport, database.remoteHydrationObligationDao().getAll(ACCOUNT.value))
            assertEquals(originalStatus, RoomSyncStatusStore(database).load(ACCOUNT.value))
            assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
        }
        val preview = store.preview(ACCOUNT, TargetedRecoveryKind.PROTON_CARD, TARGET)
        store.queue(ACCOUNT, preview, 500_000)
        val claim = requireNotNull(store.claim(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single(), 500_000))
        store.completeVerified(claim, card(), 501_000) { false }
        assertNull(store.observe(ACCOUNT).first().single().lastFailure)
        assertNull(database.targetedRecoveryRequestDao().all(ACCOUNT.value).single().hydrationCategory)
    }

    private suspend fun bindAndroid() {
        RoomAndroidProjectionLedger(database).ensureAccount(ACCOUNT)
        val binding = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        if (binding.androidAccountName == null)
            database.androidProjectionLedgerDao().bindAndroidAccountName(ACCOUNT.value, binding.revision, "synthetic-android")
    }

    private suspend fun defer(version: String = "index-version") {
        RoomRemoteHydrationObligationStore(database).defer(ACCOUNT,
            ContactInventoryBaseline(RemoteContactId(TARGET), "Synthetic rejected", RemoteVersion(version), null, null, emptyList(), emptyList()),
            GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE))
    }
    private fun card() = VerifiedContactCard(RemoteContactId(TARGET), RemoteVersion("verified-version"),
        CanonicalContact(ACCOUNT.value, TARGET, displayName = "Synthetic recovered", remoteContactId = TARGET,
            values = listOf(ContactValue("note", ContactValueKind.NOTE, "Recovered note", order = 0))))
    private companion object {
        const val NAME = "targeted-recovery-room.db"
        const val TARGET = "synthetic-rejected"
        val ACCOUNT = AccountScope("targeted-synthetic-owner")
    }
}
