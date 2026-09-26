package com.patmanak.contako.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictPolicyTest {
    @Test
    fun `03-ORDER update update implements every D-024 ordering cell`() {
        assertEquals(ConflictResolution.LOCAL_WINS, resolve(UPDATE, interval(3_000, 3_100), UPDATE, interval(1_000, 1_999)))
        assertEquals(ConflictResolution.REMOTE_WINS, resolve(UPDATE, interval(1_000, 1_999), UPDATE, interval(3_000, 3_100)))
        assertEquals(ConflictResolution.LOCAL_WINS, resolve(UPDATE, interval(1_000, 2_000), UPDATE, interval(2_000, 3_000)))
        assertEquals(ConflictResolution.LOCAL_WINS, resolve(UPDATE, interval(1_000, 2_000), UPDATE, interval(1_000, 2_000)))
        assertEquals(ConflictResolution.LOCAL_WINS, resolve(UPDATE, null, UPDATE, interval(3_000, 4_000)))
        assertEquals(ConflictResolution.LOCAL_WINS, resolve(UPDATE, interval(3_000, 4_000), UPDATE, null))
    }

    @Test
    fun `03-ORDER edit delete uses time only when intervals are strictly comparable`() {
        listOf(UPDATE to DELETE, DELETE to UPDATE).forEach { (localChange, remoteChange) ->
            assertEquals(
                ConflictResolution.LOCAL_WINS,
                resolve(localChange, interval(4_000, 5_000), remoteChange, interval(1_000, 2_000)),
            )
            assertEquals(
                ConflictResolution.REMOTE_WINS,
                resolve(localChange, interval(1_000, 2_000), remoteChange, interval(4_000, 5_000)),
            )
            assertEquals(
                ConflictResolution.ACTION_REQUIRED,
                resolve(localChange, interval(1_000, 2_000), remoteChange, interval(2_000, 3_000)),
            )
            assertEquals(ConflictResolution.ACTION_REQUIRED, resolve(localChange, null, remoteChange, interval(2_000, 3_000)))
            assertEquals(ConflictResolution.ACTION_REQUIRED, resolve(localChange, interval(2_000, 3_000), remoteChange, null))
        }
    }

    @Test
    fun `03-ORDER identical deletes converge deterministically`() {
        assertEquals(ConflictResolution.LOCAL_WINS, resolve(DELETE, null, DELETE, null))
    }

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

    private fun resolve(
        localChange: ConflictChange,
        localInterval: UtcTimeInterval?,
        remoteChange: ConflictChange,
        remoteInterval: UtcTimeInterval?,
    ): ConflictResolution = ContactConflictPolicy.resolve(
        localChange,
        evidence(localInterval),
        remoteChange,
        remoteInterval,
    )

    private fun evidence(interval: UtcTimeInterval?) = LocalWriteEvidence(
        revision = 1,
        deviceWallClockEpochMillis = 1,
        deviceElapsedRealtimeMillis = 1,
        serverOffsetMillis = interval?.let { 0 },
        calibrationAgeMillis = interval?.let { 0 },
        roundTripMillis = interval?.let { 0 },
        serverPrecisionMillis = interval?.let { 0 },
        uncertaintyMillis = interval?.let { 0 },
        interval = interval,
        clockJumpDetected = false,
        isComparable = interval != null,
    )

    private fun interval(start: Long, end: Long) = UtcTimeInterval(start, end)

    private companion object {
        val UPDATE = ConflictChange.UPDATE
        val DELETE = ConflictChange.DELETE
    }
}
