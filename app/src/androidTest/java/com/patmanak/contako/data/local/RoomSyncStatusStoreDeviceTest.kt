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
import com.patmanak.contako.domain.model.CanonicalContact
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
