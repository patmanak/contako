package com.patmanak.contako.data.local

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.sync.RemoteHydrationReadDisposition

/** Isolated Room database only; never creates an Android account or reads the provider. */
@RunWith(AndroidJUnit4::class)
class RoomRemoteHydrationObligationDeviceTest {
    @Test fun retryDeadlineAttemptAndRateLimitGuidanceSurviveReopenAndCannotBeBypassed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-hydration-backoff.db"
        context.deleteDatabase(name)
        var database = ContakoDatabase.create(context, name)
        var now = 100_000L
        val account = AccountScope("synthetic-backoff")
        val source = baseline("source")
        try {
            var store = RoomRemoteHydrationObligationStore(database) { now }
            val limited = GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 60_000)
            store.defer(account, source, limited)
            store.postpone(account, baseline("unread"), limited)
            val saved = requireNotNull(database.remoteHydrationObligationDao().get(account.value, source.id.value))
            assertEquals(1, saved.attemptCount)
            assertEquals(160_000L, saved.nextAttemptAtEpochMillis)
            assertEquals(60_000L, saved.retryAfterMillis)
            assertTrue(saved.isTransient)
            assertEquals(0, database.remoteHydrationObligationDao().get(account.value, "unread")!!.attemptCount)
            database.close()
            database = ContakoDatabase.create(context, name)
            store = RoomRemoteHydrationObligationStore(database) { now }
            now = 159_999L
            assertEquals(RemoteHydrationReadDisposition.RETRY_WAITING, store.readDisposition(account, source))
            assertEquals(RemoteHydrationReadDisposition.RETRY_WAITING,
                store.readDisposition(account, source, sourceChanged = true))
            assertFalse(store.resume(account, source.id, saved.generation))
            assertEquals(1, database.remoteHydrationObligationDao().get(account.value, source.id.value)!!.attemptCount)
            now = 160_000L
            assertEquals(RemoteHydrationReadDisposition.READ, store.readDisposition(account, source))
            store.defer(account, source, GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT))
            val retried = database.remoteHydrationObligationDao().get(account.value, source.id.value)!!
            assertEquals(2, retried.attemptCount)
            assertEquals(170_000L, retried.nextAttemptAtEpochMillis)
            store.resolved(account, setOf(source.id))
            assertNull(database.remoteHydrationObligationDao().get(account.value, source.id.value))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun ignoredUnverifiedCardRequiresExactOwnerGenerationAndReopensForPrivateOnlyChange() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-hydration-ignore.db"
        context.deleteDatabase(name)
        val database = ContakoDatabase.create(context, name)
        val account = AccountScope("synthetic-ignore")
        val source = baseline("unverified")
        try {
            val store = RoomRemoteHydrationObligationStore(database) { 100_000L }
            store.defer(account, source, GatewayOutcome.Failure(GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED))
            val saved = database.remoteHydrationObligationDao().get(account.value, source.id.value)!!
            assertEquals(RemoteHydrationReadDisposition.ACTION_REQUIRED, store.readDisposition(account, source))
            assertFalse(store.ignore(AccountScope("synthetic-foreign"), source.id, saved.generation))
            assertFalse(store.ignore(account, source.id, "stale-generation"))
            assertTrue(store.ignore(account, source.id, saved.generation))
            val ignored = database.remoteHydrationObligationDao().get(account.value, source.id.value)!!
            assertNotEquals(saved.generation, ignored.generation)
            assertEquals(RemoteHydrationReadDisposition.IGNORED, store.readDisposition(account, source))
            assertEquals(RemoteHydrationReadDisposition.READ,
                store.readDisposition(account, source, sourceChanged = true))
            val changed = database.remoteHydrationObligationDao().get(account.value, source.id.value)!!
            assertNotEquals(ignored.generation, changed.generation)
            assertNull(changed.ignoredBaselineVersion)
            assertFalse(store.ignore(account, source.id, ignored.generation))
            assertFalse(store.resume(account, source.id, saved.generation))
            assertTrue(store.resume(account, source.id, changed.generation))
            assertEquals(RemoteHydrationReadDisposition.READ, store.readDisposition(account, source))
            assertEquals(1, database.remoteHydrationObligationDao().get(account.value, source.id.value)!!.attemptCount)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    private fun baseline(id: String) = ContactInventoryBaseline(RemoteContactId(id), "Synthetic", RemoteVersion("public-v1"),
        null, null, emptyList(), emptyList())

    @Test fun obligationsSurviveReopenAndFailedTransactionsAndArePurgedOnlyForTheirOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-hydration-obligations.db"
        context.deleteDatabase(name)
        var database = ContakoDatabase.create(context, name)
        val own = RemoteHydrationObligationEntity("synthetic-a", "remote-a", "Synthetic contact",
            "MALFORMED_RESPONSE", null)
        val other = own.copy(accountId = "synthetic-b", remoteContactId = "remote-b")
        try {
            assertFalse(database.hasAccountScopedData())
            database.remoteHydrationObligationDao().upsert(own)
            database.remoteHydrationObligationDao().upsert(other)
            assertTrue(database.hasAccountScopedData())
            try {
                database.withTransaction {
                    database.remoteHydrationObligationDao().delete(own.accountId, setOf(own.remoteContactId))
                    error("SYNTHETIC_TRANSACTION_FAILURE")
                }
                fail("The synthetic transaction must fail")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf(own), database.remoteHydrationObligationDao().getAll(own.accountId))
            database.close()
            database = ContakoDatabase.create(context, name)
            assertEquals(listOf(own), database.remoteHydrationObligationDao().getAll(own.accountId))
            database.accountRemovalDao().deleteAccount(own.accountId)
            assertTrue(database.remoteHydrationObligationDao().getAll(own.accountId).isEmpty())
            assertEquals(listOf(other), database.remoteHydrationObligationDao().getAll(other.accountId))
            database.accountRemovalDao().deleteAccount(other.accountId)
            assertFalse(database.hasAccountScopedData())
            assertFalse(own.toString().contains(own.displayName!!))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
