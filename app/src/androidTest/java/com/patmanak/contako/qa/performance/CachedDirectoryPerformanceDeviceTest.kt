package com.patmanak.contako.qa.performance

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
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
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.android.account.SignOutResult
import com.patmanak.contako.data.local.AggregateType
import com.patmanak.contako.data.local.ContakoDatabase
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
import com.patmanak.contako.ui.ContakoApp
import com.patmanak.contako.ui.ContactsViewModel
import com.patmanak.contako.ui.theme.ContakoTheme
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    @After fun close() {
        disposeViewModel()
        database?.close()
        database = null
        databaseName?.let(context::deleteDatabase)
        databaseName = null
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
        context.writePerformanceArtifact("cached-directory-${profile.name}-scope.json", """
            {"schemaVersion":2,"layer":"real-room-repository-viewmodel-production-compose",
            "contactCount":${profile.contacts},"groupCount":${profile.groups},"emailsPerContact":2,
            "inlinePhotoCount":${(profile.contacts + 19) / 20},"opaqueValueCount":${(profile.contacts + 19) / 20},
            "opaqueChars":8192,"envelopeChars":16384,"groupMemberships":${fixture.groups.sumOf { it.memberships.size }},
            "remotePeer":"NOT_RUN","androidProvider":"NOT_RUN","network":"not-constructed",
            "reopenedVm":"SAME_PROCESS_AND_OPEN_DATABASE","heap":"before-after-jvm-proxy-not-native-peak",
            "sqlRowsAndBytes":"NOT_MEASURED","mappingSortComponents":"NOT_MEASURED",
            "timingProtocol":"accepted-action-v2","admissionWait":"recorded-separately-not-subtracted-from-total",
            "composeTestOverhead":"synchronization-and-semantics-included","photoPixels":"72x72-not-heavy-decode",
            "photoCompletion":"not-a-render-prerequisite","firstUsefulRender":"displayed-name-and-local-selection"}
        """.trimIndent())

        measure(profile, "REPOSITORY-FIRST", Long.MAX_VALUE) { phase ->
            phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
            val contacts = runBlocking { repository.observeContacts(ACCOUNT).first() }
            phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
            assertEquals(fixture.ids, contacts.map { it.id }.toSet())
        }
        measure(profile, "REOPENED-VM", 500, prepare = { disposeViewModel() }) { phase ->
            val vm = compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                createViewModel(repository).also { shownViewModel = it }
            }
            waitForDirectory(vm, fixture)
            proveFirstUseful(fixture.contact(0), vm, phase)
        }
        // The last reopened VM is the only live VM. Its full Room flow remains subscribed.
        val vm = requireNotNull(shownViewModel)
        measure(profile, "CACHED-RENDER", 500, prepare = {
            compose.runOnIdle { shownViewModel = null }
            compose.waitForIdle()
        }) { phase ->
            compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                shownViewModel = vm
            }
            waitForDirectory(vm, fixture)
            proveFirstUseful(fixture.contact(0), vm, phase)
        }
        measure(profile, "SEARCH-RENDER", 100, prepare = {
            compose.runOnIdle { vm.updateQuery("") }
            waitForDirectory(vm, fixture)
            compose.waitUntil(TIMEOUT) { visible(fixture.contact(2).resolvedDisplayName) }
        }) { phase ->
            compose.runOnIdle {
                phase.actionAcceptedAt = SystemClock.elapsedRealtimeNanos()
                vm.updateQuery(SEARCH)
            }
            val expected = setOf(fixture.contact(0).id, fixture.contact(1).id)
            compose.waitUntil(TIMEOUT) {
                vm.uiState.value.query == SEARCH && vm.uiState.value.contacts.map { it.id }.toSet() == expected &&
                    (0..1).all { visible(fixture.contact(it).resolvedDisplayName) } &&
                    compose.onAllNodesWithText(fixture.contact(2).resolvedDisplayName).fetchSemanticsNodes().isEmpty()
            }
            (0..1).forEach { compose.onNodeWithText(fixture.contact(it).resolvedDisplayName).assertIsDisplayed() }
            assertEquals(expected, vm.uiState.value.contacts.map { it.id }.toSet())
            phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
        }

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

    private fun proveFirstUseful(contact: CanonicalContact, vm: ContactsViewModel, phase: Phase) {
        compose.onNodeWithText(contact.resolvedDisplayName).assertIsDisplayed()
        phase.contractFinishedAt = SystemClock.elapsedRealtimeNanos()
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
                "{\"schemaVersion\":2,\"timingProtocol\":\"accepted-action-v2\",\"samples\":[${phases.joinToString(",")}]}")
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

    private fun environment(profile: Profile, operation: String) = PerformanceEnvironment(
        runId = "09-${profile.name}-$operation-api${Build.VERSION.SDK_INT}",
        caseId = "07-$operation", fixtureId = "FX-P-RICH-${profile.contacts}", fixtureSeed = 9L,
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
