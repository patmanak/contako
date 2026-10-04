package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.sync.RemoteContactActionRequiredBoundary
import com.patmanak.contako.data.sync.RemoteImportPhase
import com.patmanak.contako.data.sync.SyncPassExceptionCategory
import com.patmanak.contako.data.sync.SyncPassStage
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.data.android.provider.*
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidComponent

/** A code can only be created internally; the export independently validates its vocabulary. */
data class InvestigationTraceEntry internal constructor(internal val code: String)

/** Bounded process-local ring: no account, contact, timestamp, size, exception text or disk file. */
internal object InvestigationTrace {
    const val MAX_EVENTS = 128
    private val ring = ArrayDeque<InvestigationTraceEntry>()

    @Synchronized fun record(event: SanitizedDiagnosticEvent) {
        val code = when (event) {
            is SanitizedDiagnosticEvent.SyncStage -> "stage:${event.stage.name}"
            is SanitizedDiagnosticEvent.ImportPhase -> "import:${event.phase.name}"
            is SanitizedDiagnosticEvent.SyncException -> "failure:${event.category.name}"
            is SanitizedDiagnosticEvent.SyncOutcome -> "outcome:${event.outcome.name}"
            is SanitizedDiagnosticEvent.AndroidResult -> "android:${event.stage.name}:${event.result.name}"
            is SanitizedDiagnosticEvent.IngestFailure -> "ingest_failure:${event.reason.name}"
            is SanitizedDiagnosticEvent.IngestReplan -> "ingest_replan:${event.reason.name}"
            is SanitizedDiagnosticEvent.ExistingContactPlanFailure -> "existing_plan:${event.reason.name}"
            is SanitizedDiagnosticEvent.IngestRowCodecFailure -> "ingest_codec:${event.category.name}"
            is SanitizedDiagnosticEvent.IngestCommitReplan -> "ingest_commit:${event.reason.name}"
            is SanitizedDiagnosticEvent.IngestMembershipStale -> "membership_stale:${event.reason.name}"
            is SanitizedDiagnosticEvent.ProjectionRepair -> "projection_repair:${event.category.name}"
            is SanitizedDiagnosticEvent.ProjectionReplan -> "projection_replan:${event.category.name}"
            is SanitizedDiagnosticEvent.ProviderReplan -> "provider_replan:${event.reason.name}"
            is SanitizedDiagnosticEvent.PhotoWriteFailure -> "photo_write:${event.category.name}"
            is SanitizedDiagnosticEvent.ProjectionBindingRecoveryFailure -> "binding:${event.stage.name}.${event.detail.reason.name}"
            is SanitizedDiagnosticEvent.ProjectionRowFailure ->
                "projection_row:${event.stage.name}.${event.category.name}.${event.identity?.name ?: "NONE"}.${event.kind?.name ?: "NONE"}"
            is SanitizedDiagnosticEvent.ProjectionComponent ->
                "projection_component:${event.kind.name}.${event.component.name}.${event.difference.name}"
            is SanitizedDiagnosticEvent.IngestBaselineMismatch ->
                "ingest_baseline:${event.category.name}.${event.kind?.name ?: "NONE"}.${event.component?.name ?: "NONE"}.${event.difference?.name ?: "NONE"}"
            is SanitizedDiagnosticEvent.MutationFailure ->
                "mutation:${event.source.name}.${event.operation.name}.${event.category?.name ?: "NONE"}.${event.reason?.name ?: "NONE"}"
            is SanitizedDiagnosticEvent.ContactFailure ->
                "remote:${event.boundary.name}:${event.category?.name ?: "NONE"}:${event.hydration?.name ?: "NONE"}"
            else -> return // Exclude auth/crypto traces, fields, exact aggregates and all other event types.
        }
        check(isAllowedCode(code))
        if (ring.size == MAX_EVENTS) ring.removeFirst()
        ring.addLast(InvestigationTraceEntry(code))
    }

    @Synchronized fun snapshot(): List<InvestigationTraceEntry> = ring.toList()
    @Synchronized fun clear() = ring.clear()

