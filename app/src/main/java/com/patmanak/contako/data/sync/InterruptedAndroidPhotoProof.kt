package com.patmanak.contako.data.sync

import java.security.MessageDigest

internal val ISOLATED_PHOTO_PROOF_FAILURES = setOf(
    AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_PROOF_FAILED,
    AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_UNAVAILABLE,
    AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_SIZE_MISMATCH,
    AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_DIGEST_MISMATCH,
    AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_READBACK_MISMATCH,
)

/** Null means all exact proofs passed, never merely a visually similar photo. */
internal fun interruptedAndroidPhotoProofFailure(source: ByteArray?, expectedSize: Long,
    expectedSha256: String, verifyReadback: (ByteArray) -> Boolean): AndroidExistingContactPlanRepairReason? {
    if (source == null) return AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_UNAVAILABLE
    if (source.size.toLong() != expectedSize) return AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_SIZE_MISMATCH
    val digest = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
    if (digest != expectedSha256) return AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_SOURCE_DIGEST_MISMATCH
    return if (verifyReadback(source)) null else AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_READBACK_MISMATCH
}
