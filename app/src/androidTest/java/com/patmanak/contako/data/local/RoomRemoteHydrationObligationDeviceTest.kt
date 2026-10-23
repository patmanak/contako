package com.patmanak.contako.data.local

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room database only; never creates an Android account or reads the provider. */
@RunWith(AndroidJUnit4::class)
class RoomRemoteHydrationObligationDeviceTest {
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
