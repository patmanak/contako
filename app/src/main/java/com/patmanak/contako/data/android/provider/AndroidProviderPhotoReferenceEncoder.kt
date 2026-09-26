package com.patmanak.contako.data.android.provider

import java.util.Base64

/** Converts one bounded Android provider photo into a durable, offline canonical reference. */
internal object AndroidProviderPhotoReferenceEncoder {
    fun encode(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) return null
        val mime = when {
            bytes.startsWith(PNG_SIGNATURE) -> "image/png"
            bytes.startsWith(JPEG_SIGNATURE) && bytes.endsWith(JPEG_END) -> "image/jpeg"
            bytes.startsWith(GIF_87A) || bytes.startsWith(GIF_89A) -> "image/gif"
            bytes.size >= WEBP_MINIMUM_BYTES && bytes.startsWith(RIFF) && bytes.matchesAt(WEBP_OFFSET, WEBP) ->
                "image/webp"
            else -> return null
        }
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(0, prefix)

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && matchesAt(size - suffix.size, suffix)

    private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean =
        offset >= 0 && size - offset >= expected.size && expected.indices.all { this[offset + it] == expected[it] }

    private const val MAX_PHOTO_BYTES = 10 * 1_024 * 1_024
    private const val WEBP_OFFSET = 8
    private const val WEBP_MINIMUM_BYTES = 12
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val GIF_87A = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF_89A = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
}