    fun isAllowedCode(code: String): Boolean {
        val parts = code.split(':')
        detailVocabulary[parts.firstOrNull()]?.let { vocabulary ->
            if (parts.size != 2) return false
            val details = parts[1].split('.')
            return details.size == vocabulary.size && vocabulary.indices.all { details[it] in vocabulary[it] }
        }
        return when (parts.firstOrNull()) {
            "stage" -> parts.size == 2 && parts[1] in SyncPassStage.entries.map { it.name }
            "import" -> parts.size == 2 && parts[1] in RemoteImportPhase.entries.map { it.name }
            "failure" -> parts.size == 2 && parts[1] in SyncPassExceptionCategory.entries.map { it.name }
            "outcome" -> parts.size == 2 && parts[1] in SyncPassOutcome.entries.map { it.name }
            "android" -> parts.size == 3 && parts[1] in SyncPassStage.entries.map { it.name } &&
                parts[2] in DiagnosticAndroidResult.entries.map { it.name }
            "remote" -> parts.size == 4 && parts[1] in RemoteContactActionRequiredBoundary.entries.map { it.name } &&
                parts[2] in GatewayFailureCategory.entries.map { it.name } + "NONE" &&
                parts[3] in GatewayContactHydrationCategory.entries.map { it.name } + "NONE"
            else -> false
        }
    }

    private fun names(values: Array<out Enum<*>>, nullable: Boolean = false) =
        values.map { it.name }.toSet() + if (nullable) setOf("NONE") else emptySet()

    // Each exported component is independently checked against its exact closed enum.
    private val detailVocabulary = mapOf(
        "ingest_failure" to listOf(names(AndroidIngestActionRequiredReason.entries.toTypedArray())),
        "ingest_replan" to listOf(names(AndroidIngestReplanReason.entries.toTypedArray())),
        "existing_plan" to listOf(names(AndroidExistingContactPlanRepairReason.entries.toTypedArray())),
        "ingest_codec" to listOf(names(AndroidProviderRowCodecFailure.entries.toTypedArray())),
        "ingest_commit" to listOf(names(com.patmanak.contako.data.local.RoomAndroidUnifiedCommitReplanReason.entries.toTypedArray())),
        "membership_stale" to listOf(names(com.patmanak.contako.data.local.RoomAndroidMembershipLedgerStaleReason.entries.toTypedArray())),
        "projection_repair" to listOf(names(AndroidProjectionRepairCategory.entries.toTypedArray())),
        "projection_replan" to listOf(names(AndroidProjectionReplanCategory.entries.toTypedArray())),
        "provider_replan" to listOf(names(AndroidProviderProjectionReplanReason.entries.toTypedArray())),
        "photo_write" to listOf(names(AndroidPhotoProviderRepairCategory.entries.toTypedArray())),
        "binding" to listOf(names(AndroidProjectionDecodeStage.entries.toTypedArray()), names(AndroidBindingRecoveryReason.entries.toTypedArray())),
        "projection_row" to listOf(names(AndroidProjectionDecodeStage.entries.toTypedArray()), names(AndroidProviderRowCodecFailure.entries.toTypedArray()),
            names(AndroidProviderIdentityFailure.entries.toTypedArray(), true), names(AndroidRowKind.entries.toTypedArray(), true)),
        "projection_component" to listOf(names(AndroidRowKind.entries.toTypedArray()), names(AndroidComponent.entries.toTypedArray()), names(AndroidComponentDifference.entries.toTypedArray())),
        "ingest_baseline" to listOf(names(AndroidProjectionRepairCategory.entries.toTypedArray()), names(AndroidRowKind.entries.toTypedArray(), true),
            names(AndroidComponent.entries.toTypedArray(), true), names(AndroidComponentDifference.entries.toTypedArray(), true)),
        "mutation" to listOf(names(MutationActionRequiredSource.entries.toTypedArray()), names(RemoteMutationOperation.entries.toTypedArray()),
            names(GatewayFailureCategory.entries.toTypedArray(), true), names(MutationPreparationActionRequiredReason.entries.toTypedArray(), true)),
    )
}
