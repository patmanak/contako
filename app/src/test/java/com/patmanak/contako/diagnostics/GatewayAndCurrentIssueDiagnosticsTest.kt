package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.domain.sync.SyncScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GatewayAndCurrentIssueDiagnosticsTest {
    @Test fun nonterminalPreparationAndUploadFailuresAreObservedWithoutChangingDurableRetry() = runTest {
        for (preparationFails in listOf(true, false)) for (observerThrows in listOf(true, false)) {
            val command = DurableMutationCommand("synthetic-owner", "CONTACT", "synthetic-contact", 1,
                RemoteMutationOperation.DELETE, 0, 0, false)
            var recorded: RetryDecision? = null
            val store = object : MutationExecutionStore {
                override suspend fun recoverInterrupted(accountId: String, nowEpochMillis: Long) = 0
                override suspend fun eligible(accountId: String, nowEpochMillis: Long, limit: Int) = listOf(command)
                override suspend fun claim(command: DurableMutationCommand, nowEpochMillis: Long) = true
                override suspend fun recordFailure(command: DurableMutationCommand, category: GatewayFailureCategory, decision: RetryDecision): Boolean {
                    assertEquals(GatewayFailureCategory.TIMEOUT, category)
                    recorded = decision
                    return true
                }
                override suspend fun acknowledgeAndFinish(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement) = false
                override suspend fun supersedeAfterRemoteWinner(command: DurableMutationCommand) = false
                override suspend fun continueAfterPartialProgress(command: DurableMutationCommand, acknowledgement: RemoteMutationAcknowledgement,
                    nowEpochMillis: Long) = false
            }
            val boundaries = mutableListOf<GatewayDiagnosticBoundary>()
            var terminal = 0
            val result = DurableMutationOrchestrator(store,
                MutationPreparationGateway { if (preparationFails) GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
                    else GatewayOutcome.Success(MutationPreparation.UploadAllowed) },
                MutationUploadGateway { GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT) },
                actionRequiredObserver = MutationActionRequiredObserver { terminal++ },
                gatewayFailureObserver = GatewayFailureObserver { boundary, failure, operation ->
                    boundaries += boundary
                    assertEquals(GatewayFailureCategory.TIMEOUT, failure.category)
                    assertEquals(RemoteMutationOperation.DELETE, operation)
                    if (observerThrows) error("Synthetic observer unavailable")
                },
            ).drain("synthetic-owner", 1_000)
            assertEquals(listOf(if (preparationFails) GatewayDiagnosticBoundary.MUTATION_PREPARATION else GatewayDiagnosticBoundary.MUTATION_UPLOAD), boundaries)
            assertEquals(1, result.retryWaiting)
            assertEquals(0, result.actionRequired)
            assertEquals(0, terminal)
            assertTrue(recorded is RetryDecision.RetryAt)
        }
    }

    @Test fun transientReadsRetainCategoryStageAndBoundedProtocolFacts() {
        val journal = SyncDiagnosticBuffer({ 1_792_320_000_000L }, { 0L })
        journal.record(SanitizedDiagnosticEvent.PassStarted(SyncScope.INCREMENTAL))
        journal.record(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.REMOTE_CONTACTS))
        journal.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.CARD_READ_DONE))
        journal.record(gatewayDiagnosticFailure(GatewayDiagnosticBoundary.CARD_READ,
            GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, retryAfterMillis = 120_000,
                protonResponseCode = 2028, httpStatusCode = 429)))
        val failure = requireNotNull(journal.snapshot(JournalStorage.AVAILABLE).lastFailure)
        assertTrue(failure.encoded.contains(",REMOTE_CONTACTS,CARD_READ_DONE,gateway:CARD_READ.RATE_LIMITED."))
        assertTrue(failure.encoded.endsWith(".429.2028.MINUTES.NONE"))
        assertTrue(SyncJournalEntry.valid(failure.encoded))
        assertFalse(SyncJournalCodes.allowed("gateway:CARD_READ.RATE_LIMITED.NONE.NONE.NONE.429.2028.120000.NONE"))
        assertFalse(SyncJournalCodes.allowed("gateway:CARD_READ.RATE_LIMITED.NONE.NONE.NONE.999.2028.MINUTES.NONE"))
        assertFalse(SyncJournalCodes.allowed("gateway:CARD_READ.RATE_LIMITED.NONE.NONE.NONE.429.20280.MINUTES.NONE"))
    }

    @Test fun largeImportsCannotEraseRequestsAndMultipleFailureBoundaries() {
        val buffer = SyncDiagnosticBuffer({ 1_792_320_000_000L }, { 0L })
        buffer.record(SanitizedDiagnosticEvent.AndroidCallback(DiagnosticAndroidCallback.RECEIVED))
        buffer.record(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.REMOTE_CONTACTS))
        buffer.record(gatewayDiagnosticFailure(GatewayDiagnosticBoundary.EVENT_READ,
            GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)))
        buffer.record(gatewayDiagnosticFailure(GatewayDiagnosticBoundary.CARD_READ,
            GatewayOutcome.Failure(GatewayFailureCategory.REMOTE_SERVICE_FAILURE)))
        repeat(2_000) {
            buffer.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.CARD_READ_START))
            buffer.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.CARD_READ_DONE))
            buffer.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.LOCAL_COMMIT_START))
            buffer.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.LOCAL_COMMIT_DONE))
        }
        val snapshot = buffer.snapshot(JournalStorage.AVAILABLE)
        assertTrue(snapshot.events.size <= SyncDiagnosticBuffer.MAX_EVENTS)
        assertEquals(16, snapshot.events.count { SyncJournalCodes.progress(it.encoded.substringAfterLast(',')) })
        assertTrue(snapshot.events.any { it.encoded.endsWith("android_callback:RECEIVED") })
        assertTrue(snapshot.events.any { it.encoded.contains("gateway:EVENT_READ.TIMEOUT") })
        assertTrue(snapshot.events.any { it.encoded.contains("gateway:CARD_READ.REMOTE_SERVICE_FAILURE") })
        assertTrue(snapshot.lastFailure!!.encoded.contains("gateway:CARD_READ.REMOTE_SERVICE_FAILURE"))
    }

    @Test fun currentCausesAreIndependentFromHistoricalFailuresAndNeverAcceptIdentifiers() {
        val reducer = DiagnosticIssueReducer()
        repeat(3) { reducer.add(DiagnosticIssueScope.OUTBOX_CONTACT, DiagnosticIssueState.ACTION_REQUIRED,
            "TIMEOUT", "REMOTE_ACTION_REQUIRED", 8, RemoteMutationOperation.DELETE) }
        reducer.add(DiagnosticIssueScope.REMOTE_IMPORT, DiagnosticIssueState.RETRY_WAITING, "RATE_LIMITED", null, 2)
        reducer.add(DiagnosticIssueScope.ANDROID_PROJECTION, DiagnosticIssueState.ACTION_REQUIRED,
            "private@example.test", "password=private-value", 1)
        val snapshot = reducer.snapshot()
        assertTrue(snapshot.rows.contains("OUTBOX_CONTACT.ACTION_REQUIRED.DELETE.TIMEOUT.REMOTE_ACTION_REQUIRED.EIGHT_OR_MORE.small"))
        assertTrue(snapshot.rows.any { it.startsWith("REMOTE_IMPORT.RETRY_WAITING.NONE.RATE_LIMITED.") })
        assertTrue(snapshot.rows.any { it.startsWith("ANDROID_PROJECTION.ACTION_REQUIRED.NONE.NONE.UNKNOWN.") })
        assertFalse(snapshot.rows.joinToString().contains("private"))
        assertFalse(snapshot.rows.joinToString().contains("password"))
        val report = report(snapshot)
        assertTrue(report.startsWith("contako_diagnostic_schema=4\n"))
        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(report))
        assertTrue(report.contains("device_family=OTHER"))
        assertFalse(report.contains("UnrecognizedVendorWithPrivateIdentifier"))
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(report.replace("EIGHT_OR_MORE.small", "EIGHT_OR_MORE.3")))
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(report.replace("current_issue_read=AVAILABLE", "current_issue_read=UNAVAILABLE")))
    }

    @Test fun unavailableCurrentSnapshotIsNotReportedAsAHealthyEmptyQueue() {
        val report = report(DiagnosticCurrentIssues.unavailable())
        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(report))
        assertTrue(report.contains("current_issue_read=UNAVAILABLE"))
        assertTrue(report.contains("current_issues=none"))
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(report + "current_issue_read=AVAILABLE\n"))
    }

    @Test fun closedIssueReducerCapsDistinctGroupsAndCountsWithoutPayload() {
        val reducer = DiagnosticIssueReducer()
        for (scope in DiagnosticIssueScope.entries) for (category in GatewayFailureCategory.entries) {
            repeat(2) { reducer.add(scope, DiagnosticIssueState.ACTION_REQUIRED, category.name, "UNKNOWN", 4) }
        }
        val snapshot = reducer.snapshot()
        assertEquals(DiagnosticCurrentIssues.MAX_ROWS, snapshot.rows.size)
        assertTrue(snapshot.truncated)
        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(report(snapshot)))
    }

    @Test fun everyAllowedCurrentReasonAndGatewayCategoryPassTheIndependentExportScanner() {
        DiagnosticCurrentIssues.reasons.forEach { reason ->
            val reducer = DiagnosticIssueReducer()
            reducer.add(DiagnosticIssueScope.ANDROID_PROJECTION, DiagnosticIssueState.ACTION_REQUIRED, null, reason)
            assertTrue(reason, LocalDiagnosticSafetyScanner.isSafeReport(report(reducer.snapshot())))
        }
        GatewayFailureCategory.entries.forEach { category ->
            val buffer = SyncDiagnosticBuffer({ 1_792_320_000_000L }, { 0L })
            buffer.record(gatewayDiagnosticFailure(GatewayDiagnosticBoundary.SESSION_RESTORE, GatewayOutcome.Failure(category)))
            val entry = buffer.snapshot(JournalStorage.AVAILABLE).events.single()
            assertTrue(category.name, SyncJournalEntry.valid(entry.encoded))
            assertFalse(category.name, LocalDiagnosticSafetyScanner.containsForbiddenContent(entry.encoded))
        }
    }

    private fun report(issues: DiagnosticCurrentIssues): String = LocalDiagnosticReportGenerator.generate(LocalDiagnosticInput(
        appVersion = "0.20.0", apiLevel = 36, debugBuild = false, contactCount = 212,
        groupCount = 4, pendingMutationCount = 0, actionRequiredCount = 3,
        syncState = DiagnosticSyncState.BLOCKED, contactsPermissionGranted = true,
        journal = SyncDiagnosticBuffer({ 1_792_320_000_000L }, { 0L }).snapshot(JournalStorage.AVAILABLE),
        currentIssues = issues, deviceFamily = DiagnosticDeviceFamily.fromManufacturer("UnrecognizedVendorWithPrivateIdentifier"),
    )).content
}
