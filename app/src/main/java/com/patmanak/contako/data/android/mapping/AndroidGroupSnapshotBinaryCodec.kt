package com.patmanak.contako.data.android.mapping

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class AndroidGroupSnapshotCodecFailure {
    BOUND_EXCEEDED,
    TRUNCATED,
    MALFORMED_FORMAT,
    UNSUPPORTED_VERSION,
    UNKNOWN_ENUM,
    DUPLICATE_ENTRY,
}

/** Category-only diagnostics: titles, stable identities, accounts, and locators remain redacted. */
internal class AndroidGroupSnapshotCodecException(
    val category: AndroidGroupSnapshotCodecFailure,
) : IllegalArgumentException("Android group snapshot codec failure: ${category.name}")

/** Versioned deterministic binary codec for [AndroidGroupSnapshot]. */
internal object AndroidGroupSnapshotBinaryCodec {
    fun encode(snapshot: AndroidGroupSnapshot): ByteArray {
        val writer = AndroidGroupSnapshotBinaryWriter(MAX_TOTAL_BYTES)
        writer.writeInt(MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeString(snapshot.accountId, MAX_ID_BYTES)
        writer.writeString(snapshot.canonicalGroupId, MAX_ID_BYTES)
        writer.writeString(snapshot.title, MAX_TITLE_BYTES)
        writer.writeBoolean(snapshot.isVisible)
        return writer.toByteArray()
    }

    fun decode(encoded: ByteArray): AndroidGroupSnapshot {
        if (encoded.size > MAX_TOTAL_BYTES) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED)
        val reader = AndroidGroupSnapshotBinaryReader(encoded)
        if (reader.readInt() != MAGIC) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        if (reader.readInt() != FORMAT_VERSION) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.UNSUPPORTED_VERSION)
        }
        val accountId = reader.readString(MAX_ID_BYTES)
        val canonicalGroupId = reader.readString(MAX_ID_BYTES)
        val title = reader.readString(MAX_TITLE_BYTES)
        val isVisible = reader.readBoolean()
        if (!reader.isExhausted()) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        return try {
            AndroidGroupSnapshot(accountId, canonicalGroupId, title, isVisible)
        } catch (_: IllegalArgumentException) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        }
    }

    fun integrityFingerprint(encoded: ByteArray): AndroidSnapshotIntegrityFingerprint =
        encodedIntegrityFingerprint("contako-android-group-baseline-v1", encoded, MAX_TOTAL_BYTES)

    private const val MAGIC = 0x4347534E // CGSN
    private const val FORMAT_VERSION = 1
    private const val MAX_ID_BYTES = 4_096
    private const val MAX_TITLE_BYTES = 16 * 1_024
    private const val MAX_TOTAL_BYTES = 32 * 1_024
}

internal fun encodedIntegrityFingerprint(
    domain: String,
    encoded: ByteArray,
    maximumBytes: Int,
): AndroidSnapshotIntegrityFingerprint {
    if (encoded.size > maximumBytes) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED)
    val digest = MessageDigest.getInstance("SHA-256")
    val domainBytes = domain.toByteArray(StandardCharsets.UTF_8)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(domainBytes.size).array())
    digest.update(domainBytes)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(encoded.size).array())
    digest.update(encoded)
    return AndroidSnapshotIntegrityFingerprint(
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
    )
}

internal class AndroidGroupSnapshotBinaryWriter(
    private val maximumBytes: Int,
) {
    private val output = ByteArrayOutputStream()

    fun writeInt(value: Int) = writeBytes(
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        ),
    )

    fun writeLong(value: Long) {
        if (value <= 0) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        writeBytes(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    fun writeBoolean(value: Boolean) = writeBytes(byteArrayOf(if (value) 1 else 0))

    fun writeString(value: String, maximumStringBytes: Int) {
        val bytes = encodeSnapshotUtf8(
            value, maximumStringBytes,
            malformed = { groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) },
            boundExceeded = { groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED) },
        )
        writeInt(bytes.size)
        writeBytes(bytes)
    }

    fun writeNullableString(value: String?, maximumStringBytes: Int) {
        if (value == null) {
            writeInt(NULL_LENGTH)
        } else {
            writeString(value, maximumStringBytes)
        }
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun writeBytes(bytes: ByteArray) {
        if (bytes.size > maximumBytes - output.size()) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED)
        }
        output.write(bytes)
    }

    private companion object {
        const val NULL_LENGTH = -1
    }
}

internal class AndroidGroupSnapshotBinaryReader(
    private val input: ByteArray,
) {
    private var position = 0

    fun readInt(): Int {
        requireRemaining(Int.SIZE_BYTES)
        return ((input[position++].toInt() and 0xff) shl 24) or
            ((input[position++].toInt() and 0xff) shl 16) or
            ((input[position++].toInt() and 0xff) shl 8) or
            (input[position++].toInt() and 0xff)
    }

    fun readLong(): Long {
        requireRemaining(Long.SIZE_BYTES)
        var value = 0L
        repeat(Long.SIZE_BYTES) { value = (value shl 8) or (input[position++].toLong() and 0xffL) }
        return value.takeIf { it > 0 }
            ?: groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
    }

    fun readBoolean(): Boolean {
        requireRemaining(1)
        return when (input[position++].toInt() and 0xff) {
            0 -> false
            1 -> true
            else -> groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        }
    }

    fun readString(maximumStringBytes: Int): String {
        val length = readInt()
        if (length < 0) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        return readUtf8(length, maximumStringBytes)
    }

    fun readNullableString(maximumStringBytes: Int): String? {
        val length = readInt()
        if (length == NULL_LENGTH) return null
        if (length < 0) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        return readUtf8(length, maximumStringBytes)
    }

    fun readCount(maximum: Int): Int {
        val count = readInt()
        if (count < 0) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        if (count > maximum) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED)
        return count
    }

    fun <E : Enum<E>> readEnum(values: List<E>, maximumStringBytes: Int = 64): E {
        val name = readString(maximumStringBytes)
        return values.firstOrNull { it.name == name }
            ?: groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.UNKNOWN_ENUM)
    }

    fun isExhausted(): Boolean = position == input.size

    private fun readUtf8(length: Int, maximumStringBytes: Int): String {
        if (length > maximumStringBytes) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED)
        requireRemaining(length)
        val value = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input, position, length))
                .toString()
        } catch (_: Exception) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        }
        position += length
        return value
    }

    private fun requireRemaining(bytes: Int) {
        if (bytes < 0 || position > input.size - bytes) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.TRUNCATED)
        }
    }

    private companion object {
        const val NULL_LENGTH = -1
    }
}

internal fun groupSnapshotCodecFail(category: AndroidGroupSnapshotCodecFailure): Nothing =
    throw AndroidGroupSnapshotCodecException(category)
