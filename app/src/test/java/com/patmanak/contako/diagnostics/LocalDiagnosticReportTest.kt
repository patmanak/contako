package com.patmanak.contako.diagnostics

import com.patmanak.contako.domain.sync.SyncDashboardState

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticReportTest {
    @Test
    fun everyDashboardStateCanGenerateASafeReportIncludingAndroidFailures() {
        SyncDashboardState.entries.forEach { state ->
            val report = LocalDiagnosticReportGenerator.generate(
                LocalDiagnosticInput(
                    appVersion = "0.8.0-preview",
                    apiLevel = 35,
                    debugBuild = false,
                    contactCount = 418,
                    groupCount = 20,
                    pendingMutationCount = 0,
                    actionRequiredCount = 126,
                    syncState = DiagnosticSyncState.fromDashboard(state),
                    contactsPermissionGranted = state != SyncDashboardState.ANDROID_DEGRADED,
                ),
            ).content
            assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(report))
            assertTrue(report.contains("sync_state=${state.name.lowercase()}\n"))
            assertFalse(report.contains("418"))
            assertFalse(report.contains("126"))
        }
    }

    @Test
    fun reportUsesOnlyAllowlistedAggregateBands() {
        val report = LocalDiagnosticReportGenerator.generate(
            LocalDiagnosticInput(
                appVersion = "0.8.0-debug",
                apiLevel = 35,
                debugBuild = true,
                contactCount = 1,
                groupCount = 9,
                pendingMutationCount = 10,
                actionRequiredCount = 1_337,
                syncState = DiagnosticSyncState.FAILED,
                contactsPermissionGranted = false,
            ),
        ).content

        assertTrue(LocalDiagnosticSafetyScanner.isSafeReport(report))
        assertTrue(report.contains("contacts=small"))
        assertTrue(report.contains("groups=small"))
        assertTrue(report.contains("pending_mutations=medium"))
        assertTrue(report.contains("actions_required=very_large"))
        listOf("contacts=1", "groups=9", "=10", "1337", "error", "id=").forEach {
            assertFalse("Report disclosed forbidden exact/raw material: $it", report.contains(it))
        }
    }

    @Test
    fun schemaRejectsUnknownMissingReorderedAndFreeFormFields() {
        val valid = validReport()
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(valid + "contact_id=anything\n"))
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(valid.replace("platform=android\n", "")))
        assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(valid.replace("platform=android", "platform=ios")))
        assertFalse(
            LocalDiagnosticSafetyScanner.isSafeReport(
                valid.replace("contacts=small\ngroups=small", "groups=small\ncontacts=small"),
            ),
        )
    }

    @Test
    fun scannerDetectsEveryT01AndT16CanaryClass() {
        val canaries = listOf(
            "password=synthetic-secret",
            "otp: 123456",
            "recovery-code=fake-recovery",
            "access_token=fake-token-material",
            "derived passphrase: synthetic",
            "-----BEGIN PRIVATE KEY-----",
            "BEGIN:VCARD\\nFN:Synthetic Contact\\nEND:VCARD",
            "contact@example.test",
            "phone=+15550123456",
            "address=Synthetic Street",
            "note=Synthetic contact value",
            "contact_id=123e4567-e89b-12d3-a456-426614174000",
            "raw_error=synthetic server failure",
            "C:\\Users\\Synthetic\\private.txt",
            "/data/user/0/synthetic/file",
            "Authorization: Bearer synthetic-token",
        )
        canaries.forEach { canary ->
            assertTrue("Scanner missed a seeded canary class", LocalDiagnosticSafetyScanner.containsForbiddenContent(canary))
            assertFalse(LocalDiagnosticSafetyScanner.isSafeReport(validReport() + canary))
        }
    }

    @Test
    fun equalInputsAreDeterministicAndContainNoTimestamp() {
        val first = validReport()
        val second = validReport()
        assertTrue(first == second)
        assertFalse(first.contains("timestamp"))
        assertFalse(first.contains("generated_at"))
    }

    private fun validReport() = LocalDiagnosticReportGenerator.generate(
        LocalDiagnosticInput(
            appVersion = "0.8.0",
            apiLevel = 35,
            debugBuild = false,
            contactCount = 2,
            groupCount = 3,
            pendingMutationCount = 0,
            actionRequiredCount = 0,
            syncState = DiagnosticSyncState.CURRENT,
            contactsPermissionGranted = true,
        ),
    ).content
}
