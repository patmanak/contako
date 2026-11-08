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
    val importInvestigation: Boolean = false,
    val investigationEvents: List<InvestigationTraceEntry> = emptyList(),
    val journal: SyncJournalSnapshot? = null,
    val problem: com.patmanak.contako.domain.sync.SyncProblem? = null,
    val androidPendingContactCount: Int = 0,
    val currentIssues: DiagnosticCurrentIssues? = null,
    val deviceFamily: DiagnosticDeviceFamily = DiagnosticDeviceFamily.UNKNOWN,
)

enum class DiagnosticDeviceFamily {
    SAMSUNG, GOOGLE, ONEPLUS, LENOVO, OTHER, UNKNOWN;
    companion object {
        fun fromManufacturer(value: String): DiagnosticDeviceFamily = when (value.trim().lowercase(java.util.Locale.ROOT)) {
            "samsung" -> SAMSUNG
            "google" -> GOOGLE
            "oneplus" -> ONEPLUS
            "lenovo" -> LENOVO
            "" -> UNKNOWN
            else -> OTHER
        }
    }
}

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
                input.pendingMutationCount >= 0 && input.actionRequiredCount >= 0 && input.androidPendingContactCount >= 0,
        )
        val report = buildString {
            appendLine("contako_diagnostic_schema=${if (input.journal != null && input.currentIssues != null) 4 else if (input.journal != null) 3 else if (input.importInvestigation) 2 else 1}")
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
            if (input.journal != null) {
                appendLine("sync_problem=${input.problem?.name ?: "NONE"}")
                appendLine("android_copy_pending=${countBand(input.androidPendingContactCount)}")
                appendLine("journal_storage=${input.journal.storage.name}")
                appendLine("journal_clock=utc_and_process_elapsed_ms")
                appendLine("journal_last_failure=${input.journal.lastFailure?.encoded ?: "none"}")
                appendLine("journal_events=" + input.journal.events.takeLast(SyncDiagnosticBuffer.MAX_EVENTS)
                    .joinToString("|") { it.encoded }.ifEmpty { "none" })
                input.currentIssues?.let { issues ->
                    appendLine("device_family=${input.deviceFamily.name}")
                    appendLine("current_issue_read=${issues.read.name}")
                    appendLine("current_issues=${issues.rows.joinToString("|").ifEmpty { "none" }}")
                    appendLine("current_issue_truncated=${if (issues.truncated) "yes" else "no"}")
                    val failures = input.journal.events.map { it.encoded.substringAfterLast(',') }
                        .filter(SyncJournalCodes::failure).groupingBy { it }.eachCount().toSortedMap()
                    appendLine("journal_retained_failure_counts=" + failures.map { (code, count) ->
                        "$code~${DiagnosticCurrentIssues.countBand(count)}"
                    }.joinToString("|").ifEmpty { "none" })
                }
            } else if (input.importInvestigation) {
                appendLine("import_mode=batch_25_parallel_10")
                appendLine("investigation_events=" + input.investigationEvents.takeLast(InvestigationTrace.MAX_EVENTS)
                    .joinToString("|") { it.code }.ifEmpty { "none" })
            }
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
        "contako_diagnostic_schema" to Regex("1|2|3|4"),
        "app_version" to Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-RC[1-9][0-9]*)?(?:-(?:debug|preview|diagnostic|sync-diagnostic|benchmark|investigation[0-9]*))?"),
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
        if (candidate.length > 96 * 1024) return false
        if (forbidden.any { it.containsMatchIn(candidate) }) return false
        val lines = candidate.lineSequence().filter(String::isNotEmpty).toList()
        val investigation = lines.firstOrNull() == "contako_diagnostic_schema=2"
        val current = lines.firstOrNull() == "contako_diagnostic_schema=4"
        val journal = current || lines.firstOrNull() == "contako_diagnostic_schema=3"
        if (lines.size != schema.size + if (current) 11 else if (journal) 6 else if (investigation) 2 else 0) return false
        if (journal) {
            val metadata = lines.drop(schema.size)
            fun value(index: Int, key: String): String? = metadata[index].takeIf { it.startsWith("$key=") }
                ?.removePrefix("$key=")
            val problem = value(0, "sync_problem") ?: return false
            if (problem != "NONE" && com.patmanak.contako.domain.sync.SyncProblem.entries.none { it.name == problem }) return false
            if (!countBand.matches(value(1, "android_copy_pending") ?: return false)) return false
            if (JournalStorage.entries.none { it.name == value(2, "journal_storage") }) return false
            if (metadata[3] != "journal_clock=utc_and_process_elapsed_ms") return false
            val failure = value(4, "journal_last_failure") ?: return false
            if (failure != "none" && !SyncJournalEntry.valid(failure)) return false
            val events = value(5, "journal_events") ?: return false
            if (events != "none") {
                val entries = events.split('|')
                if (entries.size > SyncDiagnosticBuffer.MAX_EVENTS || entries.any { !SyncJournalEntry.valid(it) }) return false
            }
            if (current) {
                if (DiagnosticDeviceFamily.entries.none { it.name == value(6, "device_family") }) return false
                val read = value(7, "current_issue_read") ?: return false
                if (DiagnosticIssueRead.entries.none { it.name == read }) return false
                val issues = value(8, "current_issues") ?: return false
                if (issues != "none") {
                    val rows = issues.split('|')
                    if (rows.size > DiagnosticCurrentIssues.MAX_ROWS || rows.any { !DiagnosticCurrentIssues.validRow(it) }) return false
                    if (read != DiagnosticIssueRead.AVAILABLE.name) return false
                }
                if (value(9, "current_issue_truncated") !in setOf("yes", "no")) return false
                val failures = value(10, "journal_retained_failure_counts") ?: return false
                if (failures != "none") {
                    val groups = failures.split('|')
                    if (groups.size > SyncDiagnosticBuffer.MAX_EVENTS || groups.any { item ->
                            val pair = item.split('~')
                            pair.size != 2 || !SyncJournalCodes.allowed(pair[0]) || !SyncJournalCodes.failure(pair[0]) ||
                                pair[1] !in DiagnosticCurrentIssues.bands
                        }) return false
                }
            }
        }
        if (investigation) {
            if (lines[schema.size] != "import_mode=batch_25_parallel_10") return false
            val trace = lines.last().removePrefix("investigation_events=")
            if (lines.last() != "investigation_events=$trace") return false
            if (trace != "none") {
                val events = trace.split('|')
                if (events.size > InvestigationTrace.MAX_EVENTS || events.any { !InvestigationTrace.isAllowedCode(it) }) return false
            }
        }
        return schema.entries.zip(lines.take(schema.size)).all { (entry, line) ->
            val separator = line.indexOf('=')
            separator > 0 && line.substring(0, separator) == entry.key &&
                entry.value.matches(line.substring(separator + 1))
        }
    }

    fun containsForbiddenContent(candidate: String): Boolean = forbidden.any { it.containsMatchIn(candidate) }
}
