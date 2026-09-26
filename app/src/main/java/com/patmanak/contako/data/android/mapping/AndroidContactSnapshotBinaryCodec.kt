package com.patmanak.contako.data.android.mapping

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal enum class AndroidContactSnapshotCodecFailure {
    BOUND_EXCEEDED,
    TRUNCATED,
    MALFORMED_FORMAT,
    UNSUPPORTED_VERSION,
    UNKNOWN_ENUM,
    DUPLICATE_ENTRY,
}

/** Category-only diagnostics: snapshots, values, and stable identifiers are never disclosed. */
internal class AndroidContactSnapshotCodecException(
    val category: AndroidContactSnapshotCodecFailure,
) : IllegalArgumentException("Android contact snapshot codec failure: ${category.name}")

/**
 * Deterministic, provider-neutral persistence format for an [AndroidContactSnapshot].
 *
 * Every textual value, including enum names, is encoded as a signed 32-bit byte length followed
 * by strict UTF-8. Nullable strings use length `-1`. Photos are never embedded: the format carries
 * only [AndroidContactRow.binaryReference].
 */
internal object AndroidContactSnapshotBinaryCodec {
    fun encode(snapshot: AndroidContactSnapshot): ByteArray {
        validateSnapshot(snapshot)
        val writer = BoundedWriter(MAX_TOTAL_BYTES)
        writer.writeInt(MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeString(snapshot.canonicalContactId)
        writer.writeInt(snapshot.rows.size)
        snapshot.rows.forEach { row ->
            writer.writeString(row.identity.canonicalValueId)
            writer.writeNullableLong(row.identity.providerRowId)
            writer.writeString(row.kind.name)
            writer.writeString(row.value)
            writer.writeString(row.semanticType.name)
            writer.writeNullableString(row.customLabel)
            writer.writeInt(row.order)
            writer.writeBoolean(row.isPrimary)
            writer.writeBoolean(row.isSuperPrimary)
            writer.writeInt(row.components.size)
            row.components.entries.sortedBy { it.key.name }.forEach { (component, value) ->
                writer.writeString(component.name)
                writer.writeString(value)
            }
            writer.writeInt(row.linkedCanonicalValueIds.size)
            row.linkedCanonicalValueIds.entries.sortedBy { it.key.name }.forEach { (role, value) ->
                writer.writeString(role.name)
                writer.writeString(value)
            }
            writer.writeNullableString(
                row.binaryReference,
                if (row.kind == AndroidRowKind.PHOTO) MAX_PHOTO_REFERENCE_BYTES else MAX_STRING_BYTES,
            )
        }
        return writer.toByteArray()
    }

    fun decode(encoded: ByteArray): AndroidContactSnapshot {
        if (encoded.size > MAX_TOTAL_BYTES) fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED)
        val reader = BoundedReader(encoded)
        if (reader.readInt() != MAGIC) fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
        if (reader.readInt() != FORMAT_VERSION) fail(AndroidContactSnapshotCodecFailure.UNSUPPORTED_VERSION)
        val canonicalContactId = reader.readString()
        val rowCount = reader.readCount(MAX_ROWS)
        val rows = ArrayList<AndroidContactRow>(rowCount)
        val primaryIdentities = mutableSetOf<String>()
        val linkedIdentities = mutableSetOf<String>()
        repeat(rowCount) {
            val canonicalValueId = reader.readString()
            if (!primaryIdentities.add(canonicalValueId) || canonicalValueId in linkedIdentities) {
                fail(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY)
            }
            val providerRowId = reader.readNullableLong()
            val kind = reader.readEnum(AndroidRowKind.entries)
            val value = reader.readString()
            val semanticType = reader.readEnum(AndroidSemanticType.entries)
            val customLabel = reader.readNullableString()
            val order = reader.readInt()
            val isPrimary = reader.readBoolean()
            val isSuperPrimary = reader.readBoolean()
            val components = reader.readEnumMap(AndroidComponent.entries)
            val linked = reader.readEnumMap(AndroidLinkedValueRole.entries)
            linked.values.forEach { linkedId ->
                if (linkedId in primaryIdentities || !linkedIdentities.add(linkedId)) {
                    fail(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY)
                }
            }
            val binaryReference = reader.readNullableString(
                if (kind == AndroidRowKind.PHOTO) MAX_PHOTO_REFERENCE_BYTES else MAX_STRING_BYTES,
            )
            val row = try {
                AndroidContactRow(
                    identity = AndroidValueIdentity(canonicalValueId, providerRowId),
                    kind = kind,
                    value = value,
                    semanticType = semanticType,
                    customLabel = customLabel,
                    order = order,
                    isPrimary = isPrimary,
                    isSuperPrimary = isSuperPrimary,
                    components = components,
                    linkedCanonicalValueIds = linked,
                    binaryReference = binaryReference,
                )
            } catch (_: IllegalArgumentException) {
                fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            }
            rows += row
        }
        if (!reader.isExhausted()) fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
        return try {
            AndroidContactSnapshot(canonicalContactId, rows)
        } catch (_: IllegalArgumentException) {
            fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
        }
    }

