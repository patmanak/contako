package com.patmanak.contako.data.android.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPhotoReadbackTest {
    private val display = byteArrayOf(1, 2, 3, 4)
    private val thumbnail = byteArrayOf(5, 6)
    private val different = byteArrayOf(7, 8, 9)

    @Test fun stableReaderDisplayIsComparedWithDisplayRatherThanThumbnail() {
        assertTrue(matches(display, thumbnail, display))
        assertFalse(matches(thumbnail, thumbnail, display))
    }

    @Test fun changedFullPhotoOrThumbnailCannotAcknowledgeTheObservation() {
        assertFalse(matches(display, thumbnail, different))
        assertFalse(matches(different, thumbnail, display))
        assertFalse(matches(display, different, display))
        assertFalse(matches(display, null, display))
        assertFalse(matches(display, thumbnail, byteArrayOf()))
    }

    @Test fun inlineFallbackRequiresAbsentDisplayAndExactObservedThumbnail() {
        assertTrue(matches(thumbnail, thumbnail, null, requireDisplay = false))
        assertFalse(matches(thumbnail, thumbnail, null))
        assertFalse(matches(display, thumbnail, null, requireDisplay = false))
        assertFalse(matches(thumbnail, thumbnail, different, requireDisplay = false))
    }

    private fun matches(observed: ByteArray, inline: ByteArray?, full: ByteArray?,
        requireDisplay: Boolean = true) = matchesAndroidPhotoReadback(
        observed, inline, full, display, thumbnail, requireDisplay,
    )
}
