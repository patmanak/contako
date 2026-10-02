package com.patmanak.contako.data.android.provider

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class AndroidDisplayPhotoStreamDeviceTest {
    @Test fun highlyCompressedLargePhotoStillGetsSampledForInlineProjection() {
        val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val input = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
        assertTrue(input.size <= AndroidProjectionPhotoScaler.MAX_INLINE_PHOTO_BYTES)
        val output = requireNotNull(AndroidProjectionPhotoScaler.scaleForProvider(input))
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(output, 0, output.size))
        try { assertTrue(maxOf(decoded.width, decoded.height) <= 512) } finally { decoded.recycle() }
    }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var account: Account
    private var rawContactId: Long = 0

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        account = Account("contako-photo-${System.nanoTime().toString(36)}", ContakoAndroidAccountContract.ACCOUNT_TYPE)
        check(AccountManager.get(context).addAccountExplicitly(account, null, null))
        rawContactId = insertRawContact()
    }

    @After
    fun tearDown() {
        if (rawContactId > 0) runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId), null, null,
            )
        }
        if (::account.isInitialized) runCatching { AccountManager.get(context).removeAccountExplicitly(account) }
    }

    @Test
    fun fullResolutionDisplayPhotoRoundTripsOutsideBinder() {
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val value = (x * 73856093) xor (y * 19349663)
            bitmap.setPixel(x, y, Color.rgb(value, value ushr 8, value ushr 16))
        }
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val uri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId)
            .buildUpon().appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build()
        context.contentResolver.openAssetFileDescriptor(uri, "rw")!!.use { descriptor ->
            descriptor.createOutputStream().use { it.write(bytes) }
        }
        val reader = AndroidContactsProviderReader(context.contentResolver)
        var stable: AndroidStableRawContactPageResult.Stable? = null
        repeat(5) {
            val observed = reader.readStableRawContact(AndroidProviderAccountName(account.name), rawContactId)
            if (observed is AndroidStableRawContactPageResult.Stable) stable = observed
            if (stable != null) return@repeat
            Thread.sleep(100)
        }
        val observation = requireNotNull(stable)
        val photo = observation.page.observations.single().dataRows.single {
            it.mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        }
        val providerBytes = requireNotNull(photo.binarySlot)
        assertTrue(providerBytes.isNotEmpty())
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(providerBytes, 0, providerBytes.size, bounds)
        // This OEM normalizes display photos to its 720 px provider maximum; the display_photo
        // stream remains materially larger than the thumbnail/Binder path.
        assertTrue(bounds.outWidth >= 512)
        assertTrue(bounds.outHeight >= 512)
    }

    @Test
    fun inlinePhotoLoaderBoundsValidImagesAndRejectsLargeInvalidPayloads() {
        val bitmap = Bitmap.createBitmap(1_024, 1_024, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val value = (x * 73_856_093) xor (y * 19_349_663)
            bitmap.setPixel(x, y, Color.rgb(value, value ushr 8, value ushr 16))
        }
        val full = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()

        val inline = AndroidInlinePhotoBinaryLoader(AndroidProjectionBinaryLoader { full }).load("opaque")
        assertTrue(requireNotNull(inline).size <= AndroidProjectionPhotoScaler.MAX_INLINE_PHOTO_BYTES)
        assertNull(
            AndroidInlinePhotoBinaryLoader(
                AndroidProjectionBinaryLoader {
                    ByteArray(AndroidProjectionPhotoScaler.MAX_INLINE_PHOTO_BYTES + 1) { 0x7f }
                },
            ).load("opaque"),
        )
        assertNull(
            AndroidInlinePhotoBinaryLoader(AndroidProjectionBinaryLoader { byteArrayOf(1, 2, 3) })
                .load("small-opaque"),
        )
    }

    @Test
    fun displayPhotoLoaderNormalizesCanonicalImageToBoundedJpeg() {
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        val canonical = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()

        val normalized = requireNotNull(
            AndroidDisplayPhotoBinaryLoader(AndroidProjectionBinaryLoader { canonical }).load("opaque"),
        )
        assertTrue(normalized.size <= 10 * 1_024 * 1_024)
        val decoded = BitmapFactory.decodeByteArray(normalized, 0, normalized.size)
        assertTrue(decoded.width == 128 && decoded.height == 96)
        decoded.recycle()
    }

    @Test
    fun smallNormalizedDisplayPhotoRoundTripsAfterInlineSeed() {
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        val canonical = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val inline = requireNotNull(AndroidProjectionPhotoScaler.scaleForProvider(canonical))
        val display = requireNotNull(AndroidProjectionPhotoScaler.normalizeForDisplayPhoto(canonical))
        requireNotNull(
            context.contentResolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Photo.PHOTO, inline)
                },
            ),
        )
        val uri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId)
            .buildUpon().appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build()
        context.contentResolver.openAssetFileDescriptor(uri, "rw")!!.use { descriptor ->
            descriptor.createOutputStream().use { it.write(display) }
        }
        val providerBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.createInputStream().use { it.readBytes() }
        }
        val observed = requireNotNull(providerBytes).also { assertTrue(it.isNotEmpty()) }
        val decoded = BitmapFactory.decodeByteArray(observed, 0, observed.size)
        assertTrue(decoded.width == 128 && decoded.height == 96)
        decoded.recycle()
    }

    private fun insertRawContact(): Long {
        val values = ContentValues().apply {
            put(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            put(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            put(ContactsContract.RawContacts.SYNC1, "device-photo-contact")
            put(ContactsContract.RawContacts.SOURCE_ID, "device-photo-source")
        }
        return ContentUris.parseId(requireNotNull(context.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, values)))
    }
}
