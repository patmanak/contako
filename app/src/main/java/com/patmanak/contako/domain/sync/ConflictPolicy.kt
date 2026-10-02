package com.patmanak.contako.domain.sync

import kotlin.math.absoluteValue

/** Closed UTC interval. Neither endpoint claims more precision than the underlying evidence. */
data class UtcTimeInterval(
    val earliestEpochMillis: Long,
    val latestEpochMillis: Long,
) {
    init {
        require(earliestEpochMillis <= latestEpochMillis)
    }

    fun isStrictlyAfter(other: UtcTimeInterval): Boolean = earliestEpochMillis > other.latestEpochMillis
}

/**
 * Timing evidence captured with a durable local mutation.
 *
 * Missing or uncertain server time MUST NOT select a winning contact version. Conflict resolution
 * uses verified content baselines and an explicit user choice; local revision is a CAS guard.
 */
data class LocalWriteEvidence(
    val revision: Long,
    val deviceWallClockEpochMillis: Long,
    val deviceElapsedRealtimeMillis: Long,
    val serverOffsetMillis: Long?,
    val calibrationAgeMillis: Long?,
    val roundTripMillis: Long?,
    val serverPrecisionMillis: Long?,
    val uncertaintyMillis: Long?,
    val interval: UtcTimeInterval?,
    val clockJumpDetected: Boolean,
    val isComparable: Boolean,
) {
    init {
        require(revision > 0)
        require(deviceElapsedRealtimeMillis >= 0)
        require(calibrationAgeMillis == null || calibrationAgeMillis >= 0)
        require(roundTripMillis == null || roundTripMillis >= 0)
        require(serverPrecisionMillis == null || serverPrecisionMillis >= 0)
        require(uncertaintyMillis == null || uncertaintyMillis >= 0)
        require(isComparable == (interval != null && !clockJumpDetected))
    }
}

data class ServerClockCalibration(
    val observedDeviceWallClockEpochMillis: Long,
    val observedDeviceElapsedRealtimeMillis: Long,
    val serverOffsetMillis: Long,
    val roundTripMillis: Long,
    val serverPrecisionMillis: Long,
) {
    init {
        require(observedDeviceElapsedRealtimeMillis >= 0)
        require(roundTripMillis >= 0)
        require(serverPrecisionMillis >= 0)
    }
}

object LocalWriteEvidenceFactory {
    private const val MAX_CALIBRATION_AGE_MILLIS = 15 * 60 * 1_000L
    private const val CLOCK_JUMP_TOLERANCE_MILLIS = 2_000L
    private const val CLOCK_DRIFT_PARTS_PER_MILLION = 100L

    fun capture(
        revision: Long,
        deviceWallClockEpochMillis: Long,
        deviceElapsedRealtimeMillis: Long,
        calibration: ServerClockCalibration?,
    ): LocalWriteEvidence {
        if (calibration == null || deviceElapsedRealtimeMillis < calibration.observedDeviceElapsedRealtimeMillis) {
            return unavailable(revision, deviceWallClockEpochMillis, deviceElapsedRealtimeMillis)
        }

        val age = deviceElapsedRealtimeMillis - calibration.observedDeviceElapsedRealtimeMillis
        val elapsedWallExpectation = saturatingAdd(calibration.observedDeviceWallClockEpochMillis, age)
        val wallDeviation = saturatingSubtract(deviceWallClockEpochMillis, elapsedWallExpectation).absoluteValueSafe()
        val clockJump = wallDeviation > CLOCK_JUMP_TOLERANCE_MILLIS
        val halfRoundTrip = calibration.roundTripMillis / 2 + calibration.roundTripMillis % 2
        val drift = saturatingMultiply(age, CLOCK_DRIFT_PARTS_PER_MILLION) / 1_000_000L
        val uncertainty = saturatingAdd(
            saturatingAdd(halfRoundTrip, calibration.serverPrecisionMillis),
            saturatingAdd(drift, wallDeviation),
        )
        val aligned = saturatingAdd(deviceWallClockEpochMillis, calibration.serverOffsetMillis)
        val interval = UtcTimeInterval(
            earliestEpochMillis = saturatingSubtract(aligned, uncertainty),
            latestEpochMillis = saturatingAdd(aligned, uncertainty),
        )
        val comparable = age <= MAX_CALIBRATION_AGE_MILLIS && !clockJump

        return LocalWriteEvidence(
            revision = revision,
            deviceWallClockEpochMillis = deviceWallClockEpochMillis,
            deviceElapsedRealtimeMillis = deviceElapsedRealtimeMillis,
            serverOffsetMillis = calibration.serverOffsetMillis,
            calibrationAgeMillis = age,
            roundTripMillis = calibration.roundTripMillis,
            serverPrecisionMillis = calibration.serverPrecisionMillis,
            uncertaintyMillis = uncertainty,
            interval = if (comparable) interval else null,
            clockJumpDetected = clockJump,
            isComparable = comparable,
        )
    }

    fun remoteWholeSecond(epochSeconds: Long): UtcTimeInterval {
        val start = saturatingMultiply(epochSeconds, 1_000L)
        return UtcTimeInterval(start, saturatingAdd(start, 999L))
    }

    private fun unavailable(
        revision: Long,
        wallClock: Long,
        elapsedRealtime: Long,
    ) = LocalWriteEvidence(
        revision = revision,
        deviceWallClockEpochMillis = wallClock,
        deviceElapsedRealtimeMillis = elapsedRealtime,
        serverOffsetMillis = null,
        calibrationAgeMillis = null,
        roundTripMillis = null,
        serverPrecisionMillis = null,
        uncertaintyMillis = null,
        interval = null,
        clockJumpDetected = false,
        isComparable = false,
    )
}

private fun saturatingAdd(left: Long, right: Long): Long = when {
    right > 0 && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0 && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

private fun saturatingSubtract(left: Long, right: Long): Long = when {
    right > 0 && left < Long.MIN_VALUE + right -> Long.MIN_VALUE
    right < 0 && left > Long.MAX_VALUE + right -> Long.MAX_VALUE
    else -> left - right
}

private fun saturatingMultiply(left: Long, right: Long): Long {
    return try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        if ((left xor right) >= 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
}

private fun Long.absoluteValueSafe(): Long = if (this == Long.MIN_VALUE) Long.MAX_VALUE else absoluteValue
