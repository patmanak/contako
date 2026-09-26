package com.patmanak.contako.data.android.provider

import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.sync.AndroidComponentDifference
import com.patmanak.contako.data.sync.AndroidProjectionRepairCategory

/** Closed reasons only: no account, contact, row locator, value or exception payload. */
internal enum class AndroidBindingRecoveryReason {
    LOCAL_STATE_MISSING, CONTEXT_CHANGED, PENDING_FINGERPRINT_CHANGED, PROJECTION_STATE,
    BASELINE_MISSING, BASELINE_INTEGRITY, BASELINE_DECODING, UNSUPPORTED_OWNED_ROWS,
    CLAIM_LIMIT, CLAIM_SCOPE, CLAIM_MISSING, BINDING_MISSING, BINDING_STATE,
    BINDING_SHAPE, CLAIM_IDENTITY_MISMATCH, BINDING_LOCATORS, PENDING_ATTACHMENT,
    UNSUPPORTED_RELOCATION, OLD_ROW_PRESENT, DESTINATION_BOUND, ROW_DECODING,
    NO_RELOCATIONS, PROJECTION_MISMATCH, OBSERVATION_CHANGED, STORAGE_CONSTRAINT,
    TRANSACTION_DIVERGED,
}

internal data class AndroidBindingRecoveryDiagnostic(
    val reason: AndroidBindingRecoveryReason,
    val kind: AndroidRowKind? = null,
    val codec: AndroidProviderRowCodecFailure? = null,
    val mismatch: AndroidProjectionRepairCategory? = null,
    val component: AndroidComponent? = null,
    val difference: AndroidComponentDifference? = null,
)

/** Keep the first failed guard; a later generic codec rejection must not erase it. */
internal class AndroidBindingRecoveryDiagnosticRecorder(
    private val observer: (AndroidBindingRecoveryDiagnostic) -> Unit,
) {
    private var firstFailure: AndroidBindingRecoveryDiagnostic? = null

    fun reject(
        reason: AndroidBindingRecoveryReason,
        kind: AndroidRowKind? = null,
        codec: AndroidProviderRowCodecFailure? = null,
        mismatch: AndroidProjectionRepairCategory? = null,
        component: AndroidComponent? = null,
        difference: AndroidComponentDifference? = null,
    ): Boolean {
        if (firstFailure == null) firstFailure = AndroidBindingRecoveryDiagnostic(reason, kind, codec, mismatch, component, difference)
        return false
    }

    fun rejectClaim(reason: AndroidBindingRecoveryReason, kind: AndroidRowKind? = null): AndroidProviderIdentityResolution.Divergence {
        reject(reason, kind)
        return AndroidProviderIdentityResolution.Divergence
    }

    fun publishFailure() {
        firstFailure?.let { detail -> runCatching { observer(detail) } }
    }
}
