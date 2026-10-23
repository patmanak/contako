package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.proton.ProtonContactEncodingStage
import com.patmanak.contako.data.proton.ProtonContactUpdateStage
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncDiagnosticJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val time = 1_792_320_000_000L

    @Test fun timestampsAndMonotonicOffsetsRetainTheFailureBoundaryAndFieldKind() {
        var wall = time
        var tick = 100L
        val journal = SyncDiagnosticBuffer({ wall }, { tick })
        journal.record(SanitizedDiagnosticEvent.PassStarted(SyncScope.INCREMENTAL))
        journal.record(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.OUTBOX_DRAIN))
        tick += 150
        wall -= 10_000 // Wall clock changes do not alter elapsed process time.
        journal.record(SanitizedDiagnosticEvent.ContactUpdateFailure(ProtonContactUpdateStage.entries.first(),
            GatewayFailureCategory.VALIDATION_REJECTED, ProtonContactEncodingStage.entries.first(), ContactValueKind.PHONE))
        val failure = requireNotNull(journal.snapshot(JournalStorage.AVAILABLE).lastFailure)
        assertTrue(failure.encoded.contains(",150,OUTBOX_DRAIN,NONE,update:"))
        assertTrue(failure.encoded.endsWith(".PHONE"))
        journal.record(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.COMPLETE))
        repeat(400) { journal.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.CARD_READ_DONE)) }
        val snapshot = journal.snapshot(JournalStorage.AVAILABLE)
        assertEquals(128, snapshot.events.size)
        assertEquals(failure, snapshot.lastFailure)
        assertFalse(snapshot.events.contains(failure))
        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(report(snapshot)))
        assertTrue(report(snapshot).contains("sync_problem=INTERNAL_FAILURE"))
    }

    @Test fun rootFailureIsNotReplacedByGenericResultAndNextFailingPassReplacesIt() {
        val buffer = SyncDiagnosticBuffer({ time }, { 0 })
        buffer.record(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.REMOTE_CONTACTS))
        buffer.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.CARD_READ_START))
        buffer.record(SanitizedDiagnosticEvent.SyncException(SyncPassExceptionCategory.SQLITE_ROW_TOO_LARGE))
        buffer.record(SanitizedDiagnosticEvent.AndroidResult(SyncPassStage.ANDROID_INGEST, DiagnosticAndroidResult.ACTION_REQUIRED))
        assertTrue(buffer.snapshot(JournalStorage.AVAILABLE).lastFailure!!.encoded.endsWith("failure:SQLITE_ROW_TOO_LARGE"))
        assertTrue(buffer.snapshot(JournalStorage.AVAILABLE).lastFailure!!.encoded.contains(",REMOTE_CONTACTS,CARD_READ_START,"))
        buffer.record(SanitizedDiagnosticEvent.PassStarted(SyncScope.FULL_REPAIR))
        buffer.record(SanitizedDiagnosticEvent.GroupReadFailure(GatewayFailureCategory.MALFORMED_RESPONSE))
        assertTrue(buffer.snapshot(JournalStorage.AVAILABLE).lastFailure!!.encoded.endsWith("groups:MALFORMED_RESPONSE"))
    }

    @Test fun privateFileRoundTripAndStartupMergePreserveRecentEventsAndCanBeCleared() {
        val file = SyncJournalFile(File(temporary.root, "history/journal.txt"))
        val before = SyncDiagnosticBuffer({ time }, { 0 })
        before.record(SanitizedDiagnosticEvent.SyncException(SyncPassExceptionCategory.INVALID_STATE))
        file.write(before.snapshot(JournalStorage.AVAILABLE))
        val after = SyncDiagnosticBuffer({ time + 1_000 }, { 0 })
        after.record(SanitizedDiagnosticEvent.SyncRequest(SyncTrigger.ANDROID_UPLOAD, SyncScope.INCREMENTAL, SyncRequestDisposition.STARTED))
        after.restore(file.read())
        assertEquals(2, after.snapshot(JournalStorage.AVAILABLE).events.size)
        assertNotNull(after.snapshot(JournalStorage.AVAILABLE).lastFailure)
        assertTrue(after.snapshot(JournalStorage.AVAILABLE).events.last().encoded.endsWith("request:ANDROID_UPLOAD.INCREMENTAL.STARTED"))
        file.write(after.snapshot(JournalStorage.AVAILABLE)) // Atomic replacement of an existing file.
        after.clear()
        file.write(after.snapshot(JournalStorage.AVAILABLE))
        assertNull(file.read().second)
        assertEquals("journal:CLEARED", file.read().first.single().encoded.substringAfterLast(','))
    }

    @Test fun corruptedOversizedOrPayloadBearingFilesCannotReachExport() {
        val path = temporary.newFile()
        val file = SyncJournalFile(path)
        path.writeText("contako_sync_journal=1\nlast_failure=none\ncontact@example.test\n")
        assertThrows(IllegalArgumentException::class.java) { file.read() }
        path.writeBytes(ByteArray(SyncJournalFile.MAX_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { file.read() }
        listOf("", "failure:raw-message", "update:UNKNOWN.NONE.NONE.EMAIL", "request:MANUAL.INCREMENTAL.arbitrary",
            "journal:PROCESS_START\ncontact@example.test").forEach { assertFalse(SyncJournalCodes.allowed(it)) }
    }

    @Test fun failedAtomicReplacementKeepsThePreviousCompleteHistory() {
        val path = File(temporary.root, "journal.txt")
        val file = SyncJournalFile(path)
        val buffer = SyncDiagnosticBuffer({ time }, { 0 })
        buffer.record(SanitizedDiagnosticEvent.SyncException(SyncPassExceptionCategory.INVALID_STATE))
        file.write(buffer.snapshot(JournalStorage.AVAILABLE))
        val previous = path.readText()
        assertTrue(File(temporary.root, "journal.txt.tmp").mkdir())
        buffer.clear()
        assertThrows(java.io.IOException::class.java) { file.write(buffer.snapshot(JournalStorage.AVAILABLE)) }
        assertEquals(previous, path.readText())
        assertTrue(report(buffer.snapshot(JournalStorage.UNAVAILABLE)).contains("journal_storage=UNAVAILABLE"))
    }

    @Test fun laterDetailedFailureReplacesGenericAndNonBlockingGroupDenialsDoNotBecomeTheCause() {
        val buffer = SyncDiagnosticBuffer({ time }, { 0 })
        buffer.record(SanitizedDiagnosticEvent.GroupReadFailure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED))
        assertNull(buffer.snapshot(JournalStorage.AVAILABLE).lastFailure)
        buffer.record(SanitizedDiagnosticEvent.AndroidResult(SyncPassStage.ANDROID_INGEST, DiagnosticAndroidResult.ACTION_REQUIRED))
        buffer.record(SanitizedDiagnosticEvent.SyncException(SyncPassExceptionCategory.DATABASE))
        assertTrue(buffer.snapshot(JournalStorage.AVAILABLE).lastFailure!!.encoded.endsWith("failure:DATABASE"))
    }

    @Test fun scannerRejectsInjectedFieldsTimestampsAndUnboundedEvents() {
        val buffer = SyncDiagnosticBuffer({ time }, { 0 })
        buffer.record(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.REMOTE_CONTACTS))
        val snapshot = buffer.snapshot(JournalStorage.AVAILABLE)
        val valid = report(snapshot)
        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(valid))
        listOf(valid.replace("REMOTE_CONTACTS", "contact@example.test"),
            valid.replace("Z,0,", "Z,-1,"), valid.replace("Z,0,", "Z,9223372036854775808,"),
            valid.replace(Regex("T[0-9]{2}:"), "T99:"), valid + "raw_error=anything\n").forEach {
            assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(it))
        }
        val oversized = SyncJournalSnapshot(List(129) { snapshot.events.single() }, null, JournalStorage.AVAILABLE)
        val one = snapshot.events.single().encoded
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(valid.replace("journal_events=$one",
            "journal_events=" + oversized.events.joinToString("|") { it.encoded })))
    }

    @Test fun authenticationAndCryptographicEventsAreExcludedEvenInOrdinaryLogCalls() {
        val buffer = SyncDiagnosticBuffer({ time }, { 0 })
        buffer.record(SanitizedDiagnosticEvent.SignatureCheck(DiagnosticCardType.SIGNED, false, 1, 1, true, true))
        assertTrue(buffer.snapshot(JournalStorage.AVAILABLE).events.isEmpty())
        SanitizedDiagnosticLog.write(SanitizedDiagnosticEvent.SyncRequest(SyncTrigger.MANUAL,
            SyncScope.INCREMENTAL, SyncRequestDisposition.STARTED))
        assertTrue(SyncDiagnosticJournal.snapshot().events.any { it.encoded.endsWith("request:MANUAL.INCREMENTAL.STARTED") })
    }

    private fun report(snapshot: SyncJournalSnapshot) = LocalDiagnosticReportGenerator.generate(
        LocalDiagnosticInput("0.10.1", 36, false, 200, 2, 0, 1, DiagnosticSyncState.BLOCKED, true,
            journal = snapshot, problem = SyncProblem.INTERNAL_FAILURE, androidPendingContactCount = 200),
    ).content
}
