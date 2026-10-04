package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.sync.RemoteContactActionRequiredBoundary
import com.patmanak.contako.data.sync.SyncActionReason
import com.patmanak.contako.data.sync.SyncHealthState
import com.patmanak.contako.data.sync.SyncPassStage
import com.patmanak.contako.data.sync.SyncPassExceptionCategory
import com.patmanak.contako.data.sync.AndroidInteroperabilityStageResult
import com.patmanak.contako.data.sync.AndroidProjectionRepairCategory
import com.patmanak.contako.data.sync.AndroidProjectionReplanCategory
import com.patmanak.contako.data.sync.AndroidIngestActionRequiredReason
import com.patmanak.contako.data.sync.AndroidIngestReplanReason
import com.patmanak.contako.data.sync.MutationActionRequiredSource
import com.patmanak.contako.data.sync.MutationPreparationActionRequiredReason
import com.patmanak.contako.data.sync.RemoteMutationOperation
import com.patmanak.contako.data.android.provider.AndroidProviderProjectionReplanReason
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.sync.AndroidComponentDifference
import com.patmanak.contako.data.android.provider.AndroidPhotoProviderRepairCategory
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure
import com.patmanak.contako.data.sync.AndroidExistingContactPlanRepairReason

/** D-130 metadata only. No free text, payload, throwable, account object or identifier. */
internal sealed interface SanitizedDiagnosticEvent {
    data class AuthPhase(val phase: GateCAuthPhase) : SanitizedDiagnosticEvent
    data class AuthFailure(val event: GateCAuthDiagnosticEvent) : SanitizedDiagnosticEvent
    data class SyncStage(val stage: SyncPassStage) : SanitizedDiagnosticEvent
    data class ImportPhase(val phase: com.patmanak.contako.data.sync.RemoteImportPhase) : SanitizedDiagnosticEvent
    data class ContactUpdateFailure(
        val stage: com.patmanak.contako.data.proton.ProtonContactUpdateStage,
        val category: GatewayFailureCategory,
        val encoding: com.patmanak.contako.data.proton.ProtonContactEncodingStage?,
        val field: com.patmanak.contako.domain.model.ContactValueKind?,
    ) : SanitizedDiagnosticEvent
    data class SyncException(val category: SyncPassExceptionCategory) : SanitizedDiagnosticEvent
    data class SyncOutcome(val outcome: SyncPassOutcome) : SanitizedDiagnosticEvent
    data class AndroidResult(val stage: SyncPassStage, val result: DiagnosticAndroidResult) : SanitizedDiagnosticEvent
    data class ProjectionRepair(val category: AndroidProjectionRepairCategory) : SanitizedDiagnosticEvent
    data class ProjectionBindingRecoveryFailure(
        val stage: com.patmanak.contako.data.sync.AndroidProjectionDecodeStage,
        val detail: com.patmanak.contako.data.android.provider.AndroidBindingRecoveryDiagnostic,
    ) : SanitizedDiagnosticEvent
    data class ProjectionRowFailure(
        val stage: com.patmanak.contako.data.sync.AndroidProjectionDecodeStage,
        val category: AndroidProviderRowCodecFailure,
        val identity: com.patmanak.contako.data.android.provider.AndroidProviderIdentityFailure?,
        val kind: AndroidRowKind?,
    ) : SanitizedDiagnosticEvent
    data class ProjectionComponent(
        val kind: AndroidRowKind,
        val component: AndroidComponent,
        val difference: AndroidComponentDifference,
    ) : SanitizedDiagnosticEvent
    data class ProjectionReplan(val category: AndroidProjectionReplanCategory) : SanitizedDiagnosticEvent
    data class ProviderReplan(val reason: AndroidProviderProjectionReplanReason) : SanitizedDiagnosticEvent
    data class IngestFailure(val reason: AndroidIngestActionRequiredReason) : SanitizedDiagnosticEvent
    data class PhotoWriteFailure(val category: AndroidPhotoProviderRepairCategory) : SanitizedDiagnosticEvent
    data class ExistingContactPlanFailure(val reason: AndroidExistingContactPlanRepairReason) : SanitizedDiagnosticEvent
    data class IngestRowCodecFailure(val category: AndroidProviderRowCodecFailure) : SanitizedDiagnosticEvent
    data class IngestBaselineMismatch(
        val category: AndroidProjectionRepairCategory,
        val kind: AndroidRowKind?,
        val component: AndroidComponent?,
        val difference: AndroidComponentDifference?,
    ) : SanitizedDiagnosticEvent
    data class IngestReplan(val reason: AndroidIngestReplanReason) : SanitizedDiagnosticEvent
    data class IngestCommitReplan(
        val reason: com.patmanak.contako.data.local.RoomAndroidUnifiedCommitReplanReason,
    ) : SanitizedDiagnosticEvent
    data class IngestMembershipStale(
        val reason: com.patmanak.contako.data.local.RoomAndroidMembershipLedgerStaleReason,
    ) : SanitizedDiagnosticEvent
    data class MutationFailure(
        val source: MutationActionRequiredSource,
        val operation: RemoteMutationOperation,
        val category: GatewayFailureCategory?,
        val reason: MutationPreparationActionRequiredReason?,
    ) : SanitizedDiagnosticEvent
    data class ContactFailure(
        val boundary: RemoteContactActionRequiredBoundary,
        val category: GatewayFailureCategory?,
        val hydration: GatewayContactHydrationCategory?,
    ) : SanitizedDiagnosticEvent
    data class SyncStatus(
        val state: SyncHealthState,
        val reason: SyncActionReason?,
        val pending: Int,
        val actions: Int,
        val contacts: Long,
    ) : SanitizedDiagnosticEvent
    data class SignatureCheck(
        val type: DiagnosticCardType,
        val signatureEmpty: Boolean,
        val verifierCount: Int,
        val activeVerifierCount: Int,
        val timeIndependentProof: Boolean,
        val exactByteProof: Boolean,
    ) : SanitizedDiagnosticEvent
}

