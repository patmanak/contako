package com.patmanak.contako.data.android.provider

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidPhotoProjectionReceiptEntity
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import org.junit.Assert.*
import org.junit.Test

class AndroidPhotoProjectionReceiptTest {
    private val context = AndroidInteroperabilityContext(AccountScope("fixture"), "fixture-android", 1, 3)
    private val source = byteArrayOf(1, 2, 3, 4)
    private val produced = byteArrayOf(7, 8, 9)
    private val receipt = AndroidPhotoProjectionReceiptEntity("fixture", "contact", "fixture-android", 3,
        5, 6, "photo", "canonical-reference", androidPhotoSha256(source), source.size.toLong(),
        androidPhotoSha256(produced), produced.size.toLong(), 10)
    private fun observation(bytes: ByteArray? = produced, dirty: Boolean = false, version: Long = 10) =
        AndroidStableRawContactObservation(AndroidOwnedRawContact(5, "contact", "remote", dirty, false, version),
            listOf(AndroidOwnedDataRow(6, 5, "vnd.android.cursor.item/photo", "photo", 0, false,
                null, true, true, List(14) { null }, bytes)))

    @Test fun transformedRepresentationMatchesWithoutReplacingCanonicalSource() {
        assertFalse(source.contentEquals(produced))
        assertTrue(receipt.matchesPhoto(context, "contact", observation()))
        assertEquals(androidPhotoSha256(source), receipt.sourceSha256)
        assertNotEquals(receipt.sourceSha256, receipt.readbackSha256)
    }

    @Test fun nativeNoteDirtyFlagDoesNotTurnAnUnchangedPhotoIntoAnEdit() {
        assertTrue(receipt.matchesPhoto(context, "contact", observation(dirty = true, version = 11)))
        assertFalse(receipt.matchesPhoto(context, "contact", observation(byteArrayOf(7, 8, 0), true, 11)))
    }

    @Test fun changedMissingOrReboundPhotoNeverMatchesEvenWhenClean() {
        assertFalse(receipt.matchesPhoto(context, "contact", observation(source)))
        assertFalse(receipt.matchesPhoto(context, "contact", observation(null)))
        assertFalse(receipt.matchesPhoto(context, "contact", observation(version = 9)))
        assertFalse(receipt.copy(dataRowLocator = 7).matchesPhoto(context, "contact", observation()))
        assertFalse(receipt.copy(canonicalValueId = "other").matchesPhoto(context, "contact", observation()))
        assertFalse(receipt.matchesPhoto(context.copy(providerEpoch = 4), "contact", observation()))
        assertFalse(receipt.matchesPhoto(context.copy(androidAccountName = "other"), "contact", observation()))
        assertFalse(receipt.matchesPhoto(context.copy(account = AccountScope("other")), "contact", observation()))
    }
}
