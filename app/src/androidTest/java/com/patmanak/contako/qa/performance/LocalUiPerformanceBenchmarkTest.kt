package com.patmanak.contako.qa.performance

import com.patmanak.contako.android.account.SignOutResult

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.R
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.LocalMutationCheckpoint
import com.patmanak.contako.data.local.LocalMutationCheckpointHook
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.ui.ContactEditorState
import com.patmanak.contako.ui.ContakoApp
import com.patmanak.contako.ui.ContactsViewModel
import com.patmanak.contako.ui.LOCAL_ACCOUNT_ID
import com.patmanak.contako.ui.theme.ContakoTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalUiPerformanceBenchmarkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val evidence by lazy { context.performanceEvidence() }
    private var showUi by mutableStateOf(false)
    private var renderRevision by mutableIntStateOf(0)
    private var activeViewModel by mutableStateOf<ContactsViewModel?>(null)
    private val databases = mutableListOf<Pair<String, ContakoDatabase>>()

    @After
    fun tearDown() {
        showUi = false
        compose.waitForIdle()
        databases.forEach { (name, database) ->
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun localUiProfilesMeetReleaseLikeBudgets() {
        renderApp()

        PerformanceFixtureProfile.entries
            .filter { it in LOCAL_PROFILES }
            .forEach { profile -> runProfile(profile) }
    }

    @Test
    fun nominalLocalSaveMeetsReleaseLikeBudget() {
        renderApp()
        val prepared = prepareProfile(PerformanceFixtureProfile.NOMINAL)
        showUi = true
        compose.waitUntil(TIMEOUT_MS) {
            prepared.viewModel.uiState.value.contacts.size == PerformanceFixtureProfile.NOMINAL.contactCount
        }
        emit(measureSave(PerformanceFixtureProfile.NOMINAL, prepared))
    }

    private fun renderApp() {
        compose.setContent {
            val revision = renderRevision
            if (showUi) {
                activeViewModel?.let { viewModel ->
                    ContakoTheme { ContakoApp(viewModel, onSignOut = { revision.hashCode(); SignOutResult.SignedOut }) }
                }
            }
        }
    }

    private fun runProfile(profile: PerformanceFixtureProfile) {
        val prepared = prepareProfile(profile)
        val repository = prepared.repository
        val viewModel = prepared.viewModel
        compose.waitUntil(TIMEOUT_MS) { viewModel.uiState.value.contacts.size == profile.contactCount }

        emit(measureColdFirstVisible(profile, repository))
        emit(measureCachedFirstVisible(profile, viewModel))
        emit(measureSearch(profile, viewModel))
        emit(measureSave(profile, prepared))
    }

    private fun prepareProfile(profile: PerformanceFixtureProfile): PreparedProfile {
        val databaseName = "local-ui-${profile.fixtureId.lowercase()}-${SystemClock.elapsedRealtime()}.db"
        context.deleteDatabase(databaseName)
        val database = ContakoDatabase.create(context, databaseName)
        databases += databaseName to database
        val saveTimingProbe = SaveTimingProbe()
        val repository = RoomContactRepository(database, checkpointHook = saveTimingProbe)
        val fixture = PerformanceFixtureGenerator.generate(profile)
        runBlocking {
            fixture.contacts.forEach { generated ->
                val result = repository.saveContact(generated.toCanonical())
                check(result is SaveResult.Saved)
            }
        }
        val viewModel = ContactsViewModel(repository)
        activeViewModel = viewModel
        return PreparedProfile(repository, viewModel, saveTimingProbe)
    }

    private fun measureColdFirstVisible(
        profile: PerformanceFixtureProfile,
        repository: RoomContactRepository,
    ): PerformanceRun {
        val visibleText = if (profile.contactCount == 0) {
            compose.activity.getString(R.string.contacts_empty_title)
        } else {
            profile.visibleContactName()
        }
        val warmups = (1..REQUIRED_WARMUPS).map { ordinal ->
            sample(ordinal, coldFirstVisible(repository, visibleText))
        }
        val samples = (1..REQUIRED_SAMPLES).map { ordinal ->
            sample(ordinal, coldFirstVisible(repository, visibleText))
        }
        return run(
            profile,
            "07-LOCAL-CACHED",
            "cold",
            Long.MAX_VALUE,
            samples,
            enforceBudget = false,
            warmups = warmups,
        )
    }

    private fun coldFirstVisible(repository: RoomContactRepository, visibleText: String): Long {
        compose.runOnIdle {
            showUi = false
            renderRevision++
        }
        compose.waitForIdle()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val viewModel = ContactsViewModel(repository)
        compose.runOnIdle {
            activeViewModel = viewModel
            showUi = true
            renderRevision++
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(visibleText).fetchSemanticsNodes().isNotEmpty()
        }
        return elapsedMs(startedAt)
    }

    private fun measureCachedFirstVisible(
        profile: PerformanceFixtureProfile,
        viewModel: ContactsViewModel,
    ): PerformanceRun {
        val visibleText = if (profile.contactCount == 0) {
            compose.activity.getString(R.string.contacts_empty_title)
        } else {
            profile.visibleContactName()
        }
        val warmups = (1..REQUIRED_WARMUPS).map { ordinal ->
            sample(ordinal, renderFirstVisible(viewModel, visibleText))
        }
        val samples = (1..REQUIRED_SAMPLES).map { ordinal ->
            val duration = renderFirstVisible(viewModel, visibleText)
            sample(ordinal, duration)
        }
        return run(profile, "07-LOCAL-CACHED", "warm", CACHED_BUDGET_MS, samples, warmups = warmups)
    }

    private fun renderFirstVisible(viewModel: ContactsViewModel, visibleText: String): Long {
        compose.runOnIdle {
            showUi = false
            renderRevision++
        }
        compose.waitForIdle()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        compose.runOnIdle {
            activeViewModel = viewModel
            showUi = true
            renderRevision++
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(visibleText).fetchSemanticsNodes().isNotEmpty()
        }
        return elapsedMs(startedAt)
    }

    private fun measureSearch(
        profile: PerformanceFixtureProfile,
        viewModel: ContactsViewModel,
    ): PerformanceRun {
        showUi = true
        compose.waitForIdle()
        val warmups = (1..REQUIRED_WARMUPS).map { ordinal ->
            sample(ordinal, searchOnce(profile, viewModel))
        }
        val samples = (1..REQUIRED_SAMPLES).map { ordinal ->
            sample(ordinal, searchOnce(profile, viewModel))
        }
        return run(profile, "07-LOCAL-SEARCH", "warm", SEARCH_BUDGET_MS, samples, warmups = warmups)
    }

    private fun searchOnce(profile: PerformanceFixtureProfile, viewModel: ContactsViewModel): Long {
        val query = if (profile.contactCount == 0) "absent" else profile.visibleContactName().lowercase()
        compose.runOnIdle { viewModel.updateQuery("") }
        compose.waitUntil(TIMEOUT_MS) { viewModel.uiState.value.query.isEmpty() }
        var startedAt = 0L
        compose.runOnIdle {
            startedAt = SystemClock.elapsedRealtimeNanos()
            viewModel.updateQuery(query)
        }
        compose.waitUntil(TIMEOUT_MS) {
            val state = viewModel.uiState.value
            state.query == query && state.contacts.size == if (profile.contactCount == 0) 0 else 1
        }
        return elapsedMs(startedAt)
    }

    private fun measureSave(
        profile: PerformanceFixtureProfile,
        prepared: PreparedProfile,
    ): PerformanceRun {
        val repository = prepared.repository
        val viewModel = prepared.viewModel
        val target = runBlocking {
            if (profile.contactCount == 0) {
                val created = CanonicalContact(
                    accountId = LOCAL_ACCOUNT_ID,
                    id = "p-save-target",
                    displayName = "Synthetic Save Target",
                )
                val result = repository.saveContact(created)
                require(result is SaveResult.Saved)
                result.value
            } else {
                requireNotNull(repository.getContact(LOCAL_ACCOUNT_ID, profile.targetContactId()))
            }
        }
        compose.runOnIdle { viewModel.updateQuery("") }
        val warmups = (1..REQUIRED_WARMUPS).map { ordinal ->
            val measured = saveOnce(target, ordinal, repository, viewModel, prepared.saveTimingProbe)
            sample(ordinal, measured.contractDurationMs, checkpoints = 1)
        }
        val phaseSamples = mutableListOf<SavePhaseSample>()
        val samples = (1..REQUIRED_SAMPLES).map { ordinal ->
            val measured = saveOnce(
                target,
                REQUIRED_WARMUPS + ordinal,
                repository,
                viewModel,
                prepared.saveTimingProbe,
            )
            phaseSamples += measured.phases.copy(ordinal = ordinal)
            sample(ordinal, measured.contractDurationMs, checkpoints = 1)
        }
        emitSavePhases(profile, phaseSamples)
        return run(
            profile,
            "07-LOCAL-SAVE",
            "warm",
            SAVE_BUDGET_MS,
            samples,
            enforceBudget = false,
            warmups = warmups,
        ).also { measured ->
            assertTrue(
                "${measured.environment.runId}: ${measured.violations()}",
                measured.violations().isEmpty(),
            )
        }
    }

    private fun saveOnce(
        target: CanonicalContact,
        revision: Int,
        repository: RoomContactRepository,
        viewModel: ContactsViewModel,
        saveTimingProbe: SaveTimingProbe,
    ): SaveMeasurement {
        val stored = runBlocking { requireNotNull(repository.getContact(LOCAL_ACCOUNT_ID, target.id)) }
        val noteValue = "synthetic-note-$revision"
        val note = stored.values.firstOrNull { it.kind == ContactValueKind.NOTE }?.copy(value = noteValue)
            ?: ContactValue("note", ContactValueKind.NOTE, noteValue, order = 0)
        compose.runOnIdle {
            viewModel.editContact(stored)
            val editor = requireNotNull(viewModel.uiState.value.contactEditor)
            viewModel.updateContactEditor(
                editor.copy(values = editor.values.filterNot { it.kind == ContactValueKind.NOTE } + note),
            )
        }
        compose.waitUntil(TIMEOUT_MS) { viewModel.uiState.value.contactEditor != null }
        var startedAt = 0L
        var dispatchedAt = 0L
        compose.runOnIdle {
            startedAt = SystemClock.elapsedRealtimeNanos()
            saveTimingProbe.start(startedAt)
            viewModel.saveContact()
            dispatchedAt = SystemClock.elapsedRealtimeNanos()
        }
        compose.waitUntil(TIMEOUT_MS) { viewModel.uiState.value.contactEditor == null }
        val closedAt = SystemClock.elapsedRealtimeNanos()
        val contractDurationMs = elapsedMs(startedAt, closedAt)
        val durableReadStartedAt = SystemClock.elapsedRealtimeNanos()
        val durable = runBlocking { repository.getContact(LOCAL_ACCOUNT_ID, target.id) }
        assertNotNull(durable)
        assertEquals(noteValue, durable?.values?.single { it.kind == ContactValueKind.NOTE }?.value)
        val durableReadFinishedAt = SystemClock.elapsedRealtimeNanos()
        return SaveMeasurement(
            contractDurationMs = contractDurationMs,
            phases = saveTimingProbe.snapshot(
                dispatchedAt = dispatchedAt,
                closedAt = closedAt,
                durableReadStartedAt = durableReadStartedAt,
                durableReadFinishedAt = durableReadFinishedAt,
            ),
        )
    }

    private fun run(
        profile: PerformanceFixtureProfile,
        caseId: String,
        cacheState: String,
        budgetMs: Long,
        samples: List<PerformanceSample>,
        enforceBudget: Boolean = true,
        warmups: List<PerformanceSample>,
    ): PerformanceRun = PerformanceRun(
        environment = PerformanceEnvironment(
            runId = "$caseId-${profile.fixtureId}-$cacheState-api${Build.VERSION.SDK_INT}",
            caseId = caseId,
            fixtureId = profile.fixtureId,
            fixtureSeed = PerformanceFixtureGenerator.DEFAULT_SEED,
            buildType = PerformanceBuildType.BENCHMARK,
            deviceClass = "reference-phone",
            apiLevel = Build.VERSION.SDK_INT,
            thermalState = evidence.thermalState,
            batteryPercent = evidence.batteryPercent,
            networkProfile = "none",
            cacheState = cacheState,
            chargingState = evidence.chargingState,
            sourceRevision = evidence.sourceRevision,
            buildIdentifier = evidence.buildIdentifier,
            benchmarkCommand = evidence.benchmarkCommand,
            applicationApkSha256 = evidence.applicationApkSha256,
            testApkSha256 = evidence.testApkSha256,
        ),
        warmupCount = REQUIRED_WARMUPS,
        samples = samples,
        ceilings = PerformanceCeilings(
            durationMs = budgetMs,
            requestCount = 0,
            providerBatchCount = 0,
            providerOperationCount = 0,
            heapDeltaBytes = MAX_HEAP_DELTA_BYTES,
            cursorRows = 100,
        ),
        warmupSamples = warmups,
    ).also { measured ->
        if (enforceBudget) {
            assertTrue("${measured.environment.runId}: ${measured.violations()}", measured.violations().isEmpty())
        }
    }

    private fun emit(run: PerformanceRun) {
        val json = PerformanceMetricCollector.toJson(run)
        val csv = PerformanceMetricCollector.toCsv(run)
        context.writePerformanceArtifact("${run.environment.runId}.json", json)
        context.writePerformanceArtifact("${run.environment.runId}.csv", csv)
        println(
            "CONTAKO_PERF ${run.environment.runId} p95=${run.p95DurationMs}ms " +
                "violations=${run.violations().joinToString("+").ifEmpty { "none" }}",
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString(
                "stream",
                "CONTAKO_PERF ${run.environment.runId} p95=${run.p95DurationMs}ms " +
                    "violations=${run.violations().joinToString("+").ifEmpty { "none" }}\n",
            )
        })
    }

    private fun emitSavePhases(profile: PerformanceFixtureProfile, samples: List<SavePhaseSample>) {
        val runId = "07-LOCAL-SAVE-${profile.fixtureId}-warm-api${Build.VERSION.SDK_INT}"
        context.writePerformanceArtifact("$runId-phases.json", buildString {
            append("{\"schemaVersion\":1,\"runId\":\"").append(runId).append("\",\"samples\":[")
            append(samples.joinToString(",") { it.toJson() })
            append("]}\n")
        })
        context.writePerformanceArtifact("$runId-phases.csv", buildString {
            append(SavePhaseSample.CSV_HEADER).append('\n')
            samples.forEach { append(it.toCsv()).append('\n') }
        })
        println("CONTAKO_PERF_PHASES $runId ${SavePhaseSample.summary(samples)}")
    }

    private fun PerformanceFixtureProfile.visibleContactName(): String =
        "Synthetic ${0.toString().padStart(5, '0')}"

    private fun PerformanceFixtureProfile.targetContactId(): String =
        PerformanceFixtureGenerator.generate(this).contacts.last().id

    private fun PerformanceContact.toCanonical() = CanonicalContact(
        accountId = LOCAL_ACCOUNT_ID,
        id = id,
        displayName = displayName,
        values = listOf(
            ContactValue("email-$id", ContactValueKind.EMAIL, email, order = 0),
            ContactValue("phone-$id", ContactValueKind.PHONE, phone, order = 0),
        ),
    )

    private fun sample(ordinal: Int, durationMs: Long, checkpoints: Int = 0) = PerformanceSample(
        ordinal = ordinal,
        durationMs = durationMs,
        requestCount = 0,
        providerBatchCount = 0,
        providerOperationCount = 0,
        heapDeltaBytes = 0,
        maxCursorRows = 0,
        commitCount = checkpoints,
        checkpointCount = checkpoints,
    )

    private fun elapsedMs(startedAt: Long, finishedAt: Long = SystemClock.elapsedRealtimeNanos()): Long =
        (finishedAt - startedAt).coerceAtLeast(0) / 1_000_000

    private companion object {
        val LOCAL_PROFILES = setOf(
            PerformanceFixtureProfile.EMPTY,
            PerformanceFixtureProfile.SMALL,
            PerformanceFixtureProfile.NOMINAL,
        )
        const val REQUIRED_WARMUPS = PerformanceRun.REQUIRED_WARMUPS
        const val REQUIRED_SAMPLES = PerformanceRun.REQUIRED_SAMPLES
        const val CACHED_BUDGET_MS = 500L
        const val SEARCH_BUDGET_MS = 100L
        const val SAVE_BUDGET_MS = 300L
        const val MAX_HEAP_DELTA_BYTES = 64L * 1_024 * 1_024
        const val TIMEOUT_MS = 10_000L
    }

    private data class PreparedProfile(
        val repository: RoomContactRepository,
        val viewModel: ContactsViewModel,
        val saveTimingProbe: SaveTimingProbe,
    )

    private data class SaveMeasurement(
        val contractDurationMs: Long,
        val phases: SavePhaseSample,
    )

    private class SaveTimingProbe : LocalMutationCheckpointHook {
        private var startedAt = 0L
        private val checkpoints = LongArray(LocalMutationCheckpoint.entries.size)

        fun start(value: Long) {
            checkpoints.fill(0L)
            startedAt = value
        }

        override fun onCheckpoint(checkpoint: LocalMutationCheckpoint) {
            if (startedAt != 0L) checkpoints[checkpoint.ordinal] = SystemClock.elapsedRealtimeNanos()
        }

        fun snapshot(
            dispatchedAt: Long,
            closedAt: Long,
            durableReadStartedAt: Long,
            durableReadFinishedAt: Long,
        ): SavePhaseSample {
            fun offset(checkpoint: LocalMutationCheckpoint): Long {
                val observedAt = checkpoints[checkpoint.ordinal]
                check(observedAt != 0L) { "Missing $checkpoint" }
                return (observedAt - startedAt).coerceAtLeast(0) / 1_000
            }
            return SavePhaseSample(
                ordinal = 0,
                dispatchUs = (dispatchedAt - startedAt).coerceAtLeast(0) / 1_000,
                beforeMutationUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_BEFORE_MUTATION),
                afterAggregateUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_AGGREGATE),
                afterValueUpsertUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_VALUE_UPSERT),
                afterValuesUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_VALUES),
                afterPayloadUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_PAYLOAD),
                beforeOutboxUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_BEFORE_OUTBOX),
                afterOutboxUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_OUTBOX),
                beforeCommitUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_BEFORE_COMMIT),
                afterCommitUs = offset(LocalMutationCheckpoint.CONTACT_SAVE_AFTER_COMMIT),
                editorClosedUs = (closedAt - startedAt).coerceAtLeast(0) / 1_000,
                postconditionReadUs = (durableReadFinishedAt - durableReadStartedAt).coerceAtLeast(0) / 1_000,
            )
        }
    }

    private data class SavePhaseSample(
        val ordinal: Int,
        val dispatchUs: Long,
        val beforeMutationUs: Long,
        val afterAggregateUs: Long,
        val afterValueUpsertUs: Long,
        val afterValuesUs: Long,
        val afterPayloadUs: Long,
        val beforeOutboxUs: Long,
        val afterOutboxUs: Long,
        val beforeCommitUs: Long,
        val afterCommitUs: Long,
        val editorClosedUs: Long,
        val postconditionReadUs: Long,
    ) {
        fun toJson(): String =
            "{\"ordinal\":$ordinal,\"dispatchUs\":$dispatchUs,\"beforeMutationUs\":$beforeMutationUs," +
                "\"afterAggregateUs\":$afterAggregateUs,\"afterValueUpsertUs\":$afterValueUpsertUs," +
                "\"afterValuesUs\":$afterValuesUs,\"afterPayloadUs\":$afterPayloadUs," +
                "\"beforeOutboxUs\":$beforeOutboxUs,\"afterOutboxUs\":$afterOutboxUs," +
                "\"beforeCommitUs\":$beforeCommitUs,\"afterCommitUs\":$afterCommitUs," +
                "\"editorClosedUs\":$editorClosedUs,\"postconditionReadUs\":$postconditionReadUs}"

        fun toCsv(): String = listOf(
            ordinal,
            dispatchUs,
            beforeMutationUs,
            afterAggregateUs,
            afterValueUpsertUs,
            afterValuesUs,
            afterPayloadUs,
            beforeOutboxUs,
            afterOutboxUs,
            beforeCommitUs,
            afterCommitUs,
            editorClosedUs,
            postconditionReadUs,
        ).joinToString(",")

        companion object {
            const val CSV_HEADER =
                "ordinal,dispatch_us,before_mutation_us,after_aggregate_us,after_value_upsert_us," +
                    "after_values_us,after_payload_us,before_outbox_us,after_outbox_us,before_commit_us," +
                    "after_commit_us,editor_closed_us,postcondition_read_us"

            fun summary(samples: List<SavePhaseSample>): String = listOf(
                "dispatchP95Us=${p95(samples.map(SavePhaseSample::dispatchUs))}",
                "beforeMutationP95Us=${p95(samples.map(SavePhaseSample::beforeMutationUs))}",
                "afterAggregateP95Us=${p95(samples.map(SavePhaseSample::afterAggregateUs))}",
                "afterValuesP95Us=${p95(samples.map(SavePhaseSample::afterValuesUs))}",
                "afterOutboxP95Us=${p95(samples.map(SavePhaseSample::afterOutboxUs))}",
                "afterCommitP95Us=${p95(samples.map(SavePhaseSample::afterCommitUs))}",
                "editorClosedP95Us=${p95(samples.map(SavePhaseSample::editorClosedUs))}",
                "postconditionReadP95Us=${p95(samples.map(SavePhaseSample::postconditionReadUs))}",
            ).joinToString(" ")

            private fun p95(values: List<Long>): Long = PerformanceRun.nearestRank(values, 95)
        }
    }
}
