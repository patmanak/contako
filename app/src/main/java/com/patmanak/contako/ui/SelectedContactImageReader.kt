package com.patmanak.contako.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Converts one user-selected local raster into the bounded canonical inline representation. */
internal fun readSelectedContactImage(resolver: ContentResolver, uri: Uri): String? {
    val declaredSize = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: -1L
    return readSelectedContactImagePayload(
        mimeType = resolver.getType(uri),
        declaredSize = declaredSize,
        openStream = { resolver.openInputStream(uri) },
    )
}

internal fun readSelectedContactImagePayload(
    mimeType: String?,
    declaredSize: Long,
    openStream: () -> InputStream?,
): String? {
    val normalizedMimeType = mimeType?.substringBefore(';')?.lowercase()
        ?.let(::normalizeSelectedImageMimeType)
        ?: return null
    if (declaredSize > MAX_SELECTED_IMAGE_BYTES) return null
    val capacity = if (declaredSize in 1L..MAX_SELECTED_IMAGE_BYTES.toLong()) {
        declaredSize.toInt() + 1
    } else {
        MAX_SELECTED_IMAGE_BYTES + 1
    }
    val bytes = ByteArray(capacity)
    return try {
        val size = openStream()?.use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                if (read == 0) {
                    val singleByte = input.read()
                    if (singleByte < 0) break
                    bytes[offset++] = singleByte.toByte()
                } else {
                    offset += read
                }
            }
            if (offset == bytes.size || input.read() >= 0) null else offset
        } ?: return null
        if (size <= 0) return null
        val normalized = normalizeSelectedRaster(bytes, size, normalizedMimeType) ?: return null
        try {
            val encoded = Base64.encodeToString(normalized.bytes, Base64.NO_WRAP)
            "data:${normalized.mimeType};base64,$encoded"
        } finally {
            normalized.bytes.fill(0)
        }
    } finally {
        bytes.fill(0)
    }
}

private data class NormalizedSelectedRaster(
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Applies encoded orientation, removes source metadata, and bounds the persisted raster.
 * Images are never enlarged or cropped. The short edge targets Proton's 180 px;
 * the long edge is also capped at 1024 px for panoramas, retaining aspect ratio.
 */
private fun normalizeSelectedRaster(
    bytes: ByteArray,
    size: Int,
    mimeType: String,
): NormalizedSelectedRaster? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, size, bounds)
    if (bounds.outWidth !in 1..MAX_SELECTED_IMAGE_DIMENSION ||
        bounds.outHeight !in 1..MAX_SELECTED_IMAGE_DIMENSION ||
        bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_SELECTED_IMAGE_PIXELS ||
        bounds.outMimeType?.lowercase() != mimeType
    ) {
        return null
    }

    val decoded = try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes, 0, size))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val orientedWidth = info.size.width
            val orientedHeight = info.size.height
            if (orientedWidth !in 1..MAX_SELECTED_IMAGE_DIMENSION ||
                orientedHeight !in 1..MAX_SELECTED_IMAGE_DIMENSION ||
                orientedWidth.toLong() * orientedHeight.toLong() > MAX_SELECTED_IMAGE_PIXELS
            ) {
                throw IllegalArgumentException("Invalid image dimensions")
            }
            val (targetWidth, targetHeight) = selectedContactImageSize(orientedWidth, orientedHeight)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSize(targetWidth, targetHeight)
        }
    } catch (_: ImageDecoder.DecodeException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: OutOfMemoryError) {
        return null
    }

    return try {
        val outputMimeType: String
        val format: Bitmap.CompressFormat
        val quality: Int
        if (decoded.hasAlpha()) {
            outputMimeType = "image/png"
            format = Bitmap.CompressFormat.PNG
            quality = 100
        } else {
            outputMimeType = "image/jpeg"
            format = Bitmap.CompressFormat.JPEG
            quality = NORMALIZED_JPEG_QUALITY
        }
        val output = BoundedImageOutputStream(MAX_SELECTED_IMAGE_BYTES)
        if (!decoded.compress(format, quality, output)) return null
        val normalizedBytes = output.toByteArray()
        if (normalizedBytes.isEmpty() || normalizedBytes.size > MAX_SELECTED_IMAGE_BYTES) {
            normalizedBytes.fill(0)
            null
        } else {
            NormalizedSelectedRaster(outputMimeType, normalizedBytes)
        }
    } catch (_: ImageOutputLimitExceededException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        decoded.recycle()
    }
}

private class BoundedImageOutputStream(private val maxBytes: Int) : OutputStream() {
    private val delegate = ByteArrayOutputStream(minOf(maxBytes, 64 * 1_024))

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        ensureCapacity(length)
        delegate.write(buffer, offset, length)
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun ensureCapacity(additionalBytes: Int) {
        if (additionalBytes > maxBytes - delegate.size()) {
            throw ImageOutputLimitExceededException()
        }
    }
}

private class ImageOutputLimitExceededException : RuntimeException()

private const val MAX_SELECTED_IMAGE_BYTES = 10 * 1_024 * 1_024
private const val MAX_SELECTED_IMAGE_DIMENSION = 8_192
private const val MAX_SELECTED_IMAGE_PIXELS = 32L * 1_024 * 1_024
private const val NORMALIZED_JPEG_QUALITY = 90
private val SAFE_SELECTED_IMAGE_MIME_TYPES = setOf(
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
)

private fun normalizeSelectedImageMimeType(value: String): String? = when (value) {
    "image/jpg" -> "image/jpeg"
    in SAFE_SELECTED_IMAGE_MIME_TYPES -> value
    else -> null
}
