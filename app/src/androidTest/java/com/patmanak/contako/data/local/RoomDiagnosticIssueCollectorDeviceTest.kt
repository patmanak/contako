package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.diagnostics.*
import com.patmanak.contako.domain.model.CanonicalContact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room only; does not create an Android account or access any provider. */
@RunWith(AndroidJUnit4::class)
class RoomDiagnosticIssueCollectorDeviceTest {
    @Test fun persistedServerDelayIsReportedWithoutAnOutboxOrPrivateAccountIdentity() = inDatabase { database ->
        database.remoteRequestBackoffDao().upsert(RemoteRequestBackoffEntity("private-owner", 10_000))
        val snapshot = RoomDiagnosticIssueCollector(database) { 1_000 }.collect(AccountScope("private-owner"))
        assertEquals(listOf("REMOTE_REQUEST.RETRY_WAITING.NONE.RATE_LIMITED.NONE.NONE.small"), snapshot.rows)
        assertTrue(RoomDiagnosticIssueCollector(database) { 10_000 }.collect(AccountScope("private-owner")).rows.isEmpty())
        assertTrue(RoomDiagnosticIssueCollector(database) { 1_000 }.collect(AccountScope("foreign-owner")).rows.isEmpty())
    }

    @Test fun currentSnapshotIsAccountScopedAndExportCannotContainStoredIdentifiersOrRawReasons() = inDatabase { database ->
        database.outboxDao().upsert(intent("synthetic-owner", "private-contact-id"))
        database.outboxDao().upsert(intent("foreign-owner", "foreign-contact-id").copy(errorCategory = "AUTHENTICATION_REQUIRED"))
        val snapshot = RoomDiagnosticIssueCollector(database) { 100 }.collect(AccountScope("synthetic-owner"))
        assertEquals(listOf("OUTBOX_CONTACT.ACTION_REQUIRED.DELETE.TIMEOUT.REMOTE_ACTION_REQUIRED.EIGHT_OR_MORE.small"), snapshot.rows)
        database.outboxDao().upsert(intent("synthetic-owner", "private-contact-id").copy(
            blockedReason = "private@example.test", errorCategory = "password=canary"))
        val safe = RoomDiagnosticIssueCollector(database).collect(AccountScope("synthetic-owner"))
        assertEquals(listOf("OUTBOX_CONTACT.ACTION_REQUIRED.DELETE.NONE.UNKNOWN.EIGHT_OR_MORE.small"), safe.rows)
    }

    @Test fun deferredIgnoredAndTerminalImportsRemainDistinctAndCarryAttemptBands() = inDatabase { database ->
        val base = RemoteHydrationObligationEntity("synthetic-owner", "source-one", "Private synthetic name",
            "TIMEOUT", null, baselineVersion = "baseline")
        database.remoteHydrationObligationDao().upsert(base.copy(isTransient = true, attemptCount = 2, nextAttemptAtEpochMillis = 10_000))
        database.remoteHydrationObligationDao().upsert(base.copy(remoteContactId = "source-two", category = "CRYPTOGRAPHIC_VERIFICATION_FAILED",
            ignoredBaselineVersion = "baseline"))
        database.remoteHydrationObligationDao().upsert(base.copy(remoteContactId = "source-three", category = "MALFORMED_RESPONSE"))
        val snapshot = RoomDiagnosticIssueCollector(database).collect(AccountScope("synthetic-owner"))
        assertTrue(snapshot.rows.any { it.startsWith("REMOTE_IMPORT.RETRY_WAITING.NONE.TIMEOUT.UNKNOWN.ONE_TO_THREE.") })
        assertTrue(snapshot.rows.any { it.startsWith("REMOTE_IMPORT.IGNORED.NONE.CRYPTOGRAPHIC_VERIFICATION_FAILED.") })
        assertTrue(snapshot.rows.any { it.startsWith("REMOTE_IMPORT.ACTION_REQUIRED.NONE.MALFORMED_RESPONSE.") })
        assertFalse(snapshot.rows.joinToString().contains("source-"))
        assertFalse(snapshot.rows.joinToString().contains("Private"))
    }

    @Test fun staleProviderEpochIssuesCannotBecomeCurrentDiagnosticCauses() = inDatabase { database ->
        database.androidProjectionLedgerDao().insertAccount(AndroidProjectionAccountEntity("synthetic-owner", 1, 2))
        database.contactDao().upsert(CanonicalContact("synthetic-owner", "fixture-one", displayName = "Private fixture").toEntity())
        database.androidContactIssueDao().upsert(AndroidContactIssueEntity("synthetic-owner", "fixture-one", "PROJECTION", 1, 0,
            "POST_WRITE_CONTACT_VERIFICATION"))
        assertTrue(RoomDiagnosticIssueCollector(database).collect(AccountScope("synthetic-owner")).rows.isEmpty())
        database.androidContactIssueDao().upsert(AndroidContactIssueEntity("synthetic-owner", "fixture-one", "PROJECTION", 2, 0,
            "POST_WRITE_CONTACT_VERIFICATION"))
        val snapshot = RoomDiagnosticIssueCollector(database).collect(AccountScope("synthetic-owner"))
        assertTrue(snapshot.rows.single().contains("ANDROID_PROJECTION.ACTION_REQUIRED.NONE.NONE.POST_WRITE_CONTACT_VERIFICATION"))
    }

    private fun intent(account: String, id: String) = OutboxMutationEntity(account, "CONTACT", id, "DELETE", 1, 0, 0,
        attemptCount = 8, errorCategory = "TIMEOUT", blockedReason = "REMOTE_ACTION_REQUIRED", remoteIdentity = "private-remote-id",
        idempotencyKey = "private-idempotency", state = "ACTION_REQUIRED")

    private fun inDatabase(block: suspend (ContakoDatabase) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-diagnostic-current-issues.db"
        context.deleteDatabase(name)
        val database = ContakoDatabase.create(context, name)
        try { block(database) } finally { database.close(); context.deleteDatabase(name) }
    }
}
