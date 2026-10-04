package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.sync.RemoteImportPhase
import com.patmanak.contako.data.sync.SyncPassExceptionCategory
import org.junit.Assert.*
import org.junit.Test

class InvestigationTraceTest {
    @Test fun detailedAndroidAndUploadCausesUseClosedCodesAndDistinctBuildIdentity() {
        InvestigationTrace.clear()
        val events = listOf(
            SanitizedDiagnosticEvent.IngestFailure(com.patmanak.contako.data.sync.AndroidIngestActionRequiredReason.CONTACT_EXISTING_PLAN),
            SanitizedDiagnosticEvent.IngestReplan(com.patmanak.contako.data.sync.AndroidIngestReplanReason.CONTACT_PAGE_UNSTABLE),
            SanitizedDiagnosticEvent.ProjectionRepair(com.patmanak.contako.data.sync.AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_NAME),
            SanitizedDiagnosticEvent.ProjectionComponent(com.patmanak.contako.data.android.mapping.AndroidRowKind.STRUCTURED_NAME,
                com.patmanak.contako.data.android.mapping.AndroidComponent.GIVEN_NAME,
                com.patmanak.contako.data.sync.AndroidComponentDifference.entries.first()),
            SanitizedDiagnosticEvent.MutationFailure(com.patmanak.contako.data.sync.MutationActionRequiredSource.UPLOAD_FAILURE,
                com.patmanak.contako.data.sync.RemoteMutationOperation.entries.first(),
                com.patmanak.contako.data.gateway.GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED, null),
        )
        events.forEach(InvestigationTrace::record)
        val snapshot = InvestigationTrace.snapshot()
        assertEquals(events.size, snapshot.size)
        val exported = report(snapshot)
        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(exported))
        assertTrue(exported.contains("app_version=0.9.0-investigation14"))
        assertTrue(exported.contains("projection_repair:POST_WRITE_CONTACT_VALUE_NAME"))
        assertTrue(exported.contains("PERMISSION_OR_PLAN_DENIED.NONE"))
        repeat(300) { InvestigationTrace.record(events.last()) }
        assertEquals(128, InvestigationTrace.snapshot().size)
        assertTrue(report(InvestigationTrace.snapshot()).length < 32 * 1024)
        listOf("ingest_failure:raw-message", "projection_component:STRUCTURED_NAME:GIVEN_NAME:raw-message",
            "mutation:UPLOAD_FAILURE:raw-message:NONE:NONE", "binding:CURRENT:raw-message").forEach {
            assertFalse(InvestigationTrace.isAllowedCode(it))
        }
        InvestigationTrace.clear()
    }
    @Test fun ringRotatesAndReportHasOnlyBoundedClosedCodes() {
        InvestigationTrace.clear()
        repeat(300) { InvestigationTrace.record(SanitizedDiagnosticEvent.ImportPhase(RemoteImportPhase.CARD_READ_DONE)) }
        InvestigationTrace.record(SanitizedDiagnosticEvent.SyncException(SyncPassExceptionCategory.SQLITE_ROW_TOO_LARGE))
        val snapshot = InvestigationTrace.snapshot()
        assertEquals(128, snapshot.size)
        assertEquals("failure:SQLITE_ROW_TOO_LARGE", snapshot.last().code)
        val report = report(snapshot)
        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(report))
        assertTrue(report.contains("import_mode=batch_25_parallel_10"))
        assertTrue(report.length < 32 * 1024)
        assertFalse(report.contains("300"))
        InvestigationTrace.clear()
        assertTrue(InvestigationTrace.snapshot().isEmpty())
        assertEquals(128, snapshot.size) // Caller receives a detached snapshot.
    }

    @Test fun rejectsFreeTextAndNeverExportsOtherEventTypes() {
        InvestigationTrace.clear()
        InvestigationTrace.record(SanitizedDiagnosticEvent.ContactUpdateFailure(
            com.patmanak.contako.data.proton.ProtonContactUpdateStage.entries.first(),
            com.patmanak.contako.data.gateway.GatewayFailureCategory.UNKNOWN, null, null,
        ))
        assertTrue(InvestigationTrace.snapshot().isEmpty())
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(report(emptyList()).replace(
            "investigation_events=none", "investigation_events=failure:raw-message")))
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(report(emptyList()).replace(
            "investigation_events=none", "investigation_events=stage:REMOTE_CONTACTS|contact@example.test")))
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(report(emptyList()).replace(
            "investigation_events=none", "investigation_events=" + List(129) { "import:CARD_READ_DONE" }.joinToString("|"))))
    }

    private fun report(events: List<InvestigationTraceEntry>) = LocalDiagnosticReportGenerator.generate(
        LocalDiagnosticInput("0.9.0-investigation14", 36, false, 25, 0, 0, 1,
            DiagnosticSyncState.FAILED, true, true, events),
    ).content
}
