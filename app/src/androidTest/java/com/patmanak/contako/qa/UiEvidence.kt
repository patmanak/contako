package com.patmanak.contako.qa

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Opt-in screenshots for synthetic UI cases only; never enabled for account/provider journeys. */
internal fun saveSyntheticUiEvidence(name: String) {
    if (InstrumentationRegistry.getArguments().getString("captureSyntheticUi") != "true") return
    require(name.matches(Regex("[a-z0-9-]{1,64}")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Compose idleness does not include the platform window's fade-in animation.
    instrumentation.uiAutomation.waitForIdle(500, 5_000)
    val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("synthetic-ui"))
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        File(directory, "$name.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    } finally { bitmap.recycle() }
}
