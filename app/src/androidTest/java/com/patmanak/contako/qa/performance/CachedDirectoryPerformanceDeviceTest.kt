package com.patmanak.contako.qa.performance

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.matcher.ViewMatchers
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.android.account.SignOutResult
import com.patmanak.contako.data.local.AggregateType
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.ContactWithValues
import com.patmanak.contako.data.local.DirectoryContactRow
import com.patmanak.contako.data.local.LocalMutationCheckpoint
import com.patmanak.contako.data.local.LocalMutationCheckpointHook
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.toDomain
import com.patmanak.contako.data.local.toEntity
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.policy.ContactDirectoryPolicy
import com.patmanak.contako.domain.policy.ContactSearch
import com.patmanak.contako.ui.ContakoApp
import com.patmanak.contako.ui.ContactsViewModel
import com.patmanak.contako.ui.ContactsUiState
import com.patmanak.contako.ui.DirectorySectionIndex
import com.patmanak.contako.ui.theme.ContakoTheme
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account-free Room -> repository -> ViewModel -> production Compose qualification.
 *
 * Run this class explicitly on the minified benchmark variant. performanceProfiles accepts
 * nominal (default), robustness, or both comma-separated. A reopened VM uses the SAME open
 * database/process: neither OS cold start nor SQLite cache eviction is claimed. Setup, fixture
 * generation and correctness readback are excluded from contract durations. Compose test
 * synchronization/semantics overhead remains; admission wait is recorded separately. No account manager,
 * provider, authentication runtime, background runner or network gateway is constructed.
 */
