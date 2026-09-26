package com.patmanak.contako.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedContactImageReaderDeviceTest {
    @Test
    fun validRasterIsAcceptedButFalseMimeAndTruncatedPixelsFailClosed() {
        val png = rasterBytes()

        assertNotNull(readPayload("image/png", png))
        assertNull(readPayload("image/jpeg", png))
        assertNull(readPayload("text/plain", png))
        assertNull(readPayload("image/png", png.copyOf(png.size / 2)))
    }

    @Test
    fun declaredAndActualSizeBoundariesFailBeforeDraftMutation() {
        val png = rasterBytes()

        assertNull(
            readSelectedContactImagePayload(
                mimeType = "image/png",
                declaredSize = 10L * 1_024 * 1_024 + 1,
                openStream = { error("oversize input must not be opened") },
            ),
        )
        assertNull(
            readSelectedContactImagePayload(
                mimeType = "image/png",
                declaredSize = 1,
                openStream = { ByteArrayInputStream(png) },
            ),
        )
        assertNull(readSelectedContactImagePayload("image/png", 0) { ByteArrayInputStream(byteArrayOf()) })
    }

    @Test
    fun selectedRasterDownscalesItsShortestEdgeWithoutUpscalingSmallImages() {
        assertNormalizedDimensions(1_024, 2_048, 180, 360)
        assertNormalizedDimensions(2_048, 1_024, 360, 180)
        assertNormalizedDimensions(1_024, 1_024, 180, 180)
        assertNormalizedDimensions(512, 1_024, 180, 360)
        assertNormalizedDimensions(1_024, 256, 720, 180)
        assertNormalizedDimensions(64, 128, 64, 128)
        assertNormalizedDimensions(1_001, 2_003, 180, 360)
        assertNormalizedDimensions(8_192, 128, 1_024, 16)
        assertNormalizedDimensions(128, 8_192, 16, 1_024)
        assertNormalizedDimensions(180, 180, 180, 180)
    }

    @Test
    fun selectedJpegAppliesExifOrientationAndStripsSourceMetadata() {
        val source = jpegWithExif(
            width = 80,
            height = 40,
            orientation = ExifInterface.ORIENTATION_ROTATE_90,
            make = "Contako metadata canary",
        )
        val result = requireNotNull(readPayload("image/jpeg", source))
        decodeResult(result).useBitmap { bitmap ->
            assertEquals(40, bitmap.width)
            assertEquals(80, bitmap.height)
        }

        withTemporaryFile("normalized", ".jpg", decodeResultBytes(result)) { output ->
            assertNull(ExifInterface(output.absolutePath).getAttribute(ExifInterface.TAG_MAKE))
        }
    }

    @Test
    fun resizedTransparentLogoKeepsAlphaAndOpaquePhotoUsesJpeg() {
        val logo = requireNotNull(readPayload("image/png", rasterBytes(1_024, 1_024)))
        org.junit.Assert.assertTrue(logo.startsWith("data:image/png;base64,"))
        decodeResult(logo).useBitmap { bitmap ->
            assertEquals(180, bitmap.width)
            assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(90, 90)))
        }
        val photo = jpegWithExif(2_048, 1_024, ExifInterface.ORIENTATION_ROTATE_90, "Contako test")
        val result = requireNotNull(readPayload("image/jpeg", photo))
        org.junit.Assert.assertTrue(result.startsWith("data:image/jpeg;base64,"))
        decodeResult(result).useBitmap { bitmap ->
            assertEquals(180, bitmap.width)
            assertEquals(360, bitmap.height)
        }
        org.junit.Assert.assertTrue(decodeResultBytes(result).size < photo.size)
    }

    private fun readPayload(mimeType: String, bytes: ByteArray): String? =
        readSelectedContactImagePayload(mimeType, bytes.size.toLong()) {
            ByteArrayInputStream(bytes)
        }

    private fun decodeResult(dataUri: String): Bitmap {
        val bytes = decodeResultBytes(dataUri)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun decodeResultBytes(dataUri: String): ByteArray =
        Base64.decode(dataUri.substringAfter(','), Base64.DEFAULT)

    private fun assertNormalizedDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        expectedWidth: Int,
        expectedHeight: Int,
    ) {
        val result = requireNotNull(readPayload("image/png", rasterBytes(sourceWidth, sourceHeight)))
        decodeResult(result).useBitmap { bitmap ->
            assertEquals(expectedWidth, bitmap.width)
            assertEquals(expectedHeight, bitmap.height)
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            recycle()
        }
    }

    private fun rasterBytes(width: Int = 4, height: Int = 4): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun jpegWithExif(width: Int, height: Int, orientation: Int, make: String): ByteArray {
        val bytes = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).useBitmapResult { bitmap ->
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        }
        return withTemporaryFile("oriented", ".jpg", bytes) { file ->
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_MAKE, make)
                saveAttributes()
            }
            file.readBytes()
        }
    }

    private inline fun <T> Bitmap.useBitmapResult(block: (Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }

    private inline fun <T> withTemporaryFile(
        prefix: String,
        suffix: String,
        bytes: ByteArray,
        block: (File) -> T,
    ): T {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile(prefix, suffix, cacheDir)
        return try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
