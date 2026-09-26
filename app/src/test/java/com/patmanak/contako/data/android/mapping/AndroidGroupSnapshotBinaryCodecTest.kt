package com.patmanak.contako.data.android.mapping

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGroupSnapshotBinaryCodecTest {
    @Test
    fun malformedUtf16KeepsTheGroupClosedFailureCategory() {
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupSnapshotBinaryWriter(128).writeString("\uD800", 64)
        }
    }

    @Test
    fun `round trip is deterministic and preserves semantic group fields`() {
        val snapshot = AndroidGroupSnapshot(
            accountId = "private-account",
            canonicalGroupId = "private-group",
            title = "Équipe privée",
            isVisible = false,
        )

        val encoded = AndroidGroupSnapshotBinaryCodec.encode(snapshot)

        assertEquals(snapshot, AndroidGroupSnapshotBinaryCodec.decode(encoded))
        assertArrayEquals(encoded, AndroidGroupSnapshotBinaryCodec.encode(snapshot))
        assertEquals(
            AndroidGroupSnapshotBinaryCodec.integrityFingerprint(encoded),
            AndroidGroupSnapshotBinaryCodec.integrityFingerprint(AndroidGroupSnapshotBinaryCodec.encode(snapshot)),
        )
        assertFalse(snapshot.toString().contains("Équipe privée"))
        assertFalse(snapshot.semanticFingerprint().toString().contains("private-group"))
    }

    @Test
    fun `semantic fingerprint changes for title visibility account and identity`() {
        val baseline = AndroidGroupSnapshot("account", "group", "Title", true)

        assertNotEquals(baseline.semanticFingerprint(), baseline.copy(accountId = "other-account").semanticFingerprint())
        assertNotEquals(baseline.semanticFingerprint(), baseline.copy(canonicalGroupId = "other-group").semanticFingerprint())
        assertNotEquals(baseline.semanticFingerprint(), baseline.copy(title = "Other").semanticFingerprint())
        assertNotEquals(baseline.semanticFingerprint(), baseline.copy(isVisible = false).semanticFingerprint())
    }

    @Test
    fun `every strict prefix truncation is rejected with redacted category diagnostics`() {
        val encoded = AndroidGroupSnapshotBinaryCodec.encode(
            AndroidGroupSnapshot("private-account", "private-group", "private-title", true),
        )

        for (size in 0 until encoded.size) {
            assertFailure(AndroidGroupSnapshotCodecFailure.TRUNCATED) {
                AndroidGroupSnapshotBinaryCodec.decode(encoded.copyOf(size))
            }
        }
    }

    @Test
    fun `magic version trailing bytes malformed utf8 and boolean are rejected`() {
        val encoded = AndroidGroupSnapshotBinaryCodec.encode(AndroidGroupSnapshot("account", "group", "private-title", true))
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupSnapshotBinaryCodec.decode(encoded.copyOf().also { it[0] = 0 })
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.UNSUPPORTED_VERSION) {
            AndroidGroupSnapshotBinaryCodec.decode(encoded.copyOf().also { writeInt(it, 4, 2) })
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupSnapshotBinaryCodec.decode(encoded + byteArrayOf(0))
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupSnapshotBinaryCodec.decode(encoded.copyOf().also { bytes ->
                bytes[indexOf(bytes, "private-title")] = 0xc3.toByte()
            })
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupSnapshotBinaryCodec.decode(encoded.copyOf().also { it[it.lastIndex] = 2 })
        }
    }

    @Test
    fun `string and total bounds plus invalid unicode are rejected before unsafe allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGroupSnapshot("x".repeat(4_097), "group", "title", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGroupSnapshot("account", "group", "x".repeat(16 * 1_024 + 1), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidGroupSnapshot("account", "group", "\uD800", true)
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidGroupSnapshotBinaryCodec.decode(ByteArray(32 * 1_024 + 1))
        }
        val encoded = AndroidGroupSnapshotBinaryCodec.encode(AndroidGroupSnapshot("account", "group", "title", true))
        assertFailure(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidGroupSnapshotBinaryCodec.decode(encoded.copyOf().also { writeInt(it, 8, 4_097) })
        }
    }

    private fun assertFailure(expected: AndroidGroupSnapshotCodecFailure, action: () -> Unit) {
        val error = assertThrows(AndroidGroupSnapshotCodecException::class.java) { action() }
        assertEquals(expected, error.category)
        assertTrue(error.message.orEmpty().contains(expected.name))
        assertFalse(error.message.orEmpty().contains("private"))
    }

    private fun indexOf(bytes: ByteArray, text: String): Int {
        val target = text.toByteArray(StandardCharsets.UTF_8)
        return (0..bytes.size - target.size).first { offset ->
            target.indices.all { index -> bytes[offset + index] == target[index] }
        }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
