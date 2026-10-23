package com.patmanak.contako.data.sync

import com.patmanak.contako.domain.sync.SyncPassOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPassActionReasonTest {
    @Test
    fun currentAuthenticationAndInteractiveUnlockTakePriorityOverEveryOlderBlockedReason() {
        val authenticationReasons = listOf(SyncActionReason.AUTHENTICATION_REQUIRED,
            SyncActionReason.INTERACTIVE_AUTHENTICATION_REQUIRED)
        for (current in authenticationReasons) {
            for (older in SyncActionReason.entries) {
                val selected = syncPassActionReason(SyncPassOutcome.ACTION_REQUIRED, older,
                    current, SyncActionReason.CRYPTOGRAPHIC_VERIFICATION_FAILED)
                assertEquals(current, selected)
                assertTrue(requireNotNull(selected).immediateNotification)
            }
        }
    }

    @Test
    fun resolvedAuthenticationRevealsTheRetainedMutationReasonAgain() {
        for (retained in listOf(SyncActionReason.GROUP_CAPABILITY_REQUIRED,
            SyncActionReason.VALIDATION_REJECTED, SyncActionReason.CONFLICT_RECOVERY_REQUIRED)) {
            assertEquals(SyncActionReason.INTERACTIVE_AUTHENTICATION_REQUIRED,
                syncPassActionReason(SyncPassOutcome.ACTION_REQUIRED, retained,
                    SyncActionReason.INTERACTIVE_AUTHENTICATION_REQUIRED, null))
            assertEquals(retained,
                syncPassActionReason(SyncPassOutcome.ACTION_REQUIRED, retained, null, null))
        }
    }

    @Test
    fun ordinaryExternalErrorsDoNotOverrideOrBecomeAuthentication() {
        for (external in SyncActionReason.entries.filterNot { it.immediateNotification }) {
            assertEquals(SyncActionReason.VALIDATION_REJECTED,
                syncPassActionReason(SyncPassOutcome.ACTION_REQUIRED,
                    SyncActionReason.VALIDATION_REJECTED, external, null))
            assertEquals(external,
                syncPassActionReason(SyncPassOutcome.ACTION_REQUIRED, null, external, null))
        }
    }

    @Test
    fun durableAuthenticationStillWinsWhenThereIsNoCurrentSessionAction() {
        assertEquals(SyncActionReason.AUTHENTICATION_REQUIRED,
            syncPassActionReason(SyncPassOutcome.ACTION_REQUIRED,
                SyncActionReason.AUTHENTICATION_REQUIRED, SyncActionReason.GROUP_CAPABILITY_REQUIRED,
                SyncActionReason.CRYPTOGRAPHIC_VERIFICATION_FAILED))
    }

    @Test
    fun deferredImportAndUnclassifiedFailureRemainActionableWithoutInventingAuthentication() {
        assertEquals(SyncActionReason.CRYPTOGRAPHIC_VERIFICATION_FAILED,
            syncPassActionReason(SyncPassOutcome.RETRY_WAITING, null, null,
                SyncActionReason.CRYPTOGRAPHIC_VERIFICATION_FAILED))
        assertEquals(SyncActionReason.INTERNAL_FAILURE,
            syncPassActionReason(SyncPassOutcome.ACTION_REQUIRED, null, null, null))
        assertNull(syncPassActionReason(SyncPassOutcome.SUCCESS, null, null, null))
        assertNull(syncPassActionReason(SyncPassOutcome.RETRY_WAITING, null,
            SyncActionReason.ANDROID_INTEROPERABILITY_DEGRADED, null))
    }
}
