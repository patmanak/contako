package com.patmanak.contako.data.sync

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class InterruptedAndroidPhotoProofTest {
    private val source = byteArrayOf(1, 2, 3)
    private val digest = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }

    @Test fun missingOrChangedSourceNeverAuthorizesProviderReadback() {
        val cases = listOf(
            null to AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_UNAVAILABLE,
            byteArrayOf(1) to AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_SIZE_MISMATCH,
            byteArrayOf(1, 2, 4) to AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_DIGEST_MISMATCH,
        )
        cases.forEach { (bytes, expected) ->
            assertEquals(expected, interruptedAndroidPhotoProofFailure(bytes, 3, digest) { error("No readback allowed") })
        }
    }

    @Test fun exactSourceStillRequiresIndependentProviderProof() {
        assertEquals(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_READBACK_MISMATCH,
            interruptedAndroidPhotoProofFailure(source, 3, digest) { assertArrayEquals(source, it); false })
        assertNull(interruptedAndroidPhotoProofFailure(source, 3, digest) { assertArrayEquals(source, it); true })
        assertTrue(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_READBACK_MISMATCH in ISOLATED_PHOTO_PROOF_FAILURES)
        assertFalse(AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_SOURCE_IDENTITY_MISMATCH in ISOLATED_PHOTO_PROOF_FAILURES)
    }
}
