package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.util.Base64
import me.proton.core.crypto.common.pgp.PGPCrypto

fun interface PublicKeyMaterialInspector {
    fun isValidPublicPgpKey(armored: String): Boolean
}

/** Delegates OpenPGP parsing/classification to the maintained Proton primitive. */
internal class ProtonPgpPublicKeyMaterialInspector(
    private val pgpCrypto: PGPCrypto,
) : PublicKeyMaterialInspector {
    override fun isValidPublicPgpKey(armored: String): Boolean = try {
        pgpCrypto.isValidKey(armored) && pgpCrypto.isPublicKey(armored) && !pgpCrypto.isPrivateKey(armored)
    } catch (_: Exception) {
        false
    }
}

/** Fail-closed D-067 validation for newly created or edited values. */
class ProtonContactFieldValidator(
    private val publicKeyInspector: PublicKeyMaterialInspector? = null,
) {
    fun validate(
        contact: CanonicalContact,
        unchangedImportedPreservationKeys: Set<String> = emptySet(),
    ) {
        listOf(contact.firstName, contact.lastName, contact.displayName).forEach(::requireSafeText)
        require(contact.values.size <= MAX_VALUES)
        require(contact.valuesOf(ContactValueKind.BIRTHDAY).size <= 1)
        require(contact.valuesOf(ContactValueKind.ANNIVERSARY).size <= 1)
        require(contact.valuesOf(ContactValueKind.GENDER).size <= 1)
        contact.values
            .filterNot { value -> value.preservationKey in unchangedImportedPreservationKeys }
            .forEach { value ->
                try { validateValue(value) } catch (_: IllegalArgumentException) {
                    throw ProtonContactValueValidationException(value.kind)
                }
            }
    }

    internal fun validateSerialized(prepared: ProtonPreparedVCard) {
        val sizes = listOf(prepared.encryptedPrivate, prepared.signed, prepared.clear)
            .map { it.toByteArray(StandardCharsets.UTF_8).size }
        require(sizes.all { it <= MAX_GENERATED_CARD_BYTES })
        require(sizes.sumOf(Int::toLong) <= MAX_GENERATED_CARD_BYTES)
    }

    /** Returns a value-free message key while retaining the fail-closed wire validator as the oracle. */
    fun editableValueError(value: ContactValue): EditableValueError? = try {
        validateValue(value)
        null
    } catch (_: IllegalArgumentException) {
        when (value.kind) {
            ContactValueKind.PUBLIC_KEY -> EditableValueError.PUBLIC_KEY
            ContactValueKind.LANGUAGE -> EditableValueError.LANGUAGE
            ContactValueKind.TIME_ZONE -> EditableValueError.TIME_ZONE
            ContactValueKind.GENDER -> EditableValueError.GENDER
            ContactValueKind.PHOTO, ContactValueKind.LOGO -> EditableValueError.IMAGE
            ContactValueKind.URL -> EditableValueError.URL
            ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY ->
                EditableValueError.DATE
            else -> EditableValueError.GENERIC
        }
    }

    private fun validateValue(value: ContactValue) {
        requireSafeText(value.value)
        value.label?.let(::requireSafeText)
        value.components.values.forEach(::requireSafeText)
        when (value.kind) {
            ContactValueKind.PHOTO, ContactValueKind.LOGO -> validateImage(value.value)
            ContactValueKind.PUBLIC_KEY -> validatePublicKey(value.value)
            else -> require(value.value.utf8Size() <= MAX_SCALAR_BYTES)
        }
        value.components.values.forEach { require(it.utf8Size() <= MAX_SCALAR_BYTES) }
        value.label?.let { require(it.utf8Size() <= MAX_SCALAR_BYTES) }
        when (value.kind) {
            ContactValueKind.URL -> validateSafeUri(value.value)
            ContactValueKind.BIRTHDAY, ContactValueKind.ANNIVERSARY -> validateStandardDate(value.value)
            ContactValueKind.LANGUAGE -> require(LANGUAGE_TAG.matches(value.value))
            ContactValueKind.TIME_ZONE -> validateTimeZone(value.value)
            ContactValueKind.GENDER -> require(GENDER.matches(value.value))
            else -> Unit
        }
    }

    private fun requireSafeText(value: String) {
        require(value.none { it in BIDI_CONTROL_CHARACTERS })
    }

    private fun validateTimeZone(raw: String) {
        require(raw.isNotBlank())
        require(raw.none(Char::isISOControl))
        when {
            UTC_OFFSET.matches(raw) -> Unit
            raw.startsWith("https://", ignoreCase = true) -> validateSafeUri(raw)
            else -> require(TIME_ZONE_TEXT.matches(raw))
        }
    }

    private fun validateStandardDate(raw: String) {
        try {
            when {
                FULL_DATE.matches(raw) -> {
                    val digits = raw.replace("-", "")
                    val year = digits.substring(0, 4).toInt()
                    require(year in 1..9999)
                    LocalDate.of(year, digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
                }
                YEARLESS_DATE.matches(raw) -> {
                    val digits = raw.removePrefix("--").replace("-", "")
                    MonthDay.of(digits.substring(0, 2).toInt(), digits.substring(2, 4).toInt())
                }
                else -> throw IllegalArgumentException()
            }
        } catch (_: DateTimeException) {
            throw IllegalArgumentException()
        }
    }

    private fun validatePublicKey(raw: String) {
        require(raw.isNotBlank())
        require(raw.utf8Size() <= MAX_SCALAR_BYTES || raw.startsWith("data:", ignoreCase = true))
        require(PRIVATE_KEY_MARKERS.none { marker -> raw.contains(marker, ignoreCase = true) })
        when {
            raw.startsWith("https://", ignoreCase = true) -> validateSafeUri(raw)
            raw.startsWith("data:", ignoreCase = true) -> validateKeyDataUri(raw)
            raw.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----") -> {
                require(raw.utf8Size() <= MAX_DECODED_KEY_BYTES)
                require(publicKeyInspector?.isValidPublicPgpKey(raw) == true)
            }
            raw.contains("-----BEGIN CERTIFICATE-----") -> validateCertificate(raw)
            else -> throw IllegalArgumentException()
        }
    }

    private fun validateKeyDataUri(raw: String) {
        require(raw.utf8Size() <= MAX_DATA_URI_BYTES)
        val comma = raw.indexOf(',')
        require(comma > 5)
        val header = raw.substring(5, comma).lowercase()
        require(header.endsWith(";base64"))
        val decoded = decodeBounded(raw.substring(comma + 1), MAX_DECODED_KEY_BYTES)
        try {
            when (header.removeSuffix(";base64")) {
                "application/pgp-keys" -> {
                    val armored = decoded.toString(StandardCharsets.UTF_8)
                    require(PRIVATE_KEY_MARKERS.none { armored.contains(it, ignoreCase = true) })
                    require(publicKeyInspector?.isValidPublicPgpKey(armored) == true)
                }
                "application/pkix-cert" -> parseCertificate(decoded)
                else -> throw IllegalArgumentException()
            }
        } finally {
            decoded.fill(0)
        }
    }

    private fun validateCertificate(raw: String) {
        require(raw.utf8Size() <= MAX_DECODED_KEY_BYTES)
        val trimmed = raw.trim()
        require(trimmed.startsWith(CERTIFICATE_BEGIN))
        require(trimmed.endsWith(CERTIFICATE_END))
        val payload = trimmed.removePrefix(CERTIFICATE_BEGIN)
            .removeSuffix(CERTIFICATE_END)
            .filterNot(Char::isWhitespace)
        val decoded = decodeBounded(payload, MAX_DECODED_KEY_BYTES)
        try {
            parseCertificate(decoded)
        } finally {
            decoded.fill(0)
        }
    }

    private fun parseCertificate(decoded: ByteArray) {
        try {
            val input = ByteArrayInputStream(decoded)
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input)
            require(certificate is X509Certificate)
            require(input.available() == 0)
            require(certificate.encoded.contentEquals(decoded))
        } catch (_: CertificateException) {
            throw IllegalArgumentException()
        }
    }

    private fun validateImage(raw: String) {
        if (raw.startsWith("https://", ignoreCase = true)) {
            validateSafeUri(raw)
            return
        }
        require(raw.startsWith("data:", ignoreCase = true))
        require(raw.utf8Size() <= MAX_DATA_URI_BYTES)
        val comma = raw.indexOf(',')
        require(comma > 5)
        val header = raw.substring(5, comma).lowercase()
        require(header.endsWith(";base64"))
        val mime = header.removeSuffix(";base64")
        require(mime in SAFE_RASTER_MIME_TYPES)
        val decoded = decodeBounded(raw.substring(comma + 1), MAX_DECODED_IMAGE_BYTES)
        try {
            require(isStructurallyValidRaster(mime, decoded))
        } finally {
            decoded.fill(0)
        }
    }

    private fun validateSafeUri(raw: String) {
        require(raw.utf8Size() <= MAX_URI_BYTES)
        require(raw.none(Char::isISOControl))
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException()
        }
        require(uri.isAbsolute)
        require(uri.scheme.equals("https", ignoreCase = true))
        require(!uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null)
        require(uri.port in -1..65_535)
    }

    private fun decodeBounded(encoded: String, maximum: Int): ByteArray {
        require(encoded.length <= ((maximum.toLong() + 2) / 3 * 4 + 16).toInt())
        require(encoded.none(Char::isWhitespace))
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException()
        }
        if (decoded.size > maximum || Base64.getEncoder().encodeToString(decoded) != encoded) {
            decoded.fill(0)
            throw IllegalArgumentException()
        }
        return decoded
    }

    private fun isStructurallyValidRaster(mime: String, decoded: ByteArray): Boolean = when (mime) {
        "image/png" -> isStructurallyValidPng(decoded)
        "image/jpeg" -> isStructurallyValidJpeg(decoded)
        "image/gif" -> isStructurallyValidGif(decoded)
        "image/webp" -> isStructurallyValidWebp(decoded)
        else -> false
    }

    private fun isStructurallyValidPng(decoded: ByteArray): Boolean {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (!decoded.startsWith(signature)) return false
        var offset = signature.size
        var firstChunk = true
        var sawImageData = false
        while (offset + PNG_CHUNK_OVERHEAD <= decoded.size) {
            val length = decoded.readUnsignedIntBigEndian(offset) ?: return false
            if (length > Int.MAX_VALUE || length > decoded.size - offset - PNG_CHUNK_OVERHEAD) return false
            val dataLength = length.toInt()
            val typeOffset = offset + 4
            val type = decoded.ascii(typeOffset, 4) ?: return false
            val dataOffset = typeOffset + 4
            if (firstChunk) {
                if (type != "IHDR" || dataLength != 13) return false
                val width = decoded.readUnsignedIntBigEndian(dataOffset) ?: return false
                val height = decoded.readUnsignedIntBigEndian(dataOffset + 4) ?: return false
                if (!areSafeRasterDimensions(width, height)) return false
                firstChunk = false
            }
            if (type == "IDAT" && dataLength > 0) sawImageData = true
            val next = offset + PNG_CHUNK_OVERHEAD + dataLength
            if (type == "IEND") return dataLength == 0 && sawImageData && next == decoded.size
            offset = next
        }
        return false
    }

    private fun isStructurallyValidJpeg(decoded: ByteArray): Boolean {
        if (decoded.size < 4 || decoded[0] != 0xFF.toByte() || decoded[1] != 0xD8.toByte() ||
            decoded[decoded.lastIndex - 1] != 0xFF.toByte() || decoded.last() != 0xD9.toByte()
        ) return false
        var offset = 2
        var sawFrame = false
        while (offset < decoded.size - 2) {
            if (decoded[offset] != 0xFF.toByte()) return false
            while (offset < decoded.size && decoded[offset] == 0xFF.toByte()) offset++
            if (offset >= decoded.size) return false
            val marker = decoded[offset].toInt() and 0xFF
            offset++
            if (marker == 0xD9) return sawFrame && offset == decoded.size
            if (marker in 0xD0..0xD7 || marker == 0x01) continue
            if (offset + 2 > decoded.size) return false
            val segmentLength = decoded.readUnsignedShortBigEndian(offset)
            if (segmentLength < 2 || offset + segmentLength > decoded.size) return false
            if (marker in JPEG_START_OF_FRAME_MARKERS) {
                if (segmentLength < 8) return false
                val height = decoded.readUnsignedShortBigEndian(offset + 3)
                val width = decoded.readUnsignedShortBigEndian(offset + 5)
                if (!areSafeRasterDimensions(width.toLong(), height.toLong())) return false
                sawFrame = true
            }
            if (marker == 0xDA) return sawFrame
            offset += segmentLength
        }
        return false
    }

    private fun isStructurallyValidGif(decoded: ByteArray): Boolean {
        if (decoded.size < 14) return false
        val validHeader = decoded.startsWith("GIF87a".toByteArray()) || decoded.startsWith("GIF89a".toByteArray())
        if (!validHeader) return false
        val width = decoded.readUnsignedShortLittleEndian(6)
        val height = decoded.readUnsignedShortLittleEndian(8)
        if (!areSafeRasterDimensions(width.toLong(), height.toLong())) return false
        val packed = decoded[10].toInt() and 0xFF
        var offset = 13
        if (packed and 0x80 != 0) {
            val tableBytes = 3L * (1L shl ((packed and 0x07) + 1))
            if (tableBytes > decoded.size - offset) return false
            offset += tableBytes.toInt()
        }
        var sawImage = false
        while (offset < decoded.size) {
            when (decoded[offset]) {
                GIF_IMAGE_SEPARATOR -> {
                    if (offset + 10 > decoded.size) return false
                    val imageWidth = decoded.readUnsignedShortLittleEndian(offset + 5)
                    val imageHeight = decoded.readUnsignedShortLittleEndian(offset + 7)
                    if (!areSafeRasterDimensions(imageWidth.toLong(), imageHeight.toLong())) return false
                    val imagePacked = decoded[offset + 9].toInt() and 0xFF
                    offset += 10
                    if (imagePacked and 0x80 != 0) {
                        val tableBytes = 3L * (1L shl ((imagePacked and 0x07) + 1))
                        if (tableBytes > decoded.size - offset) return false
                        offset += tableBytes.toInt()
                    }
                    if (offset >= decoded.size || (decoded[offset].toInt() and 0xFF) !in 2..8) return false
                    offset++
                    offset = decoded.skipGifSubBlocks(offset) ?: return false
                    sawImage = true
                }
                GIF_EXTENSION_INTRODUCER -> {
                    if (offset + 2 > decoded.size) return false
                    offset = decoded.skipGifSubBlocks(offset + 2) ?: return false
                }
                GIF_TRAILER -> return sawImage && offset == decoded.lastIndex
                else -> return false
            }
        }
        return false
    }

    private fun ByteArray.skipGifSubBlocks(initialOffset: Int): Int? {
        var offset = initialOffset
        while (offset < size) {
            val length = this[offset].toInt() and 0xFF
            offset++
            if (length == 0) return offset
            if (length > size - offset) return null
            offset += length
        }
        return null
    }

    private fun isStructurallyValidWebp(decoded: ByteArray): Boolean {
        if (decoded.size < 20 || decoded.ascii(0, 4) != "RIFF" || decoded.ascii(8, 4) != "WEBP") return false
        val riffPayload = decoded.readUnsignedIntLittleEndian(4) ?: return false
        if (riffPayload + 8 != decoded.size.toLong()) return false
        var offset = 12
        var sawImagePayload = false
        var sawSafeDimensions = false
        while (offset + 8 <= decoded.size) {
            val type = decoded.ascii(offset, 4) ?: return false
            val length = decoded.readUnsignedIntLittleEndian(offset + 4) ?: return false
            if (length > Int.MAX_VALUE) return false
            val paddedLength = length + (length and 1L)
            if (paddedLength > decoded.size - offset - 8) return false
            val dataOffset = offset + 8
            when (type) {
                "VP8 " -> {
                    if (length < 10 || !decoded.matchesAt(
                            dataOffset + 3,
                            byteArrayOf(0x9D.toByte(), 0x01, 0x2A),
                        )
                    ) return false
                    val width = decoded.readUnsignedShortLittleEndian(dataOffset + 6) and 0x3FFF
                    val height = decoded.readUnsignedShortLittleEndian(dataOffset + 8) and 0x3FFF
                    if (!areSafeRasterDimensions(width.toLong(), height.toLong())) return false
                    sawSafeDimensions = true
                    sawImagePayload = true
                }
                "VP8L" -> {
                    if (length < 5 || decoded[dataOffset] != 0x2F.toByte()) return false
                    val b1 = decoded[dataOffset + 1].toInt() and 0xFF
                    val b2 = decoded[dataOffset + 2].toInt() and 0xFF
                    val b3 = decoded[dataOffset + 3].toInt() and 0xFF
                    val b4 = decoded[dataOffset + 4].toInt() and 0xFF
                    val width = 1 + b1 + ((b2 and 0x3F) shl 8)
                    val height = 1 + ((b2 and 0xC0) shr 6) + (b3 shl 2) + ((b4 and 0x0F) shl 10)
                    if (!areSafeRasterDimensions(width.toLong(), height.toLong())) return false
                    sawSafeDimensions = true
                    sawImagePayload = true
                }
                "VP8X" -> {
                    if (length < 10) return false
                    val width = 1L + (decoded.readUnsignedInt24LittleEndian(dataOffset + 4) ?: return false)
                    val height = 1L + (decoded.readUnsignedInt24LittleEndian(dataOffset + 7) ?: return false)
                    if (!areSafeRasterDimensions(width, height)) return false
                    sawSafeDimensions = true
                }
                "ANMF" -> {
                    if (length < 24) return false
                    val width = 1L + (decoded.readUnsignedInt24LittleEndian(dataOffset + 6) ?: return false)
                    val height = 1L + (decoded.readUnsignedInt24LittleEndian(dataOffset + 9) ?: return false)
                    if (!areSafeRasterDimensions(width, height)) return false
                    sawImagePayload = true
                }
            }
            offset += 8 + paddedLength.toInt()
        }
        return sawSafeDimensions && sawImagePayload && offset == decoded.size
    }

    private fun areSafeRasterDimensions(width: Long, height: Long): Boolean =
        width in 1..MAX_RASTER_DIMENSION &&
            height in 1..MAX_RASTER_DIMENSION &&
            width <= MAX_RASTER_PIXELS / height

    private fun ByteArray.readUnsignedIntBigEndian(offset: Int): Long? {
        if (offset < 0 || offset + 4 > size) return null
        return (this[offset].toLong() and 0xFF shl 24) or
            (this[offset + 1].toLong() and 0xFF shl 16) or
            (this[offset + 2].toLong() and 0xFF shl 8) or
            (this[offset + 3].toLong() and 0xFF)
    }

    private fun ByteArray.readUnsignedIntLittleEndian(offset: Int): Long? {
        if (offset < 0 || offset + 4 > size) return null
        return (this[offset].toLong() and 0xFF) or
            (this[offset + 1].toLong() and 0xFF shl 8) or
            (this[offset + 2].toLong() and 0xFF shl 16) or
            (this[offset + 3].toLong() and 0xFF shl 24)
    }

    private fun ByteArray.readUnsignedInt24LittleEndian(offset: Int): Long? {
        if (offset < 0 || offset + 3 > size) return null
        return (this[offset].toLong() and 0xFF) or
            (this[offset + 1].toLong() and 0xFF shl 8) or
            (this[offset + 2].toLong() and 0xFF shl 16)
    }

    private fun ByteArray.readUnsignedShortBigEndian(offset: Int): Int {
        if (offset < 0 || offset + 2 > size) return -1
        return (this[offset].toInt() and 0xFF shl 8) or (this[offset + 1].toInt() and 0xFF)
    }

    private fun ByteArray.readUnsignedShortLittleEndian(offset: Int): Int {
        if (offset < 0 || offset + 2 > size) return -1
        return (this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() and 0xFF shl 8)
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String? {
        if (offset < 0 || length < 0 || offset + length > size) return null
        if ((offset until offset + length).any { index -> this[index].toInt() !in 0x20..0x7E }) return null
        return String(this, offset, length, StandardCharsets.US_ASCII)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean =
        offset >= 0 && offset + expected.size <= size &&
            expected.indices.all { index -> this[offset + index] == expected[index] }

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

    private companion object {
        const val MAX_VALUES = 10_000
        const val MAX_SCALAR_BYTES = 16 * 1_024
        const val MAX_URI_BYTES = 8 * 1_024
        const val MAX_DECODED_KEY_BYTES = 1 * 1_024 * 1_024
        const val MAX_DECODED_IMAGE_BYTES = 10 * 1_024 * 1_024
        const val MAX_GENERATED_CARD_BYTES = 10 * 1_024 * 1_024L
        const val MAX_DATA_URI_BYTES = 14 * 1_024 * 1_024
        const val MAX_RASTER_DIMENSION = 8_192L
        const val MAX_RASTER_PIXELS = 32L * 1_024 * 1_024
        val LANGUAGE_TAG = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
        val UTC_OFFSET = Regex("^[+-](?:0\\d|1[0-4]):?[0-5]\\d$")
        val TIME_ZONE_TEXT = Regex("^[^;,:\\r\\n]{1,16384}(?:/[^;,:\\r\\n]{1,16384})*$")
        val GENDER = Regex("^(?:M|F|O|N|U)?(?:;[^;\\r\\n]{0,16384})?$")
        val FULL_DATE = Regex("^(?:\\d{4}-\\d{2}-\\d{2}|\\d{8})$")
        val YEARLESS_DATE = Regex("^--(?:\\d{2}-\\d{2}|\\d{4})$")
        val SAFE_RASTER_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        const val CERTIFICATE_BEGIN = "-----BEGIN CERTIFICATE-----"
        const val CERTIFICATE_END = "-----END CERTIFICATE-----"
        val PRIVATE_KEY_MARKERS = listOf(
            "BEGIN PGP PRIVATE KEY BLOCK",
            "BEGIN PRIVATE KEY",
            "BEGIN ENCRYPTED PRIVATE KEY",
            "BEGIN RSA PRIVATE KEY",
            "BEGIN EC PRIVATE KEY",
            "OPENSSH PRIVATE KEY",
        )
        val BIDI_CONTROL_CHARACTERS = setOf(
            '\u061C', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
        )
        const val PNG_CHUNK_OVERHEAD = 12
        val JPEG_START_OF_FRAME_MARKERS = setOf(
            0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
        )
        const val GIF_TRAILER: Byte = 0x3B
        const val GIF_IMAGE_SEPARATOR: Byte = 0x2C
        const val GIF_EXTENSION_INTRODUCER: Byte = 0x21
    }
}

enum class EditableValueError {
    PUBLIC_KEY,
    LANGUAGE,
    TIME_ZONE,
    GENDER,
    IMAGE,
    URL,
    DATE,
    GENERIC,
}
