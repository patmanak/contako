package com.patmanak.contako.data.sync

import com.patmanak.contako.domain.sync.LocalWriteEvidenceFactory
import org.junit.Assert.*
import org.junit.Test

class VerifiedServerClockTest {
    private val serverMillis = 1_600_000_000_000L
    private val date = "Sun, 13 Sep 2020 12:26:40 GMT"

    private fun sample(clock: VerifiedServerClock, https: Boolean = true, host: String = "api.protonmail.ch",
        header: String? = date, age: String? = null, elapsedEnd: Long = 1_200, wallEnd: Long = 10_200,
    ) = clock.observe(https, host, header, age, 10_000, 1_000, wallEnd, elapsedEnd)

    @Test fun calibratedWritesRetainServerAlignedEvidenceWithoutChoosingAWinner() {
        val clock = VerifiedServerClock()
        sample(clock)
        val calibration = requireNotNull(clock.current())
        assertEquals(200L, calibration.roundTripMillis)
        assertEquals(1_000L, calibration.serverPrecisionMillis)
        val evidence = LocalWriteEvidenceFactory.capture(1, 11_200, 2_200, calibration)
        assertTrue(evidence.isComparable)
        assertTrue(requireNotNull(evidence.interval).earliestEpochMillis <= serverMillis + 1_200)
        assertTrue(requireNotNull(evidence.interval).latestEpochMillis >= serverMillis + 1_200)
    }

    @Test fun untrustedMissingMalformedAndCachedTimeNeverCalibrates() {
        val clock = VerifiedServerClock()
        sample(clock, https = false)
        sample(clock, host = "untrusted.example")
        sample(clock, header = null)
        sample(clock, header = "invalid")
        sample(clock, age = "60")
        sample(clock, elapsedEnd = 40_000, wallEnd = 49_000)
        assertNull(clock.current())
        val evidence = LocalWriteEvidenceFactory.capture(1, 20_000, 11_000, clock.current())
        assertFalse(evidence.isComparable)
    }

    @Test fun ageAndClockChangesInvalidateComparability() {
        val clock = VerifiedServerClock()
        sample(clock)
        assertFalse(LocalWriteEvidenceFactory.capture(1, 910_201, 901_201, clock.current()).isComparable)
        assertFalse(LocalWriteEvidenceFactory.capture(1, 99_999, 1_300, clock.current()).isComparable)
        sample(clock, wallEnd = 99_999)
        assertNull(clock.current())
    }

    @Test fun smallWallAdjustmentsWidenTheIntervalInsteadOfInventingPrecision() {
        val clock = VerifiedServerClock()
        sample(clock)
        val evidence = LocalWriteEvidenceFactory.capture(1, 11_800, 1_300, clock.current())
        assertTrue(evidence.isComparable)
        assertEquals(2_600L, evidence.uncertaintyMillis)
    }

    @Test fun olderConcurrentResponseCannotReplaceNewerCalibration() {
        val clock = VerifiedServerClock()
        sample(clock, elapsedEnd = 1_400, wallEnd = 10_400)
        val newer = clock.current()
        sample(clock)
        assertSame(newer, clock.current())
    }
}