    private fun validateSnapshot(snapshot: AndroidContactSnapshot) {
        validateString(snapshot.canonicalContactId)
        if (snapshot.rows.size > MAX_ROWS) fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED)
        val primaryIdentities = mutableSetOf<String>()
        val linkedIdentities = mutableSetOf<String>()
        snapshot.rows.forEach { row ->
            validateString(row.identity.canonicalValueId)
            if (!primaryIdentities.add(row.identity.canonicalValueId) ||
                row.identity.canonicalValueId in linkedIdentities
            ) {
                fail(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY)
            }
            if (row.identity.providerRowId != null && row.identity.providerRowId <= 0) {
                fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            }
            validateString(row.value)
            row.customLabel?.let(::validateString)
            if (row.order < 0) fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            if (row.components.size > AndroidComponent.entries.size ||
                row.linkedCanonicalValueIds.size > AndroidLinkedValueRole.entries.size
            ) {
                fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED)
            }
            row.components.values.forEach(::validateString)
            row.linkedCanonicalValueIds.values.forEach { linkedId ->
                validateString(linkedId)
                if (linkedId in primaryIdentities || !linkedIdentities.add(linkedId)) {
                    fail(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY)
                }
            }
            row.binaryReference?.let { reference ->
                validateString(
                    reference,
                    if (row.kind == AndroidRowKind.PHOTO) MAX_PHOTO_REFERENCE_BYTES else MAX_STRING_BYTES,
                )
            }
        }
        if (primaryIdentities.any { it in linkedIdentities }) {
            fail(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY)
        }
    }

    private fun validateString(value: String, maximumBytes: Int = MAX_STRING_BYTES) {
        if (value.toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
            fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED)
        }
    }

    private class BoundedWriter(private val maximumBytes: Int) {
        private val output = ByteArrayOutputStream()

        fun writeInt(value: Int) = writeBytes(
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )

        fun writeNullableLong(value: Long?) {
            writeBoolean(value != null)
            if (value != null) {
                writeBytes(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
            }
        }

        fun writeBoolean(value: Boolean) = writeBytes(byteArrayOf(if (value) 1 else 0))

        fun writeString(value: String) {
            val bytes = strictUtf8(value, MAX_STRING_BYTES)
            writeInt(bytes.size)
            writeBytes(bytes)
        }

        fun writeNullableString(value: String?, maximumBytes: Int = MAX_STRING_BYTES) {
            if (value == null) {
                writeInt(NULL_LENGTH)
            } else {
                val bytes = strictUtf8(value, maximumBytes)
                writeInt(bytes.size)
                writeBytes(bytes)
            }
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun strictUtf8(value: String, maximumBytes: Int): ByteArray = encodeSnapshotUtf8(
            value, maximumBytes,
            malformed = { fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT) },
            boundExceeded = { fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED) },
        )

        private fun writeBytes(bytes: ByteArray) {
            if (bytes.size > maximumBytes - output.size()) {
                fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED)
            }
            output.write(bytes)
        }
    }

    private class BoundedReader(private val input: ByteArray) {
        private var position = 0

        fun readInt(): Int {
            requireRemaining(Int.SIZE_BYTES)
            return ((input[position++].toInt() and 0xff) shl 24) or
                ((input[position++].toInt() and 0xff) shl 16) or
                ((input[position++].toInt() and 0xff) shl 8) or
                (input[position++].toInt() and 0xff)
        }

        fun readNullableLong(): Long? = when (readBooleanTag()) {
            false -> null
            true -> {
                requireRemaining(Long.SIZE_BYTES)
                var value = 0L
                repeat(Long.SIZE_BYTES) { value = (value shl 8) or (input[position++].toLong() and 0xffL) }
                value.takeIf { it > 0 } ?: fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            }
        }

        fun readBoolean(): Boolean = readBooleanTag()

        fun readString(): String {
            val length = readInt()
            if (length < 0) fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            return readUtf8(length)
        }

        fun readNullableString(maximumBytes: Int = MAX_STRING_BYTES): String? {
            val length = readInt()
            if (length == NULL_LENGTH) return null
            if (length < 0) fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            return readUtf8(length, maximumBytes)
        }

        fun readCount(maximum: Int): Int {
            val count = readInt()
            if (count < 0) fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            if (count > maximum) fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED)
            return count
        }

        fun <E : Enum<E>> readEnum(values: List<E>): E {
            val name = readString()
            return values.firstOrNull { it.name == name }
                ?: fail(AndroidContactSnapshotCodecFailure.UNKNOWN_ENUM)
        }

        fun <E : Enum<E>> readEnumMap(values: List<E>): Map<E, String> {
            val count = readCount(values.size)
            val result = linkedMapOf<E, String>()
            repeat(count) {
                val key = readEnum(values)
                if (key in result) fail(AndroidContactSnapshotCodecFailure.DUPLICATE_ENTRY)
                result[key] = readString()
            }
            return result
        }

        fun isExhausted(): Boolean = position == input.size

        private fun readBooleanTag(): Boolean {
            requireRemaining(1)
            return when (input[position++].toInt() and 0xff) {
                0 -> false
                1 -> true
                else -> fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            }
        }

        private fun readUtf8(length: Int, maximumBytes: Int = MAX_STRING_BYTES): String {
            if (length > maximumBytes) fail(AndroidContactSnapshotCodecFailure.BOUND_EXCEEDED)
            requireRemaining(length)
            val value = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input, position, length))
                    .toString()
            } catch (_: Exception) {
                fail(AndroidContactSnapshotCodecFailure.MALFORMED_FORMAT)
            }
            position += length
            return value
        }

        private fun requireRemaining(bytes: Int) {
            if (bytes < 0 || position > input.size - bytes) {
                fail(AndroidContactSnapshotCodecFailure.TRUNCATED)
            }
        }
    }

}

private const val MAGIC = 0x43544153 // CTAS
private const val FORMAT_VERSION = 1
private const val NULL_LENGTH = -1
private const val MAX_ROWS = 128
private const val MAX_STRING_BYTES = 16 * 1_024
// Proton contact photos are accepted up to 10 MiB of decoded bytes. Their data-URI/base64
// references need roughly 13.4 MiB, while all ordinary strings retain the strict 16 KiB cap.
private const val MAX_PHOTO_REFERENCE_BYTES = 14 * 1_024 * 1_024
private const val MAX_TOTAL_BYTES = 16 * 1_024 * 1_024

private fun fail(category: AndroidContactSnapshotCodecFailure): Nothing =
    throw AndroidContactSnapshotCodecException(category)
