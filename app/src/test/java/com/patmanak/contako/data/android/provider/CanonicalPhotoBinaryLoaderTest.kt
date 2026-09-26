package com.patmanak.contako.data.android.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Production composed the projection with the default `{ null }` loader, so every photo-bearing
 * contact resolved to a null payload and was excluded from the Android provider with
 * `RepairRequired`. These cases pin what the replacement accepts and, just as importantly, what it
 * refuses without failing a whole projection page.
 */
class CanonicalPhotoBinaryLoaderTest {

    @Test
    fun `an inline base64 data uri resolves to its bytes`() {
        // "hi" encoded; the payload is what a vCard PHOTO property carries inline.
        val loaded = CanonicalPhotoBinaryLoader.load("data:image/png;base64,aGk=")

        assertArrayEquals(byteArrayOf('h'.code.toByte(), 'i'.code.toByte()), loaded)
    }

    @Test
    fun `surrounding whitespace and header casing are tolerated`() {
        val loaded = CanonicalPhotoBinaryLoader.load("  DATA:image/jpeg;BASE64,aGk=  ")

        assertArrayEquals(byteArrayOf('h'.code.toByte(), 'i'.code.toByte()), loaded)
    }

    @Test
    fun `full resolution bytes are not transformed by the canonical loader`() {
        val source = ByteArray(AndroidProjectionPhotoScaler.MAX_INLINE_PHOTO_BYTES + 1) { index ->
            (index and 0xff).toByte()
        }
        val reference = "data:image/png;base64,${Base64.getEncoder().encodeToString(source)}"

        assertArrayEquals(source, CanonicalPhotoBinaryLoader.load(reference))
    }

    @Test
    fun `a remote uri is not fetched`() {
        // This boundary must never perform network I/O; the contact projects without a photo.
        assertNull(CanonicalPhotoBinaryLoader.load("https://example.test/avatar.png"))
    }

    @Test
    fun `a non base64 data uri is refused rather than guessed`() {
        assertNull(CanonicalPhotoBinaryLoader.load("data:image/png,%89PNG"))
    }

    @Test
    fun `malformed input degrades to null instead of throwing`() {
        listOf(
            "",
            "data:",
            "data:image/png;base64,",
            "data:image/png;base64,!!!not-base64!!!",
        ).forEach { reference ->
            assertNull("Expected null for '$reference'", CanonicalPhotoBinaryLoader.load(reference))
        }
    }
}
