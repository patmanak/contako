package com.patmanak.contako.diagnostics

import com.patmanak.contako.domain.sync.SyncDashboardState

data class LocalDiagnosticInput(
    val appVersion: String,
    val apiLevel: Int,
    val debugBuild: Boolean,
    val contactCount: Int,
    val groupCount: Int,
    val pendingMutationCount: Int,
    val actionRequiredCount: Int,
    val syncState: DiagnosticSyncState,
    val contactsPermissionGranted: Boolean,
)

enum class DiagnosticSyncState {
    CURRENT,
    PENDING,
    BLOCKED,
    FAILED,
    OFFLINE,
    AUTHENTICATION_REQUIRED,
    ANDROID_DEGRADED,
    ANDROID_PARTIAL,
    ;

    companion object {
        fun fromDashboard(state: SyncDashboardState): DiagnosticSyncState = when (state) {
            SyncDashboardState.CURRENT -> CURRENT
            SyncDashboardState.PENDING -> PENDING
            SyncDashboardState.BLOCKED -> BLOCKED
            SyncDashboardState.FAILED -> FAILED
            SyncDashboardState.OFFLINE -> OFFLINE
            SyncDashboardState.AUTHENTICATION_REQUIRED -> AUTHENTICATION_REQUIRED
            SyncDashboardState.ANDROID_DEGRADED -> ANDROID_DEGRADED
            SyncDashboardState.ANDROID_PARTIAL -> ANDROID_PARTIAL
        }
    }
}

@JvmInline
value class LocalDiagnosticReport internal constructor(val content: String)

/** Builds a fixed-schema report without accepting payload, identifier, path, or error text. */
object LocalDiagnosticReportGenerator {
    fun generate(input: LocalDiagnosticInput): LocalDiagnosticReport {
        require(input.apiLevel >= 1)
        require(
            input.contactCount >= 0 && input.groupCount >= 0 &&
                input.pendingMutationCount >= 0 && input.actionRequiredCount >= 0,
        )
        val report = buildString {
            appendLine("contako_diagnostic_schema=1")
            appendLine("app_version=${input.appVersion}")
            appendLine("platform=android")
            appendLine("api_level_band=${apiBand(input.apiLevel)}")
            appendLine("build_type=${if (input.debugBuild) "debug" else "release"}")
            appendLine("telemetry=absent")
            appendLine("contacts=${countBand(input.contactCount)}")
            appendLine("groups=${countBand(input.groupCount)}")
            appendLine("pending_mutations=${countBand(input.pendingMutationCount)}")
            appendLine("actions_required=${countBand(input.actionRequiredCount)}")
            appendLine("sync_state=${input.syncState.name.lowercase()}")
            appendLine("contacts_permission=${if (input.contactsPermissionGranted) "granted" else "not_granted"}")
        }
        check(LocalDiagnosticSafetyScanner.isSafeReport(report))
        return LocalDiagnosticReport(report)
    }

    private fun countBand(count: Int): String = when {
        count == 0 -> "none"
        count < 10 -> "small"
        count < 100 -> "medium"
        count < 1_000 -> "large"
        else -> "very_large"
    }

    private fun apiBand(apiLevel: Int): String = when {
        apiLevel < 31 -> "below_supported"
        apiLevel < 35 -> "31_34"
        apiLevel == 35 -> "35"
        else -> "36_or_later"
    }
}

object LocalDiagnosticSafetyScanner {
    private val countBand = Regex("none|small|medium|large|very_large")
    private val schema = linkedMapOf(
        "contako_diagnostic_schema" to Regex("1"),
        "app_version" to Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-(?:debug|preview|diagnostic|sync-diagnostic|benchmark))?"),
        "platform" to Regex("android"),
        "api_level_band" to Regex("below_supported|31_34|35|36_or_later"),
        "build_type" to Regex("debug|release"),
        "telemetry" to Regex("absent"),
        "contacts" to countBand,
        "groups" to countBand,
        "pending_mutations" to countBand,
        "actions_required" to countBand,
        "sync_state" to Regex("current|pending|blocked|failed|offline|authentication_required|android_degraded|android_partial"),
        "contacts_permission" to Regex("granted|not_granted"),
    )

    private val forbidden = listOf(
        Regex("(?i)password|passwd|passphrase|recovery[ _-]?code|one[ _-]?time|otp"),
        Regex("(?i)(?:access|refresh|session|auth)[ _-]?token|authorization\\s*[:=]|bearer\\s+"),
        Regex("(?i)-----BEGIN(?: [A-Z]+)? PRIVATE KEY-----|private[ _-]?key"),
        Regex("(?i)BEGIN:VCARD|END:VCARD|raw[ _-]?(?:error|response|payload)|stacktrace|exception:"),
        Regex("(?i)\\b[A-F0-9]{8}-[A-F0-9]{4}-[1-5][A-F0-9]{3}-[89AB][A-F0-9]{3}-[A-F0-9]{12}\\b"),
        Regex("(?i)\\b[A-Z]:\\\\|/(?:data|storage|home|users?)/"),
        Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"),
        Regex("(?i)(?:phone|address|name|note|contact)[ _-]?(?:value)?\\s*[:=]"),
    )

    fun isSafeReport(candidate: String): Boolean {
        if (forbidden.any { it.containsMatchIn(candidate) }) return false
        val lines = candidate.lineSequence().filter(String::isNotEmpty).toList()
        if (lines.size != schema.size) return false
        return schema.entries.zip(lines).all { (entry, line) ->
            val separator = line.indexOf('=')
            separator > 0 && line.substring(0, separator) == entry.key &&
                entry.value.matches(line.substring(separator + 1))
        }
    }

    fun containsForbiddenContent(candidate: String): Boolean = forbidden.any { it.containsMatchIn(candidate) }
}
