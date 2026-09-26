package com.patmanak.contako.data.sync

import com.patmanak.contako.domain.sync.ServerClockCalibration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Process-only UTC evidence shared by UI and Android writers; contains no account/session data. */
internal class VerifiedServerClock {
    @Volatile private var calibration: ServerClockCalibration? = null

    fun current(): ServerClockCalibration? = calibration

    @Synchronized fun observe(
        verifiedHttps: Boolean,
        host: String,
        date: String?,
        ageSeconds: String?,
        startWallMillis: Long,
        startElapsedMillis: Long,
        endWallMillis: Long,
        endElapsedMillis: Long,
    ) {
        if (!verifiedHttps || host != "api.protonmail.ch" || date == null ||
            (ageSeconds != null && ageSeconds != "0")
        ) return
        try {
            val roundTrip = Math.subtractExact(endElapsedMillis, startElapsedMillis)
            val wallDuration = Math.subtractExact(endWallMillis, startWallMillis)
            if (roundTrip < 0 || wallDuration < roundTrip - 2_000 || wallDuration > roundTrip + 2_000) {
                calibration = null
                return
            }
            // Do not promote a stalled request into useful conflict-ordering evidence.
            if (roundTrip > 30_000 || startElapsedMillis < 0) return
            val serverMillis = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            val midpointWall = Math.addExact(startWallMillis, roundTrip / 2)
            val offset = Math.subtractExact(Math.addExact(serverMillis, 500), midpointWall)
            // Concurrent requests may complete out of order. Never replace newer evidence.
            if ((calibration?.observedDeviceElapsedRealtimeMillis ?: Long.MIN_VALUE) > endElapsedMillis) return
            calibration = ServerClockCalibration(
                observedDeviceWallClockEpochMillis = endWallMillis,
                observedDeviceElapsedRealtimeMillis = endElapsedMillis,
                serverOffsetMillis = offset,
                roundTripMillis = roundTrip,
                serverPrecisionMillis = 1_000 + kotlin.math.abs(wallDuration - roundTrip),
            )
        } catch (_: RuntimeException) {
            // Invalid HTTP date/overflow is unavailable evidence, never an invented local time.
        }
    }
}

internal object ProductionServerClock {
    val calibration = VerifiedServerClock()
}
