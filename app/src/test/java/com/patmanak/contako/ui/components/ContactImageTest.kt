package com.patmanak.contako.ui.components

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactImageTest {
    @Test
    fun `inline payload accepts only strict supported raster data URIs`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(bytes)

        val decoded = decodeInlineImagePayload("data:image/png;base64,$encoded")

        assertEquals("image/png", decoded?.mimeType)
        assertArrayEquals(bytes, decoded?.bytes)
        decoded?.bytes?.fill(0)
        val jpegAlias = decodeInlineImagePayload("data:image/jpg;base64,$encoded")
        assertEquals("image/jpeg", jpegAlias?.mimeType)
        jpegAlias?.bytes?.fill(0)
        assertNull(decodeInlineImagePayload("data:text/html;base64,$encoded"))
        assertNull(decodeInlineImagePayload("data:image/png;base64, $encoded"))
        assertNull(decodeInlineImagePayload("https://img.example.test/photo.png"))
    }

    @Test
    fun `encoded payload is rejected before an oversized decode allocation`() {
        val oversized = "A".repeat(13_981_017)

        assertNull(decodeInlineImagePayload("data:image/png;base64,$oversized"))
    }
}
