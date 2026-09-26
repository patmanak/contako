package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.AndroidProjectionFingerprint
import com.patmanak.contako.domain.model.CanonicalContact
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidContactSnapshotBinaryCodecTest {
    @Test
    fun malformedUtf16IsRejectedInsteadOfSilentlyReplacingContactFields() {
        for (invalid in listOf("\uD800", "\uDC00")) {
            assertFailure(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT) {
                AndroidContactSnapshotBinaryCodec.encode(AndroidContactSnapshot("contact", listOf(row(value = invalid))))
            }
            assertFailure(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT) {
                AndroidContactSnapshotBinaryCodec.encode(AndroidContactSnapshot("contact",
                    listOf(row().copy(binaryReference = invalid))))
            }
        }
    }

    @Test
    fun `round trip preserves every field enum map and nullable shape`() {
        val rowCount = maxOf(AndroidRowKind.entries.size, AndroidSemanticType.entries.size)
        val rows = List(rowCount) { index ->
            val semantic = AndroidSemanticType.entries[index % AndroidSemanticType.entries.size]
            AndroidContactRow(
                identity = AndroidValueIdentity(
                    canonicalValueId = "primary-$index",
                    providerRowId = if (index % 2 == 0) index.toLong() + 1 else null,
                ),
                kind = AndroidRowKind.entries[index % AndroidRowKind.entries.size],
                value = "value-$index-😀",
                semanticType = semantic,
                customLabel = if (semantic == AndroidSemanticType.CUSTOM) "custom-$index" else null,
                order = index * 3,
                isPrimary = index % 2 == 0,
                isSuperPrimary = index % 3 == 0,
                components = AndroidComponent.entries.associateWith { component ->
                    "component-$index-${component.name}"
                },
                linkedCanonicalValueIds = AndroidLinkedValueRole.entries.associateWith { role ->
                    "linked-$index-${role.name}"
                },
                binaryReference = if (index % 2 == 0) "private://photo/$index" else null,
            )
        }
        val snapshot = AndroidContactSnapshot("contact-😀", rows)

        val encoded = AndroidContactSnapshotBinaryCodec.encode(snapshot)

        assertEquals(snapshot, AndroidContactSnapshotBinaryCodec.decode(encoded))
        assertArrayEquals(encoded, AndroidContactSnapshotBinaryCodec.encode(snapshot))
        assertFalse(String(encoded, StandardCharsets.ISO_8859_1).contains("PHOTO_BINARY_PAYLOAD"))
    }

    @Test
    fun `map insertion order does not affect deterministic encoding`() {
        val forward = row(
            components = linkedMapOf(
                AndroidComponent.PREFIX to "prefix",
                AndroidComponent.SUFFIX to "suffix",
            ),
            linked = linkedMapOf(
                AndroidLinkedValueRole.TITLE to "title-id",
                AndroidLinkedValueRole.ROLE to "role-id",
            ),
        )
        val reverse = row(
            components = linkedMapOf(
                AndroidComponent.SUFFIX to "suffix",
                AndroidComponent.PREFIX to "prefix",
            ),
            linked = linkedMapOf(
                AndroidLinkedValueRole.ROLE to "role-id",
                AndroidLinkedValueRole.TITLE to "title-id",
            ),
        )

        assertArrayEquals(
            AndroidContactSnapshotBinaryCodec.encode(AndroidContactSnapshot("contact", listOf(forward))),
            AndroidContactSnapshotBinaryCodec.encode(AndroidContactSnapshot("contact", listOf(reverse))),
        )
    }

    @Test
    fun `every strict prefix truncation is rejected without payload diagnostics`() {
        val encoded = AndroidContactSnapshotBinaryCodec.encode(
            AndroidContactSnapshot("private-contact", listOf(row(value = "private-value-😀"))),
        )

        for (size in 0 until encoded.size) {
            assertFailure(AndroidContactSnapshotCodecFailure.TRUNCATED) {
                AndroidContactSnapshotBinaryCodec.decode(encoded.copyOf(size))
            }
        }
    }

    @Test
    fun `malformed magic version trailing bytes and utf8 are categorized`() {
        val encoded = AndroidContactSnapshotBinaryCodec.encode(
            AndroidContactSnapshot("id", listOf(row())),
        )
        assertFailure(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidContactSnapshotBinaryCodec.decode(encoded.copyOf().also { it[0] = 0 })
        }
        assertFailure(AndroidContactSnapshotCodecFailure.UNSUPPORTED_VERSION) {
            AndroidContactSnapshotBinaryCodec.decode(encoded.copyOf().also { writeInt(it, 4, 2) })
        }
        assertFailure(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidContactSnapshotBinaryCodec.decode(encoded + byteArrayOf(0))
        }
        assertFailure(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidContactSnapshotBinaryCodec.decode(encoded.copyOf().also { it[12] = 0xc3.toByte() })
        }
    }

    @Test
    fun `unknown enum duplicate row identity and duplicate map key are rejected`() {
        val unknownKind = AndroidContactSnapshotBinaryCodec.encode(
            AndroidContactSnapshot("contact", listOf(row(kind = AndroidRowKind.EMAIL))),
        ).replaceAsciiOnce("EMAIL", "ZZZZZ")
        assertFailure(AndroidContactSnapshotCodecFailure.UNKNOWN_ENUM) {
            AndroidContactSnapshotBinaryCodec.decode(unknownKind)
        }

        val duplicateRows = AndroidContactSnapshotBinaryCodec.encode(
            AndroidContactSnapshot(
                "contact",
                listOf(row(id = "row-a"), row(id = "row-b", value = "second")),
            ),
        ).replaceAsciiOnce("row-b", "row-a")
        assertFailure(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY) {
            AndroidContactSnapshotBinaryCodec.decode(duplicateRows)
        }

        val duplicateComponent = AndroidContactSnapshotBinaryCodec.encode(
            AndroidContactSnapshot(
                "contact",
                listOf(
                    row(
                        components = linkedMapOf(
                            AndroidComponent.PREFIX to "a",
                            AndroidComponent.SUFFIX to "b",
                        ),
                    ),
                ),
            ),
        ).replaceAsciiOnce("SUFFIX", "PREFIX")
        assertFailure(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY) {
            AndroidContactSnapshotBinaryCodec.decode(duplicateComponent)
        }
    }

    @Test
    fun `row string and total limits are enforced before unbounded allocation`() {
        assertFailure(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidContactSnapshotBinaryCodec.encode(
                AndroidContactSnapshot("é".repeat(8_193), emptyList()),
            )
        }
        assertFailure(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidContactSnapshotBinaryCodec.encode(
                AndroidContactSnapshot(
                    "contact",
                    List(129) { index -> row(id = "row-$index") },
                ),
            )
        }
        val maximumPhotoReference = "data:image/jpeg;base64," + "A".repeat(9 * 1_024 * 1_024)
        assertFailure(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidContactSnapshotBinaryCodec.encode(
                AndroidContactSnapshot(
                    "contact",
                    List(2) { index ->
                        AndroidContactRow(
                            identity = AndroidValueIdentity("photo-$index", index.toLong() + 1),
                            kind = AndroidRowKind.PHOTO,
                            binaryReference = maximumPhotoReference,
                        )
                    },
                ),
            )
        }
        assertFailure(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidContactSnapshotBinaryCodec.decode(ByteArray(16 * 1_024 * 1_024 + 1))
        }

        val valid = AndroidContactSnapshotBinaryCodec.encode(AndroidContactSnapshot("id", emptyList()))
        assertFailure(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidContactSnapshotBinaryCodec.decode(valid.copyOf().also { writeInt(it, 8, 16 * 1_024 + 1) })
        }
    }

    @Test
    fun `photo data uri larger than ordinary text limit round trips within the photo bound`() {
        val reference = "data:image/jpeg;base64," + "A".repeat(32 * 1_024)
        val snapshot = AndroidContactSnapshot(
            "contact",
            listOf(
                AndroidContactRow(
                    identity = AndroidValueIdentity("photo", 42),
                    kind = AndroidRowKind.PHOTO,
                    value = "",
                    binaryReference = reference,
                ),
            ),
        )

        val encoded = AndroidContactSnapshotBinaryCodec.encode(snapshot)

        assertEquals(snapshot, AndroidContactSnapshotBinaryCodec.decode(encoded))
    }

    @Test
    fun `provider neutral models redact contact values and stable identities`() {
        val privateRow = row(id = "private-value-id", value = "Private Person")
        val snapshot = AndroidContactSnapshot("private-contact-id", listOf(privateRow))
        val delta = AndroidCanonicalDelta(
            CanonicalContact("private-account", "private-contact-id", displayName = "Private Person"),
            AndroidProjectionFingerprint("a".repeat(64)),
            AndroidProjectionFingerprint("b".repeat(64)),
            setOf("private-value-id"),
        )

        listOf(privateRow.identity, privateRow, snapshot, delta).forEach { model ->
            val diagnostic = model.toString()
            assertFalse(diagnostic.contains("Private Person"))
            assertFalse(diagnostic.contains("private-contact-id"))
            assertFalse(diagnostic.contains("private-value-id"))
        }
    }

    private fun row(
        id: String = "value-id",
        kind: AndroidRowKind = AndroidRowKind.STRUCTURED_NAME,
        value: String = "value",
        components: Map<AndroidComponent, String> = emptyMap(),
        linked: Map<AndroidLinkedValueRole, String> = emptyMap(),
    ) = AndroidContactRow(
        identity = AndroidValueIdentity(id, 42),
        kind = kind,
        value = value,
        components = components,
        linkedCanonicalValueIds = linked,
        binaryReference = "private://photo/reference",
    )

    private fun assertFailure(
        expected: AndroidContactSnapshotCodecFailure,
        action: () -> Unit,
    ) {
        val failure = assertThrows(AndroidContactSnapshotCodecException::class.java) { action() }
        assertEquals(expected, failure.category)
        assertFalse(failure.message.orEmpty().contains("private-contact"))
        assertFalse(failure.message.orEmpty().contains("private-value"))
        assertFalse(failure.message.orEmpty().contains("value-id"))
    }

    private fun ByteArray.replaceAsciiOnce(before: String, after: String): ByteArray {
        require(before.length == after.length)
        val source = before.toByteArray(StandardCharsets.US_ASCII)
        val replacement = after.toByteArray(StandardCharsets.US_ASCII)
        val result = copyOf()
        val indices = (0..result.size - source.size).filter { offset ->
            source.indices.all { index -> result[offset + index] == source[index] }
        }
        require(indices.size == 1) { "Expected exactly one fixture token" }
        replacement.copyInto(result, indices.single())
        return result
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
