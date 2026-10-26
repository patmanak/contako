package com.patmanak.contako.qa.performance

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.domain.model.CanonicalContact
import java.text.Collator
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated CPU attribution, not a Room/UI latency or full-directory qualification.
 *
 * EXACT_CURRENT retains the pre-optimization comparator: getInstance(getDefault()) on EVERY
 * comparison. ONE_PER_SORT creates its Collator inside EVERY timed sort operation. Both include
 * sortedWith's list allocation and resolvedDisplayName access. Fixture creation and correctness
 * assertions are outside timing. Locale and strength are never changed. Physical minified runs
 * require the same mandatory source/APK arguments as the directory benchmark.
 */
@RunWith(AndroidJUnit4::class)
class ContactSortPerformanceDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun compareCollatorConstructionOnIdenticalPermutations() {
        require(BuildConfig.BUILD_TYPE == "benchmark") { "Use the minified benchmark variant" }
        require(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val evidence = context.performanceEvidence()
        val selected = (InstrumentationRegistry.getArguments().getString("performanceProfiles")
            ?: "nominal,robustness").split(',').map(String::trim).toSet()
        require(selected.isNotEmpty() && selected.all { it in setOf("nominal", "robustness") })
        selected.forEach { profile ->
            compareProfile(if (profile == "nominal") 300 else 5_000, evidence)
        }
    }

