package com.patmanak.contako.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {
    @Test
    fun packagedLauncherIconIsAdaptiveRenderableAndMonochromeCapable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertTrue(
            "MANIFEST_LAUNCHER_RESOURCE_REQUIRED",
            applicationInfo.icon in setOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round),
        )

        val icon = context.packageManager.getApplicationIcon(applicationInfo)
        assertTrue("LAUNCHER_ICON_MUST_BE_ADAPTIVE", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertNotNull("MONOCHROME_ICON_REQUIRED", icon.monochrome)
        }

        val bitmap = Bitmap.createBitmap(RENDER_SIZE, RENDER_SIZE, Bitmap.Config.ARGB_8888)
        icon.bounds = android.graphics.Rect(0, 0, RENDER_SIZE, RENDER_SIZE)
        icon.draw(Canvas(bitmap))
        val opaquePixels = IntArray(RENDER_SIZE * RENDER_SIZE).also {
            bitmap.getPixels(it, 0, RENDER_SIZE, 0, 0, RENDER_SIZE, RENDER_SIZE)
        }.count { pixel -> android.graphics.Color.alpha(pixel) == 255 }
        assertTrue("ADAPTIVE_ICON_RENDER_IS_EMPTY", opaquePixels > RENDER_SIZE * RENDER_SIZE / 2)
        bitmap.recycle()
    }

    private companion object {
        const val RENDER_SIZE = 432
    }
}