internal enum class DiagnosticAndroidResult {
    SUCCESS, RETRY_WAITING, ACTION_REQUIRED, CANCELLED, LOCAL_PERSISTENCE_FAILURE;

    companion object {
        fun from(result: AndroidInteroperabilityStageResult): DiagnosticAndroidResult = when (result) {
            AndroidInteroperabilityStageResult.Success -> SUCCESS
            AndroidInteroperabilityStageResult.RetryWaiting -> RETRY_WAITING
            AndroidInteroperabilityStageResult.ActionRequired -> ACTION_REQUIRED
            AndroidInteroperabilityStageResult.Cancelled -> CANCELLED
            AndroidInteroperabilityStageResult.LocalPersistenceFailure -> LOCAL_PERSISTENCE_FAILURE
        }
    }
}

/** The minified sync-only artifact MUST NOT enable authentication/cryptography traces. */
internal fun isSyncDiagnostic(event: SanitizedDiagnosticEvent): Boolean = when (event) {
    is SanitizedDiagnosticEvent.AuthPhase,
    is SanitizedDiagnosticEvent.AuthFailure,
    is SanitizedDiagnosticEvent.SignatureCheck -> false
    else -> true
}

internal enum class DiagnosticCardType(val wireValue: Int) {
    SIGNED(2), ENCRYPTED_AND_SIGNED(3),
}

