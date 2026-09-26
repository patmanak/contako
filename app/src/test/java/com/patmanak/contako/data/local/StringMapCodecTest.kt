package com.patmanak.contako.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StringMapCodecTest {
    @Test
    fun encodingIsDeterministicAndRoundTripsDelimiterAndUnicodeContent() {
        val forward = linkedMapOf(
            "z:key" to "line one\nline two",
            "accent" to "Élodie / 東京",
            "empty" to "",
            "nul" to "a\u0000b",
        )
        val reverse = forward.entries.reversed().associate { it.toPair() }

        val encoded = StringMapCodec.encode(forward)

        assertEquals(encoded, StringMapCodec.encode(reverse))
        assertEquals(forward, StringMapCodec.decode(encoded))
        assertEquals(encoded, StringMapCodec.encode(StringMapCodec.decode(encoded)))
    }
}
