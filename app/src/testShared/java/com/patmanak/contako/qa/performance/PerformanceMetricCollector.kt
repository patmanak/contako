package com.patmanak.contako.qa.performance

internal object PerformanceMetricCollector {
    fun toJson(run: PerformanceRun): String = buildString {
        append('{')
        append("\"schemaVersion\":2,")
        append("\"runId\":\"").append(escape(run.environment.runId)).append("\",")
        append("\"caseId\":\"").append(run.environment.caseId).append("\",")
        append("\"fixtureId\":\"").append(run.environment.fixtureId).append("\",")
        append("\"fixtureSeed\":").append(run.environment.fixtureSeed).append(',')
        append("\"buildType\":\"").append(run.environment.buildType.name.lowercase()).append("\",")
        append("\"deviceClass\":\"").append(escape(run.environment.deviceClass)).append("\",")
        append("\"apiLevel\":").append(run.environment.apiLevel).append(',')
        append("\"thermalState\":\"").append(run.environment.thermalState).append("\",")
        append("\"batteryPercent\":").append(run.environment.batteryPercent).append(',')
        append("\"chargingState\":\"").append(run.environment.chargingState).append("\",")
        append("\"sourceRevision\":\"").append(run.environment.sourceRevision).append("\",")
        append("\"buildIdentifier\":\"").append(run.environment.buildIdentifier).append("\",")
        append("\"benchmarkCommand\":\"").append(escape(run.environment.benchmarkCommand)).append("\",")
        append("\"applicationApkSha256\":\"").append(run.environment.applicationApkSha256.lowercase()).append("\",")
        append("\"testApkSha256\":\"").append(run.environment.testApkSha256.lowercase()).append("\",")
        append("\"networkProfile\":\"").append(run.environment.networkProfile).append("\",")
        append("\"cacheState\":\"").append(run.environment.cacheState).append("\",")
        append("\"warmupCount\":").append(run.warmupCount).append(',')
        append("\"sampleCount\":").append(run.samples.size).append(',')
        append("\"percentileMethod\":\"nearest-rank\",")
        append("\"p95DurationMs\":").append(run.p95DurationMs).append(',')
        append("\"violations\":[")
        append(run.violations().joinToString(",") { "\"$it\"" }).append("],")
        append("\"warmups\":[")
        append(run.warmupSamples.joinToString(",") { sampleJson(it) }).append("],")
        append("\"samples\":[")
        append(run.samples.joinToString(",") { sampleJson(it) })
        append("]}")
    }

    fun toCsv(run: PerformanceRun): String = buildString {
        append("run_id,case_id,fixture_id,build_type,phase,ordinal,duration_ms,requests,provider_queries,query_rows,provider_batches,provider_operations,max_batch_operations,max_batch_estimated_bytes,photo_streams,no_change_writes,heap_delta_bytes,max_cursor_rows,commits,checkpoints,heartbeats,crashes,anr_proxies,oom_proxies\n")
        fun appendSample(phase: String, sample: PerformanceSample) {
            append(csv(run.environment.runId)).append(',')
            append(run.environment.caseId).append(',').append(run.environment.fixtureId).append(',')
            append(run.environment.buildType.name.lowercase()).append(',').append(phase).append(',').append(sample.ordinal).append(',')
            append(sample.durationMs).append(',').append(sample.requestCount).append(',')
            append(sample.providerQueryCount).append(',').append(sample.providerQueryRows).append(',')
            append(sample.providerBatchCount).append(',').append(sample.providerOperationCount).append(',')
            append(sample.maxProviderBatchOperations).append(',')
            append(sample.maxProviderBatchEstimatedBytes).append(',')
            append(sample.photoStreamCount).append(',').append(sample.noChangeWriteCount).append(',')
            append(sample.heapDeltaBytes).append(',').append(sample.maxCursorRows).append(',')
            append(sample.commitCount).append(',')
            append(sample.checkpointCount).append(',').append(sample.heartbeatCount).append(',')
            append(sample.crashCount).append(',').append(sample.anrProxyCount).append(',')
            append(sample.oomProxyCount).append('\n')
        }
        run.warmupSamples.forEach { appendSample("warmup", it) }
        run.samples.forEach { appendSample("sample", it) }
    }

    private fun sampleJson(sample: PerformanceSample) =
        "{\"ordinal\":${sample.ordinal},\"durationMs\":${sample.durationMs}," +
            "\"requestCount\":${sample.requestCount},\"providerBatchCount\":${sample.providerBatchCount}," +
            "\"providerOperationCount\":${sample.providerOperationCount},\"heapDeltaBytes\":${sample.heapDeltaBytes}," +
            "\"maxCursorRows\":${sample.maxCursorRows},\"commitCount\":${sample.commitCount}," +
            "\"checkpointCount\":${sample.checkpointCount},\"providerQueryCount\":${sample.providerQueryCount}," +
            "\"providerQueryRows\":${sample.providerQueryRows},\"maxProviderBatchOperations\":${sample.maxProviderBatchOperations}," +
            "\"maxProviderBatchEstimatedBytes\":${sample.maxProviderBatchEstimatedBytes},\"photoStreamCount\":${sample.photoStreamCount}," +
            "\"noChangeWriteCount\":${sample.noChangeWriteCount},\"heartbeatCount\":${sample.heartbeatCount}," +
            "\"crashCount\":${sample.crashCount},\"anrProxyCount\":${sample.anrProxyCount}," +
            "\"oomProxyCount\":${sample.oomProxyCount}}"

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append('?') else append(character)
            }
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