@RunWith(AndroidJUnit4::class)
class CachedDirectoryPerformanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val evidence by lazy { context.performanceEvidence() }
    private var shownViewModel by mutableStateOf<ContactsViewModel?>(null)
    private var store: ViewModelStore? = null
    private var database: ContakoDatabase? = null
    private var databaseName: String? = null
    private val committedAt = AtomicLong()
    private val budgetViolations = mutableListOf<String>()
    private val renderOracle by lazy {
        when (InstrumentationRegistry.getArguments().getString("renderOracle") ?: "legacy-v3") {
            "legacy-v3" -> RenderOracle.LEGACY_V3
            "atomic-v4" -> RenderOracle.ATOMIC_V4
            "atomic-v5" -> RenderOracle.ATOMIC_V5
            else -> error("Unknown renderOracle; use legacy-v3, atomic-v4 or atomic-v5")
        }
    }

    @After fun close() {
        disposeViewModel()
        database?.close()
        database = null
        databaseName?.let(context::deleteDatabase)
        databaseName = null
    }

    @Test fun atomicOracleRejectsStaleDuplicateAndNonvisibleWitnesses() {
        val first = RenderNode(1, setOf("Synthetic first"), displayed = true)
        val second = RenderNode(2, setOf("Synthetic second"), displayed = true)
        val third = RenderNode(3, setOf("Synthetic third"), displayed = true)
        val wanted = listOf("Synthetic first", "Synthetic second")
        val absent = listOf("Synthetic third")
        assertTrue(RenderSnapshot(listOf(first, second), 0).matches(wanted, absent))
        assertFalse(RenderSnapshot(listOf(first, second, third), 0).matches(wanted, absent))
        assertFalse(RenderSnapshot(listOf(first, second.copy(displayed = false)), 0).matches(wanted, absent))
        assertFalse(RenderSnapshot(listOf(first, second.copy(id = first.id)), 0).matches(wanted, absent))
        assertFalse(RenderSnapshot(listOf(first, first.copy(id = 4), second), 0).matches(wanted, absent))
        assertFalse(RenderSnapshot(listOf(first), 0).matches(wanted, absent))
        val window = android.graphics.Rect(20, 30, 120, 130)
        val offset = intArrayOf(20, 30)
        assertTrue(overlapsVisibleWindow(androidx.compose.ui.geometry.Rect(0f, 0f, 30f, 20f), offset, window))
        assertTrue(overlapsVisibleWindow(androidx.compose.ui.geometry.Rect(-10f, 0f, 10f, 20f), offset, window))
        assertFalse(overlapsVisibleWindow(androidx.compose.ui.geometry.Rect(100f, 0f, 110f, 20f), offset, window))
        assertFalse(overlapsVisibleWindow(androidx.compose.ui.geometry.Rect(-30f, 0f, -10f, 20f), offset, window))
        assertFalse(overlapsVisibleWindow(androidx.compose.ui.geometry.Rect(0f, 0f, 0f, 20f), offset, window))
    }

    @Test fun atomicNodeVisibilityAgreesWithPublicAssertionsForClippingAndHiddenHost() {
        val visibleText = "Visible witness"
        val clippedText = "Clipped witness"
        compose.setContent {
            ContakoTheme {
                Box(Modifier.size(120.dp).clipToBounds()) {
                    Text(visibleText, Modifier.size(100.dp, 32.dp))
                    Text(clippedText, Modifier.offset(x = 10_000.dp))
                }
            }
        }
        val nodes = compose.onAllNodes(hasText(visibleText) or hasText(clippedText)).fetchSemanticsNodes()
        val visibleNode = compose.runOnUiThread {
            fun named(text: String) = nodes.single { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }
            val visible = named(visibleText)
            assertTrue(nodeIsPartiallyDisplayed(visible))
            assertFalse(nodeIsPartiallyDisplayed(named(clippedText)))
            visible
        }
        compose.onNodeWithText(visibleText).assertIsDisplayed()
        compose.onNodeWithText(clippedText).assertIsNotDisplayed()
        val host = compose.runOnUiThread { requireNotNull(visibleNode.root as? ViewRootForTest).view }
        val previousVisibility = compose.runOnUiThread { host.visibility }
        try {
            compose.runOnUiThread {
                host.visibility = android.view.View.INVISIBLE
                // Use the already captured node; do not synchronize/fetch through a hidden root.
                assertFalse(nodeIsPartiallyDisplayed(visibleNode))
            }
        } finally {
            compose.runOnUiThread { host.visibility = previousVisibility }
        }
        compose.onNodeWithText(visibleText).assertIsDisplayed()
        compose.onNodeWithText(clippedText).assertIsNotDisplayed()
    }

    @Test fun capturedIdentityProofCannotBeHealedByLaterModelReplacement() {
        val wrong = CanonicalContact(ACCOUNT, "synthetic-wrong")
        val correct = CanonicalContact(ACCOUNT, "synthetic-correct")
        val live = mutableListOf(wrong)
        val atClock = CapturedModelIdentity.capture(live, live)
        live.clear()
        live += correct
        assertFalse(atClock.matches(setOf(correct.id)))
        assertTrue(atClock.matches(setOf(wrong.id)))
        assertTrue(CapturedModelIdentity.capture(live, live).matches(setOf(correct.id)))
        assertFalse(CapturedModelIdentity.capture(listOf(correct, correct), listOf(correct)).matches(setOf(correct.id)))
    }

    @Test fun cachedDirectoryRichProfiles() {
        require(BuildConfig.BUILD_TYPE == "benchmark") { "Use the minified benchmark variant" }
        require(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        // Evaluate mandatory source/APK evidence before creating any fixture.
        evidence
        val selected = (InstrumentationRegistry.getArguments().getString("performanceProfiles") ?: "nominal")
            .split(',').map(String::trim).toSet()
        require(selected.isNotEmpty() && selected.all { it in setOf("nominal", "robustness") })
        compose.setContent {
            shownViewModel?.let { vm ->
                ContakoTheme { ContakoApp(vm, onSignOut = { SignOutResult.SignedOut }) }
            }
        }
        selected.forEach { name ->
            val profile = if (name == "nominal") Profile(name, 300, 20) else Profile(name, 5_000, 200)
            runProfile(profile)
            close()
        }
        assertTrue("Performance ceilings: ${budgetViolations.joinToString()}", budgetViolations.isEmpty())
    }

    private fun runProfile(profile: Profile) {
        val dbName = "cached-directory-${profile.name}-${SystemClock.elapsedRealtimeNanos()}.db"
        databaseName = dbName
        val db = ContakoDatabase.create(context, dbName).also { database = it }
        val fixture = Fixture(profile, validPng())
        runBlocking {
            db.withTransaction {
                // Seed exact rows once, without uploading or creating thousands of local intents.
                repeat(profile.contacts) { index -> insert(db, fixture.contact(index)) }
                fixture.groups.forEach { group ->
                    db.contactGroupDao().upsert(group.toEntity())
                    db.contactGroupDao().insertMemberships(group.memberships.map { it.toEntity(group) })
                }
                insert(db, fixture.foreign)
            }
        }
        val repository = RoomContactRepository(db, calibrationProvider = { null }, checkpointHook = LocalMutationCheckpointHook { checkpoint ->
            if (checkpoint == LocalMutationCheckpoint.CONTACT_SAVE_AFTER_COMMIT) {
                committedAt.set(SystemClock.elapsedRealtimeNanos())
            }
        })
        verifyFixture(db, repository, fixture)
        // One immutable DTO capture and production oracle, outside every timed interval.
        val diagnosticRows = runBlocking { db.contactContentChangesDao().observeDirectory(ACCOUNT).first() }
        writeDirectoryQueryPlans(db, profile)
        val directoryOracle = runBlocking { repository.observeDirectory(ACCOUNT).first() }
        val materializedUtf8Bytes = materializedUtf8Bytes(diagnosticRows)
        context.writePerformanceArtifact("cached-directory-${profile.name}-scope.json", """
            {"schemaVersion":3,"layer":"real-room-repository-viewmodel-production-compose",
            "contactCount":${profile.contacts},"groupCount":${profile.groups},"emailsPerContact":2,
            "inlinePhotoCount":${(profile.contacts + 19) / 20},"opaqueValueCount":${(profile.contacts + 19) / 20},
            "opaqueChars":8192,"envelopeChars":16384,"groupMemberships":${fixture.groups.sumOf { it.memberships.size }},
            "remotePeer":"NOT_RUN","androidProvider":"NOT_RUN","network":"not-constructed",
            "reopenedVm":"SAME_PROCESS_AND_OPEN_DATABASE","heap":"before-after-jvm-proxy-not-native-peak",
            "materializedContactRows":${diagnosticRows.size},"materializedValueRows":${diagnosticRows.sumOf { it.values.size }},
            "materializedStringUtf8Bytes":$materializedUtf8Bytes,
            "volumeScope":"all-string-columns-in-directory-contact-and-value-dtos-excludes-payload-table-numeric-columns-and-object-overhead",
            "volumeMethod":"aggregate-outside-timing-not-cursor-native-or-peak-memory",
            "daoAcquisition":"Room-observation-transaction-relations-and-materialization-not-pure-SQL",
            "mappingSortComponents":"replicated-diagnostic-components-not-production-total-do-not-sum-P95",
            "timingProtocol":"${if (renderOracle == RenderOracle.LEGACY_V3) "accepted-action-v3" else "mixed-${renderOracle.argument}-and-other-v3"}",
            "renderOracle":"${renderOracle.argument}","renderedTimingProtocol":"${timingProtocol("SEARCH-RENDER")}",
            "otherTimingProtocol":"accepted-action-v3",
            "coldSearch":"fresh-ViewModel-and-snapshot-per-sample-blank-list-ready-before-first-query-db-process-warm",
            "coldSearchOnly":${InstrumentationRegistry.getArguments().getString("performanceColdSearchOnly") == "true"},
            "modelIdentityVerification":"${if (renderOracle == RenderOracle.ATOMIC_V5) "immutable-model-captured-at-stop-verified-after" else "inside-render-clock"}",
            "protocolBreak":"REPOSITORY-FIRST-vs-v2-and-rendered-v3-v4-v5-are-not-directly-comparable",
            "admissionWait":"recorded-separately-not-subtracted-from-total",
            "composeTestOverhead":"synchronization-and-semantics-included","photoPixels":"72x72-not-heavy-decode",
            "searchVmPublication":"Compose-detached-active-first-collector-includes-state-predicate-observation-overhead",
            "photoCompletion":"not-a-render-prerequisite","firstUsefulRender":"displayed-name-and-local-selection"}
        """.trimIndent())

        measure(profile, "REPOSITORY-FIRST", Long.MAX_VALUE) { phase ->
            phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
            val snapshot = runBlocking { repository.observeDirectory(ACCOUNT).first() }
            phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
            assertTrue("Production directory snapshot differs", directoryOracle == snapshot)
            assertEquals(fixture.ids, snapshot.contacts.map { it.id }.toSet())
        }
        if (InstrumentationRegistry.getArguments().getString("performanceColdSearchOnly") == "true") {
            measureColdSearch(profile, repository, fixture)
            return // Distribution only; this does not qualify the other directory budgets.
        }
        measureDiagnosticComponents(profile, db, diagnosticRows, directoryOracle.contacts, fixture)
        // This opt-in is a component diagnostic, never a pass for the UI latency contracts.
        if (InstrumentationRegistry.getArguments().getString("performanceDiagnosticsOnly") == "true") {
            val vm = compose.runOnIdle { createViewModel(repository).also { shownViewModel = it } }
            waitForDirectory(vm, fixture)
            compose.runOnIdle { vm.updateQuery(SEARCH) }
            compose.waitUntil(TIMEOUT) {
                vm.uiState.value.query == SEARCH && vm.uiState.value.contacts.size == 2 &&
                    (0..1).all { visible(fixture.contact(it).resolvedDisplayName) }
            }
            measureSteadySearchSemantics(profile, vm, fixture)
            return
        }
        measure(profile, "REOPENED-VM", 500, prepare = { disposeViewModel() }) { phase ->
            val vm = compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                createViewModel(repository).also { shownViewModel = it }
            }
            waitForFirstRender(vm, fixture, phase)
            proveFirstUseful(fixture.contact(0), vm, phase)
        }
        // The last reopened VM is the only live VM. Its full Room flow remains subscribed.
        val vm = requireNotNull(shownViewModel)
        compose.runOnIdle { shownViewModel = null }
        compose.waitForIdle()
        measure(profile, "SEARCH-VM-PUBLICATION", Long.MAX_VALUE, prepare = {
            compose.runOnIdle { vm.updateQuery("") }
            runBlocking {
                withTimeout(TIMEOUT) {
                    vm.uiState.first { state ->
                        state.query.isEmpty() && state.contacts.size == profile.contacts &&
                            state.groups.size == profile.groups && !state.directoryLoading && !state.directoryReadFailed
                    }
                }
            }
        }) { phase ->
            val expected = setOf(fixture.contact(0).id, fixture.contact(1).id)
            runBlocking {
                // UNDISPATCHED enters first() before the action; no polling or Compose render oracle.
                val publication = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT) {
                        vm.uiState.first { state ->
                            state.query == SEARCH && state.contacts.map { it.id }.toSet() == expected &&
                                state.directoryContacts.map { it.id }.toSet() == expected
                        }.also { phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos() }
                    }
                }
                compose.runOnIdle {
                    phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                    vm.updateQuery(SEARCH)
                }
                val state = publication.await()
                assertEquals(expected, state.contacts.map { it.id }.toSet())
            }
        }
        compose.runOnIdle { vm.updateQuery(""); shownViewModel = vm }
        waitForDirectory(vm, fixture)
        measure(profile, "CACHED-RENDER", 500, prepare = {
            compose.runOnIdle { shownViewModel = null }
            compose.waitForIdle()
        }) { phase ->
            compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                shownViewModel = vm
            }
            waitForFirstRender(vm, fixture, phase)
            proveFirstUseful(fixture.contact(0), vm, phase)
        }
        measure(profile, "SEARCH-RENDER", 100, prepare = {
            compose.runOnIdle { vm.updateQuery("") }
            waitForDirectory(vm, fixture)
            if (renderOracle != RenderOracle.LEGACY_V3) {
                val before = awaitAtomicRender(vm, fixture, "", fixture.ids,
                    wantedNames = (0..2).map { fixture.contact(it).resolvedDisplayName })
                assertFalse("Unfiltered semantics must not satisfy the filtered render oracle",
                    before.matches((0..1).map { fixture.contact(it).resolvedDisplayName },
                        listOf(fixture.contact(2).resolvedDisplayName)))
            } else compose.waitUntil(TIMEOUT) { visible(fixture.contact(2).resolvedDisplayName) }
        }) { phase ->
            compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                vm.updateQuery(SEARCH)
            }
            val expected = setOf(fixture.contact(0).id, fixture.contact(1).id)
            if (renderOracle != RenderOracle.LEGACY_V3) {
                awaitAtomicRender(vm, fixture, SEARCH, expected,
                    wantedNames = (0..1).map { fixture.contact(it).resolvedDisplayName },
                    absentNames = listOf(fixture.contact(2).resolvedDisplayName), phase = phase)
            } else compose.waitUntil(TIMEOUT) {
                vm.uiState.value.query == SEARCH && vm.uiState.value.contacts.map { it.id }.toSet() == expected &&
                    (0..1).all { visible(fixture.contact(it).resolvedDisplayName) } &&
                    compose.onAllNodesWithText(fixture.contact(2).resolvedDisplayName).fetchSemanticsNodes().isEmpty()
            }
            (0..1).forEach { compose.onNodeWithText(fixture.contact(it).resolvedDisplayName).assertIsDisplayed() }
            assertEquals(expected, vm.uiState.value.contacts.map { it.id }.toSet())
            if (renderOracle == RenderOracle.LEGACY_V3) phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
        }
        measureSteadySearchSemantics(profile, vm, fixture)

        var before: CanonicalContact? = null
        var witnessBefore: CanonicalContact? = null
        var groupsBefore = emptyList<ContactGroup>()
        var note = ""
        measure(profile, "SAVE-COMMIT", 300, prepare = {
            committedAt.set(0)
            compose.runOnIdle { vm.updateQuery("") }
            waitForDirectory(vm, fixture)
            before = runBlocking { requireNotNull(repository.getContact(ACCOUNT, fixture.contact(0).id)) }
            witnessBefore = runBlocking { requireNotNull(repository.getContact(ACCOUNT, fixture.contact(20).id)) }
            groupsBefore = runBlocking { db.contactGroupDao().getAll(ACCOUNT).map { it.toDomain() } }
            note = "synthetic-benchmark-${requireNotNull(before).revision}"
            compose.runOnIdle { vm.editContact(requireNotNull(before)) }
            compose.waitUntil(TIMEOUT) { vm.uiState.value.contactEditor != null }
            compose.runOnIdle {
                val editor = requireNotNull(vm.uiState.value.contactEditor)
                vm.updateContactEditor(editor.copy(values = editor.values.map {
                    if (it.kind == ContactValueKind.NOTE) it.copy(value = note) else it
                }))
            }
        }, verify = {
            verifySaved(db, repository, fixture, requireNotNull(before), note, requireNotNull(witnessBefore), groupsBefore)
        }) { phase ->
            compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                vm.saveContact()
            }
            compose.waitUntil(TIMEOUT) { committedAt.get() != 0L }
            phase.contractFinishedAt = committedAt.get()
            compose.waitUntil(TIMEOUT) { vm.uiState.value.contactEditor == null }
            phase.editorClosedAt = SystemClock.elapsedRealtimeNanos()
        }
        measureColdSearch(profile, repository, fixture)
    }

    private fun measureColdSearch(profile: Profile, repository: RoomContactRepository, fixture: Fixture) {
        val oracle = runBlocking { repository.observeDirectory(ACCOUNT).first() }
        val groups = runBlocking { repository.observeGroups(ACCOUNT).first() }
        val matchingGroups = groups.filter { ContactSearch.prepare(SEARCH).matchesGroupName(it.name) }
        val expected = setOf(fixture.contact(0).id, fixture.contact(1).id)
        lateinit var fresh: ContactsViewModel
        // Every warmup AND measured attempt owns a new PreparedDirectory with no search index.
        // Initial list acquisition is outside query time, and MUST remain usable without search.
        measure(profile, "COLD-SEARCH-RENDER", Long.MAX_VALUE, prepare = {
            disposeViewModel()
            fresh = compose.runOnIdle { createViewModel(repository).also { shownViewModel = it } }
            waitForDirectory(fresh, fixture)
            assertEquals(oracle.contacts, fresh.uiState.value.directoryContacts)
            assertEquals(groups, fresh.uiState.value.groups)
            compose.onNodeWithText(fixture.contact(2).resolvedDisplayName).assertIsDisplayed()
        }) { phase ->
            compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                fresh.updateQuery(SEARCH)
            }
            if (renderOracle != RenderOracle.LEGACY_V3) {
                awaitAtomicRender(fresh, fixture, SEARCH, expected,
                    wantedNames = (0..1).map { fixture.contact(it).resolvedDisplayName },
                    absentNames = listOf(fixture.contact(2).resolvedDisplayName), phase = phase)
            } else compose.waitUntil(TIMEOUT) {
                fresh.uiState.value.query == SEARCH && !fresh.uiState.value.directoryLoading &&
                    fresh.uiState.value.directoryContacts.map { it.id }.toSet() == expected &&
                    (0..1).all { visible(fixture.contact(it).resolvedDisplayName) } &&
                    compose.onAllNodesWithText(fixture.contact(2).resolvedDisplayName).fetchSemanticsNodes().isEmpty()
            }
            (0..1).forEach { compose.onNodeWithText(fixture.contact(it).resolvedDisplayName).assertIsDisplayed() }
            if (renderOracle == RenderOracle.LEGACY_V3) phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
            assertEquals(oracle.contacts.filter { it.id in expected }, fresh.uiState.value.directoryContacts)
            assertEquals(matchingGroups, fresh.uiState.value.groups)
        }
        val retained = runBlocking { repository.observeDirectory(ACCOUNT).first() }
        assertEquals(oracle.contacts, retained.contacts)
        assertEquals(oracle.contentChanges, retained.contentChanges)
        assertEquals(groups, runBlocking { repository.observeGroups(ACCOUNT).first() })
    }

    private fun createViewModel(repository: RoomContactRepository): ContactsViewModel {
        val owner = ViewModelStore().also { store = it }
        return ViewModelProvider(owner, ContactsViewModel.factory(
            repository = repository, accountId = ACCOUNT, navigationState = SavedStateHandle(),
        ))[ContactsViewModel::class.java]
    }

    private fun disposeViewModel() {
        compose.runOnIdle { shownViewModel = null }
        compose.waitForIdle()
        compose.runOnIdle { store?.clear(); store = null }
    }

    private fun waitForDirectory(vm: ContactsViewModel, fixture: Fixture) {
        compose.waitUntil(TIMEOUT) {
            val state = vm.uiState.value
            state.query.isEmpty() && state.contacts.size == fixture.profile.contacts &&
                state.groups.size == fixture.profile.groups && visible(fixture.contact(0).resolvedDisplayName)
        }
    }

    private fun visible(name: String): Boolean =
        compose.onAllNodesWithText(name).fetchSemanticsNodes().any {
            it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0
        }

    private fun waitForFirstRender(vm: ContactsViewModel, fixture: Fixture, phase: Phase) {
        if (renderOracle == RenderOracle.LEGACY_V3) waitForDirectory(vm, fixture)
        else {
            compose.waitUntil(TIMEOUT) { modelMatches(vm.uiState.value, fixture, "", fixture.ids,
                verifyIds = renderOracle != RenderOracle.ATOMIC_V5) }
            awaitAtomicRender(vm, fixture, "", fixture.ids,
                wantedNames = listOf(fixture.contact(0).resolvedDisplayName), phase = phase)
        }
    }

    /** One synchronized semantics acquisition per attempt; all proof values are captured on Main. */
    private fun awaitAtomicRender(
        vm: ContactsViewModel,
        fixture: Fixture,
        query: String,
        expectedIds: Set<String>,
        wantedNames: List<String>,
        absentNames: List<String> = emptyList(),
        phase: Phase? = null,
    ): RenderSnapshot {
        require(wantedNames.isNotEmpty() && wantedNames.distinct().size == wantedNames.size)
        val matcher = (wantedNames + absentNames).distinct().map { hasText(it) }.reduce { left, right -> left or right }
        var proof: RenderSnapshot? = null
        val verifyIdsInClock = renderOracle != RenderOracle.ATOMIC_V5
        compose.waitUntil(TIMEOUT) {
            if (!modelMatches(vm.uiState.value, fixture, query, expectedIds, verifyIdsInClock)) false else {
                val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
                compose.runOnUiThread {
                    val model = vm.uiState.value
                    if (modelMatches(model, fixture, query, expectedIds, verifyIdsInClock)) {
                        val captured = nodes.map { node ->
                            RenderNode(node.id, node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                                .map { it.text }.toSet(), nodeIsPartiallyDisplayed(node))
                        }
                        val identity = if (renderOracle == RenderOracle.ATOMIC_V5)
                            CapturedModelIdentity.capture(model.contacts, model.directoryContacts) else null
                        val snapshot = RenderSnapshot(captured, SystemClock.elapsedRealtimeNanos(), identity)
                        if (snapshot.matches(wantedNames, absentNames) && vm.uiState.value === model &&
                            modelMatches(model, fixture, query, expectedIds, verifyIdsInClock)) {
                            proof = snapshot
                            phase?.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
                        }
                    }
                }
                proof != null
            }
        }
        return requireNotNull(proof).also { captured ->
            // Verify the lists captured AT the stop, never a later healed/current state. A failure
            // still invalidates the whole sample; its CPU cost is not application display latency.
            if (renderOracle == RenderOracle.ATOMIC_V5) {
                assertTrue("Captured model identities differ", requireNotNull(captured.identity).matches(expectedIds))
            }
        }
    }

    private fun modelMatches(state: ContactsUiState, fixture: Fixture, query: String, ids: Set<String>,
        verifyIds: Boolean = true): Boolean {
        return state.query == query && !state.directoryLoading && !state.directoryReadFailed &&
            state.totalContactCount == fixture.profile.contacts && state.totalGroupCount == fixture.profile.groups &&
            state.navigation.destination == com.patmanak.contako.ui.RootDestination.CONTACTS &&
            state.navigation.secondary == null && state.navigation.current.selectedId == null && state.contactEditor == null &&
            state.contacts.size == ids.size && state.directoryContacts.size == ids.size &&
            (!verifyIds || (state.contacts.map { it.id }.toSet() == ids && state.directoryContacts.map { it.id }.toSet() == ids))
    }

    /**
     * Visibility conditions verified against pinned ui-test 1.10.4 AndroidAssertions.android.kt
     * (including its compiled implementation), using public layout/View APIs and no reflection.
     * https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-test/src/androidMain/kotlin/androidx/compose/ui/test/AndroidAssertions.android.kt
     * This is attached, placed, partially visible layout evidence, not GPU presentation timing.
     */
    private fun nodeIsPartiallyDisplayed(node: SemanticsNode): Boolean {
        var layout: LayoutInfo? = node.layoutInfo
        while (layout != null) {
            if (!layout.isAttached || !layout.isPlaced) return false
            layout = layout.parentInfo
        }
        val view = (node.root as? ViewRootForTest)?.view ?: return false
        if (!ViewMatchers.isDisplayed().matches(view)) return false
        val visibleWindow = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(visibleWindow)) return false
        val localBounds = node.boundsInRoot
        val windowBounds = node.boundsInWindow
        if (localBounds.width <= 0 || localBounds.height <= 0 || windowBounds.width <= 0 || windowBounds.height <= 0) return false
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return overlapsVisibleWindow(localBounds, location, visibleWindow)
    }

    private fun overlapsVisibleWindow(bounds: androidx.compose.ui.geometry.Rect, location: IntArray,
        window: android.graphics.Rect): Boolean = bounds.width > 0 && bounds.height > 0 &&
        bounds.left + location[0] < window.right && bounds.right + location[0] > window.left &&
        bounds.top + location[1] < window.bottom && bounds.bottom + location[1] > window.top

    private data class RenderNode(val id: Int, val texts: Set<String>, val displayed: Boolean)
    private data class RenderSnapshot(val nodes: List<RenderNode>, val capturedAtNanos: Long,
        val identity: CapturedModelIdentity? = null) {
        fun matches(wanted: List<String>, absent: List<String>): Boolean {
            if (nodes.any { node -> absent.any { it in node.texts } }) return false
            val found = wanted.map { name -> nodes.filter { name in it.texts }.singleOrNull() ?: return false }
            return found.all(RenderNode::displayed) && found.map { it.id }.distinct().size == wanted.size
        }
    }

    private class CapturedModelIdentity private constructor(
        private val contacts: List<CanonicalContact>, private val directory: List<CanonicalContact>,
    ) {
        fun matches(ids: Set<String>): Boolean = contacts.size == ids.size && directory.size == ids.size &&
            contacts.map { it.id }.toSet() == ids && directory.map { it.id }.toSet() == ids

        companion object {
            fun capture(contacts: List<CanonicalContact>, directory: List<CanonicalContact>): CapturedModelIdentity {
                // Copy list containers before stopping. CanonicalContact IDs are immutable vals.
                val frozenContacts = contacts.toList()
                return CapturedModelIdentity(frozenContacts,
                    if (directory === contacts) frozenContacts else directory.toList())
            }
        }
    }

    private enum class RenderOracle(val argument: String) {
        LEGACY_V3("legacy-v3"), ATOMIC_V4("atomic-v4"), ATOMIC_V5("atomic-v5"),
    }

    private fun isAtomicRenderedOperation(operation: String): Boolean = renderOracle != RenderOracle.LEGACY_V3 &&
        operation in setOf("REOPENED-VM", "CACHED-RENDER", "SEARCH-RENDER", "COLD-SEARCH-RENDER", "DIAGNOSTIC-STEADY-SEARCH-SEMANTICS")

    private fun timingProtocol(operation: String): String = when {
        !isAtomicRenderedOperation(operation) -> "accepted-action-v3"
        renderOracle == RenderOracle.ATOMIC_V4 -> "accepted-action-atomic-render-v4"
        else -> "accepted-action-atomic-render-v5"
    }

    private fun proveFirstUseful(contact: CanonicalContact, vm: ContactsViewModel, phase: Phase) {
        if (renderOracle != RenderOracle.LEGACY_V3) check(phase.contractFinishedAt != null)
        compose.onNodeWithText(contact.resolvedDisplayName).assertIsDisplayed()
        if (renderOracle == RenderOracle.LEGACY_V3) phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
        // Interaction is a postcondition, outside the first-render duration.
        compose.onNodeWithText(contact.resolvedDisplayName).performClick()
        compose.waitUntil(TIMEOUT) { vm.uiState.value.navigation.current.selectedId == contact.id }
        compose.runOnIdle { check(vm.handleBack()) }
        compose.waitUntil(TIMEOUT) {
            vm.uiState.value.navigation.current.selectedId == null && visible(contact.resolvedDisplayName)
        }
    }

    private fun measure(
        profile: Profile,
        operation: String,
        ceilingMs: Long,
        prepare: () -> Unit = {},
        verify: () -> Unit = {},
        action: (Phase) -> Unit,
    ) {
        val warmups = mutableListOf<PerformanceSample>()
        val samples = mutableListOf<PerformanceSample>()
        val phases = mutableListOf<String>()
        val environment = environment(profile, operation)
        val ceilings = PerformanceCeilings(ceilingMs, 0, 0, 0, Long.MAX_VALUE, 100)
        repeat(PerformanceRun.REQUIRED_WARMUPS + PerformanceRun.REQUIRED_SAMPLES) { index ->
            val warmup = index < PerformanceRun.REQUIRED_WARMUPS
            val ordinal = if (warmup) index + 1 else index - PerformanceRun.REQUIRED_WARMUPS + 1
            val counter = PerformanceCounter(SystemClock::elapsedRealtimeNanos)
            var started = SystemClock.elapsedRealtimeNanos()
            counter.start()
            val phase = Phase()
            var outcome = PerformanceSampleOutcome.SUCCESS
            var failure: Throwable? = null
            try {
                prepare()
                compose.waitForIdle()
                counter.start()
                started = SystemClock.elapsedRealtimeNanos()
                action(phase)
            } catch (_: TimeoutCancellationException) {
                outcome = PerformanceSampleOutcome.TIMEOUT
            } catch (_: CancellationException) {
                outcome = PerformanceSampleOutcome.CANCELLED
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                outcome = PerformanceSampleOutcome.TIMEOUT
            } catch (caught: Throwable) {
                outcome = PerformanceSampleOutcome.LOCAL_FAILURE
                failure = caught
            }
            val finished = SystemClock.elapsedRealtimeNanos()
            val accepted = phase.actionAcceptedAt
            val contractEnd = phase.contractFinishedAt ?: finished
            var sample = counter.finish(ordinal).copy(
                durationMs = (contractEnd - (accepted ?: started)).coerceAtLeast(0) / 1_000_000,
                commitCount = if (operation == "SAVE-COMMIT" && committedAt.get() != 0L) 1 else 0,
                outcome = outcome,
            )
            if (outcome == PerformanceSampleOutcome.SUCCESS) {
                try { verify() } catch (caught: Throwable) {
                    outcome = PerformanceSampleOutcome.LOCAL_FAILURE
                    sample = sample.copy(outcome = outcome)
                    failure = caught
                }
            }
            if (warmup) warmups += sample else samples += sample
            phases += "{\"ordinal\":$ordinal,\"warmup\":$warmup,\"outcome\":\"${outcome.name}\"," +
                "\"actionAccepted\":${accepted != null}," +
                "\"admissionWaitUs\":${accepted?.let { (it - started).coerceAtLeast(0) / 1_000 } ?: "null"}," +
                "\"requestToContractUs\":${(contractEnd - started).coerceAtLeast(0) / 1_000}," +
                "\"elapsedAttemptUs\":${(finished - started).coerceAtLeast(0) / 1_000}," +
                "\"contractUs\":${accepted?.let { (contractEnd - it).coerceAtLeast(0) / 1_000 } ?: "null"}," +
                "\"editorClosedUs\":${phase.editorClosedAt?.let { closed -> accepted?.let { (closed - it).coerceAtLeast(0) / 1_000 } } ?: "null"}}"
            // Persist partial evidence BEFORE a failure assertion, including timed-out elapsed time.
            val partial = PerformanceReport(environment, warmups.size, samples, ceilings, warmups)
            context.writePerformanceArtifact("${environment.runId}.json", PerformanceMetricCollector.toJson(partial))
            context.writePerformanceArtifact("${environment.runId}.csv", PerformanceMetricCollector.toCsv(partial))
            context.writePerformanceArtifact("${environment.runId}-phases.json",
                "{\"schemaVersion\":3,\"timingProtocol\":\"${timingProtocol(operation)}\",\"renderOracle\":\"${renderOracle.argument}\",\"samples\":[${phases.joinToString(",")}]}")
            if (outcome != PerformanceSampleOutcome.SUCCESS) {
                throw AssertionError("${environment.runId}: $outcome; see local evidence", failure)
            }
        }
        val run = PerformanceRun(environment, PerformanceRun.REQUIRED_WARMUPS, samples, ceilings, warmups)
        context.writePerformanceArtifact("${environment.runId}.json", PerformanceMetricCollector.toJson(run))
        context.writePerformanceArtifact("${environment.runId}.csv", PerformanceMetricCollector.toCsv(run))
        // A baseline is diagnostic: report budget failures without aborting later operations.
        println("CONTAKO_PERF ${environment.runId} p95=${run.p95DurationMs}ms violations=${run.violations()}")
        budgetViolations += run.violations().map { "${environment.runId}:$it" }
    }

    /** Replicated diagnostic work on identical captured data; these P95s are not additive. */
    private fun measureDiagnosticComponents(
        profile: Profile,
        db: ContakoDatabase,
        rows: List<DirectoryContactRow>,
        oracle: List<CanonicalContact>,
        fixture: Fixture,
    ) {
        val locale = Locale.getDefault()
        val dtos = rows.map { ContactWithValues(it.contact, it.values) }
        val mapped = dtos.map(ContactWithValues::toDomain)
        val oracleById = oracle.associateBy { it.id }
        assertTrue("Mapping differs from production snapshot", mapped.associateBy { it.id } == oracleById)
        val facets = ContactDirectoryPolicy.organizationFacets(oracle)
        assertEquals(profile.contacts, facets.size)
        val sections = DirectorySectionIndex.create(oracle, locale, CanonicalContact::resolvedDisplayName)
        assertEquals(oracle.map { it.id }, sections.sections.flatMap { it.items }.map { it.id })

        // Same collector, monotonic clock, five warmups, thirty samples and partial failure evidence.
        fun <T> component(name: String, work: () -> T, verify: (T) -> Unit) {
            measure(profile, "DIAGNOSTIC-$name", Long.MAX_VALUE) { phase ->
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                val result = work()
                phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
                verify(result)
            }
        }
        component("DAO-DIRECTORY-MATERIALIZATION", {
            runBlocking { db.contactContentChangesDao().observeDirectory(ACCOUNT).first() }
        }) { actual -> assertTrue("Materialized DTOs differ", rows == actual) }
        component("DAO-SCOPED-DIRECTORY-MATERIALIZATION", {
            runBlocking { db.contactContentChangesDao().readDirectory(ACCOUNT) }
        }) { actual -> assertTrue("Scoped materialized DTOs differ", rows == actual) }
        component("CANONICAL-CONVERSION", { dtos.map(ContactWithValues::toDomain) }) { actual ->
            assertTrue("Converted canonical fields differ", mapped == actual)
        }
        component("COLLATOR-SORT", {
            val collator = Collator.getInstance(locale)
            mapped.sortedWith { left, right -> collator.compare(left.resolvedDisplayName, right.resolvedDisplayName) }
        }) { actual -> assertTrue("Sort differs from production order and values", oracle == actual) }
        component("ORGANIZATION-FACETS", { ContactDirectoryPolicy.organizationFacets(oracle) }) { actual ->
            assertTrue("Organization facets differ", facets == actual)
        }
        component("SEARCH-AND-GROUP-NAMES", {
            val search = ContactSearch.prepare(SEARCH)
            val matchingGroups = fixture.groups.filter { search.matchesGroupName(it.name) }
            val memberIds = matchingGroups.flatMap(ContactGroup::memberships).map(GroupMembership::contactId).toSet()
            val contacts = oracle.filter { search.matches(it) || it.id in memberIds }
            contacts to matchingGroups
        }) { (contacts, groups) ->
            assertEquals(setOf(fixture.contact(0).id, fixture.contact(1).id), contacts.map { it.id }.toSet())
            assertTrue("Unexpected matching groups", groups.isEmpty())
            assertTrue("Search changed canonical fields", contacts.all { it == oracleById[it.id] })
        }
        val preparedInputs = oracle.map(ContactSearch::prepareContact)
        val query = ContactSearch.prepare(SEARCH)
        component("SEARCH-INPUT-PREPARATION", { oracle.map(ContactSearch::prepareContact) }) { actual ->
            assertEquals(oracle.size, actual.size)
            assertEquals(setOf(fixture.contact(0).id, fixture.contact(1).id),
                oracle.filterIndexed { index, _ -> query.matches(actual[index]) }.map { it.id }.toSet())
        }
        component("PREPARED-SEARCH-AND-GROUP-NAMES", {
            val search = ContactSearch.prepare(SEARCH)
            val matchingGroups = fixture.groups.filter { search.matchesGroupName(it.name) }
            val memberIds = matchingGroups.flatMap(ContactGroup::memberships).map(GroupMembership::contactId).toSet()
            val contacts = oracle.filterIndexed { index, contact ->
                search.matches(preparedInputs[index]) || contact.id in memberIds
            }
            contacts to matchingGroups
        }) { (contacts, groups) ->
            assertEquals(setOf(fixture.contact(0).id, fixture.contact(1).id), contacts.map { it.id }.toSet())
            assertTrue("Unexpected prepared-search matching groups", groups.isEmpty())
            assertTrue("Prepared search changed canonical fields", contacts.all { it == oracleById[it.id] })
        }
        component("DIRECTORY-SECTION-INDEX", {
            DirectorySectionIndex.create(oracle, locale, CanonicalContact::resolvedDisplayName)
        }) { actual -> assertTrue("Section order, offsets or contents differ", sections == actual) }
    }

    /**
     * Calibration only: the searched UI is already stable. Repeat the v3 semantics oracle without
     * changing query, model or contents. Its P95 MUST NOT be subtracted from another P95 or used to
     * turn a failed render contract into a pass.
     */
    private fun measureSteadySearchSemantics(profile: Profile, vm: ContactsViewModel, fixture: Fixture) {
        val expected = setOf(fixture.contact(0).id, fixture.contact(1).id)
        measure(profile, "DIAGNOSTIC-STEADY-SEARCH-SEMANTICS", Long.MAX_VALUE) { phase ->
            compose.runOnIdle { phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos() }
            if (renderOracle != RenderOracle.LEGACY_V3) {
                awaitAtomicRender(vm, fixture, SEARCH, expected,
                    wantedNames = (0..1).map { fixture.contact(it).resolvedDisplayName },
                    absentNames = listOf(fixture.contact(2).resolvedDisplayName), phase = phase)
            } else compose.waitUntil(TIMEOUT) {
                vm.uiState.value.query == SEARCH && vm.uiState.value.contacts.map { it.id }.toSet() == expected &&
                    (0..1).all { visible(fixture.contact(it).resolvedDisplayName) } &&
                    compose.onAllNodesWithText(fixture.contact(2).resolvedDisplayName).fetchSemanticsNodes().isEmpty()
            }
            (0..1).forEach { compose.onNodeWithText(fixture.contact(it).resolvedDisplayName).assertIsDisplayed() }
            assertEquals(expected, vm.uiState.value.contacts.map { it.id }.toSet())
            if (renderOracle == RenderOracle.LEGACY_V3) phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
        }
    }

    /** Planner text describes only fixed SQL identifiers, never bound values or contact data. */
    private fun writeDirectoryQueryPlans(db: ContakoDatabase, profile: Profile) {
        fun plan(sql: String, vararg arguments: String): JSONArray = db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $sql", arguments).use { cursor ->
                val detail = cursor.getColumnIndexOrThrow("detail")
                JSONArray().also { result -> while (cursor.moveToNext()) result.put(cursor.getString(detail)) }
            }
        val headers = plan("""
            SELECT contacts.*, changes.observed_at AS content_observed_at FROM contacts
            LEFT JOIN contact_content_changes AS changes
                ON contacts.account_id=changes.account_id AND contacts.id=changes.contact_id
            WHERE contacts.account_id=? AND contacts.is_deleted=0
            ORDER BY contacts.sort_name COLLATE NOCASE, contacts.id
        """.trimIndent(), ACCOUNT)
        val legacyFlatValues = plan("""
            SELECT v.* FROM contact_values AS v
            INNER JOIN contacts AS c ON c.owner_key=v.owner_key
                AND c.account_id=v.account_id AND c.id=v.contact_id
            WHERE c.account_id=? AND v.account_id=? AND c.is_deleted=0
            ORDER BY v.owner_key, v.rowid
        """.trimIndent(), ACCOUNT, ACCOUNT)
        val ownerKeys = runBlocking { db.contactContentChangesDao().readDirectoryHeaders(ACCOUNT) }
            .take(com.patmanak.contako.data.local.ContactContentChangesDao.DIRECTORY_OWNER_BATCH_SIZE)
            .map { it.contact.ownerKey }
        val boundedValues = plan("""
            SELECT v.* FROM contact_values AS v INDEXED BY index_contact_values_owner_key
            WHERE v.account_id=? AND v.owner_key IN (${ownerKeys.joinToString(",") { "?" }})
            ORDER BY v.owner_key, v.rowid
        """.trimIndent(), ACCOUNT, *ownerKeys.toTypedArray())
        context.writePerformanceArtifact("cached-directory-${profile.name}-query-plan.json", JSONObject()
            .put("scope", "fixed-sql-planner-identifiers-only-no-bind-values")
            .put("ownershipGuard", "account-contact-owner-checked-against-transactional-headers")
            .put("headers", headers).put("legacyUnboundedFlatValues", legacyFlatValues)
            .put("currentBoundedOwnerValues", boundedValues).put("ownerBatchLimit", ownerKeys.size).toString(2))
    }

    /** String-column UTF-8 aggregate only: never serialize a payload into evidence. */
    private fun materializedUtf8Bytes(rows: List<DirectoryContactRow>): Long {
        fun bytes(vararg values: String?): Long = values.sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
        return rows.sumOf { row ->
            val contact = row.contact
            bytes(contact.accountId, contact.id, contact.ownerKey, contact.firstName, contact.lastName,
                contact.displayName, contact.sortName, contact.remoteContactId, contact.remoteVCardUid,
                contact.remoteVersion, contact.actionRequiredReasonsEncoding, contact.conflictState) +
                row.values.sumOf { value ->
                    bytes(value.accountId, value.contactId, value.ownerKey, value.id, value.kind, value.value,
                        value.label, value.componentsEncoding, value.metadataEncoding, value.binaryReference,
                        value.preservationKey)
                }
        }
    }

    private fun environment(profile: Profile, operation: String) = PerformanceEnvironment(
        runId = "09-${profile.name}-$operation${if (isAtomicRenderedOperation(operation)) "-${renderOracle.argument}" else ""}-api${Build.VERSION.SDK_INT}",
        caseId = "07-$operation${if (isAtomicRenderedOperation(operation)) "-${renderOracle.argument.uppercase(Locale.ROOT)}" else ""}", fixtureId = "FX-P-RICH-${profile.contacts}", fixtureSeed = 9L,
        buildType = PerformanceBuildType.BENCHMARK, deviceClass = "physical-reference",
        apiLevel = Build.VERSION.SDK_INT, thermalState = evidence.thermalState,
        batteryPercent = evidence.batteryPercent, networkProfile = "none", cacheState = "warm",
        chargingState = evidence.chargingState, sourceRevision = evidence.sourceRevision,
        buildIdentifier = evidence.buildIdentifier, benchmarkCommand = evidence.benchmarkCommand,
        applicationApkSha256 = evidence.applicationApkSha256, testApkSha256 = evidence.testApkSha256,
    )

    private suspend fun insert(db: ContakoDatabase, contact: CanonicalContact) {
        db.contactDao().upsert(contact.toEntity())
        db.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
        contact.preservationEnvelope?.let { db.contactPayloadDao().upsert(it.toEntity(contact.accountId, contact.id)) }
    }

    private fun verifyFixture(db: ContakoDatabase, repository: RoomContactRepository, fixture: Fixture) = runBlocking {
        assertEquals(fixture.ids, repository.observeContacts(ACCOUNT).first().map { it.id }.toSet())
        assertEquals(fixture.profile.groups, db.contactGroupDao().getAll(ACCOUNT).size)
        assertCanonical(fixture.foreign, requireNotNull(repository.getContact(FOREIGN, fixture.foreign.id)))
        assertCanonical(fixture.contact(0), requireNotNull(repository.getContact(ACCOUNT, fixture.contact(0).id)))
        assertCanonical(fixture.contact(20), requireNotNull(repository.getContact(ACCOUNT, fixture.contact(20).id)))
    }

    private fun verifySaved(
        db: ContakoDatabase, repository: RoomContactRepository, fixture: Fixture,
        before: CanonicalContact, note: String, witnessBefore: CanonicalContact, groupsBefore: List<ContactGroup>,
    ) = runBlocking {
        val saved = requireNotNull(repository.getContact(ACCOUNT, before.id))
        assertEquals(before.values.map { if (it.kind == ContactValueKind.NOTE) it.copy(value = note) else it }
            .associateBy { it.id }, saved.values.associateBy { it.id })
        assertEquals(before.preservationEnvelope, saved.preservationEnvelope)
        assertEquals(before.accountId, saved.accountId)
        assertEquals(before.id, saved.id)
        assertEquals(before.remoteContactId, saved.remoteContactId)
        assertEquals(before.remoteVCardUid, saved.remoteVCardUid)
        assertEquals(before.remoteVersion, saved.remoteVersion)
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(saved.revision, saved.pendingMutationRevision)
        assertEquals(before.resolvedDisplayName, saved.resolvedDisplayName)
        assertEquals(before.copy(
            values = saved.values, revision = saved.revision,
            pendingMutationRevision = saved.pendingMutationRevision,
            updatedAtEpochMillis = saved.updatedAtEpochMillis,
        ), saved)
        assertCanonical(fixture.foreign, requireNotNull(repository.getContact(FOREIGN, fixture.foreign.id)))
        assertCanonical(witnessBefore, requireNotNull(repository.getContact(ACCOUNT, witnessBefore.id)))
        val outbox = requireNotNull(db.outboxDao().get(ACCOUNT, AggregateType.CONTACT.name, saved.id))
        assertEquals(saved.revision, outbox.revision)
        assertEquals("UPSERT", outbox.operation)
        assertEquals(saved.remoteContactId, outbox.remoteIdentity)
        assertEquals(saved.remoteVersion, outbox.remoteVersion)
        val groups = db.contactGroupDao().getAll(ACCOUNT).map { it.toDomain() }
        assertEquals(fixture.groups.associate { it.id to it.memberships.toSet() },
            groups.associate { it.id to it.memberships.toSet() })
        val affectedGroups = groups.filter { group -> group.memberships.any { it.contactId == saved.id } }
        val affectedIds = affectedGroups.map { it.id }.toSet()
        val beforeById = groupsBefore.associateBy { it.id }
        assertEquals(beforeById.keys, groups.map { it.id }.toSet())
        groups.forEach { group ->
            val previous = beforeById.getValue(group.id)
            val expected = if (group.id in affectedIds) previous.copy(
                revision = previous.revision + 1,
                pendingMutationRevision = previous.revision + 1,
                updatedAtEpochMillis = group.updatedAtEpochMillis,
            ) else previous
            // Membership ordering is immaterial; every other group field remains exact.
            assertEquals(expected.copy(memberships = emptyList()), group.copy(memberships = emptyList()))
            assertEquals(previous.memberships.toSet(), group.memberships.toSet())
        }
        affectedGroups.forEach { group ->
            val pending = requireNotNull(db.outboxDao().get(ACCOUNT, AggregateType.GROUP.name, group.id))
            assertEquals(group.revision, pending.revision)
            assertEquals(group.revision, group.pendingMutationRevision)
            assertEquals("ASSIGNMENTS", pending.operation)
            assertEquals(group.remoteLabelId, pending.remoteIdentity)
            assertEquals(group.remoteVersion, pending.remoteVersion)
        }
        assertEquals(setOf(saved.id) + affectedGroups.map { it.id },
            db.outboxDao().getAll(ACCOUNT).map { it.aggregateId }.toSet())
        assertEquals(null, db.outboxDao().get(FOREIGN, AggregateType.CONTACT.name, fixture.foreign.id))
    }

    private fun assertCanonical(expected: CanonicalContact, actual: CanonicalContact) {
        assertEquals(expected.copy(values = emptyList()), actual.copy(values = emptyList()))
        assertEquals(expected.values.associateBy { it.id }, actual.values.associateBy { it.id })
    }

    private fun validPng(): String {
        val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xff5682b9.toInt())
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        } finally { bitmap.recycle() }
    }

    private data class Profile(val name: String, val contacts: Int, val groups: Int)
    private class Phase(
        var actionAcceptedAt: Long? = null,
        var contractFinishedAt: Long? = null,
        var editorClosedAt: Long? = null,
    )

    private class Fixture(val profile: Profile, private val png: String) {
        private val first by lazy { buildContact(0) }
        private val second by lazy { buildContact(1) }
        val ids = (0 until profile.contacts).map { id(it) }.toSet()
        val groups = List(profile.groups) { group -> ContactGroup(
            ACCOUNT, "group-$group", "Équipe ${group.toString().padStart(3, '0')}", revision = 1,
            remoteLabelId = "synthetic-label-$group", remoteVersion = "v1",
            memberships = List(10) { offset ->
                val index = if (group == 0 && offset == 1) 0 else (group * 10 + offset) % profile.contacts
                GroupMembership(id(index), "email-${offset % 2}")
            },
        ) }
        val foreign: CanonicalContact get() = contact(0).copy(accountId = FOREIGN, displayName = "Foreign canary")
        fun contact(index: Int): CanonicalContact = when (index) {
            0 -> first
            1 -> second
            else -> buildContact(index)
        }
        private fun buildContact(index: Int): CanonicalContact {
            val rich = index % 20 == 0
            return CanonicalContact(
                ACCOUNT, id(index), firstName = "Élodie", lastName = "İşık-$index",
                displayName = "A ${index.toString().padStart(5, '0')} Élodie 李",
                revision = 1, remoteContactId = "synthetic-remote-$index", remoteVCardUid = "synthetic-uid-$index",
                remoteVersion = "v1", values = buildList {
                    add(value("email-0", ContactValueKind.EMAIL, "duplicate@example.invalid", primary = true))
                    add(value("email-1", ContactValueKind.EMAIL, "duplicate@example.invalid", order = 1))
                    add(value("phone", ContactValueKind.PHONE, "+1555000${index.toString().padStart(4, '0')}"))
                    add(value("nickname", ContactValueKind.NICKNAME, if (index < 2) SEARCH else "Lili-$index"))
                    add(value("organization", ContactValueKind.ORGANIZATION, "Société Δ-$index"))
                    add(value("note", ContactValueKind.NOTE, "Synthetic unchanged note"))
                    add(value("url", ContactValueKind.URL, "https://example.invalid/$index"))
                    add(value("category", ContactValueKind.CATEGORY, "Hidden synthetic category"))
                    if (rich) {
                        add(value("photo", ContactValueKind.PHOTO, png, primary = true).copy(preservationKey = "photo-key"))
                        add(value("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-SYNTHETIC:" + "x".repeat(8192))
                            .copy(preservationKey = "opaque-key", metadata = mapOf("synthetic" to "preserve")))
                    }
                },
                preservationEnvelope = if (rich) PreservationEnvelope(
                    mapOf("opaque-key" to "X-SYNTHETIC:" + "y".repeat(16384)), "synthetic-baseline-$index",
                ) else null,
            )
        }
        private fun id(index: Int) = "rich-${index.toString().padStart(5, '0')}"
        private fun value(id: String, kind: ContactValueKind, text: String, order: Int = 0, primary: Boolean = false) =
            ContactValue(id, kind, text, order = order, isPrimary = primary)
    }

    private companion object {
        const val ACCOUNT = "benchmark-rich-owner"
        const val FOREIGN = "benchmark-foreign-owner"
        const val SEARCH = "needle-equality"
        const val TIMEOUT = 30_000L
    }
}