    private fun compareProfile(count: Int, evidence: AndroidPerformanceEvidence) {
        val locale = Locale.getDefault()
        val contacts = fixture(count)
        val sourceIds = contacts.map { it.id }
        val modes = Mode.entries.associateWith { State(it, environment(count, it, evidence)) }
        val ceilings = PerformanceCeilings(Long.MAX_VALUE, 0, 0, 0, Long.MAX_VALUE, 100)
        val scopeId = "09-sort-$count-scope.json"
        context.writePerformanceArtifact(scopeId, """
            {"schemaVersion":1,"layer":"isolated-comparator-component-not-production-total",
            "contactCount":$count,"fixtureSeed":$SEED,"locale":"${locale.toLanguageTag()}",
            "permutation":"same-seeded-permutation-every-operation","pairOrder":"alternating",
            "warmupsPerConfiguration":5,"samplesPerConfiguration":30,
            "clock":"elapsedRealtimeNanos","oneCollatorConstructionIncluded":true,
            "duplicateNames":"stable-input-order-asserted","hashNames":"ordinary-collation-unchanged",
            "memory":"NOT_MEASURED","room":"NOT_RUN","compose":"NOT_RUN",
            "network":"not-constructed","provider":"not-constructed","account":"not-constructed"}
        """.trimIndent())

        repeat(PerformanceRun.REQUIRED_WARMUPS + PerformanceRun.REQUIRED_SAMPLES) { index ->
            val warmup = index < PerformanceRun.REQUIRED_WARMUPS
            val ordinal = if (warmup) index + 1 else index - PerformanceRun.REQUIRED_WARMUPS + 1
            val order = if (index % 2 == 0) Mode.entries else Mode.entries.reversed()
            val results = mutableMapOf<Mode, List<CanonicalContact>>()
            order.forEach { mode ->
                check(Locale.getDefault() == locale) { "Default locale changed during qualification" }
                var outcome = PerformanceSampleOutcome.SUCCESS
                var result = emptyList<CanonicalContact>()
                val startedAt = SystemClock.elapsedRealtimeNanos()
                try {
                    result = when (mode) {
                        Mode.EXACT_CURRENT -> contacts.sortedWith { left, right ->
                            Collator.getInstance(Locale.getDefault()).compare(
                                left.resolvedDisplayName, right.resolvedDisplayName,
                            )
                        }
                        Mode.ONE_PER_SORT -> {
                            val collator = Collator.getInstance(Locale.getDefault())
                            contacts.sortedWith { left, right ->
                                collator.compare(left.resolvedDisplayName, right.resolvedDisplayName)
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    outcome = PerformanceSampleOutcome.CANCELLED
                } catch (_: Throwable) {
                    outcome = PerformanceSampleOutcome.LOCAL_FAILURE
                }
                val nanos = (SystemClock.elapsedRealtimeNanos() - startedAt).coerceAtLeast(0)
                val state = modes.getValue(mode)
                state.add(warmup, ordinal, nanos, outcome)
                results[mode] = result
                // Errors retain measured elapsed time and partial series before asserting.
                persist(state, ceilings)
                assertEquals("Sort failed; see local component evidence", PerformanceSampleOutcome.SUCCESS, outcome)
            }
            try {
                assertEquals(locale, Locale.getDefault())
                val baseline = results.getValue(Mode.EXACT_CURRENT)
                val candidate = results.getValue(Mode.ONE_PER_SORT)
                assertEquals(baseline.map { it.id }, candidate.map { it.id })
                assertEquals(count, candidate.size)
                assertEquals(sourceIds.toSet(), candidate.map { it.id }.toSet())
                assertEquals(sourceIds, contacts.map { it.id })
                // Equal names MUST retain their exact source order, including duplicated # names.
                val inputTies = contacts.groupBy { it.resolvedDisplayName }
                    .mapValues { (_, tied) -> tied.map { it.id } }
                val outputTies = candidate.groupBy { it.resolvedDisplayName }
                    .mapValues { (_, tied) -> tied.map { it.id } }
                assertEquals(inputTies, outputTies)
            } catch (_: Throwable) {
                modes.values.forEach { state ->
                    state.failLast(warmup)
                    persist(state, ceilings)
                }
                throw AssertionError("Sort identity/order/stability postcondition failed; see local evidence")
            }
        }
        modes.values.forEach { state ->
            val run = PerformanceRun(state.environment, PerformanceRun.REQUIRED_WARMUPS,
                state.samples, ceilings, state.warmups)
            context.writePerformanceArtifact("${state.environment.runId}.json", PerformanceMetricCollector.toJson(run))
            context.writePerformanceArtifact("${state.environment.runId}.csv", PerformanceMetricCollector.toCsv(run))
            val nanos = state.observations.filterNot { it.warmup }.map { it.durationNanos }
            println("CONTAKO_SORT ${state.environment.runId} " +
                "medianUs=${median(nanos) / 1_000.0} p95Us=${PerformanceRun.nearestRank(nanos, 95) / 1_000.0} " +
                "maxUs=${nanos.max() / 1_000.0} exactOrder=true")
        }
    }

    private fun persist(state: State, ceilings: PerformanceCeilings) {
        val report = PerformanceReport(state.environment, state.warmups.size,
            state.samples, ceilings, state.warmups)
        context.writePerformanceArtifact("${state.environment.runId}.json", PerformanceMetricCollector.toJson(report))
        context.writePerformanceArtifact("${state.environment.runId}.csv", PerformanceMetricCollector.toCsv(report))
        val measured = state.observations.filterNot { it.warmup }.map { it.durationNanos }
        val p95 = if (report.isComplete) PerformanceRun.nearestRank(measured, 95).toString() else "null"
        val median = if (measured.isEmpty()) "null" else median(measured).toString()
        val maximum = measured.maxOrNull()?.toString() ?: "null"
        context.writePerformanceArtifact("${state.environment.runId}-nanos.json", buildString {
            append("{\"schemaVersion\":1,\"layer\":\"isolated-sort-component\",\"unit\":\"nanoseconds\",")
            append("\"median\":$median,\"p95\":$p95,\"maximum\":$maximum,\"samples\":[")
            append(state.observations.joinToString(",") {
                "{\"ordinal\":${it.ordinal},\"warmup\":${it.warmup}," +
                    "\"durationNanos\":${it.durationNanos},\"outcome\":\"${it.outcome.name}\"}"
            })
            append("]}")
        })
    }

    private fun fixture(count: Int): List<CanonicalContact> {
        val names = listOf("Élodie", "İpek", "Ångström", "Östen", "李 明", "Αλέξανδρος", "Мария", "# Directory", "Álvaro", "Zulu")
        return List(count) { index ->
            // Two distinct identities per exact name, repeated # names included.
            val nameOrdinal = index / 2
            CanonicalContact("synthetic-sort-owner", "sort-${index.toString().padStart(5, '0')}",
                displayName = " ${names[nameOrdinal % names.size]} ${nameOrdinal.toString().padStart(5, '0')} ")
        }.shuffled(Random(SEED))
    }

    private fun environment(count: Int, mode: Mode, evidence: AndroidPerformanceEvidence) = PerformanceEnvironment(
        runId = "09-sort-$count-${mode.name.lowercase(Locale.ROOT)}-api${Build.VERSION.SDK_INT}",
        caseId = "07-SORT-COMPONENT", fixtureId = "FX-P-SORT-$count", fixtureSeed = SEED,
        buildType = PerformanceBuildType.BENCHMARK, deviceClass = "physical-reference",
        apiLevel = Build.VERSION.SDK_INT, thermalState = evidence.thermalState,
        batteryPercent = evidence.batteryPercent, networkProfile = "none", cacheState = "warm",
        chargingState = evidence.chargingState, sourceRevision = evidence.sourceRevision,
        buildIdentifier = evidence.buildIdentifier, benchmarkCommand = evidence.benchmarkCommand,
        applicationApkSha256 = evidence.applicationApkSha256, testApkSha256 = evidence.testApkSha256,
    )

    private enum class Mode { EXACT_CURRENT, ONE_PER_SORT }
    private data class Observation(val warmup: Boolean, val ordinal: Int, val durationNanos: Long,
        var outcome: PerformanceSampleOutcome)
    private class State(val mode: Mode, val environment: PerformanceEnvironment) {
        val warmups = mutableListOf<PerformanceSample>()
        val samples = mutableListOf<PerformanceSample>()
        val observations = mutableListOf<Observation>()
        fun add(warmup: Boolean, ordinal: Int, nanos: Long, outcome: PerformanceSampleOutcome) {
            val sample = PerformanceSample(ordinal, nanos / 1_000_000, 0, 0, 0, 0, 0, 0, 0, outcome = outcome)
            if (warmup) warmups += sample else samples += sample
            observations += Observation(warmup, ordinal, nanos, outcome)
        }
        fun failLast(warmup: Boolean) {
            val list = if (warmup) warmups else samples
            list[list.lastIndex] = list.last().copy(outcome = PerformanceSampleOutcome.LOCAL_FAILURE)
            observations.last().outcome = PerformanceSampleOutcome.LOCAL_FAILURE
        }
    }

    private companion object {
        const val SEED = 0x09C07A6BL
        fun median(values: List<Long>): Double {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle].toDouble()
            else sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
        }
    }
}