/** Preserve the approved operator protocol; counts are D-130 exact aggregates, not identifiers. */
internal fun renderDiagnostic(event: SanitizedDiagnosticEvent): String = when (event) {
    is SanitizedDiagnosticEvent.AuthPhase -> "AUTH_STAGE=${event.phase.name}"
    is SanitizedDiagnosticEvent.AuthFailure ->
        "AUTH_ERROR=${event.event.diagnosticClass.name} HTTP=${event.event.httpCode} CODE=${event.event.protonCode}"
    is SanitizedDiagnosticEvent.SyncStage -> "SYNC_STAGE=${event.stage.name}"
    is SanitizedDiagnosticEvent.ImportPhase -> "IMPORT_PHASE=${event.phase.name}"
    is SanitizedDiagnosticEvent.ContactUpdateFailure ->
        "CONTACT_UPDATE_FAILURE=${event.stage.name} CATEGORY=${event.category.name} " +
            "ENCODING=${event.encoding?.name} FIELD=${event.field?.name}"
    is SanitizedDiagnosticEvent.SyncException -> "SYNC_EXCEPTION=${event.category.name}"
    is SanitizedDiagnosticEvent.SyncOutcome -> "SYNC_OUTCOME=${event.outcome.name}"
    is SanitizedDiagnosticEvent.AndroidResult -> "ANDROID_STAGE=${event.stage.name} RESULT=${event.result.name}"
    is SanitizedDiagnosticEvent.ProjectionRepair -> "PROJECTION_REPAIR=${event.category.name}"
    is SanitizedDiagnosticEvent.ProjectionBindingRecoveryFailure ->
        "PROJECTION_BINDING_RECOVERY=${event.detail.reason.name} STAGE=${event.stage.name} " +
            "KIND=${event.detail.kind?.name} CODEC=${event.detail.codec?.name} " +
            "MISMATCH=${event.detail.mismatch?.name} COMPONENT=${event.detail.component?.name} " +
            "DIFFERENCE=${event.detail.difference?.name}"
    is SanitizedDiagnosticEvent.ProjectionRowFailure ->
        "PROJECTION_ROW_FAILURE=${event.category.name} STAGE=${event.stage.name} " +
            "IDENTITY=${event.identity?.name} KIND=${event.kind?.name}"
    is SanitizedDiagnosticEvent.ProjectionComponent ->
        "PROJECTION_COMPONENT=${event.component.name} KIND=${event.kind.name} DIFFERENCE=${event.difference.name}"
    is SanitizedDiagnosticEvent.ProjectionReplan -> "PROJECTION_REPLAN=${event.category.name}"
    is SanitizedDiagnosticEvent.ProviderReplan -> "PROVIDER_REPLAN=${event.reason.name}"
    is SanitizedDiagnosticEvent.IngestFailure -> "INGEST_FAILURE=${event.reason.name}"
    is SanitizedDiagnosticEvent.PhotoWriteFailure -> "PHOTO_WRITE_FAILURE=${event.category.name}"
    is SanitizedDiagnosticEvent.ExistingContactPlanFailure -> "INGEST_PLAN_FAILURE=${event.reason.name}"
    is SanitizedDiagnosticEvent.IngestRowCodecFailure -> "INGEST_ROW_FAILURE=${event.category.name}"
    is SanitizedDiagnosticEvent.IngestBaselineMismatch ->
        "INGEST_BASELINE_MISMATCH=${event.category.name} KIND=${event.kind?.name} " +
            "COMPONENT=${event.component?.name} DIFFERENCE=${event.difference?.name}"
    is SanitizedDiagnosticEvent.IngestReplan -> "INGEST_REPLAN=${event.reason.name}"
    is SanitizedDiagnosticEvent.IngestCommitReplan -> "INGEST_COMMIT_REPLAN=${event.reason.name}"
    is SanitizedDiagnosticEvent.IngestMembershipStale -> "INGEST_MEMBERSHIP_STALE=${event.reason.name}"
    is SanitizedDiagnosticEvent.MutationFailure ->
        "MUTATION_FAILURE=${event.source.name} OPERATION=${event.operation.name} " +
            "CATEGORY=${event.category?.name} REASON=${event.reason?.name}"
    is SanitizedDiagnosticEvent.ContactFailure ->
        "CONTACT_ERROR=${event.boundary.name} CATEGORY=${event.category?.name} HYDRATION=${event.hydration?.name}"
    is SanitizedDiagnosticEvent.SyncStatus ->
        "SYNC_STATUS=${event.state.name} REASON=${event.reason?.name} " +
            "PENDING=${event.pending.coerceAtLeast(0)} ACTIONS=${event.actions.coerceAtLeast(0)} CONTACTS=${event.contacts.coerceAtLeast(0)}"
    is SanitizedDiagnosticEvent.SignatureCheck ->
        "CARD_TYPE=${event.type.wireValue} SIGNATURE_EMPTY=${event.signatureEmpty} " +
            "VERIFY_KEYS=${event.verifierCount.coerceAtLeast(0)} ACTIVE_VERIFIERS=${event.activeVerifierCount.coerceAtLeast(0)} " +
            "TIME_INDEPENDENT_PROOF=${event.timeIndependentProof} EXACT_BYTE_PROOF=${event.exactByteProof}"
}
