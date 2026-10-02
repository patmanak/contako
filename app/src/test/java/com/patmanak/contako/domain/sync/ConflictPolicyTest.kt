package com.patmanak.contako.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictPolicyTest {
    @Test
    fun `capture retains server aligned uncertainty inputs`() {
        val evidence = LocalWriteEvidenceFactory.capture(
            revision = 7,
            deviceWallClockEpochMillis = 1_010_000,
            deviceElapsedRealtimeMillis = 20_000,
            calibration = ServerClockCalibration(
                observedDeviceWallClockEpochMillis = 1_000_000,
                observedDeviceElapsedRealtimeMillis = 10_000,
                serverOffsetMillis = 500,
                roundTripMillis = 101,
                serverPrecisionMillis = 1_000,
            ),
        )

        assertTrue(evidence.isComparable)
        assertFalse(evidence.clockJumpDetected)
        assertEquals(10_000L, evidence.calibrationAgeMillis)
        assertEquals(1_052L, evidence.uncertaintyMillis)
        assertEquals(UtcTimeInterval(1_009_448, 1_011_552), evidence.interval)
    }

    @Test
    fun `missing stale future or jumped calibration is not comparable`() {
        val missing = LocalWriteEvidenceFactory.capture(1, 10_000, 5_000, null)
        val stale = LocalWriteEvidenceFactory.capture(
            2,
            2_000_000,
            1_000_001,
            ServerClockCalibration(1_100_000, 100_000, 0, 10, 1_000),
        )
        val future = LocalWriteEvidenceFactory.capture(
            3,
            2_000,
            2_000,
            ServerClockCalibration(1_000, 3_000, 0, 10, 1_000),
        )
        val jumped = LocalWriteEvidenceFactory.capture(
            4,
            20_000,
            2_000,
            ServerClockCalibration(10_000, 1_000, 0, 10, 1_000),
        )

        listOf(missing, stale, future, jumped).forEach {
            assertFalse(it.isComparable)
            assertNull(it.interval)
        }
        assertTrue(jumped.clockJumpDetected)
    }

    @Test
    fun `whole second Proton timestamp represents the complete second`() {
        assertEquals(UtcTimeInterval(12_000, 12_999), LocalWriteEvidenceFactory.remoteWholeSecond(12))
    }

}
