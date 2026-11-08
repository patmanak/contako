package com.patmanak.contako.data.local

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.diagnostics.*

/** One current account snapshot, with all identifiers removed before returning to report generation. */
internal class RoomDiagnosticIssueCollector(private val database: ContakoDatabase,
    private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun collect(account: AccountScope): DiagnosticCurrentIssues = database.withTransaction {
        val summary = DiagnosticIssueReducer()
        if (RoomRemoteRequestBackoff(database, clock).remainingMillis(account) > 0L) {
            summary.add(DiagnosticIssueScope.REMOTE_REQUEST, DiagnosticIssueState.RETRY_WAITING,
                "RATE_LIMITED", "NONE")
        }
        database.outboxDao().getAll(account.value).forEach { item ->
            val state = DiagnosticIssueState.entries.firstOrNull { it.name == item.state }
                ?: return@forEach
            if (item.errorCategory == null && item.blockedReason == null && state != DiagnosticIssueState.IN_FLIGHT) return@forEach
            summary.add(if (item.aggregateType == "CONTACT") DiagnosticIssueScope.OUTBOX_CONTACT else DiagnosticIssueScope.OUTBOX_GROUP,
                if (state == DiagnosticIssueState.PENDING && item.nextAttemptAtEpochMillis > clock()) DiagnosticIssueState.RETRY_WAITING else state,
                item.errorCategory, item.blockedReason, item.attemptCount, item.remoteOperation())
        }
        database.remoteHydrationObligationDao().getAll(account.value).forEach { item ->
            summary.add(DiagnosticIssueScope.REMOTE_IMPORT, when {
                item.ignoredBaselineVersion != null -> DiagnosticIssueState.IGNORED
                item.isTransient -> DiagnosticIssueState.RETRY_WAITING
                else -> DiagnosticIssueState.ACTION_REQUIRED
            }, item.category, item.hydrationCategory, item.attemptCount)
        }
        database.contactEmailUploadOverlayDao().getAll(account.value).map { it.contactId }.distinct().forEach {
            summary.add(DiagnosticIssueScope.OUTBOX_CONTACT, DiagnosticIssueState.ACTION_REQUIRED,
                "VALIDATION_REJECTED", "INVALID_EMAIL")
        }
        database.androidContactIssueDao().getCurrent(account.value).forEach { item ->
            summary.add(if (item.origin == "INGEST") DiagnosticIssueScope.ANDROID_INGEST else DiagnosticIssueScope.ANDROID_PROJECTION,
                DiagnosticIssueState.ACTION_REQUIRED, null, item.reason)
        }
        summary.snapshot()
    }
}
