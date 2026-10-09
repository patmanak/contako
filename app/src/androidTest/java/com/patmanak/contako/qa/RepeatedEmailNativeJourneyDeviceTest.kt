package com.patmanak.contako.qa

import android.accounts.Account
import android.content.ContentProviderOperation
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteContactPresence
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.policy.PostalAddressPolicy
import com.patmanak.contako.domain.repository.ContactGroupAssignment
import com.patmanak.contako.domain.repository.ContactEditBaseline
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.SyncActivity
import com.patmanak.contako.domain.sync.SyncDashboardState
import com.patmanak.contako.domain.sync.SyncPassOutcome
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in FT-06/07 repeated-email journey on an already authenticated installation.
 * Arguments: runRichDuplicateJourney=true,
 * richDuplicatePhase=prepare|resume_preparation|native_edit|native_replay|verify|linked_verify|ui_locator|cleanup|inspect|inspect_fixture.
 * linked_verify also requires the authorized foreign source type in richForeignAccountType.
 * Preparation retains two fictional contacts and three groups for independent editor/Web checks.
 * Native edits exercise ContentResolver/DIRTY semantics; they do not qualify an OEM editor UI.
 * No credentials, new Android account, session reset, repair or sync-adapter writes are used.
 * Cleanup is explicit and limited to successful creates recorded in the app-private journal.
 */
@RunWith(AndroidJUnit4::class)
class RepeatedEmailNativeJourneyDeviceTest {
    private enum class Phase { PREPARE, RESUME_PREPARATION, NATIVE_EDIT, NATIVE_REPLAY, VERIFY, LINKED_VERIFY, UI_LOCATOR, CLEANUP, INSPECT, INSPECT_FIXTURE }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ContakoApplication
    private val repository get() = app.contactRepository
    private val runtime get() = app.protonGateCRuntime
    private val resolver get() = context.contentResolver
    private val journalFile get() = AtomicFile(File(context.filesDir, "qa-rich-duplicate-journey.json"))
    private var checks = 0
    private var stage = "ENTRY"
    private var evidenceAccount: String? = null
    private var evidenceAddress: String? = null

