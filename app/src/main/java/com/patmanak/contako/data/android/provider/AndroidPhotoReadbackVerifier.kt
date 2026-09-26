package com.patmanak.contako.data.android.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.provider.ContactsContract
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Exact readback of an Android copy, never a perceptual/tolerant image comparison.
 * Recognizes the reference ContactsProvider's documented raster pipeline (fit,
 * white alpha background, JPEG 75 display / 90 or 95 thumbnail). An OEM producing
 * different bytes remains unverified; it MUST NOT authorize clearing native intent.
 * Reference: AOSP ContactsProvider PhotoProcessor.java; projection contract: docs/CONTACTS.md.
 */
internal class AndroidPhotoReadbackVerifier(private val resolver: ContentResolver) {
    fun matches(account: AndroidProviderAccountName, observation: AndroidStableRawContactObservation,
        source: ByteArray, requireDisplay: Boolean = false): Boolean { return try {
        val row = observation.dataRows.singleOrNull { it.isStandardPhoto }
        // binarySlot follows AndroidContactsProviderReader: full display bytes when
        // available, inline thumbnail only as a fallback. It is NOT Data.PHOTO.
        val observedBytes = row?.binarySlot
        val limits = dimensions()
        if (row == null || observedBytes == null || limits == null) false else {
            val expected = render(source, limits.first, limits.second)
            val thumbnail = readThumbnail(account, observation.rawContact.rawContactId, row.dataRowId)
            if (expected == null || !thumbnail.contentEquals(expected.second)) false else {
                val uri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI,
                    observation.rawContact.rawContactId).buildUpon()
                    .appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.value)
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE,
                        com.patmanak.contako.android.account.ContakoAndroidAccountContract.ACCOUNT_TYPE).build()
                val display = try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.createInputStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_BYTES) return false
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                } catch (_: IOException) { null }
                matchesAndroidPhotoReadback(observedBytes, thumbnail, display,
                    expected.first, expected.second, requireDisplay)
            }
        }
    } catch (_: RuntimeException) { false } catch (_: OutOfMemoryError) { false }
    }

    private fun readThumbnail(account: AndroidProviderAccountName, rawContactId: Long,
        dataRowId: Long): ByteArray? = resolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO),
        "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
            "${ContactsContract.Data.MIMETYPE} = ? AND length(${ContactsContract.CommonDataKinds.Photo.PHOTO}) <= ?",
        arrayOf(dataRowId.toString(), rawContactId.toString(), account.value,
            com.patmanak.contako.android.account.ContakoAndroidAccountContract.ACCOUNT_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, MAX_THUMBNAIL_BYTES.toString()),
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) null else {
            val bytes = cursor.getBlob(0)
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_THUMBNAIL_BYTES && !cursor.moveToNext() }
        }
    }

    private fun dimensions(): Pair<Int, Int>? = resolver.query(
        ContactsContract.DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI,
        arrayOf(ContactsContract.DisplayPhoto.DISPLAY_MAX_DIM, ContactsContract.DisplayPhoto.THUMBNAIL_MAX_DIM),
        null, null, null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) null else {
            val display = cursor.getInt(0)
            val thumbnail = cursor.getInt(1)
            if (display in 1..4096 && thumbnail in 1..display) display to thumbnail else null
        }
    }

    internal companion object {
        private const val MAX_BYTES = 10 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 256 * 1024

        /** Independent implementation of the reference provider's public raster conventions. */
        fun render(source: ByteArray, displayLimit: Int, thumbnailLimit: Int): Pair<ByteArray, ByteArray>? {
            if (source.isEmpty() || source.size > MAX_BYTES || displayLimit !in 1..4096 ||
                thumbnailLimit !in 1..displayLimit) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > 32L * 1024 * 1024) return null
            val original = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
            var display: Bitmap? = null
            var thumbnail: Bitmap? = null
            try {
                fun fit(limit: Int): Bitmap {
                    val ratio = minOf(1f, limit.toFloat() / maxOf(original.width, original.height))
                    if (ratio == 1f && !original.hasAlpha()) return original
                    val width = (original.width * ratio).toInt()
                    val height = (original.height * ratio).toInt()
                    if (width <= 0 || height <= 0) throw IllegalArgumentException()
                    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        val canvas = Canvas(bitmap)
                        if (original.hasAlpha()) canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(original, Rect(0, 0, original.width, original.height),
                            RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
                    }
                }
                display = fit(displayLimit)
                thumbnail = fit(thumbnailLimit)
                fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    output.toByteArray().also { check(it.size <= MAX_BYTES) }
                }
                val thumbnailQuality = if (display.width > thumbnail.width || display.height > thumbnail.height) 90 else 95
                return jpeg(display, 75) to jpeg(thumbnail, thumbnailQuality)
            } finally {
                if (display !== original) display?.recycle()
                if (thumbnail !== original && thumbnail !== display) thumbnail?.recycle()
                original.recycle()
            }
        }
    }
}
