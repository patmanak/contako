package com.patmanak.contako.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedContactImageSizeTest {
    @Test fun portraitsLandscapesAndSquaresMatchTheContactTarget() {
        assertEquals(180 to 360, selectedContactImageSize(1_024, 2_048))
        assertEquals(360 to 180, selectedContactImageSize(2_048, 1_024))
        assertEquals(180 to 180, selectedContactImageSize(1_024, 1_024))
        assertEquals(720 to 180, selectedContactImageSize(1_024, 256))
        assertEquals(180 to 360, selectedContactImageSize(1_001, 2_003))
    }

    @Test fun smallAndThinImagesAreNeverEnlarged() {
        assertEquals(64 to 128, selectedContactImageSize(64, 128))
        assertEquals(128 to 512, selectedContactImageSize(128, 512))
        assertEquals(180 to 180, selectedContactImageSize(180, 180))
        assertEquals(1 to 1, selectedContactImageSize(1, 1))
    }

    @Test fun panoramasRemainBoundedWithoutCroppingOrZeroDimensions() {
        assertEquals(1_024 to 16, selectedContactImageSize(8_192, 128))
        assertEquals(16 to 1_024, selectedContactImageSize(128, 8_192))
        assertEquals(1 to 1_024, selectedContactImageSize(1, 8_192))
        listOf(1 to 8192, 8192 to 128, 1001 to 2003, 64 to 128).forEach { (width, height) ->
            val (outWidth, outHeight) = selectedContactImageSize(width, height)
            assertTrue(outWidth in 1..minOf(width, 1024))
            assertTrue(outHeight in 1..minOf(height, 1024))
            assertTrue(minOf(outWidth, outHeight) <= 180)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() {
        selectedContactImageSize(0, 180)
    }
}
