package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.*

enum class DiagnosticIssueScope { OUTBOX_CONTACT, OUTBOX_GROUP, REMOTE_IMPORT, ANDROID_INGEST, ANDROID_PROJECTION, REMOTE_REQUEST }
enum class DiagnosticIssueState { PENDING, IN_FLIGHT, ACTION_REQUIRED, RETRY_WAITING, IGNORED }
enum class DiagnosticAttemptBand { NONE, ONE_TO_THREE, FOUR_TO_SEVEN, EIGHT_OR_MORE }
enum class DiagnosticIssueRead { AVAILABLE, UNAVAILABLE }

/** Only validated closed codes and count bands may leave the local collection boundary. */
@ConsistentCopyVisibility
data class DiagnosticCurrentIssues internal constructor(
    internal val rows: List<String> = emptyList(),
    internal val read: DiagnosticIssueRead = DiagnosticIssueRead.AVAILABLE,
    internal val truncated: Boolean = false,
) {
    init { require(rows.size <= MAX_ROWS && rows.all(::validRow)) }

    companion object {
        const val MAX_ROWS = 64
        internal val bands = setOf("small", "medium", "large", "very_large")
        internal val reasons: Set<String> by lazy {
            setOf("NONE", "UNKNOWN", "MISSING_NAME", "INVALID_EMAIL", "REMOTE_ACTION_REQUIRED",
                "AUTHENTICATION_REQUIRED", "INTERACTIVE_AUTHENTICATION_REQUIRED", "CRYPTOGRAPHIC_VERIFICATION_FAILED",
                "VALIDATION_REJECTED", "GROUP_CAPABILITY_REQUIRED", "REMOTE_PERMISSION_REQUIRED",
                "CONFLICT_RECOVERY_REQUIRED", "EDIT_DELETE_RECOVERY_REQUIRED", "REMOTE_DELETION_RECOVERY_REQUIRED") +
                MutationPreparationActionRequiredReason.entries.map { it.name } +
                GatewayContactHydrationCategory.entries.map { it.name } +
                AndroidProjectionRepairCategory.entries.map { it.name } +
                AndroidIngestActionRequiredReason.entries.map { it.name } +
                AndroidExistingContactPlanRepairReason.entries.map { it.name }
        }
        fun unavailable() = DiagnosticCurrentIssues(read = DiagnosticIssueRead.UNAVAILABLE)
        internal fun validRow(row: String): Boolean {
            val p = row.split('.')
            return p.size == 7 && DiagnosticIssueScope.entries.any { it.name == p[0] } &&
                DiagnosticIssueState.entries.any { it.name == p[1] } &&
                (p[2] == "NONE" || RemoteMutationOperation.entries.any { it.name == p[2] }) &&
                (p[3] == "NONE" || GatewayFailureCategory.entries.any { it.name == p[3] }) &&
                p[4] in reasons && DiagnosticAttemptBand.entries.any { it.name == p[5] } && p[6] in bands
        }
        internal fun countBand(count: Int) = when {
            count < 10 -> "small"
            count < 100 -> "medium"
            count < 1_000 -> "large"
            else -> "very_large"
        }
        internal fun attemptBand(count: Int) = when {
            count <= 0 -> DiagnosticAttemptBand.NONE
            count < 4 -> DiagnosticAttemptBand.ONE_TO_THREE
            count < 8 -> DiagnosticAttemptBand.FOUR_TO_SEVEN
            else -> DiagnosticAttemptBand.EIGHT_OR_MORE
        }
    }
}

/** No aggregate identity is accepted by this bounded reducer. Unknown storage text is discarded. */
internal class DiagnosticIssueReducer {
    private val counts = linkedMapOf<String, Int>()
    private var truncated = false
    fun add(scope: DiagnosticIssueScope, state: DiagnosticIssueState, category: String?, reason: String?, attempts: Int = 0,
        operation: RemoteMutationOperation? = null) {
        val safeCategory = category?.takeIf { c -> GatewayFailureCategory.entries.any { it.name == c } } ?: "NONE"
        val safeReason = reason?.takeIf { it in DiagnosticCurrentIssues.reasons } ?: "UNKNOWN"
        val key = "${scope.name}.${state.name}.${operation?.name ?: "NONE"}.$safeCategory.$safeReason.${DiagnosticCurrentIssues.attemptBand(attempts).name}"
        if (key !in counts && counts.size == DiagnosticCurrentIssues.MAX_ROWS) { truncated = true; return }
        counts[key] = ((counts[key] ?: 0) + 1).coerceAtMost(1_000)
    }
    fun snapshot() = DiagnosticCurrentIssues(counts.toSortedMap().map { (key, count) ->
        "$key.${DiagnosticCurrentIssues.countBand(count)}"
    }, truncated = truncated)
}
