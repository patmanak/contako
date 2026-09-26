package com.patmanak.contako.data.android.provider

import java.util.Base64

/**
 * Resolves a canonical photo reference into raw bytes for the Android provider.
 *
 * Production composed the projection with the default `{ null }` loader, so any contact carrying a
 * photo resolved to a null payload and the projection item executor returned `RepairRequired`. That
 * silently excluded every photo-bearing contact from the Android projection.
 *
 * The reference is the decoded vCard `PHOTO` value, which per RFC 6350 is either an inline
 * `data:` URI or a remote URI. Only inline data is resolved here: this boundary MUST NOT perform
 * network I/O, so a remote URI yields `null` and the contact projects without a photo rather than
 * failing the whole page.
 */
internal object CanonicalPhotoBinaryLoader : AndroidProjectionBinaryLoader {

    /** Matches Android's own provider limit for a single display photo. */
    private const val MAX_DECODED_BYTES = 10 * 1_024 * 1_024

    override fun load(reference: String): ByteArray? {
        val trimmed = reference.trim()
        if (!trimmed.startsWith(DATA_URI_PREFIX, ignoreCase = true)) return null

        val separator = trimmed.indexOf(',')
        if (separator <= 0) return null
        val header = trimmed.substring(0, separator)
        val payload = trimmed.substring(separator + 1)
        if (payload.isEmpty()) return null

        // Only base64 payloads are supported; percent-encoded data URIs are not produced by the
        // Proton clients and decoding them here would be speculative.
        if (!header.contains(BASE64_MARKER, ignoreCase = true)) return null

        return try {
            // MIME decoder: vCard folding means the payload may carry line breaks, which the
            // strict decoder rejects. java.util.Base64 also keeps this class unit-testable,
            // unlike android.util.Base64 which is a stub on the JVM.
            if (payload.length > MAX_ENCODED_CHARS) return null
            Base64.getMimeDecoder().decode(payload)
                .takeIf { it.isNotEmpty() && it.size <= MAX_DECODED_BYTES }
        } catch (_: IllegalArgumentException) {
            // Malformed payloads must not fail the projection pass for the whole page.
            null
        }
    }

    private const val DATA_URI_PREFIX = "data:"
    private const val BASE64_MARKER = ";base64"
    // Base64 plus conservative folding/whitespace headroom for the 10 MiB decoded ceiling.
    private const val MAX_ENCODED_CHARS = 15 * 1_024 * 1_024
}
