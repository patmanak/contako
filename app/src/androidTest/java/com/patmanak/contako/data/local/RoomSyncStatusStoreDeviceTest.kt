package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.NetworkState
import com.patmanak.contako.data.sync.NetworkStateProvider
import com.patmanak.contako.data.sync.RoomSyncRecoveryDataSource
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.RoomSyncPassStatusPublisher
import com.patmanak.contako.data.sync.SyncActionReason
import com.patmanak.contako.data.sync.SyncHealthState
import com.patmanak.contako.data.sync.SyncNotificationReason
import com.patmanak.contako.data.sync.SyncStatusUpdate
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncDashboardState
import com.patmanak.contako.domain.sync.SyncPassExecutor
import com.patmanak.contako.domain.sync.SyncProblem
import com.patmanak.contako.domain.sync.SyncAndroidContactIssue
import com.patmanak.contako.domain.sync.SyncAndroidContactIssueKind
import com.patmanak.contako.domain.sync.SyncBlockedItem
import com.patmanak.contako.domain.sync.SyncBlockedItemType
import com.patmanak.contako.domain.sync.SyncDashboardSnapshot
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSyncStatusStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var store: RoomSyncStatusStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun successfulOfflineAndPendingStatesRetainLastSuccessHonestly() = runBlocking {
        val success = store.publish(ACCOUNT, 1_000, update(SyncPassOutcome.SUCCESS))
        assertEquals(SyncHealthState.IDLE, success.state)
        assertEquals(1_000L, success.lastSuccessAtEpochMillis)

        val offline = store.publish(
            ACCOUNT,
            2_000,
            SyncStatusUpdate(
                SyncPassOutcome.RETRY_WAITING,
                pendingMutationCount = 0,
                actionRequiredCount = 0,
                offline = true,
            ),
        )
        assertEquals(SyncHealthState.OFFLINE, offline.state)
        assertEquals(1_000L, offline.lastSuccessAtEpochMillis)
        val pending = store.publish(
            ACCOUNT,
            3_000,
            SyncStatusUpdate(SyncPassOutcome.RETRY_WAITING, pendingMutationCount = 2, actionRequiredCount = 0),
        )
        assertEquals(SyncHealthState.PENDING, pending.state)
        assertEquals(2, pending.pendingMutationCount)
        assertEquals(1_000L, pending.lastSuccessAtEpochMillis)
    }

    @Test
    fun failedPublicationDoesNotConsumeClaimAndCurrentStateControlsCancellation() = runBlocking {
        store.publish(ACCOUNT, 1_000, blocked(SyncActionReason.VALIDATION_REJECTED))
        var cancels = 0
        assertNull(store.deliverNotificationIfDue(ACCOUNT, 1_000 + DAY, { cancels++ }) { false })
        assertEquals(0, cancels)
        assertTrue(runCatching { store.deliverNotificationIfDue(ACCOUNT, 1_000 + DAY) { error("SYNTHETIC_FAILURE") } }.isFailure)
        assertEquals(SyncNotificationReason.BLOCKED_FOR_24_HOURS,
            store.deliverNotificationIfDue(ACCOUNT, 1_000 + DAY) { true })
        assertNull(store.deliverNotificationIfDue(ACCOUNT, 1_000 + DAY) { error("ALREADY_DELIVERED") })
        store.publish(ACCOUNT, 2_000 + DAY, update(SyncPassOutcome.SUCCESS))
        store.deliverNotificationIfDue(ACCOUNT, 2_000 + DAY, { cancels++ }) { error("CURRENT_MUST_NOT_NOTIFY") }
        assertEquals(1, cancels)
    }

    @Test
    fun ordinaryBlockedMutationNotifiesOnceAfter24HoursAcrossRestart() = runBlocking {
        store.publish(
            ACCOUNT,
            1_000,
            blocked(SyncActionReason.VALIDATION_REJECTED),
        )
        assertNull(store.claimNotificationIfDue(ACCOUNT, 1_000 + DAY - 1))
        assertEquals(
            SyncNotificationReason.BLOCKED_FOR_24_HOURS,
            store.claimNotificationIfDue(ACCOUNT, 1_000 + DAY),
        )
        assertNull(store.claimNotificationIfDue(ACCOUNT, 1_000 + DAY + 1))

        reopenDatabase()
        assertNull(store.claimNotificationIfDue(ACCOUNT, 1_000 + DAY * 2))
        val retained = requireNotNull(store.load(ACCOUNT))
        assertTrue(retained.notificationClaimed)
        assertEquals(1_000L, retained.blockedSinceEpochMillis)
    }

    @Test
    fun repeatedPublicationDoesNotResetBlockAgeOrNotificationDeduplication() = runBlocking {
        store.publish(ACCOUNT, 1_000, blocked(SyncActionReason.CONFLICT_RECOVERY_REQUIRED))
        store.publish(ACCOUNT, 10_000, blocked(SyncActionReason.CONFLICT_RECOVERY_REQUIRED))
        assertEquals(1_000L, store.load(ACCOUNT)?.blockedSinceEpochMillis)
        assertEquals(
            SyncNotificationReason.BLOCKED_FOR_24_HOURS,
            store.claimNotificationIfDue(ACCOUNT, 1_000 + DAY),
        )
        store.publish(ACCOUNT, 1_000 + DAY + 1, blocked(SyncActionReason.CONFLICT_RECOVERY_REQUIRED))
        assertNull(store.claimNotificationIfDue(ACCOUNT, 1_000 + DAY * 2))
    }

    @Test
    fun authenticationActionIsImmediateAndAResolvedThenNewBlockGetsANewClaim() = runBlocking {
        store.publish(ACCOUNT, 1_000, blocked(SyncActionReason.AUTHENTICATION_REQUIRED))
        assertEquals(
            SyncNotificationReason.IMMEDIATE_ACTION_REQUIRED,
            store.claimNotificationIfDue(ACCOUNT, 1_000),
        )
        assertNull(store.claimNotificationIfDue(ACCOUNT, 2_000))

        store.publish(ACCOUNT, 3_000, update(SyncPassOutcome.SUCCESS))
        val resolved = requireNotNull(store.load(ACCOUNT))
        assertEquals(SyncHealthState.IDLE, resolved.state)
        assertNull(resolved.blockedSinceEpochMillis)
        assertFalse(resolved.notificationClaimed)

        store.publish(ACCOUNT, 4_000, blocked(SyncActionReason.INTERACTIVE_AUTHENTICATION_REQUIRED))
        assertEquals(
            SyncNotificationReason.IMMEDIATE_ACTION_REQUIRED,
            store.claimNotificationIfDue(ACCOUNT, 4_000),
        )
    }

    @Test
    fun statusAndNotificationClaimsRemainAccountScopedAndRedacted() = runBlocking {
        store.publish(ACCOUNT, 1_000, blocked(SyncActionReason.CRYPTOGRAPHIC_VERIFICATION_FAILED))
        store.publish(OTHER_ACCOUNT, 2_000, update(SyncPassOutcome.SUCCESS))

        assertEquals(SyncHealthState.ACTION_REQUIRED, store.load(ACCOUNT)?.state)
        assertEquals(SyncHealthState.IDLE, store.load(OTHER_ACCOUNT)?.state)
        assertNull(store.claimNotificationIfDue(OTHER_ACCOUNT, Long.MAX_VALUE))
        val entity = requireNotNull(database.syncAccountStatusDao().get(ACCOUNT))
        assertFalse(entity.toString().contains(ACCOUNT))
        assertTrue(store.clear(ACCOUNT))
        assertNull(store.load(ACCOUNT))
        assertEquals(SyncHealthState.IDLE, store.load(OTHER_ACCOUNT)?.state)
    }

    @Test
    fun productionPublisherDerivesValidationActionAndOfflineStateFromDurableInputs() = runBlocking {
        val repository = RoomContactRepository(database, clock = { 1_000 }, idFactory = { "draft" })
        check(repository.saveContact(CanonicalContact(ACCOUNT, "draft")) is com.patmanak.contako.domain.repository.SaveResult.Saved)
        var offline = false
        val publisher = RoomSyncPassStatusPublisher(
            accountId = ACCOUNT,
            database = database,
            statusStore = store,
            wallClock = { 2_000 },
            isOffline = { offline },
        )

        publisher.publish(SyncPassOutcome.ACTION_REQUIRED)
        val blocked = requireNotNull(store.load(ACCOUNT))
        assertEquals(1, blocked.actionRequiredCount)
        assertEquals(SyncActionReason.VALIDATION_REJECTED, blocked.actionReason)

        database.outboxDao().deleteRevision(ACCOUNT, AggregateType.CONTACT.name, "draft", 1)
        offline = true
        publisher.publish(SyncPassOutcome.RETRY_WAITING)
        assertEquals(SyncHealthState.OFFLINE, store.load(ACCOUNT)?.state)
    }

    @Test
    fun productionPublisherAcceptsExplicitAuthenticationReasonWithoutAnOutboxMutation() = runBlocking {
        val publisher = RoomSyncPassStatusPublisher(
            accountId = ACCOUNT,
            database = database,
            statusStore = store,
            wallClock = { 1_000 },
            externalActionReason = { SyncActionReason.AUTHENTICATION_REQUIRED },
        )

        publisher.publish(SyncPassOutcome.ACTION_REQUIRED)

        assertEquals(SyncHealthState.ACTION_REQUIRED, store.load(ACCOUNT)?.state)
        assertEquals(SyncActionReason.AUTHENTICATION_REQUIRED, store.load(ACCOUNT)?.actionReason)
        assertEquals(
            SyncNotificationReason.IMMEDIATE_ACTION_REQUIRED,
            store.claimNotificationIfDue(ACCOUNT, 1_000),
        )
    }

    @Test
    fun currentAuthenticationOverridesOldGroupErrorsWithoutDiscardingTheirIntent() = runBlocking {
        verifyCurrentAuthenticationPriority(
            SyncActionReason.GROUP_CAPABILITY_REQUIRED,
            SyncActionReason.AUTHENTICATION_REQUIRED,
        )
    }

    @Test
    fun currentInteractiveUnlockOverridesOldValidationAndImportErrorsAcrossRestart() = runBlocking {
        verifyCurrentAuthenticationPriority(
            SyncActionReason.VALIDATION_REJECTED,
            SyncActionReason.INTERACTIVE_AUTHENTICATION_REQUIRED,
        )
    }

    @Test
    fun retainedIngestIssueIdentifiesCleanContactWithoutOutboxOrInventedPendingCopy() = runBlocking {
        val affected = seedCleanContact("ingest-fixture")
        seedIssue(affected, "INGEST")
        val other = seedCleanContact("other-ingest-fixture", OTHER_ACCOUNT)
        seedIssue(other, "INGEST")
        RoomSyncPassStatusPublisher(ACCOUNT, database, store, wallClock = { 1_000 })
            .publish(SyncPassOutcome.ACTION_REQUIRED)

        val dashboard = recoverySnapshot()

        assertEquals(listOf(SyncAndroidContactIssue(affected.id, SyncAndroidContactIssueKind.ANDROID_INGEST,
            displayName = affected.displayName, isDeleted = false)),
            dashboard.androidContactIssues)
        assertTrue(dashboard.actionRequiredCount >= 1)
        assertEquals(SyncDashboardState.ANDROID_DEGRADED, dashboard.state)
        assertEquals(SyncProblem.ANDROID_INTEROPERABILITY_DEGRADED, dashboard.problem)
        assertEquals(0, dashboard.pendingMutationCount)
        assertTrue(dashboard.blockedMutationContactIds.isEmpty())
        assertTrue(dashboard.androidPendingContactIds.isEmpty())
        assertTrue(database.outboxDao().getAll(ACCOUNT).isEmpty())
        assertEquals("CLEAN", database.androidProjectionLedgerDao().get(ACCOUNT, affected.id)?.projectionState)
    }

    @Test
    fun retainedProjectionIssueSurvivesAuthenticationPriorityAndRestartDespiteCleanLedger() = runBlocking {
        val affected = seedCleanContact("projection-fixture")
        seedIssue(affected, "PROJECTION")
        assertTrue(database.androidProjectionLedgerDao().observePendingContactIds(ACCOUNT).first().isEmpty())
        RoomSyncPassStatusPublisher(ACCOUNT, database, store, wallClock = { 1_000 },
            externalActionReason = { SyncActionReason.AUTHENTICATION_REQUIRED })
            .publish(SyncPassOutcome.ACTION_REQUIRED)
        reopenDatabase()

        val dashboard = recoverySnapshot()

        assertEquals(SyncDashboardState.AUTHENTICATION_REQUIRED, dashboard.state)
        assertEquals(SyncProblem.AUTHENTICATION_REQUIRED, dashboard.problem)
        assertEquals(listOf(SyncAndroidContactIssue(affected.id, SyncAndroidContactIssueKind.ANDROID_PROJECTION,
            displayName = affected.displayName, isDeleted = false)),
            dashboard.androidContactIssues)
        assertEquals(setOf(affected.id), dashboard.androidPendingContactIds)
        assertTrue(dashboard.actionRequiredCount >= 1)
        assertEquals(0, dashboard.pendingMutationCount)
        assertEquals("CLEAN", database.androidProjectionLedgerDao().get(ACCOUNT, affected.id)?.projectionState)
        assertTrue(database.androidProjectionLedgerDao().observePendingContactIds(ACCOUNT).first().isEmpty())
    }

    @Test
    fun blockedDeletedContactAndGroupRetainPrivateNamesAndTypesOutsideActiveDirectoryWithoutCrossAccountLeak() = runBlocking {
        val deleted = CanonicalContact(ACCOUNT, "shared-contact-id", displayName = "Deleted synthetic contact",
            revision = 1, isDeleted = true)
        val group = ContactGroup(ACCOUNT, "shared-group-id", "Blocked synthetic group", revision = 1)
        database.contactDao().upsert(deleted.toEntity())
        database.contactGroupDao().upsert(group.toEntity())
        database.contactDao().upsert(deleted.copy(accountId = OTHER_ACCOUNT, displayName = "Foreign contact").toEntity())
        database.contactGroupDao().upsert(group.copy(accountId = OTHER_ACCOUNT, name = "Foreign group").toEntity())
        val contactMutation = OutboxMutationEntity(ACCOUNT, AggregateType.CONTACT.name, deleted.id,
            MutationOperation.DELETE.name, revision = 1, createdAtEpochMillis = 100,
            updatedAtEpochMillis = 100, idempotencyKey = "synthetic-contact-delete",
            state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = SyncActionReason.VALIDATION_REJECTED.name)
        val groupMutation = contactMutation.copy(aggregateType = AggregateType.GROUP.name,
            aggregateId = group.id, operation = MutationOperation.UPSERT.name,
            createdAtEpochMillis = 200, idempotencyKey = "synthetic-group-update",
            blockedReason = SyncActionReason.GROUP_CAPABILITY_REQUIRED.name)
        listOf(contactMutation, groupMutation, contactMutation.copy(accountId = OTHER_ACCOUNT,
            idempotencyKey = "foreign-contact-delete"), groupMutation.copy(accountId = OTHER_ACCOUNT,
            idempotencyKey = "foreign-group-update")).forEach { database.outboxDao().upsert(it) }
        RoomSyncPassStatusPublisher(ACCOUNT, database, store, wallClock = { 1_000 })
            .publish(SyncPassOutcome.ACTION_REQUIRED)

        val dashboard = recoverySnapshot()

        assertTrue(database.contactDao().observeActive(ACCOUNT).first().isEmpty())
        assertEquals(listOf(
            SyncBlockedItem(deleted.id, SyncBlockedItemType.CONTACT, deleted.displayName,
                isDeleted = true, problem = SyncProblem.VALIDATION_REJECTED),
            SyncBlockedItem(group.id, SyncBlockedItemType.GROUP, group.name,
                isDeleted = false, problem = SyncProblem.GROUP_CAPABILITY_REQUIRED),
        ), dashboard.blockedItems)
        assertEquals(2, dashboard.pendingMutationCount)
        assertEquals(2, dashboard.actionRequiredCount)
        assertEquals(setOf(deleted.id), dashboard.blockedMutationContactIds)
        assertTrue(dashboard.blockedItems.none { it.displayName?.startsWith("Foreign") == true })
        assertTrue(dashboard.blockedItems.none { it.toString().contains("synthetic") || it.toString().contains(it.id) })
    }

    @Test
    fun resolvingRetainedAndroidIssueClearsAttentionWhileOrdinaryInitialCopiesRemainPending() = runBlocking {
        val affected = seedCleanContact("resolved-fixture")
        seedIssue(affected, "INGEST")
        val ordinary = CanonicalContact(ACCOUNT, "ordinary-copy", displayName = "Ordinary pending copy", revision = 1)
        database.contactDao().upsert(ordinary.toEntity())
        val publisher = RoomSyncPassStatusPublisher(ACCOUNT, database, store, wallClock = { 1_000 })
        publisher.publish(SyncPassOutcome.ACTION_REQUIRED)
        val before = recoverySnapshot()
        assertEquals(1, before.actionRequiredCount)
        assertEquals(setOf(ordinary.id), before.androidPendingContactIds)
        assertEquals(listOf(SyncAndroidContactIssue(affected.id, SyncAndroidContactIssueKind.ANDROID_INGEST,
            displayName = affected.displayName, isDeleted = false)),
            before.androidContactIssues)

        assertEquals(1, database.androidContactIssueDao().delete(ACCOUNT, affected.id, "INGEST", 1))
        publisher.publish(SyncPassOutcome.SUCCESS)
        val after = recoverySnapshot()

        assertTrue(after.androidContactIssues.isEmpty())
        assertEquals(0, after.actionRequiredCount)
        assertEquals(0, after.pendingMutationCount)
        assertEquals(SyncDashboardState.PENDING, after.state)
        assertEquals(setOf(ordinary.id), after.androidPendingContactIds)
        assertTrue(after.blockedItems.isEmpty())
        assertTrue(database.androidContactIssueDao().getAll(ACCOUNT).isEmpty())
        assertEquals(SyncHealthState.IDLE, store.load(ACCOUNT)?.state)
    }

    @Test
    fun durableBlockedGroupOverridesAbsentOrStaleCurrentStatusWithoutPublisherRefresh() = runBlocking {
        val group = ContactGroup(ACCOUNT, "unpublished-blocked-group", "Retained synthetic group", revision = 1)
        database.contactGroupDao().upsert(group.toEntity())
        database.outboxDao().upsert(OutboxMutationEntity(ACCOUNT, AggregateType.GROUP.name, group.id,
            MutationOperation.UPSERT.name, revision = 1, createdAtEpochMillis = 100,
            updatedAtEpochMillis = 100, idempotencyKey = "synthetic-unpublished-group-update",
            state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = SyncActionReason.GROUP_CAPABILITY_REQUIRED.name))
        assertNull(store.load(ACCOUNT))

        // The durable row is already authoritative before another pass can publish a banner.
        for (staleCurrentStatus in listOf(false, true)) {
            if (staleCurrentStatus) {
                store.publish(ACCOUNT, 1_000, update(SyncPassOutcome.SUCCESS))
                assertEquals(SyncHealthState.IDLE, store.load(ACCOUNT)?.state)
                assertEquals(0, store.load(ACCOUNT)?.actionRequiredCount)
            }
            val dashboard = recoverySnapshot()

            assertEquals(SyncDashboardState.BLOCKED, dashboard.state)
            assertEquals(1, dashboard.actionRequiredCount)
            assertEquals(SyncProblem.GROUP_CAPABILITY_REQUIRED, dashboard.problem)
            assertEquals(listOf(SyncBlockedItem(group.id, SyncBlockedItemType.GROUP, group.name,
                isDeleted = false, problem = SyncProblem.GROUP_CAPABILITY_REQUIRED)), dashboard.blockedItems)
            assertTrue(dashboard.blockedMutationContactIds.isEmpty())
            assertTrue(dashboard.androidContactIssues.isEmpty())
            assertTrue(dashboard.androidPendingContactIds.isEmpty())
        }
    }

    private suspend fun seedCleanContact(id: String, accountId: String = ACCOUNT): CanonicalContact {
        val contact = CanonicalContact(accountId, id, displayName = "Synthetic $id", revision = 1,
            remoteContactId = "remote-$id")
        database.contactDao().upsert(contact.toEntity())
        database.androidProjectionLedgerDao().insertAccount(AndroidProjectionAccountEntity(accountId,
            revision = 1, providerEpoch = 1, androidAccountName = "synthetic-$accountId@example.test"))
        database.androidProjectionLedgerDao().insert(AndroidProjectionLedgerEntity(accountId, id,
            revision = 1, providerEpoch = 1, rawContactLocator = 1,
            sourceIdentity = contact.remoteContactId,
            canonicalProjectionFingerprint = "a".repeat(64), androidBaselineFingerprint = "a".repeat(64),
            pendingProjectionFingerprint = null, observedAndroidFingerprint = null,
            projectionState = "CLEAN", ingestionState = "BASELINED", tombstoneState = "NONE", adoptionState = "ADOPTED"))
        return contact
    }

    private suspend fun seedIssue(contact: CanonicalContact, origin: String) {
        database.androidContactIssueDao().upsert(AndroidContactIssueEntity(contact.accountId, contact.id,
            origin, providerEpoch = 1, canonicalRevision = contact.revision, reason = "REPAIR_REQUIRED"))
    }

    private suspend fun recoverySnapshot(): SyncDashboardSnapshot {
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()),
            SyncPassExecutor { error("STATUS_OBSERVATION_MUST_NOT_START_SYNC") })
        return requireNotNull(withTimeout(10_000) {
            RoomSyncRecoveryDataSource(database, runner, NetworkStateProvider { NetworkState.WIFI },
                AccountScope(ACCOUNT)).observeStatus(ACCOUNT).first()
        })
    }

    private suspend fun verifyCurrentAuthenticationPriority(
        olderReason: SyncActionReason,
        currentReason: SyncActionReason,
    ) {
        val blockedContact = OutboxMutationEntity(
            accountId = ACCOUNT,
            aggregateType = AggregateType.CONTACT.name,
            aggregateId = "blocked-contact",
            operation = MutationOperation.UPSERT.name,
            revision = 3,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 100,
            idempotencyKey = "retained-contact-intent",
            state = DurableMutationState.ACTION_REQUIRED.name,
            blockedReason = olderReason.name,
        )
        val blockedGroup = blockedContact.copy(
            aggregateType = AggregateType.GROUP.name,
            aggregateId = "blocked-group",
            idempotencyKey = "retained-group-intent",
            createdAtEpochMillis = 200,
        )
        val pendingContact = blockedContact.copy(
            aggregateId = "pending-contact",
            idempotencyKey = "retained-pending-intent",
            state = DurableMutationState.PENDING.name,
            blockedReason = null,
            createdAtEpochMillis = 300,
        )
        val retainedMutations = listOf(blockedContact, blockedGroup, pendingContact)
        retainedMutations.forEach { database.outboxDao().upsert(it) }
        val importFailure = RemoteHydrationObligationEntity(
            ACCOUNT, "unreadable-contact", "Synthetic import", "CRYPTOGRAPHIC_VERIFICATION_FAILED", null,
        )
        database.remoteHydrationObligationDao().upsert(importFailure)
        var externalReason: SyncActionReason? = null
        var now = 1_000L
        fun publisher() = RoomSyncPassStatusPublisher(
            accountId = ACCOUNT,
            database = database,
            statusStore = store,
            wallClock = { now },
            externalActionReason = { externalReason },
        )
        publisher().publish(SyncPassOutcome.ACTION_REQUIRED)
        assertEquals(olderReason, store.load(ACCOUNT)?.actionReason)
        assertEquals(SyncNotificationReason.BLOCKED_FOR_24_HOURS,
            store.claimNotificationIfDue(ACCOUNT, now + DAY))

        // A current prerequisite failure must replace an already-notified old banner, not its data.
        now += DAY + 1
        externalReason = currentReason
        publisher().publish(SyncPassOutcome.ACTION_REQUIRED)
        val status = requireNotNull(store.load(ACCOUNT))
        assertEquals(currentReason, status.actionReason)
        assertEquals(3, status.pendingMutationCount)
        assertEquals(3, status.actionRequiredCount)
        assertFalse(status.notificationClaimed)
        assertEquals(SyncNotificationReason.IMMEDIATE_ACTION_REQUIRED,
            store.claimNotificationIfDue(ACCOUNT, now))
        assertNull(store.claimNotificationIfDue(ACCOUNT, now + 1))

        reopenDatabase()
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()),
            SyncPassExecutor { error("STATUS_OBSERVATION_MUST_NOT_START_SYNC") })
        val recovery = RoomSyncRecoveryDataSource(database, runner,
            NetworkStateProvider { NetworkState.WIFI }, AccountScope(ACCOUNT))
        val dashboard = withTimeout(10_000) { recovery.observeStatus(ACCOUNT).first() }
        // This state is the production Sync screen's reconnect-button predicate.
        assertEquals(SyncDashboardState.AUTHENTICATION_REQUIRED, dashboard?.state)
        assertEquals(SyncProblem.valueOf(currentReason.name), dashboard?.problem)
        assertEquals(3, dashboard?.pendingMutationCount)
        assertEquals(3, dashboard?.actionRequiredCount)
        assertEquals(setOf("blocked-contact"), dashboard?.blockedMutationContactIds)
        assertEquals("unreadable-contact", dashboard?.importProblems?.single()?.remoteId)
        assertEquals(SyncProblem.CRYPTOGRAPHIC_VERIFICATION_FAILED,
            dashboard?.importProblems?.single()?.problem)
        assertNull(store.claimNotificationIfDue(ACCOUNT, now + 2))
        assertEquals(retainedMutations, database.outboxDao().getAll(ACCOUNT))
        assertEquals(listOf(importFailure), database.remoteHydrationObligationDao().getAll(ACCOUNT))

        externalReason = null
        now++
        publisher().publish(SyncPassOutcome.ACTION_REQUIRED)
        assertEquals(olderReason, store.load(ACCOUNT)?.actionReason)
        val afterAuthentication = withTimeout(10_000) { recovery.observeStatus(ACCOUNT).first() }
        assertEquals(SyncDashboardState.BLOCKED, afterAuthentication?.state)
        assertEquals(SyncProblem.valueOf(olderReason.name), afterAuthentication?.problem)
        assertEquals(setOf("blocked-contact"), afterAuthentication?.blockedMutationContactIds)
        assertEquals(retainedMutations, database.outboxDao().getAll(ACCOUNT))
        assertEquals(listOf(importFailure), database.remoteHydrationObligationDao().getAll(ACCOUNT))
        assertNull(store.claimNotificationIfDue(ACCOUNT, now))
    }

    private fun blocked(reason: SyncActionReason) = SyncStatusUpdate(
        SyncPassOutcome.ACTION_REQUIRED,
        pendingMutationCount = 1,
        actionRequiredCount = 1,
        actionReason = reason,
    )

    private fun update(outcome: SyncPassOutcome) = SyncStatusUpdate(
        outcome,
        pendingMutationCount = 0,
        actionRequiredCount = 0,
    )

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun openDatabase() {
        database = ContakoDatabase.create(context, DATABASE_NAME)
        store = RoomSyncStatusStore(database)
    }

    private companion object {
        const val DATABASE_NAME = "v03-sync-status.db"
        const val ACCOUNT = "account-status"
        const val OTHER_ACCOUNT = "account-other"
        const val DAY = 24 * 60 * 60 * 1_000L
    }
}
