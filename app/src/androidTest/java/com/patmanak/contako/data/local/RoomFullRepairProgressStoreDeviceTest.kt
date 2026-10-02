package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.FullRepairBeginResult
import com.patmanak.contako.data.sync.FullRepairPhase
import com.patmanak.contako.data.sync.RoomFullRepairProgressStore
import com.patmanak.contako.data.sync.FakeNetworkStateProvider
import com.patmanak.contako.data.sync.NetworkState
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
class RoomFullRepairProgressStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var store: RoomFullRepairProgressStore

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
    fun nonWifiAttemptRequiresOneConfirmationAndRestartResumesWithoutANewPrompt() = runBlocking {
        assertEquals(
            FullRepairBeginResult.ConfirmationRequired,
            store.beginOrResume(ACCOUNT, nonWifiConfirmationRequired = true, confirmationGranted = false, 1_000),
        )
        assertNull(store.load(ACCOUNT))

        val started = store.beginOrResume(
            ACCOUNT,
            nonWifiConfirmationRequired = true,
            confirmationGranted = true,
            nowEpochMillis = 2_000,
        ) as FullRepairBeginResult.Started
        assertTrue(started.progress.nonWifiConfirmed)

        reopenDatabase()
        val resumed = store.beginOrResume(
            ACCOUNT,
            nonWifiConfirmationRequired = true,
            confirmationGranted = false,
            nowEpochMillis = 3_000,
        ) as FullRepairBeginResult.Resumed
        assertEquals(started.progress, resumed.progress)
    }

    @Test
    fun checkpointsAreMonotonicRevisionGuardedAndCancellationSafe() = runBlocking {
        val initial = startOnWifi()
        assertTrue(store.checkpoint(ACCOUNT, initial.revision, FullRepairPhase.REMOTE_ENUMERATION, 5, 10, 2_000))
        val afterPage = requireNotNull(store.load(ACCOUNT))
        assertFalse(store.checkpoint(ACCOUNT, initial.revision, FullRepairPhase.REMOTE_ENUMERATION, 6, 10, 3_000))
        assertTrue(
            store.checkpoint(
                ACCOUNT,
                afterPage.revision,
                FullRepairPhase.CANONICAL_RECONCILIATION,
                0,
                10,
                3_000,
            ),
        )
        val canonical = requireNotNull(store.load(ACCOUNT))
        assertTrue(store.requestCancellation(ACCOUNT, 4_000))
        val cancelled = requireNotNull(store.load(ACCOUNT))
        assertTrue(cancelled.cancellationRequested)
        assertFalse(
            store.checkpoint(
                ACCOUNT,
                canonical.revision,
                FullRepairPhase.CANONICAL_RECONCILIATION,
                1,
                10,
                5_000,
            ),
        )
        assertTrue(store.clearAfterExplicitCancellation(ACCOUNT, cancelled.revision))
        assertNull(store.load(ACCOUNT))
    }

    @Test
    fun cancelledAttemptIsClearedAndLaterNonWifiAttemptRequiresFreshConfirmation() = runBlocking {
        startOnWifi()
        assertTrue(store.requestCancellation(ACCOUNT, 2_000))
        val cancelled = requireNotNull(store.load(ACCOUNT))
        assertTrue(store.clearAfterExplicitCancellation(ACCOUNT, cancelled.revision))

        assertEquals(
            FullRepairBeginResult.ConfirmationRequired,
            store.beginOrResume(ACCOUNT, nonWifiConfirmationRequired = true, confirmationGranted = false, 3_000),
        )
        assertNull(store.load(ACCOUNT))
    }

    @Test
    fun networkChangeAfterConfirmationResumesWithoutSecondPrompt() = runBlocking {
        val network = FakeNetworkStateProvider(NetworkState.NON_WIFI)
        assertEquals(
            FullRepairBeginResult.ConfirmationRequired,
            store.beginOrResume(ACCOUNT, network.current() != NetworkState.WIFI, false, 1_000),
        )
        val started = store.beginOrResume(ACCOUNT, true, true, 2_000)
        assertTrue(started is FullRepairBeginResult.Started)

        network.state = NetworkState.WIFI
        assertTrue(store.beginOrResume(ACCOUNT, false, false, 3_000) is FullRepairBeginResult.Resumed)
        network.state = NetworkState.NON_WIFI
        assertTrue(store.beginOrResume(ACCOUNT, true, false, 4_000) is FullRepairBeginResult.Resumed)
    }

    @Test
    fun lateCheckpointsSurviveDatabaseReopenWithExactRevisionAndScope() = runBlocking {
        var progress = startOnWifi()
        listOf(FullRepairPhase.CANONICAL_RECONCILIATION,
            FullRepairPhase.ANDROID_PROJECTION, FullRepairPhase.PUBLISHING).forEachIndexed { index, phase ->
            assertTrue(store.checkpoint(ACCOUNT, progress.revision, phase, 0, 325, 2_000L + index))
            val committed = requireNotNull(store.load(ACCOUNT))
            reopenDatabase()
            assertEquals(committed, store.load(ACCOUNT))
            assertNull(store.load(AccountScope("other-account")))
            assertFalse(store.clearAfterSuccessfulPublish(ACCOUNT, committed.revision))
            progress = committed
        }
    }

    @Test
    fun scopeClearsOnlyAfterCompletedPublish() = runBlocking {
        var progress = startOnWifi()
        assertFalse(store.clearAfterSuccessfulPublish(ACCOUNT, progress.revision))
        listOf(
            FullRepairPhase.CANONICAL_RECONCILIATION,
            FullRepairPhase.ANDROID_PROJECTION,
            FullRepairPhase.PUBLISHING,
        ).forEachIndexed { index, phase ->
            assertTrue(store.checkpoint(ACCOUNT, progress.revision, phase, 0, 1, 2_000L + index))
            progress = requireNotNull(store.load(ACCOUNT))
        }
        assertFalse(store.clearAfterSuccessfulPublish(ACCOUNT, progress.revision))
        assertTrue(store.checkpoint(ACCOUNT, progress.revision, FullRepairPhase.PUBLISHING, 1, 1, 3_000))
        progress = requireNotNull(store.load(ACCOUNT))
        assertTrue(store.clearAfterSuccessfulPublish(ACCOUNT, progress.revision))
        assertNull(store.load(ACCOUNT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun phaseCannotMoveBackwards() = runBlocking {
        var progress = startOnWifi()
        assertTrue(store.checkpoint(ACCOUNT, progress.revision, FullRepairPhase.CANONICAL_RECONCILIATION, 0, 1, 2_000))
        progress = requireNotNull(store.load(ACCOUNT))
        store.checkpoint(ACCOUNT, progress.revision, FullRepairPhase.REMOTE_ENUMERATION, 1, 1, 3_000)
        Unit
    }

    @Test
    fun progressIsAccountScopedAndRedacted() = runBlocking {
        startOnWifi()
        val other = AccountScope("other-account")
        assertNull(store.load(other))
        val entity = requireNotNull(database.fullRepairProgressDao().get(ACCOUNT.value))
        assertFalse(entity.toString().contains(ACCOUNT.value))
    }

    private suspend fun startOnWifi() =
        (store.beginOrResume(ACCOUNT, false, false, 1_000) as FullRepairBeginResult.Started).progress

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun openDatabase() {
        database = ContakoDatabase.create(context, DATABASE_NAME)
        store = RoomFullRepairProgressStore(database)
    }

    private companion object {
        const val DATABASE_NAME = "v03-full-repair.db"
        val ACCOUNT = AccountScope("full-repair-account")
    }
}
