package com.patmanak.contako.ui.components

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Base64
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Asynchronously decodes a bounded thumbnail for an inline canonical image.
 *
 * Remote references deliberately remain placeholders: rendering MUST NOT perform network I/O.
 * Base64 work, bounds inspection, sampling, and bitmap allocation all run away from the main
 * dispatcher so list composition never decodes a full contact image synchronously.
 */
@Composable
internal fun rememberContactImage(source: String?): ImageBitmap? =
    rememberContactImage(listOfNotNull(source))

/** Uses the first locally renderable image while retaining the supplied preference order. */
@Composable
internal fun rememberContactImage(
    sources: List<String>,
    decode: suspend (String) -> ImageBitmap? = { decodeInlineImage(it) },
): ImageBitmap? = key(sources) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = sources) {
        value = withContext(Dispatchers.Default) {
            IMAGE_DECODE_PERMITS.withPermit {
                var decoded: ImageBitmap? = null
                for (source in sources) {
                    decoded = decode(source)
                    if (decoded != null) break
                }
                decoded
            }
        }
    }
    image
}

internal data class InlineImagePayload(
    val mimeType: String,
    val bytes: ByteArray,
)

/** Strict, allocation-bounded data-URI decoding kept separate for deterministic JVM tests. */
internal fun decodeInlineImagePayload(source: String): InlineImagePayload? {
    if (source.length > MAX_DATA_URI_CHARS) return null
    val trimmed = source.trim()
    val separator = trimmed.indexOf(',')
    if (separator <= 0 || separator > MAX_HEADER_CHARS) return null
    val header = trimmed.substring(0, separator).lowercase()
    val declaredMimeType = header.removePrefix(DATA_URI_PREFIX).removeSuffix(BASE64_MARKER)
    val mimeType = normalizeRasterMimeType(declaredMimeType)
    if (header != "$DATA_URI_PREFIX$declaredMimeType$BASE64_MARKER" || mimeType == null) {
        return null
    }
    val payload = trimmed.substring(separator + 1)
    if (payload.isEmpty() || payload.length > MAX_ENCODED_CHARS || payload.any(Char::isWhitespace)) {
        return null
    }
    return try {
        val bytes = Base64.getDecoder().decode(payload)
        if (bytes.isEmpty() || bytes.size > MAX_DECODED_BYTES) {
            bytes.fill(0)
            null
        } else {
            InlineImagePayload(mimeType, bytes)
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun decodeInlineImage(source: String): ImageBitmap? {
    var payload: InlineImagePayload? = null
    return try {
        val decodedPayload = decodeInlineImagePayload(source) ?: return null
        payload = decodedPayload
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(decodedPayload.bytes, 0, decodedPayload.bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION ||
            width.toLong() * height.toLong() > MAX_SOURCE_PIXELS ||
            bounds.outMimeType?.lowercase() != decodedPayload.mimeType
        ) return null

        val sourceBuffer = ImageDecoder.createSource(ByteBuffer.wrap(decodedPayload.bytes))
        ImageDecoder.decodeBitmap(sourceBuffer) { decoder, info, _ ->
            val longestEdge = maxOf(info.size.width, info.size.height)
            val scale = if (longestEdge > MAX_THUMBNAIL_DIMENSION) {
                MAX_THUMBNAIL_DIMENSION.toFloat() / longestEdge.toFloat()
            } else {
                1f
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }.asImageBitmap()
    } catch (_: ImageDecoder.DecodeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        payload?.bytes?.fill(0)
    }
}

private const val DATA_URI_PREFIX = "data:"
private const val BASE64_MARKER = ";base64"
private const val MAX_DECODED_BYTES = 10 * 1_024 * 1_024
private const val MAX_ENCODED_CHARS = ((MAX_DECODED_BYTES + 2) / 3) * 4
private const val MAX_HEADER_CHARS = 64
private const val MAX_DATA_URI_CHARS = MAX_HEADER_CHARS + 1 + MAX_ENCODED_CHARS
private const val MAX_SOURCE_DIMENSION = 8_192
private const val MAX_SOURCE_PIXELS = 32L * 1_024 * 1_024
private const val MAX_THUMBNAIL_DIMENSION = 1_024
private val IMAGE_DECODE_PERMITS = Semaphore(2)
private val SAFE_RASTER_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

private fun normalizeRasterMimeType(value: String): String? = when (value) {
    "image/jpg" -> "image/jpeg"
    in SAFE_RASTER_MIME_TYPES -> value
    else -> null
}
