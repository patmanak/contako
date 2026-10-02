package com.patmanak.contako.data.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidProjectionPhotoScalerTest {
    @Test fun dimensionsAreBoundedBeforeDecoding() {
        assertNull(AndroidProjectionPhotoScaler.decodeSampleSize(1, 9000, 512))
        assertNull(AndroidProjectionPhotoScaler.decodeSampleSize(8192, 8192, 512))
        assertNull(AndroidProjectionPhotoScaler.decodeSampleSize(-1, 1, 512))
        assertEquals(4, AndroidProjectionPhotoScaler.decodeSampleSize(2048, 2048, 512))
        assertEquals(1, AndroidProjectionPhotoScaler.decodeSampleSize(128, 128, 512))
        assertEquals(2, AndroidProjectionPhotoScaler.decodeSampleSize(4096, 4096, 2048))
    }
}
