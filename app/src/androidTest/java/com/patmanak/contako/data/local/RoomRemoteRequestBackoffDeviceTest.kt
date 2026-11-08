package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room state only; no account or network operation is performed. */
@RunWith(AndroidJUnit4::class)
class RoomRemoteRequestBackoffDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private var now = 100_000L

    @Before fun before() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
    }
    @After fun after() { database.close(); context.deleteDatabase(DB) }

    @Test fun serverDeadlineSurvivesReopenAndAnotherFailureCannotShortenIt() = runBlocking {
        var store = RoomRemoteRequestBackoff(database) { now }
        store.record(ACCOUNT, GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 90_000))
        assertEquals(90_000L, store.remainingMillis(ACCOUNT))
        database.close()
        database = ContakoDatabase.create(context, DB)
        store = RoomRemoteRequestBackoff(database) { now }
        now += 1_000
        assertEquals(89_000L, store.remainingMillis(ACCOUNT))
        store.record(ACCOUNT, GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 1_000))
        store.record(ACCOUNT, GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE, retryAfterMillis = 300_000))
        store.record(ACCOUNT, GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED))
        assertEquals(89_000L, store.remainingMillis(ACCOUNT))
        now = 190_000
        assertEquals(0L, store.remainingMillis(ACCOUNT))
    }

    @Test fun delayIsOwnerScopedAndRemovedByAccountCleanup() = runBlocking {
        val store = RoomRemoteRequestBackoff(database) { now }
        val other = AccountScope("synthetic-other-backoff-owner")
        assertFalse(database.hasAccountScopedData())
        store.record(ACCOUNT, GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 60_000))
        assertTrue(database.hasAccountScopedData())
        assertEquals(0L, store.remainingMillis(other))
        store.record(other, GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 120_000))
        database.accountRemovalDao().deleteAccount(ACCOUNT.value)
        assertEquals(0L, store.remainingMillis(ACCOUNT))
        assertEquals(120_000L, store.remainingMillis(other))
        database.accountRemovalDao().deleteAccount(other.value)
        assertFalse(database.hasAccountScopedData())
    }

    @Test fun extremeServerDelaySaturatesWithoutOverflowOrImmediateRetry() = runBlocking {
        val store = RoomRemoteRequestBackoff(database) { now }
        now = Long.MAX_VALUE - 2
        store.record(ACCOUNT, GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, database.remoteRequestBackoffDao().get(ACCOUNT.value)!!.nextAttemptAtEpochMillis)
        assertEquals(2L, store.remainingMillis(ACCOUNT))
        now = Long.MAX_VALUE
        assertEquals(0L, store.remainingMillis(ACCOUNT))
        assertFalse(database.remoteRequestBackoffDao().get(ACCOUNT.value).toString().contains(ACCOUNT.value))
    }

    companion object {
        private val ACCOUNT = AccountScope("synthetic-request-backoff-owner")
        private const val DB = "synthetic-remote-request-backoff.db"
    }
}
