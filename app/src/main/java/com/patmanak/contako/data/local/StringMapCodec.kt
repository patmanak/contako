package com.patmanak.contako.data.local

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Deterministic, delimiter-safe persistence codec for small canonical metadata maps. */
internal object StringMapCodec {
    fun encode(values: Map<String, String>): String = values.toSortedMap().entries.joinToString("\n") {
        "${encodePart(it.key)}:${encodePart(it.value)}"
    }

    fun decode(encoded: String): Map<String, String> {
        if (encoded.isEmpty()) return emptyMap()
        return encoded.lineSequence().associate { line ->
            val separator = line.indexOf(':')
            require(separator >= 0) { "Invalid metadata encoding" }
            decodePart(line.substring(0, separator)) to decodePart(line.substring(separator + 1))
        }
    }

    private fun encodePart(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodePart(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}
