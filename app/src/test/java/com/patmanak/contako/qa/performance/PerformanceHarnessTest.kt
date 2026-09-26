package com.patmanak.contako.qa.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceHarnessTest {
    @Test
    fun `07-HARNESS fixtures are deterministic and profile sizes are exact`() {
        PerformanceFixtureProfile.entries.forEach { profile ->
            val first = PerformanceFixtureGenerator.generate(profile, 42)
            val second = PerformanceFixtureGenerator.generate(profile, 42)
            assertEquals(profile.contactCount, first.contacts.size)
            assertEquals(profile.groupCount, first.groups.size)
            assertEquals(first, second)
            assertEquals(first.canonicalDigest, second.canonicalDigest)
            if (profile.contactCount > 0) {
                assertNotEquals(first.canonicalDigest, PerformanceFixtureGenerator.generate(profile, 43).canonicalDigest)
            }
        }
    }

    @Test
    fun `07-HARNESS PHOTO and VCARD payloads remain bounded`() {
        val photo = PerformanceFixtureGenerator.generate(PerformanceFixtureProfile.PHOTO)
        val vcard = PerformanceFixtureGenerator.generate(PerformanceFixtureProfile.VCARD)
        assertTrue(photo.contacts.all { it.photoBytes.size in 32 * 1_024..PerformanceFixtureGenerator.MAX_PHOTO_BYTES })
        assertTrue(vcard.contacts.all { it.opaqueVCardProperty.length in 2 * 1_024..PerformanceFixtureGenerator.MAX_VCARD_PROPERTY_CHARS })
        assertTrue(photo.contacts.all { it.email.endsWith("@example.invalid") })
    }

    @Test
    fun `07-HARNESS P95 uses nearest rank and excludes five warmups`() {
        val values = (1L..30L).toList()
        assertEquals(29L, PerformanceRun.nearestRank(values, 95))
        val run = run(values)
        assertEquals(5, run.warmupCount)
        assertEquals(30, run.samples.size)
        assertEquals(29L, run.p95DurationMs)
    }

    @Test
    fun `07-HARNESS counter captures requests provider heap duration and checkpoints`() {
        var nanos = 1_000_000L
        val counter = PerformanceCounter { nanos }
        counter.start(heapBytes = 1_000)
        counter.request(2)
        counter.providerQuery(100)
        counter.providerBatch(100, 8_192)
        counter.photoStream()
        counter.noChangeWrite()
        counter.heartbeat()
        counter.observeHeap(4_000)
        counter.commit()
        counter.checkpoint()
        nanos += 12_000_000
        assertEquals(
            PerformanceSample(
                1, 12, 2, 1, 100, 3_000, 100, 1, 1,
                providerQueryCount = 1,
                providerQueryRows = 100,
                maxProviderBatchOperations = 100,
                maxProviderBatchEstimatedBytes = 8_192,
                photoStreamCount = 1,
                noChangeWriteCount = 1,
                heartbeatCount = 1,
            ),
            counter.finish(1, 2_000),
        )
    }

    @Test
    fun `07-HARNESS JSON and CSV serialization is stable and redacted by construction`() {
        val warmups = (1L..5L).mapIndexed { index, value -> sample(index + 1, value) }
        val run = run((1L..30L).toList(), runId = "offline-run", warmups = warmups)
        val json = PerformanceMetricCollector.toJson(run)
        val csv = PerformanceMetricCollector.toCsv(run)
        assertEquals(json, PerformanceMetricCollector.toJson(run))
        assertEquals(csv, PerformanceMetricCollector.toCsv(run))
        assertTrue(json.contains("\"percentileMethod\":\"nearest-rank\""))
        assertTrue(json.contains("\"maxProviderBatchEstimatedBytes\""))
        assertTrue(json.contains("\"schemaVersion\":2"))
        assertTrue(json.contains("\"warmups\":[{\"ordinal\":1"))
        assertTrue(json.contains("\"chargingState\":\"unknown\""))
        assertTrue(json.contains("\"sourceRevision\":\"unspecified\""))
        assertTrue(json.contains("\"applicationApkSha256\":\"unspecified\""))
        assertTrue(csv.contains("phase,ordinal"))
        assertEquals(36, csv.lineSequence().filter(String::isNotEmpty).count())
        assertEquals(5, csv.lineSequence().count { it.contains(",warmup,") })
        listOf("password-canary", "BEGIN:VCARD", "fixture-1@example.invalid", "+1555", "account-id").forEach {
            assertFalse(json.contains(it)); assertFalse(csv.contains(it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `07-HARNESS metadata rejects values that could carry payloads`() {
        environment(runId = "bad\npassword-canary")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `07-HARNESS command metadata rejects machine paths`() {
        environment(runId = "safe-run").copy(benchmarkCommand = "C:\\Users\\private\\gradlew.bat test")
    }

    private fun run(
        values: List<Long>,
        runId: String = "run-1",
        warmups: List<PerformanceSample> = emptyList(),
    ) = PerformanceRun(
        environment = environment(runId),
        warmupCount = 5,
        samples = values.mapIndexed { index, value -> PerformanceSample(index + 1, value, 1, 1, 10, 1_024, 100, 1, 1) },
        ceilings = PerformanceCeilings(500, 2, 2, 200, 64 * 1_024 * 1_024, 100),
        warmupSamples = warmups,
    )

    private fun sample(ordinal: Int, durationMs: Long) =
        PerformanceSample(ordinal, durationMs, 1, 1, 10, 1_024, 100, 1, 1)

    private fun environment(runId: String) = PerformanceEnvironment(
        runId, "07-LOCAL-CACHED", "FX-P-300", 42, PerformanceBuildType.DEBUG,
        "host-jvm", 36, "nominal", 80, "none", "warm",
    )
}
