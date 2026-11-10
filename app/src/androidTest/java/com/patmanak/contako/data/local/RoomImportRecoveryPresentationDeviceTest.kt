package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.NetworkState
import com.patmanak.contako.data.sync.NetworkStateProvider
import com.patmanak.contako.data.sync.RoomSyncRecoveryDataSource
import com.patmanak.contako.domain.sync.*
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and runner only; no Android account, provider contact or network access. */
@RunWith(AndroidJUnit4::class)
class RoomImportRecoveryPresentationDeviceTest {
    private val application: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private val account = AccountScope("synthetic-import-recovery-owner")

    @Before fun setup() {
        application.deleteDatabase(NAME)
        database = ContakoDatabase.create(application, NAME)
    }
    @After fun cleanup() { database.close(); application.deleteDatabase(NAME) }

    @Test fun ignoredTransientAndBlockingImportsRemainDistinctInTheActualDashboardFlow() = runBlocking {
        val ids = listOf("ignored", "waiting", "blocked")
        database.contactInventoryCheckpointDao().insertInitialCheckpoint(ContactInventoryCheckpointEntity(account.value, 0))
        database.contactInventoryCheckpointDao().insertEntries(ids.map {
            ContactInventoryEntryEntity(account.value, it, "Synthetic inventory", "baseline", null, null)
        })
        database.remoteHydrationObligationDao().upsert(obligation("ignored").copy(ignoredBaselineVersion = "baseline"))
        database.remoteHydrationObligationDao().upsert(obligation("waiting").copy(category = "TIMEOUT", isTransient = true,
            nextAttemptAtEpochMillis = NOW + 60_000))
        database.remoteHydrationObligationDao().upsert(obligation("blocked"))
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()), SyncPassExecutor { error("Observation must not run sync") })
        val recovery = RoomSyncRecoveryDataSource(database, runner, NetworkStateProvider { NetworkState.WIFI }, account, clock = { NOW })
        suspend fun snapshot() = requireNotNull(withTimeout(10_000) { recovery.observeStatus(account.value).first() })
        val mixed = snapshot()
        assertEquals(SyncDashboardState.BLOCKED, mixed.state)
        assertEquals(1, mixed.actionRequiredCount)
        assertEquals(2, mixed.initialImport?.deferredContactCount)
        assertFalse(requireNotNull(mixed.initialImport).remoteComplete)
        assertTrue(mixed.importProblems.single { it.remoteId == "ignored" }.ignored)
        assertTrue(mixed.importProblems.single { it.remoteId == "waiting" }.isTransient)

        database.remoteHydrationObligationDao().delete(account.value, setOf("blocked"))
        val waiting = snapshot()
        assertEquals(SyncDashboardState.PENDING, waiting.state)
        assertEquals(0, waiting.actionRequiredCount)
        assertEquals(1, waiting.initialImport?.deferredContactCount)
        // SQL already excludes the ignored row. Subtracting it again would incorrectly report finished.
        assertFalse(requireNotNull(waiting.initialImport).remoteComplete)

        database.remoteHydrationObligationDao().delete(account.value, setOf("waiting"))
        val ignoredOnly = snapshot()
        assertEquals(0, ignoredOnly.actionRequiredCount)
        assertEquals(0, ignoredOnly.initialImport?.deferredContactCount)
        assertTrue(requireNotNull(ignoredOnly.initialImport).remoteComplete)
    }

    @Test fun explicitImportResumeRunsTheNormalImportPassAndCannotBypassAServerDeadline() = runBlocking {
        database.remoteHydrationObligationDao().upsert(obligation("blocked"))
        val requests = mutableListOf<SyncPassRequest>()
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()), SyncPassExecutor {
            requests += it
            SyncPassOutcome.SUCCESS
        })
        val recovery = RoomSyncRecoveryDataSource(database, runner, NetworkStateProvider { NetworkState.WIFI }, account, clock = { NOW })
        assertFalse(recovery.resumeImportProblem(account.value, "blocked", "stale-generation"))
        assertTrue(recovery.resumeImportProblem(account.value, "blocked", "generation-blocked"))
        withTimeout(10_000) { runner.awaitIdle() }
        assertEquals(1, requests.size)
        assertEquals(setOf(SyncTrigger.MANUAL), requests.single().triggers)
        assertEquals(SyncScope.INCREMENTAL, requests.single().scope)
        val resumed = requireNotNull(database.remoteHydrationObligationDao().get(account.value, "blocked"))
        assertTrue(resumed.isTransient)
        assertNotEquals("generation-blocked", resumed.generation)

        val delayed = obligation("waiting").copy(category = "RATE_LIMITED", isTransient = true,
            nextAttemptAtEpochMillis = NOW + 90_000, retryAfterMillis = 90_000)
        database.remoteHydrationObligationDao().upsert(delayed)
        assertFalse(recovery.resumeImportProblem(account.value, "waiting", delayed.generation))
        assertEquals(delayed, database.remoteHydrationObligationDao().get(account.value, "waiting"))
        assertEquals(1, requests.size)
    }

    @Test fun legacyGroupReviewIsScopedReadOnlyAndOnlyAnExplicitSaveReplacesItsIntent() = runBlocking {
        val group = ContactGroup(account.value, "legacy-group", "Synthetic group", revision = 7,
            pendingMutationRevision = 7, remoteLabelId = "remote-group", remoteVersion = "accepted-version")
        database.contactGroupDao().upsert(group.toEntity())
        val original = OutboxMutationEntity(account.value, "GROUP", group.id, "ASSIGNMENTS", 7, 100, 100,
            errorCategory = "RECONCILIATION_INCOMPLETE", blockedReason = "PARTIAL_GROUP_SCOPE_UNVERIFIED",
            remoteIdentity = group.remoteLabelId, remoteVersion = group.remoteVersion,
            idempotencyKey = "synthetic-choice", state = "ACTION_REQUIRED")
        database.outboxDao().upsert(original)
        database.contactGroupDao().upsert(group.copy(accountId = "foreign-owner").toEntity())
        database.outboxDao().upsert(original.copy(accountId = "foreign-owner"))
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()),
            SyncPassExecutor { error("Consultation must not request synchronization") })
        val recovery = RoomSyncRecoveryDataSource(database, runner, NetworkStateProvider { NetworkState.WIFI },
            account, clock = { NOW })
        repeat(2) {
            val snapshot = requireNotNull(withTimeout(10_000) { recovery.observeStatus(account.value).first() })
            val issue = snapshot.blockedItems.single()
            assertEquals(group.id, issue.id)
            assertEquals(SyncBlockedItemType.GROUP, issue.type)
            assertEquals(SyncProblem.GROUP_REVIEW_REQUIRED, issue.problem)
            assertEquals(SyncProblem.GROUP_REVIEW_REQUIRED, snapshot.problem)
            assertEquals(SyncIssueAction.REVIEW_ITEM, SyncIssuePresentationPolicy.present(issue.problem,
                SyncIssueScope.GROUP, hasDurableIntent = true, canReview = true).action)
            assertEquals(original, database.outboxDao().get(account.value, "GROUP", group.id))
            assertEquals(group, database.contactGroupDao().get(account.value, group.id)!!.toDomain())
        }
        val repository = RoomContactRepository(database, clock = { NOW }, elapsedRealtimeClock = { NOW })
        assertTrue(repository.saveGroup(group) is SaveResult.Saved)
        val saved = database.outboxDao().get(account.value, "GROUP", group.id)!!
        assertEquals(8L, saved.revision)
        assertEquals("PENDING", saved.state)
        assertNull(saved.blockedReason)
        assertEquals(group.memberships, database.contactGroupDao().get(account.value, group.id)!!.toDomain().memberships)
        assertEquals(original.copy(accountId = "foreign-owner"),
            database.outboxDao().get("foreign-owner", "GROUP", group.id))
    }

    @Test fun aContactWithAnUnexpectedGroupScopeReasonIsNotGivenAGroupReviewAction() = runBlocking {
        val contact = CanonicalContact(account.value, "contact", displayName = "Synthetic contact", pendingMutationRevision = 1)
        database.contactDao().upsert(contact.toEntity())
        database.outboxDao().upsert(OutboxMutationEntity(account.value, "CONTACT", contact.id, "UPSERT", 1, 0, 0,
            blockedReason = "PARTIAL_GROUP_SCOPE_UNVERIFIED", idempotencyKey = "synthetic-contact-choice", state = "ACTION_REQUIRED"))
        val runner = AccountSyncRunner(CoroutineScope(currentCoroutineContext()),
            SyncPassExecutor { error("Consultation must not request synchronization") })
        val recovery = RoomSyncRecoveryDataSource(database, runner, NetworkStateProvider { NetworkState.WIFI },
            account, clock = { NOW })
        val snapshot = requireNotNull(withTimeout(10_000) { recovery.observeStatus(account.value).first() })
        assertEquals(SyncProblem.INTERNAL_FAILURE, snapshot.blockedItems.single().problem)
    }

    private fun obligation(id: String) = RemoteHydrationObligationEntity(account.value, id, "Synthetic card",
        "CRYPTOGRAPHIC_VERIFICATION_FAILED", "DECRYPT_VERIFY", generation = "generation-$id", baselineVersion = "baseline")
    private companion object { const val NAME = "import-recovery-presentation.db"; const val NOW = 100_000L }
}
