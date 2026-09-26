package com.patmanak.contako.qa.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.patmanak.contako.BuildConfig
import java.io.File

internal data class AndroidPerformanceEvidence(
    val thermalState: String,
    val batteryPercent: Int,
    val chargingState: String,
    val sourceRevision: String,
    val buildIdentifier: String,
    val benchmarkCommand: String,
    val applicationApkSha256: String,
    val testApkSha256: String,
)

internal fun Context.performanceEvidence(): AndroidPerformanceEvidence {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val arguments = InstrumentationRegistry.getArguments()
    fun requiredArgument(name: String): String = requireNotNull(arguments.getString(name)) {
        "Missing qualifying instrumentation argument: $name"
    }
    return AndroidPerformanceEvidence(
        thermalState = when (getSystemService(PowerManager::class.java).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "nominal"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            else -> "shutdown"
        },
        batteryPercent = percent,
        chargingState = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            else -> "unknown"
        },
        sourceRevision = requiredArgument("sourceRevision"),
        buildIdentifier = BuildConfig.VERSION_NAME,
        benchmarkCommand = requiredArgument("benchmarkCommand"),
        applicationApkSha256 = requiredArgument("applicationApkSha256"),
        testApkSha256 = requiredArgument("testApkSha256"),
    )
}

internal fun Context.writePerformanceArtifact(fileName: String, contents: String) {
    require(fileName.matches(Regex("[A-Za-z0-9._-]+")))

    val externalOutput = requireNotNull(getExternalFilesDir("performance"))
    File(externalOutput, fileName).writeText(contents)

    PlatformTestStorageRegistry.getInstance()
        .openOutputFile("performance/$fileName")
        .bufferedWriter()
        .use { it.write(contents) }
}
