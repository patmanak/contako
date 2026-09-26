package com.patmanak.contako.data.android.provider

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidProviderPhotoReferenceEncoderTest {
    @Test
    fun `encodes supported raster signatures with the matching media type`() {
        val cases = listOf(
            "image/png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1),
            "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 0xFF.toByte(), 0xD9.toByte()),
            "image/gif" to "GIF89a-payload".toByteArray(),
            "image/webp" to "RIFF0000WEBPpayload".toByteArray(),
        )

        cases.forEach { (mime, bytes) ->
            assertEquals(
                "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}",
                AndroidProviderPhotoReferenceEncoder.encode(bytes),
            )
        }
    }

    @Test
    fun `rejects empty unknown truncated and oversized payloads`() {
        assertNull(AndroidProviderPhotoReferenceEncoder.encode(byteArrayOf()))
        assertNull(AndroidProviderPhotoReferenceEncoder.encode("not-an-image".toByteArray()))
        assertNull(AndroidProviderPhotoReferenceEncoder.encode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(AndroidProviderPhotoReferenceEncoder.encode(ByteArray(10 * 1_024 * 1_024 + 1)))
    }
}
