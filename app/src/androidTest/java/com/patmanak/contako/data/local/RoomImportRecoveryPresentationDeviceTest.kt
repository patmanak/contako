package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.NetworkState
import com.patmanak.contako.data.sync.NetworkStateProvider
import com.patmanak.contako.data.sync.RoomSyncRecoveryDataSource
import com.patmanak.contako.domain.sync.*
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

    private fun obligation(id: String) = RemoteHydrationObligationEntity(account.value, id, "Synthetic card",
        "CRYPTOGRAPHIC_VERIFICATION_FAILED", "DECRYPT_VERIFY", generation = "generation-$id", baselineVersion = "baseline")
    private companion object { const val NAME = "import-recovery-presentation.db"; const val NOW = 100_000L }
}
