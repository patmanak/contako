package com.patmanak.contako.data.android.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Scales a canonical photo down to what the Android contacts provider accepts inline.
 *
 * A `Data` row photo travels through a Binder transaction, so an image of a few hundred kilobytes
 * is rejected even though the canonical store happily holds ten megabytes. Contako inlined photos
 * at their original size, which the provider refused, and the projection then never acknowledged.
 *
 * The bounds match the working prototype: 512 px on the longest edge and 96 KiB after JPEG
 * compression, with quality stepped down until it fits.
 *
 * Fail-closed for the Android projection only: undecodable or unbounded input returns `null`, so a
 * malformed image cannot block projection of the rest of the contact. The canonical bytes remain
 * untouched and available to the app-private store.
 */
internal object AndroidProjectionPhotoScaler {

    /** Largest inline photo the provider reliably accepts across OEM implementations. */
    const val MAX_INLINE_PHOTO_BYTES = 96 * 1_024

    private const val MAX_DIMENSION = 512
    private const val MAX_DISPLAY_DIMENSION = 2_048
    private const val MAX_DISPLAY_PHOTO_BYTES = 10 * 1_024 * 1_024
    private const val INITIAL_QUALITY = 90
    private const val MIN_QUALITY = 40
    private const val QUALITY_STEP = 10

    /** Bounds precede pixel allocation, including for very small compressed payloads. */
    internal fun decodeSampleSize(width: Int, height: Int, target: Int): Int? {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 ||
            width.toLong() * height > 32L * 1024 * 1024 || target <= 0) return null
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    fun scaleForProvider(bytes: ByteArray): ByteArray? {
        return scale(bytes, MAX_DIMENSION, MAX_INLINE_PHOTO_BYTES, INITIAL_QUALITY, preserveSmall = true)
    }

    /** Produces a broadly supported, bounded JPEG for the full-resolution display-photo pipe. */
    fun normalizeForDisplayPhoto(bytes: ByteArray): ByteArray? {
        return scale(bytes, MAX_DISPLAY_DIMENSION, MAX_DISPLAY_PHOTO_BYTES, 92, preserveSmall = false)
    }

    private fun scale(bytes: ByteArray, target: Int, byteLimit: Int, qualityStart: Int, preserveSmall: Boolean): ByteArray? {
        return AndroidPhotoWorkBudget.withDecodedPhoto {
            scaleWithinBudget(bytes, target, byteLimit, qualityStart, preserveSmall)
        }
    }

    private fun scaleWithinBudget(bytes: ByteArray, target: Int, byteLimit: Int, qualityStart: Int, preserveSmall: Boolean): ByteArray? {
        if (bytes.isEmpty() || bytes.size > MAX_DISPLAY_PHOTO_BYTES) return null
        var source: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = decodeSampleSize(bounds.outWidth, bounds.outHeight, target) ?: return null
            source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (preserveSmall && bytes.size <= byteLimit && maxOf(bounds.outWidth, bounds.outHeight) <= target) return bytes
            scaled = source.scaleDownToFit(target)
            ByteArrayOutputStream().use { stream ->
                var quality = qualityStart
                do {
                    stream.reset()
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) return null
                    quality -= QUALITY_STEP
                } while (stream.size() > byteLimit && quality >= MIN_QUALITY)
                stream.toByteArray().takeIf { it.isNotEmpty() && it.size <= byteLimit }
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            if (scaled !== source) scaled?.recycle()
            source?.recycle()
        }
    }

    private fun Bitmap.scaleDownToFit(maxDimension: Int): Bitmap {
        val longestEdge = maxOf(width, height)
        if (longestEdge <= maxDimension) return this
        val ratio = maxDimension.toFloat() / longestEdge
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }
}

/** Separates the Binder-safe thumbnail boundary from the full-resolution stream loader. */
internal class AndroidInlinePhotoBinaryLoader(
    private val canonicalLoader: AndroidProjectionBinaryLoader,
) : AndroidProjectionBinaryLoader {
    override fun load(reference: String): ByteArray? = canonicalLoader.load(reference)?.let(
        AndroidProjectionPhotoScaler::scaleForProvider,
    )
}


/** Normalizes only the Android display-photo copy; canonical/Proton bytes remain untouched. */
internal class AndroidDisplayPhotoBinaryLoader(
    private val canonicalLoader: AndroidProjectionBinaryLoader,
) : AndroidProjectionBinaryLoader {
    override fun load(reference: String): ByteArray? = canonicalLoader.load(reference)?.let(
        AndroidProjectionPhotoScaler::normalizeForDisplayPhoto,
    )
}
