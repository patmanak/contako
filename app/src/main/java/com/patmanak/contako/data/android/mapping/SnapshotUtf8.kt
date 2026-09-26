package com.patmanak.contako.data.android.mapping

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Shared encoding boundary; callers retain their own format and closed failure categories. */
internal fun encodeSnapshotUtf8(
    value: String,
    maximumBytes: Int,
    malformed: () -> Nothing,
    boundExceeded: () -> Nothing,
): ByteArray {
    // A valid UTF-8 encoding cannot be shorter than its UTF-16 code-unit count.
    if (value.length > maximumBytes) boundExceeded()
    val encoded = try {
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
    } catch (_: CharacterCodingException) {
        malformed()
    }
    if (encoded.remaining() > maximumBytes) boundExceeded()
    return ByteArray(encoded.remaining()).also(encoded::get)
}
