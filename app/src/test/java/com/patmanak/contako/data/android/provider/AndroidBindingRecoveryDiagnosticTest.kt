package com.patmanak.contako.data.android.provider

import com.patmanak.contako.data.android.mapping.AndroidRowKind
import org.junit.Assert.*
import org.junit.Test

class AndroidBindingRecoveryDiagnosticTest {
    @Test fun firstSpecificRejectionSurvivesCodecFallbackAndObserverFailure() {
        val received = mutableListOf<AndroidBindingRecoveryDiagnostic>()
        val recorder = AndroidBindingRecoveryDiagnosticRecorder {
            received += it
            throw IllegalStateException("Observer failure")
        }
        assertEquals(AndroidProviderIdentityResolution.Divergence,
            recorder.rejectClaim(AndroidBindingRecoveryReason.OLD_ROW_PRESENT, AndroidRowKind.STRUCTURED_NAME))
        assertFalse(recorder.reject(AndroidBindingRecoveryReason.ROW_DECODING,
            codec = AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE))
        assertTrue(received.isEmpty())
        recorder.publishFailure()
        assertEquals(listOf(AndroidBindingRecoveryDiagnostic(AndroidBindingRecoveryReason.OLD_ROW_PRESENT,
            kind = AndroidRowKind.STRUCTURED_NAME)), received)
    }

    @Test fun noFailureEmitsNothing() {
        val received = mutableListOf<AndroidBindingRecoveryDiagnostic>()
        AndroidBindingRecoveryDiagnosticRecorder { received += it }.publishFailure()
        assertTrue(received.isEmpty())
    }
}
