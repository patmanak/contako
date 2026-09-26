package com.patmanak.contako.qa.performance

import kotlin.math.ceil

internal enum class PerformanceBuildType { DEBUG, RELEASE, BENCHMARK }

internal data class PerformanceEnvironment(
    val runId: String,
    val caseId: String,
    val fixtureId: String,
    val fixtureSeed: Long,
    val buildType: PerformanceBuildType,
    val deviceClass: String,
    val apiLevel: Int,
    val thermalState: String,
    val batteryPercent: Int,
    val networkProfile: String,
    val cacheState: String,
    val chargingState: String = "unknown",
    val sourceRevision: String = "unspecified",
    val buildIdentifier: String = "unspecified",
    val benchmarkCommand: String = "unspecified",
    val applicationApkSha256: String = "unspecified",
    val testApkSha256: String = "unspecified",
) {
    init {
        require(runId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(caseId.matches(Regex("07-[A-Z0-9-]{2,48}")))
        require(fixtureId.matches(Regex("FX-P-[A-Z0-9-]{3,16}")))
        require(deviceClass.matches(Regex("[A-Za-z0-9._ -]{1,80}")))
        require(apiLevel in 31..100)
        require(thermalState in setOf("nominal", "light", "moderate", "severe", "critical", "emergency", "shutdown"))
        require(batteryPercent in -1..100)
        require(networkProfile in setOf("none", "fake-wifi", "fake-cellular", "fake-shaped"))
        require(cacheState in setOf("cold", "warm", "mixed"))
        require(chargingState in setOf("charging", "discharging", "not_charging", "full", "unknown"))
        require(sourceRevision.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(buildIdentifier.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(benchmarkCommand.length in 1..1_000 && benchmarkCommand.none { it == '\n' || it == '\r' })
        require(!benchmarkCommand.contains(Regex("(?i)([a-z]:[\\\\/]|/users/|/home/|\\\\\\\\)")))
        require(applicationApkSha256 == "unspecified" || applicationApkSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(testApkSha256 == "unspecified" || testApkSha256.matches(Regex("[a-fA-F0-9]{64}")))
    }
}

internal data class PerformanceCeilings(
    val durationMs: Long,
    val requestCount: Int,
    val providerBatchCount: Int,
    val providerOperationCount: Int,
    val heapDeltaBytes: Long,
    val cursorRows: Int,
) {
    init {
        require(durationMs > 0 && requestCount >= 0 && providerBatchCount >= 0)
        require(providerOperationCount >= 0 && heapDeltaBytes > 0 && cursorRows in 0..100)
    }
}

internal data class PerformanceSample(
    val ordinal: Int,
    val durationMs: Long,
    val requestCount: Int,
    val providerBatchCount: Int,
    val providerOperationCount: Int,
    val heapDeltaBytes: Long,
    val maxCursorRows: Int,
    val commitCount: Int,
    val checkpointCount: Int,
    val providerQueryCount: Int = 0,
    val providerQueryRows: Int = 0,
    val maxProviderBatchOperations: Int = 0,
    val maxProviderBatchEstimatedBytes: Int = 0,
    val photoStreamCount: Int = 0,
    val noChangeWriteCount: Int = 0,
    val heartbeatCount: Int = 0,
    val crashCount: Int = 0,
    val anrProxyCount: Int = 0,
    val oomProxyCount: Int = 0,
) {
    init {
        require(ordinal >= 0 && durationMs >= 0 && requestCount >= 0)
        require(providerBatchCount >= 0 && providerOperationCount >= 0)
        require(heapDeltaBytes >= 0 && maxCursorRows >= 0 && commitCount >= 0 && checkpointCount >= 0)
        require(providerQueryCount >= 0 && providerQueryRows >= 0)
        require(maxProviderBatchOperations in 0..200 && maxProviderBatchEstimatedBytes >= 0)
        require(photoStreamCount >= 0 && noChangeWriteCount >= 0 && heartbeatCount >= 0)
        require(crashCount >= 0 && anrProxyCount >= 0 && oomProxyCount >= 0)
    }
}

internal data class PerformanceRun(
    val environment: PerformanceEnvironment,
    val warmupCount: Int,
    val samples: List<PerformanceSample>,
    val ceilings: PerformanceCeilings,
    val warmupSamples: List<PerformanceSample> = emptyList(),
) {
    init {
        require(warmupCount == REQUIRED_WARMUPS)
        require(samples.size == REQUIRED_SAMPLES)
        require(samples.map(PerformanceSample::ordinal) == (1..REQUIRED_SAMPLES).toList())
        require(warmupSamples.isEmpty() || warmupSamples.map(PerformanceSample::ordinal) == (1..REQUIRED_WARMUPS).toList())
    }

    val p95DurationMs: Long get() = nearestRank(samples.map(PerformanceSample::durationMs), 95)

    fun violations(): List<String> = buildList {
        if (p95DurationMs > ceilings.durationMs) add("duration_p95")
        if (samples.maxOf(PerformanceSample::requestCount) > ceilings.requestCount) add("requests")
        if (samples.maxOf(PerformanceSample::providerBatchCount) > ceilings.providerBatchCount) add("provider_batches")
        if (samples.maxOf(PerformanceSample::providerOperationCount) > ceilings.providerOperationCount) add("provider_operations")
        if (samples.maxOf(PerformanceSample::heapDeltaBytes) > ceilings.heapDeltaBytes) add("heap_delta")
        if (samples.maxOf(PerformanceSample::maxCursorRows) > ceilings.cursorRows) add("cursor_rows")
    }

    companion object {
        const val REQUIRED_WARMUPS = 5
        const val REQUIRED_SAMPLES = 30

        fun nearestRank(values: List<Long>, percentile: Int): Long {
            require(values.isNotEmpty() && percentile in 1..100)
            val sorted = values.sorted()
            return sorted[ceil(percentile / 100.0 * sorted.size).toInt() - 1]
        }
    }
}

internal class PerformanceCounter(private val monotonicNanos: () -> Long = System::nanoTime) {
    private var startedAt = 0L
    private var startHeap = 0L
    private var peakHeap = 0L
    private var requests = 0
    private var batches = 0
    private var operations = 0
    private var cursorRows = 0
    private var commits = 0
    private var checkpoints = 0
    private var providerQueries = 0
    private var providerQueryRows = 0
    private var maxBatchOperations = 0
    private var maxBatchEstimatedBytes = 0
    private var photoStreams = 0
    private var noChangeWrites = 0
    private var heartbeats = 0
    private var crashes = 0
    private var anrProxies = 0
    private var oomProxies = 0

    fun start(heapBytes: Long = usedHeapBytes()) {
        startedAt = monotonicNanos()
        startHeap = heapBytes
        peakHeap = heapBytes
    }

    fun request(count: Int = 1) { require(count > 0); requests += count }
    fun providerBatch(operationCount: Int, estimatedBytes: Int = 0) {
        require(operationCount in 0..200 && estimatedBytes >= 0)
        batches++
        operations += operationCount
        maxBatchOperations = maxOf(maxBatchOperations, operationCount)
        maxBatchEstimatedBytes = maxOf(maxBatchEstimatedBytes, estimatedBytes)
    }
    fun providerQuery(rows: Int) {
        require(rows in 0..100)
        providerQueries++
        providerQueryRows += rows
        observeCursor(rows)
    }
    fun photoStream() { photoStreams++ }
    fun noChangeWrite() { noChangeWrites++ }
    fun heartbeat() { heartbeats++ }
    fun crashProxy() { crashes++ }
    fun anrProxy() { anrProxies++ }
    fun oomProxy() { oomProxies++ }
    fun observeHeap(bytes: Long = usedHeapBytes()) { require(bytes >= 0); peakHeap = maxOf(peakHeap, bytes) }
    fun observeCursor(rows: Int) { require(rows in 0..100); cursorRows = maxOf(cursorRows, rows) }
    fun commit() { commits++ }
    fun checkpoint() { checkpoints++ }

    fun finish(ordinal: Int, heapBytes: Long = usedHeapBytes()): PerformanceSample {
        require(startedAt != 0L)
        observeHeap(heapBytes)
        return PerformanceSample(
            ordinal = ordinal,
            durationMs = (monotonicNanos() - startedAt).coerceAtLeast(0) / 1_000_000,
            requestCount = requests,
            providerBatchCount = batches,
            providerOperationCount = operations,
            heapDeltaBytes = (peakHeap - startHeap).coerceAtLeast(0),
            maxCursorRows = cursorRows,
            commitCount = commits,
            checkpointCount = checkpoints,
            providerQueryCount = providerQueries,
            providerQueryRows = providerQueryRows,
            maxProviderBatchOperations = maxBatchOperations,
            maxProviderBatchEstimatedBytes = maxBatchEstimatedBytes,
            photoStreamCount = photoStreams,
            noChangeWriteCount = noChangeWrites,
            heartbeatCount = heartbeats,
            crashCount = crashes,
            anrProxyCount = anrProxies,
            oomProxyCount = oomProxies,
        )
    }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