    @Test fun retainedRichRepeatedEmailsSurviveNativeDeltas() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runRichDuplicateJourney") == "true")
        val result = Bundle()
        try {
            val phase = Phase.valueOf(checkNotNull(args.getString("richDuplicatePhase")).uppercase(Locale.ROOT))
            result.putString("rich_phase", phase.name)
            runBlocking {
                stage = "SESSION"
                ensure(runtime.session.restore(runtime.accountScope).value() == SessionState.READY)
                val address = checkNotNull(runtime.currentAccountAddress())
                val account = runtime.accountScope.value
                evidenceAccount = account
                evidenceAddress = address
                if (phase !in setOf(Phase.INSPECT, Phase.INSPECT_FIXTURE)) awaitIdle(account)
                if (phase == Phase.PREPARE) {
                    stage = "BASELINE"
                    assertUnrelatedClean(account, address, emptySet(), emptySet())
                    archiveCleanedJournal(account, address)
                    val journal = newJournal(account, address)
                    writeJournal(journal)
                    prepare(journal)
                } else {
                    val journal = JSONObject(journalFile.readFully().toString(Charsets.UTF_8))
                    ensure(journal.getInt("schema") == 1 && journal.getString("account") == account &&
                        journal.getString("address") == address)
                    val contactIds = entries(journal, "contacts").filter { it.getBoolean("created") }
                        .map { it.getString("id") }.toSet()
                    val groupIds = entries(journal, "groups").filter { it.getBoolean("created") }
                        .map { it.getString("id") }.toSet()
                    stage = "BASELINE"
                    if (phase !in setOf(Phase.INSPECT, Phase.INSPECT_FIXTURE)) assertUnrelatedClean(account, address, contactIds, groupIds)
                    when (phase) {
                        Phase.RESUME_PREPARATION -> resumePreparation(journal)
                        Phase.NATIVE_EDIT -> nativeEdit(journal)
                        Phase.NATIVE_REPLAY -> nativeReplay(journal)
                        Phase.VERIFY -> {
                            ensure(journal.getBoolean("prepared") && !journal.getBoolean("inFlight"))
                            verify(journal)
                            sync(account)
                            verify(journal)
                        }
                        Phase.LINKED_VERIFY -> {
                            ensure(journal.getBoolean("prepared") && !journal.getBoolean("inFlight"))
                            verify(journal)
                            linkedVerify(journal, checkNotNull(args.getString("richForeignAccountType")))
                        }
                        Phase.UI_LOCATOR -> uiLocator(journal)
                        Phase.CLEANUP -> cleanup(journal)
                        Phase.INSPECT -> {
                            stage = "INSPECT_CLOSED_STATE"
                            failureStatus(result)
                            result.putString("rich_inspection_outcome", if (result.getBoolean("rich_db_status_available")) "COMPLETE" else "PARTIAL")
                        }
                        Phase.INSPECT_FIXTURE -> {
                            stage = "INSPECT_FIXTURE_SCOPE"
                            val contacts = entries(journal, "contacts")
                            val groups = entries(journal, "groups")
                            ensure(contacts.size == 2 && groups.size == 3 && contacts.all { it.getBoolean("created") } &&
                                groups.all { it.getBoolean("created") })
                            result.putBoolean("rich_fixture_in_flight", journal.getBoolean("inFlight"))
                            (0..2).forEach { step -> result.putInt("rich_fixture_step_${step}_count", contacts.count { it.getInt("step") == step }) }
                            ensure(contacts.all { it.getInt("step") in 0..2 })
                            verify(journal)
                            result.putString("rich_fixture_readback_outcome", "READBACK_PASS")
                        }
                        Phase.PREPARE -> error("UNREACHABLE")
                    }
                }
            }
            result.putString("rich_outcome", if (phase in setOf(Phase.INSPECT, Phase.INSPECT_FIXTURE)) "INSPECTION" else "PASS")
        } catch (_: Throwable) {
            // Neither JUnit output nor status bundles may contain payloads, account IDs or causes.
            result.putString("rich_outcome", "FAIL")
            failureStatus(result)
            throw AssertionError("RICH_JOURNEY_$stage")
        } finally {
            result.putString("rich_stage", stage)
            result.putInt("rich_checks", checks)
            instrumentation.sendStatus(0, result)
        }
    }

    private fun ensure(condition: Boolean) {
        checks++
        check(condition) { "RICH_FIELD_OR_SCOPE_MISMATCH" }
    }

    private fun failureStatus(result: Bundle) {
        result.putBoolean("rich_status_available", false)
        result.putBoolean("rich_db_status_available", false)
        val account = evidenceAccount ?: return
        try {
            runBlocking {
                withTimeout(3_000) {
                    val status = app.syncRecoveryDataSource.observeStatus(account).first()
                    result.putString("rich_sync_activity", app.syncRecoveryDataSource.observeActivity(account).first().name)
                    if (status != null) {
                        result.putString("rich_sync_state", status.state.name)
                        result.putString("rich_sync_problem", status.problem?.name ?: "NONE")
                        result.putInt("rich_sync_pending", status.pendingMutationCount)
                        result.putInt("rich_sync_action_required", status.actionRequiredCount)
                        result.putInt("rich_android_pending", status.androidPendingContactIds.size)
                        result.putInt("rich_blocked_contacts", status.blockedMutationContactIds.size)
                        result.putBoolean("rich_status_available", true)
                    }
                    evidenceAddress?.let { address ->
                        val completion = app.androidAccountSyncRunnerResolver.resolve(Account(address, BuildConfig.APPLICATION_ID))
                            ?.lastCompletion?.value
                        result.putString("rich_runner_outcome", completion?.outcome?.name ?: "UNAVAILABLE")
                    }
                    databaseStatus(result, account)
                }
            }
        } catch (_: Throwable) { /* Diagnostics cannot mask the original closed-stage failure. */ }
    }

    private fun databaseStatus(result: Bundle, account: String) {
        val database = ContakoDatabase.create(context)
        try {
            val reader = database.openHelper.readableDatabase
            fun categories(table: String, column: String, key: String, allowed: List<String>) {
                allowed.forEach { result.putInt("${key}_$it", 0) }
                result.putInt("${key}_OTHER", 0)
                reader.query("SELECT $column, COUNT(*) FROM $table WHERE account_id=? GROUP BY $column",
                    arrayOf(account)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val category = cursor.getString(0)?.takeIf { it in allowed } ?: "OTHER"
                        result.putInt("${key}_$category", result.getInt("${key}_$category") + cursor.getInt(1))
                    }
                }
            }
            val projections = listOf("DETACHED", "WRITE_PENDING", "CLEAN", "REPAIR_REQUIRED")
            val tombstones = listOf("NONE", "CANONICAL_COMMITTED", "REMOTE_CONVERGED")
            categories("android_group_projection_ledger", "projection_state", "rich_group_projection", projections)
            categories("android_group_membership_projection_ledger", "projection_state", "rich_membership_projection", projections)
            categories("android_projection_ledger", "projection_state", "rich_contact_projection", projections)
            categories("android_group_projection_ledger", "tombstone_state", "rich_group_tombstone", tombstones)
            categories("android_projection_ledger", "tombstone_state", "rich_contact_tombstone", tombstones)
            categories("contact_groups", "is_deleted", "rich_group_deleted", listOf("0", "1"))
            categories("contacts", "is_deleted", "rich_contact_deleted", listOf("0", "1"))
            val aggregates = listOf("CONTACT", "GROUP")
            val operations = listOf("UPSERT", "DELETE", "ASSIGNMENTS")
            val states = listOf("PENDING", "IN_FLIGHT", "ACKNOWLEDGED", "ACTION_REQUIRED")
            aggregates.forEach { aggregate -> operations.forEach { operation -> states.forEach { state ->
                result.putInt("rich_outbox_${aggregate}_${operation}_$state", 0)
            } } }
            result.putInt("rich_outbox_OTHER", 0)
            reader.query("SELECT aggregate_type, operation, state, COUNT(*) FROM outbox_mutations WHERE account_id=? " +
                "GROUP BY aggregate_type, operation, state", arrayOf(account)).use { cursor ->
                while (cursor.moveToNext()) {
                    val aggregate = cursor.getString(0)
                    val operation = cursor.getString(1)
                    val state = cursor.getString(2)
                    val key = if (aggregate in aggregates && operation in operations && state in states)
                        "rich_outbox_${aggregate}_${operation}_$state" else "rich_outbox_OTHER"
                    result.putInt(key, result.getInt(key) + cursor.getInt(3))
                }
            }
            result.putBoolean("rich_db_status_available", true)
        } finally { database.close() }
    }

    private suspend fun cleanupSync(journal: JSONObject) {
        val account = journal.getString("account")
        val runner = checkNotNull(app.androidAccountSyncRunnerResolver.resolve(
            Account(journal.getString("address"), BuildConfig.APPLICATION_ID)))
        // Teardown alone permits up to three ordinary passes for deletion dependency ordering.
        // Each pass must actually complete; nominal creation/editing still requires CURRENT.
        for (pass in 1..3) {
            stage = "CLEANUP_SERIALIZED_PASS_$pass"
            awaitIdle(account)
            val before = runner.lastCompletion.value?.passNumber ?: 0
            app.syncRecoveryDataSource.requestSync(account)
            withTimeout(120_000) {
                while ((runner.lastCompletion.value?.passNumber ?: 0) <= before ||
                    app.syncRecoveryDataSource.observeActivity(account).first() != SyncActivity.IDLE) delay(250)
            }
            val outcome = checkNotNull(runner.lastCompletion.value).outcome
            val status = app.syncRecoveryDataSource.observeStatus(account).first()
            if (outcome == SyncPassOutcome.SUCCESS && status?.state == SyncDashboardState.CURRENT &&
                status.pendingMutationCount == 0 && status.actionRequiredCount == 0 && status.androidPendingContactIds.isEmpty()) return
            ensure(outcome == SyncPassOutcome.SUCCESS || outcome == SyncPassOutcome.RETRY_WAITING)
        }
        ensure(false)
    }

    private fun newJournal(account: String, address: String): JSONObject = JSONObject()
        .put("schema", 1).put("account", account).put("address", address)
        .put("urlLabelPolicy", "NONE")
        .put("tag", UUID.randomUUID().toString().take(8))
        .put("prepared", false).put("inFlight", false)
        .put("contacts", JSONArray((0..1).map {
            JSONObject().put("id", UUID.randomUUID().toString()).put("index", it)
                .put("created", false).put("step", 0)
        }))
        .put("groups", JSONArray((0..2).map {
            JSONObject().put("id", UUID.randomUUID().toString()).put("index", it).put("created", false)
        }))

    private fun entries(journal: JSONObject, key: String): List<JSONObject> = journal.getJSONArray(key).let { array ->
        (0 until array.length()).map(array::getJSONObject)
    }

    private fun writeJournal(journal: JSONObject) {
        val output = journalFile.startWrite()
        try {
            output.write(journal.toString().toByteArray(Charsets.UTF_8))
            journalFile.finishWrite(output)
        } catch (failure: Throwable) {
            journalFile.failWrite(output)
            throw failure
        }
    }

    private fun archiveCleanedJournal(account: String, address: String) {
        if (!journalFile.baseFile.exists() && !File(journalFile.baseFile.path + ".bak").exists()) return
        stage = "ARCHIVE_CLEANED_JOURNAL"
        val prior = JSONObject(journalFile.readFully().toString(Charsets.UTF_8))
        ensure(prior.getInt("schema") == 1 && prior.getString("account") == account &&
            prior.getString("address") == address && prior.optBoolean("cleaned"))
        val archive = File(context.filesDir, "qa-rich-duplicate-journey-archive-${UUID.randomUUID()}.json")
        ensure(!archive.exists())
        ensure(journalFile.baseFile.renameTo(archive))
    }

    private fun fixture(journal: JSONObject, entry: JSONObject): CanonicalContact {
        val id = entry.getString("id")
        val index = entry.getInt("index")
        val tag = journal.getString("tag")
        val first = if (index == 0) "Élodie" else "Maël"
        val last = if (index == 0) "Répétée" else "Distinct"
        val email = "rich.repeat.$tag.$index@example.invalid"
        val values = mutableListOf<ContactValue>()
        fun add(kind: ContactValueKind, suffix: String, value: String, label: String? = null,
                order: Int = 0, primary: Boolean = false, components: Map<String, String> = emptyMap()) {
            values += ContactValue("$id-$suffix", kind, value, label, order, primary, components)
        }
        add(ContactValueKind.EMAIL, "email-0", email, "home", primary = true)
        add(ContactValueKind.EMAIL, "email-1", email, "work", order = 1)
        add(ContactValueKind.PHONE, "phone-0", "+1202555010${index * 3}", "home", primary = true)
        if (entry.getInt("step") < 1) {
            add(ContactValueKind.PHONE, "phone-1", "+1202555010${index * 3 + 1}", "work", order = 1)
        }
        add(ContactValueKind.PHONE, "phone-duplicate", "+1202555010${index * 3}", "other", order = 2)
        if (entry.getInt("step") >= 2) {
            add(ContactValueKind.PHONE, "phone-native", "+1202555010${index * 3 + 2}", "cell", order = 3)
        }
        (0..1).filter { it == 0 || entry.getInt("step") < 2 }.forEach { occurrence ->
            val components = mapOf("street" to "${17 + index} rue des Nuages fictifs",
                "locality" to "Ville Imaginaire", "region" to "QA", "postal_code" to "00000", "country" to "France")
            add(ContactValueKind.POSTAL_ADDRESS, "address-$occurrence", PostalAddressPolicy.formattedValue(components),
                if (occurrence == 0) "home" else "work", occurrence, occurrence == 0, components)
        }
        add(ContactValueKind.NOTE, "note", "Fictional QA contact $tag. Café, naïve, résumé.\nPreserve repeated email groups.", primary = true)
        // Nominal email-focused fixtures repeat URLs without labels. Labeled URL loss is a
        // separately observed failure; the legacy journal keeps its original strict oracle.
        val unlabeledUrls = journal.optString("urlLabelPolicy", "LEGACY_WORK_HOME") == "NONE"
        add(ContactValueKind.URL, "url", "https://example.invalid/qa/$tag/$index",
            if (unlabeledUrls) null else "work", primary = true)
        add(ContactValueKind.URL, "url-duplicate", "https://example.invalid/qa/$tag/$index",
            if (unlabeledUrls) null else "home", order = 1)
        val variant = if (index == 0) "Shared" else "Distinct"
        return CanonicalContact(journal.getString("account"), id, first, last,
            "CTK-QA Duplicate $variant $first $tag", values)
    }

    private fun expectedGroups(journal: JSONObject, entry: JSONObject, emailIndex: Int): Set<String> {
        val groups = entries(journal, "groups").map { it.getString("id") }
        // Same assignments on both occurrences, then different overlapping assignments.
        return if (entry.getInt("index") == 0 || emailIndex == 0) setOf(groups[0], groups[1])
        else setOf(groups[1], groups[2])
    }

    private suspend fun prepare(journal: JSONObject) {
        stage = "CREATE_GROUPS"
        entries(journal, "groups").forEach { entry ->
            val id = entry.getString("id")
            ensure(repository.observeGroups(journal.getString("account")).first().none { it.id == id })
            ensure(repository.saveGroup(ContactGroup(journal.getString("account"), id,
                "Rich QA ${journal.getString("tag")} ${entry.getInt("index") + 1}")) is SaveResult.Saved)
            entry.put("created", true)
            writeJournal(journal)
        }
        stage = "CREATE_CONTACTS"
        entries(journal, "contacts").forEach { entry ->
            val contact = fixture(journal, entry)
            ensure(repository.getContact(contact.accountId, contact.id) == null)
            val assignments = contact.valuesOf(ContactValueKind.EMAIL).flatMapIndexed { index, email ->
                expectedGroups(journal, entry, index).map { ContactGroupAssignment(it, email.id) }
            }.toSet()
            ensure(repository.saveContactWithGroupAssignments(contact, assignments,
                entries(journal, "groups").map { it.getString("id") }.toSet()) is SaveResult.Saved)
            entry.put("created", true)
            writeJournal(journal)
        }
        sync(journal.getString("account"))
        verify(journal)
        journal.put("prepared", true)
        writeJournal(journal)
        stage = "PREPARED_RETAINED"
    }

    private suspend fun resumePreparation(journal: JSONObject) {
        stage = "RESUME_EXISTING_SCOPE"
        ensure(!journal.getBoolean("prepared") && !journal.getBoolean("inFlight") && !journal.optBoolean("cleaned"))
        val contacts = entries(journal, "contacts")
        val groups = entries(journal, "groups")
        ensure(contacts.size == 2 && groups.size == 3)
        ensure(contacts.all { it.getBoolean("created") && it.getInt("step") == 0 } &&
            groups.all { it.getBoolean("created") })
        // No saves or creates: retain the failed fixture state until every oracle passes.
        sync(journal.getString("account"))
        verify(journal)
        journal.put("prepared", true)
        writeJournal(journal)
        stage = "PREPARED_RETAINED"
    }

    private suspend fun nativeEdit(journal: JSONObject) {
        ensure(journal.getBoolean("prepared") && !journal.getBoolean("inFlight"))
        ensure(entries(journal, "contacts").all { it.getInt("step") == 0 })
        verify(journal)
        val database = ContakoDatabase.create(context)
        try {
            for (step in 1..2) {
                stage = if (step == 1) "NATIVE_PHONE_DELETE" else "NATIVE_ADDRESS_DELETE_PHONE_ADD"
                journal.put("inFlight", true)
                writeJournal(journal)
                entries(journal, "contacts").forEach { entry ->
                    val contact = checkNotNull(repository.getContact(journal.getString("account"), entry.getString("id")))
                    val raw = ownedRaw(database, journal, contact)
                    val target = contact.valuesOf(if (step == 1) ContactValueKind.PHONE else ContactValueKind.POSTAL_ADDRESS)
                        .single { it.label?.lowercase(Locale.ROOT) == "work" }
                    val mime = if (step == 1) ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        else ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
                    val targetRows = rows(ContactsContract.Data.CONTENT_URI, arrayOf("_id"),
                        "raw_contact_id=? AND mimetype=? AND data_sync1=?", arrayOf(raw.toString(), mime, target.id))
                    ensure(targetRows.size == 1)
                    val operations = arrayListOf(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection("_id=? AND raw_contact_id=? AND mimetype=? AND data_sync1=?",
                            arrayOf(targetRows.single().getValue("_id").orEmpty(), raw.toString(), mime, target.id))
                        .withExpectedCount(1).build())
                    if (step == 2) {
                        entry.put("step", 2)
                        val added = fixture(journal, entry).valuesOf(ContactValueKind.PHONE).last()
                        operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, added.value)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build()
                    }
                    // The plain URI intentionally leaves native DIRTY/version handling to Android.
                    resolver.applyBatch(ContactsContract.AUTHORITY, operations)
                    entry.put("step", step)
                    writeJournal(journal)
                }
                journal.put("inFlight", false)
                writeJournal(journal)
                sync(journal.getString("account"))
                verify(journal)
            }
        } finally {
            database.close()
        }
        sync(journal.getString("account"))
        verify(journal)
        stage = "NATIVE_EDIT_RETAINED"
    }

    private suspend fun nativeReplay(journal: JSONObject) {
        stage = "REPLAY_EXISTING_SCOPE"
        ensure(journal.getBoolean("prepared") && !journal.getBoolean("inFlight") && !journal.optBoolean("cleaned"))
        val entries = entries(journal, "contacts")
        ensure(entries.size == 2 && entries.all { it.getBoolean("created") && it.getInt("step") == 2 })
        verify(journal)
        val account = journal.getString("account")
        val groups = repository.observeGroups(account).first()
        val managed = groups.map { it.id }.toSet()
        val originals = entries.associate { entry ->
            entry.getString("id") to checkNotNull(repository.getContact(account, entry.getString("id")))
        }
        fun assignments(contactId: String, source: List<ContactGroup>): Set<ContactGroupAssignment> =
            source.flatMap { group -> group.memberships.filter { it.contactId == contactId }
                .map { ContactGroupAssignment(group.id, it.emailValueId) } }.toSet()
        val retainedAssignments = originals.keys.associateWith { assignments(it, groups) }
        // Persist the restoration intent before either durable write. A failed/interrupted reset
        // retains inFlight and its desired step; it never fabricates an Android baseline.
        journal.put("inFlight", true).put("replayResetIntent", true)
        entries.forEach { it.put("step", 0) }
        writeJournal(journal)
        stage = "REPLAY_RESTORE_DURABLE_FIELDS"
        val changedFamilies = setOf(ContactValueKind.PHONE, ContactValueKind.POSTAL_ADDRESS)
        entries.forEach { entry ->
            val original = originals.getValue(entry.getString("id"))
            val initial = fixture(journal, entry)
            val restoredValues = initial.values.filter { it.kind in changedFamilies }.map { expected ->
                original.values.singleOrNull { current -> current.kind == expected.kind && current.value == expected.value &&
                    current.label?.lowercase(Locale.ROOT) == expected.label?.lowercase(Locale.ROOT) }
                    ?.copy(order = expected.order, isPrimary = expected.isPrimary)
                    ?: expected.copy(id = UUID.randomUUID().toString())
            }
            val restored = original.copy(values = original.values.filterNot { it.kind in changedFamilies } + restoredValues)
            val unchanged = retainedAssignments.getValue(original.id)
            val saved = repository.saveContactWithGroupAssignments(restored, unchanged, managed,
                ContactEditBaseline(original, unchanged))
            ensure(saved is SaveResult.Saved && saved.value.id == original.id)
        }
        sync(account)
        verify(journal)
        stage = "REPLAY_ASSIGNMENTS_UNCHANGED"
        val after = repository.observeGroups(account).first()
        originals.keys.forEach { ensure(assignments(it, after) == retainedAssignments.getValue(it)) }
        journal.put("inFlight", false).put("replayResetIntent", false)
        writeJournal(journal)
        nativeEdit(journal)
    }

    private suspend fun awaitIdle(account: String) = withTimeout(30_000) {
        while (app.syncRecoveryDataSource.observeActivity(account).first() != SyncActivity.IDLE) delay(200)
    }

    private suspend fun sync(account: String) {
        stage = "SERIALIZED_SYNC"
        val before = app.syncRecoveryDataSource.observeStatus(account).first()?.lastSuccessAtEpochMillis ?: 0
        app.syncRecoveryDataSource.requestSync(account)
        withTimeout(120_000) {
            while (true) {
                val status = app.syncRecoveryDataSource.observeStatus(account).first()
                if (app.syncRecoveryDataSource.observeActivity(account).first() == SyncActivity.IDLE &&
                    status?.state == SyncDashboardState.CURRENT && status.pendingMutationCount == 0 &&
                    status.actionRequiredCount == 0 && (status.lastSuccessAtEpochMillis ?: 0) > before) break
                delay(250)
            }
        }
    }

    private suspend fun assertUnrelatedClean(account: String, address: String,
                                             contacts: Set<String>, groups: Set<String>) {
        ensure(app.syncRecoveryDataSource.observeActivity(account).first() == SyncActivity.IDLE)
        ensure(app.syncRecoveryDataSource.observeRepair(account).first() == null)
        ensure(repository.observeContacts(account).first().filter { it.id !in contacts }.all {
            it.pendingMutationRevision == null && it.actionRequiredReasons.isEmpty() && it.conflictState == null
        })
        ensure(repository.observeGroups(account).first().filter { it.id !in groups }.all {
            it.pendingMutationRevision == null && it.conflictState == null
        })
        ensure(app.syncRecoveryDataSource.observeConflicts(account).first().all { it.contactId in contacts })
        // Include deleted native rows: a visible-contact query alone would miss pending tombstones.
        listOf(ContactsContract.RawContacts.CONTENT_URI to contacts, ContactsContract.Groups.CONTENT_URI to groups)
            .forEach { (uri, allowed) ->
                val dirty = rows(uri, arrayOf("sync1"), "account_name=? AND account_type=? AND dirty=1",
                    arrayOf(address, BuildConfig.APPLICATION_ID))
                ensure(dirty.all { it["sync1"] in allowed })
            }
        val database = ContakoDatabase.create(context)
        try {
            database.openHelper.readableDatabase.query(
                "SELECT aggregate_id FROM outbox_mutations WHERE account_id = ?", arrayOf(account),
            ).use { cursor ->
                while (cursor.moveToNext()) ensure(cursor.getString(0) in contacts || cursor.getString(0) in groups)
            }
            listOf("android_projection_ledger" to ("canonical_contact_id" to contacts),
                "android_group_projection_ledger" to ("canonical_group_id" to groups))
                .forEach { (table, owner) ->
                    database.openHelper.readableDatabase.query(
                        "SELECT ${owner.first} FROM $table WHERE account_id = ? AND projection_state IN ('WRITE_PENDING', 'REPAIR_REQUIRED')",
                        arrayOf(account),
                    ).use { cursor -> while (cursor.moveToNext()) ensure(cursor.getString(0) in owner.second) }
                }
        } finally { database.close() }
        if (contacts.isEmpty() && groups.isEmpty()) {
            val status = checkNotNull(app.syncRecoveryDataSource.observeStatus(account).first())
            ensure(status.state == SyncDashboardState.CURRENT && status.pendingMutationCount == 0 &&
                status.actionRequiredCount == 0 && status.androidPendingContactIds.isEmpty())
        }
    }

    private fun rows(uri: Uri, columns: Array<String>, selection: String,
                     arguments: Array<String>): List<Map<String, String?>> =
        checkNotNull(resolver.query(uri, columns, selection, arguments, "_id ASC")).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    ensure(size < 256)
                    add(columns.indices.associate { columns[it] to cursor.getString(it) })
                }
            }
        }

    private suspend fun ownedRaw(database: ContakoDatabase, journal: JSONObject, contact: CanonicalContact): Long {
        val enclosingStage = stage
        stage = "AND_OWN_LEDGER"
        val ledger = checkNotNull(database.androidProjectionLedgerDao().get(contact.accountId, contact.id))
        stage = "AND_OWN_LOCATOR"
        val raw = checkNotNull(ledger.rawContactLocator)
        stage = "AND_OWN_ROW_COUNT"
        val owners = rows(ContactsContract.RawContacts.CONTENT_URI,
            arrayOf("_id", "account_name", "account_type", "sync1", "sourceid", "deleted"),
            "_id=?", arrayOf(raw.toString()))
        ensure(owners.size == 1)
        val owner = owners.single()
        stage = "AND_OWN_ACCOUNT_NAME"
        ensure(owner["account_name"] == journal.getString("address"))
        stage = "AND_OWN_ACCOUNT_TYPE"
        ensure(owner["account_type"] == BuildConfig.APPLICATION_ID)
        stage = "AND_OWN_CANONICAL_ID"
        ensure(owner["sync1"] == contact.id)
        stage = "AND_OWN_SOURCE_ID"
        ensure(owner["sourceid"] == ledger.sourceIdentity)
        stage = "AND_OWN_SOURCE_PRESENT"
        ensure(!owner["sourceid"].isNullOrBlank())
        stage = "AND_OWN_NOT_DELETED"
        ensure(owner["deleted"] == "0")
        stage = enclosingStage
        return raw
    }

    private fun compareContact(expected: CanonicalContact, actual: CanonicalContact) {
        val peer = stage.removeSuffix("_FIELDS")
        fun field(suffix: String, matches: Boolean) {
            stage = "${peer}_$suffix"
            ensure(matches)
        }
        field("NAME_FIRST", expected.firstName == actual.firstName)
        field("NAME_LAST", expected.lastName == actual.lastName)
        field("DISPLAY", expected.displayName == actual.displayName)
        for (kind in FIELD_KINDS) {
            val left = expected.valuesOf(kind)
            val right = actual.valuesOf(kind)
            field("${kind.name}_COUNT", left.size == right.size)
            left.zip(right).forEachIndexed { index, (a, b) ->
                val family = "${kind.name}_${index}"
                field("${family}_VALUE", a.value == b.value)
                field("${family}_LABEL", a.label?.lowercase(Locale.ROOT) == b.label?.lowercase(Locale.ROOT))
                if (kind in PRIMARY_KINDS) field("${family}_PRIMARY", a.isPrimary == b.isPrimary)
                if (kind == ContactValueKind.POSTAL_ADDRESS) PostalAddressPolicy.componentKeys.forEach { component ->
                    field("${family}_${component.uppercase(Locale.ROOT)}", a.components[component].orEmpty() == b.components[component].orEmpty())
                }
            }
        }
    }

    private suspend fun verify(journal: JSONObject) {
        stage = "GROUPS"
        val account = journal.getString("account")
        val allGroups = repository.observeGroups(account).first()
        val groups = entries(journal, "groups").map { entry ->
            allGroups.single { it.id == entry.getString("id") }.also {
                ensure(it.name == "Rich QA ${journal.getString("tag")} ${entry.getInt("index") + 1}" && it.remoteLabelId != null)
            }
        }
        val remoteMembers = groups.associate { group ->
            group.id to runtime.gateD.membershipReader.members(runtime.accountScope,
                RemoteGroupId(checkNotNull(group.remoteLabelId))).value().emailIds.map { it.value }.toSet()
        }
        val expectedRemoteMembers = groups.associate { it.id to mutableSetOf<String>() }
        val database = ContakoDatabase.create(context)
        try {
            entries(journal, "contacts").forEach { entry ->
                ensure(entry.getBoolean("created"))
                val expected = fixture(journal, entry)
                val local = checkNotNull(repository.getContact(account, expected.id))
                stage = "PROTON_CARD_READ"
                val remote = runtime.gateD.verifiedCards.fetch(runtime.accountScope,
                    RemoteContactId(checkNotNull(local.remoteContactId))).value().contact
                stage = "ROOM_FIELDS"
                compareContact(expected, local)
                stage = "PROTON_FIELDS"
                compareContact(expected, remote)
                stage = "EMAIL_OCCURRENCE_GROUPS"
                val localEmails = local.valuesOf(ContactValueKind.EMAIL)
                val remoteEmails = remote.valuesOf(ContactValueKind.EMAIL)
                localEmails.zip(remoteEmails).forEachIndexed { index, (a, b) ->
                    val emailId = checkNotNull(b.metadata["protonEmailId"])
                    ensure(emailId.isNotBlank() && a.metadata["protonEmailId"] == emailId)
                    val wanted = expectedGroups(journal, entry, index)
                    ensure(groups.filter { group -> group.memberships.any {
                        it.contactId == local.id && it.emailValueId == a.id
                    } }.map { it.id }.toSet() == wanted)
                    val remoteLabelIds = wanted.map { groupId -> checkNotNull(groups.single { it.id == groupId }.remoteLabelId) }.toSet()
                    ensure(b.metadata["protonGroupIds"].orEmpty().split(',').filter(String::isNotEmpty).toSet() == remoteLabelIds)
                    groups.forEach { group ->
                        ensure((emailId in remoteMembers.getValue(group.id)) == (group.id in wanted))
                        if (group.id in wanted) expectedRemoteMembers.getValue(group.id).add(emailId)
                    }
                }
                stage = "ANDROID_FIELDS"
                verifyProvider(database, journal, local, expected, groups, entry)
            }
            groups.forEach { ensure(remoteMembers.getValue(it.id) == expectedRemoteMembers.getValue(it.id)) }
        } finally { database.close() }
    }

    private suspend fun uiLocator(journal: JSONObject) {
        stage = "PRIVATE_UI_LOCATORS"
        ensure(journal.getBoolean("prepared") && !journal.getBoolean("inFlight"))
        val report = JSONObject().put("schema", 1)
        val targets = JSONArray()
        val database = ContakoDatabase.create(context)
        try {
            entries(journal, "contacts").forEach { entry ->
                val local = checkNotNull(repository.getContact(journal.getString("account"), entry.getString("id")))
                val raw = ownedRaw(database, journal, local)
                val aggregate = rows(ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id", "contact_id"),
                    "_id=?", arrayOf(raw.toString())).single()["contact_id"]
                targets.put(JSONObject().put("displayName", local.displayName)
                    .put("step", entry.getInt("step"))
                    .put("email", local.valuesOf(ContactValueKind.EMAIL).first().value)
                    .put("ownedRawId", raw).put("aggregateId", aggregate)
                    .put("foreignDisplayName", "CTK-QA Native Linked ${journal.getString("tag")}")
                    .put("foreignRetainedPhone", "+1202555010${entry.getInt("index") * 3 + 1}"))
            }
        } finally { database.close() }
        report.put("contacts", targets)
        val bytes = report.toString().toByteArray(Charsets.UTF_8)
        // Export only these synthetic UI locators into app-scoped external files for private
        // operator retrieval. The fixture authority/cleanup journal remains app-private.
        listOf(context.filesDir, checkNotNull(context.getExternalFilesDir(null))).forEach { directory ->
            val file = AtomicFile(File(directory, "qa-rich-duplicate-ui.json"))
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: Throwable) { file.failWrite(output); throw failure }
        }
    }

    private suspend fun linkedVerify(journal: JSONObject, foreignType: String) {
        stage = "LINKED_FOREIGN_SOURCE"
        ensure(foreignType.isNotBlank() && foreignType != BuildConfig.APPLICATION_ID)
        val entry = entries(journal, "contacts").single { it.getInt("index") == 1 }
        ensure(entry.getInt("step") >= 1)
        val local = checkNotNull(repository.getContact(journal.getString("account"), entry.getString("id")))
        val database = ContakoDatabase.create(context)
        try {
            val owned = ownedRaw(database, journal, local)
            val aggregate = checkNotNull(rows(ContactsContract.RawContacts.CONTENT_URI,
                arrayOf("_id", "contact_id"), "_id=?", arrayOf(owned.toString())).single()["contact_id"])
            val sources = rows(ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id", "account_type", "deleted"),
                "contact_id=? AND deleted=0", arrayOf(aggregate))
            ensure(sources.size == 2)
            val foreign = sources.single { it["account_type"] == foreignType }
            ensure(sources.single { it["account_type"] == BuildConfig.APPLICATION_ID }["_id"] == owned.toString())
            val foreignData = rows(ContactsContract.Data.CONTENT_URI,
                arrayOf("_id", "mimetype", "data1"), "raw_contact_id=?", arrayOf(checkNotNull(foreign["_id"])))
            val name = foreignData.single { it["mimetype"] == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }["data1"]
            ensure(name == "CTK-QA Native Linked ${journal.getString("tag")}" && name != local.displayName)
            ensure(foreignData.filter { it["mimetype"] == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE }
                .any { it["data1"] == fixture(journal, entry).valuesOf(ContactValueKind.EMAIL).first().value })
            val retainedPhone = "+12025550104"
            ensure(foreignData.filter { it["mimetype"] == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }
                .any { it["data1"]?.replace(Regex("[^+0-9]"), "") == retainedPhone })
            ensure(local.valuesOf(ContactValueKind.PHONE).none { it.value == retainedPhone })
            // verify() above independently proved the full owned raw card, Room and Proton.
            // The retained foreign number may still be visible in the aggregate; it is never adopted.
        } finally { database.close() }
    }

    private suspend fun verifyProvider(database: ContakoDatabase, journal: JSONObject, local: CanonicalContact,
                                       expected: CanonicalContact, groups: List<ContactGroup>, entry: JSONObject) {
        fun field(suffix: String, matches: Boolean) {
            stage = "AND_$suffix"
            ensure(matches)
        }
        val raw = ownedRaw(database, journal, local)
        stage = "AND_DATA_READ"
        val data = rows(ContactsContract.Data.CONTENT_URI,
            arrayOf("_id", "mimetype", "data1", "data2", "data3", "data4", "data5", "data6", "data7", "data8", "data9", "data10", "is_primary", "data_sync1", "data_sync2"),
            "raw_contact_id=?", arrayOf(raw.toString()))
        val names = data.filter { it["mimetype"] == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }
        field("NAME_COUNT", names.size == 1)
        val name = names.single()
        field("NAME_DISPLAY", name["data1"] == expected.displayName)
        field("NAME_FIRST", name["data2"] == expected.firstName)
        field("NAME_LAST", name["data3"] == expected.lastName)
        val mapping = mapOf(ContactValueKind.EMAIL to ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactValueKind.PHONE to ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactValueKind.POSTAL_ADDRESS to ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactValueKind.NOTE to ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactValueKind.URL to ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
        stage = "AND_BINDINGS_READ"
        val ledger = checkNotNull(database.androidProjectionLedgerDao().get(local.accountId, local.id))
        val bindings = database.androidProviderIdentityDao().getAllForContact(local.accountId, local.id)
        // The production codec falls back to global routed-row order, before family mapping.
        // Group memberships and the documented opaque RCS family are not routed contact rows.
        val routedOrder = data.filter { it["mimetype"] != ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE &&
            it["mimetype"] != "vnd.android.cursor.item/rcs_data" }
            .sortedBy { checkNotNull(it["_id"]).toLong() }.mapIndexed { order, row ->
                checkNotNull(row["_id"]).toLong() to order
            }.toMap()
        mapping.forEach { (kind, mime) ->
            val observed = data.filter { it["mimetype"] == mime }
            val values = local.valuesOf(kind)
            field("${kind.name}_COUNT", observed.size == values.size)
            values.forEachIndexed { index, value ->
                val family = "${kind.name}_$index"
                val androidKind = if (kind == ContactValueKind.URL) "WEBSITE" else kind.name
                val attached = bindings.filter { binding -> binding.accountId == local.accountId &&
                    binding.canonicalContactId == local.id && binding.providerEpoch == ledger.providerEpoch &&
                    binding.rawContactLocator == raw && binding.androidAccountName == journal.getString("address") &&
                    binding.role == "PRIMARY" && binding.state == "ATTACHED" && binding.kind == androidKind &&
                    binding.canonicalValueId == value.id && binding.bindingPrimaryId == value.id }
                field("${family}_BINDING", attached.size == 1)
                val matchingRows = observed.filter { it["_id"]?.toLongOrNull() == attached.single().dataRowLocator }
                field("${family}_BOUND_ROW", matchingRows.size == 1)
                val row = matchingRows.single()
                // New native rows may omit claims; the durable attached binding is authority.
                if (row["data_sync1"] != null) field("${family}_ID_CLAIM", row["data_sync1"] == value.id)
                val orderClaim = row["data_sync2"]
                if (orderClaim != null) field("${family}_ORDER_CLAIM_VALID", orderClaim.toIntOrNull()?.let { it >= 0 } == true)
                val order = orderClaim?.toIntOrNull() ?: routedOrder.getValue(checkNotNull(row["_id"]).toLong())
                field("${family}_ORDER", order == value.order)
                field("${family}_VALUE", row["data1"].orEmpty() == value.value)
                if (kind in PRIMARY_KINDS) field("${family}_PRIMARY", (row["is_primary"] == "1") == value.isPrimary)
                val type = when (kind) {
                    ContactValueKind.EMAIL, ContactValueKind.POSTAL_ADDRESS -> if (value.label?.lowercase(Locale.ROOT) == "home") 1 else 2
                    ContactValueKind.PHONE -> when (value.label?.lowercase(Locale.ROOT)) { "home" -> 1; "work" -> 3; "cell", "mobile" -> 2; "other" -> 7; else -> -1 }
                    ContactValueKind.URL -> when (value.label?.lowercase(Locale.ROOT)) { null -> null; "home" -> 4; else -> 5 }
                    else -> null
                }
                if (type != null) field("${family}_LABEL_TYPE", row["data2"]?.toIntOrNull() == type)
                if (kind == ContactValueKind.URL && value.label == null) field("${family}_UNLABELED", row["data2"].isNullOrEmpty())
                if (kind == ContactValueKind.POSTAL_ADDRESS) {
                    mapOf("street" to "data4", "locality" to "data7", "region" to "data8", "postal_code" to "data9", "country" to "data10")
                        .forEach { (component, column) -> field("${family}_${component.uppercase(Locale.ROOT)}", row[column].orEmpty() == value.components[component].orEmpty()) }
                }
            }
        }
        stage = "AND_GROUP_MEMBERSHIP_READ"
        val membershipRows = data.filter { it["mimetype"] == ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE }
        val memberships = membershipRows.map { checkNotNull(it["data1"]).toLong() }.toSet()
        val wanted = expectedGroups(journal, entry, 0)
        val wantedRows = wanted.mapIndexed { index, groupId ->
            stage = "AND_GROUP_${index}_CANONICAL"
            val group = groups.single { it.id == groupId }
            stage = "AND_GROUP_${index}_ROW_COUNT"
            val observedGroups = rows(ContactsContract.Groups.CONTENT_URI,
                arrayOf("_id", "title", "sourceid", "sync1", "account_name", "account_type", "deleted"),
                "sync1=? AND account_name=? AND account_type=? AND deleted=0",
                arrayOf(group.id, journal.getString("address"), BuildConfig.APPLICATION_ID))
            field("GROUP_${index}_ROW_COUNT", observedGroups.size == 1)
            val row = observedGroups.single()
            field("GROUP_${index}_TITLE", row["title"] == group.name)
            field("GROUP_${index}_SOURCE_ID", row["sourceid"] == group.remoteLabelId)
            stage = "AND_GROUP_${index}_LOCATOR"
            checkNotNull(row["_id"]).toLong()
        }.toSet()
        field("GROUP_MEMBERSHIP_ROW_COUNT", membershipRows.size == wantedRows.size)
        field("GROUP_MEMBERSHIP_SET", memberships == wantedRows)
        // AndroidProviderMimeRouter explicitly preserves this Samsung-local opaque family.
        // All other unmodeled MIME rows still fail the exact managed-row count.
        val managedCount = data.count { it["mimetype"] != "vnd.android.cursor.item/rcs_data" }
        field("MANAGED_ROW_COUNT", managedCount == 1 + FIELD_KINDS.sumOf { local.valuesOf(it).size } + memberships.size)
    }

    private suspend fun cleanup(journal: JSONObject) {
        stage = "EXACT_JOURNAL_CLEANUP"
        val account = journal.getString("account")
        val contacts = entries(journal, "contacts").filter { it.getBoolean("created") }
        val contactIds = contacts.map { it.getString("id") }.toSet()
        val groupEntries = entries(journal, "groups").filter { it.getBoolean("created") }
        val groups = repository.observeGroups(account).first()
        val fixtureRemoteEmails = mutableSetOf<String>()
        stage = "CLEANUP_REMOTE_CONTACT_SCOPE"
        contacts.forEach { entry ->
            val local = repository.getContact(account, entry.getString("id"))
            if (local != null) {
                ensure(local.displayName == fixture(journal, entry).displayName)
                local.remoteContactId?.let { remoteId ->
                    ensure(!entry.has("remoteId") || entry.getString("remoteId") == remoteId)
                    entry.put("remoteId", remoteId)
                }
            }
            if (entry.has("remoteId")) {
                val remoteId = RemoteContactId(entry.getString("remoteId"))
                when (runtime.gateD.existence.check(runtime.accountScope, remoteId).value()) {
                    RemoteContactPresence.CONFIRMED_ABSENT -> Unit
                    RemoteContactPresence.PRESENT -> {
                        val remote = runtime.gateD.verifiedCards.fetch(runtime.accountScope, remoteId).value().contact
                        val expected = fixture(journal, entry)
                        ensure(remote.displayName == expected.displayName)
                        val actualEmails = remote.valuesOf(ContactValueKind.EMAIL)
                        ensure(actualEmails.map { it.value } == expected.valuesOf(ContactValueKind.EMAIL).map { it.value })
                        actualEmails.forEach { email ->
                            val emailId = checkNotNull(email.metadata["protonEmailId"])
                            ensure(emailId.isNotBlank())
                            fixtureRemoteEmails += emailId
                        }
                    }
                }
            }
        }
        stage = "CLEANUP_REMOTE_GROUP_SCOPE"
        val freshRemoteGroups = runtime.gateD.groups.list(runtime.accountScope).value().groups
        // Room is insufficient: another peer can add foreign members after preparation.
        // Guard every intended remote group using fresh full-card identities and member reads.
        groupEntries.forEach { entry ->
            val group = groups.singleOrNull { it.id == entry.getString("id") }
            if (group != null) {
                ensure(group.name == "Rich QA ${journal.getString("tag")} ${entry.getInt("index") + 1}" &&
                    group.memberships.all { it.contactId in contactIds })
                group.remoteLabelId?.let { remoteId ->
                    ensure(!entry.has("remoteId") || entry.getString("remoteId") == remoteId)
                    entry.put("remoteId", remoteId)
                }
            }
            if (entry.has("remoteId")) {
                val remoteGroup = freshRemoteGroups.singleOrNull { it.id.value == entry.getString("remoteId") }
                if (remoteGroup != null) {
                    ensure(remoteGroup.name == "Rich QA ${journal.getString("tag")} ${entry.getInt("index") + 1}")
                    val members = runtime.gateD.membershipReader.members(runtime.accountScope, remoteGroup.id).value().emailIds
                    ensure(members.all { it.value in fixtureRemoteEmails })
                }
            } else {
                // A name-only match after an uncertain create is not deletion authority.
                ensure(freshRemoteGroups.none {
                    it.name == "Rich QA ${journal.getString("tag")} ${entry.getInt("index") + 1}"
                })
            }
        }
        writeJournal(journal)
        stage = "EXACT_JOURNAL_CLEANUP"
        contacts.forEach { entry ->
            val local = repository.getContact(account, entry.getString("id"))
            if (local != null && !local.isDeleted) {
                ensure(local.displayName == fixture(journal, entry).displayName)
                local.remoteContactId?.let { entry.put("remoteId", it) }
                writeJournal(journal)
                repository.deleteContact(account, entry.getString("id"))
            }
        }
        groupEntries.forEach { repository.deleteGroup(account, it.getString("id")) }
        cleanupSync(journal)
        ensure(repository.observeContacts(account).first().none { it.id in contactIds })
        ensure(repository.observeGroups(account).first().none { group -> groupEntries.any { it.getString("id") == group.id } })
        contacts.forEach {
            ensure(rows(ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id"),
                "sync1=? AND account_name=? AND account_type=?", arrayOf(it.getString("id"), journal.getString("address"), BuildConfig.APPLICATION_ID)).isEmpty())
        }
        groupEntries.forEach {
            ensure(rows(ContactsContract.Groups.CONTENT_URI, arrayOf("_id"),
                "sync1=? AND account_name=? AND account_type=?", arrayOf(it.getString("id"), journal.getString("address"), BuildConfig.APPLICATION_ID)).isEmpty())
        }
        stage = "REMOTE_CLEANUP_PROOF"
        contacts.forEach { entry ->
            if (entry.has("remoteId")) ensure(runtime.gateD.existence.check(runtime.accountScope,
                RemoteContactId(entry.getString("remoteId"))).value() == RemoteContactPresence.CONFIRMED_ABSENT)
        }
        val remoteGroups = runtime.gateD.groups.list(runtime.accountScope).value().groups.map { it.id.value }.toSet()
        groupEntries.forEach { if (it.has("remoteId")) ensure(it.getString("remoteId") !in remoteGroups) }
        // Keep the exact private ledger after cleanup; it is recovery evidence, not reusable scope.
        journal.put("cleaned", true)
        writeJournal(journal)
        stage = "CLEANED"
    }

    private fun <T> GatewayOutcome<T>.value(): T = when (this) {
        is GatewayOutcome.Success -> value
        is GatewayOutcome.Failure -> error("RICH_GATEWAY_${category.name}")
    }

    private companion object {
        val FIELD_KINDS = listOf(ContactValueKind.EMAIL, ContactValueKind.PHONE,
            ContactValueKind.POSTAL_ADDRESS, ContactValueKind.NOTE, ContactValueKind.URL)
        val PRIMARY_KINDS = setOf(ContactValueKind.EMAIL, ContactValueKind.PHONE, ContactValueKind.POSTAL_ADDRESS)
    }
}
