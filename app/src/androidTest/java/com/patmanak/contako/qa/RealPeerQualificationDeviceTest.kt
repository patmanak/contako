package com.patmanak.contako.qa

import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-peer fixture readback. Never creates accounts, resets sessions or repairs sync.
 *
 * Requires runRealPeerQualification=true and qaFixtureEmail=ctk.qa.<token>@example.test.
 * Phases: capture, verify, remote_note, remote_delete, verify_absent.
 * Capture records an already synchronized, UI-created CTK-QA contact with names, email,
 * phone, note and URL fields. Rich/media fixtures require their dedicated journey. Verify accepts only
 * qaChangedField=NONE|LAST_NAME|DISPLAY_NAME|PHONE|NOTE and an exact qaExpectedValue;
 * every other field must match the previous successful checkpoint in all applicable peers.
 * Remote phases use the real maintained gateway without writing Room or starting sync.
 * They qualify an external API peer, not the Proton Web UI. Private fixture locators and
 * baselines remain in app-private storage; instrumentation emits closed stage codes only.
 */
@RunWith(AndroidJUnit4::class)
class RealPeerQualificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ContakoApplication
    private var stage = "ADMISSION"
    private val audit = RealPeerAudit()

    @Test fun qualifyOwnedFixture() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runRealPeerQualification") == "true")
        val phase = args.getString("phase")
        check(phase in setOf("capture", "verify", "remote_note", "remote_delete", "verify_absent"))
        val email = args.getString("qaFixtureEmail").orEmpty()
        check(email.matches(Regex("ctk\\.qa\\.[a-zA-Z0-9.-]{1,64}@example\\.test")))
        val key = MessageDigest.getInstance("SHA-256").digest(email.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = AtomicFile(File(context.filesDir, "qa-real-peer-$key.json"))
        val runtime = ProtonGateCRuntime.createForInstrumentedGateC(context, audit,
            object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) = Unit
                override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
            }, app.humanVerification.hooks)
        val report = Bundle()
        try {
            runBlocking { withTimeout(90_000) {
                stage = "SESSION"
                ensure(runtime.session.restore(runtime.accountScope).checked() == SessionState.READY)
                val address = checkNotNull(runtime.currentAccountAddress())
                val repository = app.contactRepository
                val journal = if (phase == "capture") {
                    ensure(!file.baseFile.exists())
                    stage = "EXACT_FIXTURE_LOOKUP"
                    val local = repository.observeContacts(runtime.accountScope.value).first().singleOrNull {
                        it.displayName.startsWith("CTK-QA-") &&
                            it.valuesOf(ContactValueKind.EMAIL).any { value -> value.value == email }
                    } ?: fail()
                    ensure(local.remoteContactId != null && !local.isDeleted)
                    JSONObject().put("schema", 1).put("address", address)
                        .put("account", runtime.accountScope.value).put("email", email)
                        .put("id", local.id).put("remoteId", local.remoteContactId)
                        .put("expected", semantic(local))
                } else {
                    stage = "PRIVATE_JOURNAL"
                    ensure(file.baseFile.length() in 1..262144)
                    JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                }
                ensure(journal.getString("address") == address && journal.getString("email") == email &&
                    journal.getString("account") == runtime.accountScope.value)
                val id = RemoteContactId(journal.getString("remoteId"))
                if (phase == "verify_absent") {
                    stage = "REMOTE_ABSENCE"
                    ensure(journal.optBoolean("deleteAttempted"))
                    ensure(runtime.gateD.existence.check(runtime.accountScope, id).checked() == RemoteContactPresence.CONFIRMED_ABSENT)
                    stage = "ROOM_ABSENCE"
                    val local = repository.getContact(runtime.accountScope.value, journal.getString("id"))
                    ensure(local == null || local.isDeleted)
                    stage = "ANDROID_ABSENCE"
                    ensure(rows(ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id"),
                        "account_name=? AND account_type=? AND sync1=? AND deleted=0",
                        arrayOf(address, BuildConfig.APPLICATION_ID, journal.getString("id"))).isEmpty())
                    journal.put("absentVerified", true)
                    persist(file, journal)
                    stage = "COMPLETE"
                    return@withTimeout
                }
                stage = "REMOTE_HYDRATION"
                val remote = runtime.gateD.verifiedCards.fetch(runtime.accountScope, id).checked().contact
                ensure(remote.remoteContactId == id.value && remote.displayName.startsWith("CTK-QA-") &&
                    remote.valuesOf(ContactValueKind.EMAIL).any { it.value == email })
                if (phase == "remote_delete") {
                    stage = "EXACT_FIXTURE_DELETE"
                    ensure(!journal.optBoolean("deleteAttempted"))
                    compare(journal.getJSONObject("expected"), semantic(remote), "DELETE_PRECONDITION")
                    // Persist before the write: an uncertain acknowledgement must never be replayed.
                    journal.put("deleteAttempted", true)
                    persist(file, journal)
                    audit.allow(id.value, GateCDataMutationClass.CONTACT_DELETE)
                    try {
                        runtime.gateD.contactMutations.apply(runtime.accountScope,
                            ContactMutation.Delete(id, expectedVersion = null)).checked()
                    } catch (error: Throwable) {
                        if (audit.rejectedBeforeWrite) {
                            // Only an interceptor rejection before chain.proceed proves that
                            // no write was sent. Network/lost-ack failures retain the latch.
                            journal.put("deleteAttempted", false)
                            persist(file, journal)
                        }
                        throw error
                    }
                    stage = "REMOTE_ABSENCE"
                    ensure(runtime.gateD.existence.check(runtime.accountScope, id).checked() == RemoteContactPresence.CONFIRMED_ABSENT)
                } else if (phase == "remote_note") {
                    stage = "REMOTE_NOTE_PRECONDITION"
                    ensure(!journal.optBoolean("noteAttempted"))
                    compare(journal.getJSONObject("expected"), semantic(remote), "NOTE_PRECONDITION")
                    val notes = remote.valuesOf(ContactValueKind.NOTE)
                    ensure(notes.size <= 1)
                    val note = notes.singleOrNull()?.copy(value = REMOTE_NOTE) ?:
                        ContactValue("qa-real-note", ContactValueKind.NOTE, REMOTE_NOTE,
                            // decode() selects the first occurrence as the canonical primary
                            // for every family, even without an explicit vCard PREF parameter.
                            // Match that contract in the synthetic write oracle.
                            order = (remote.values.maxOfOrNull { it.order } ?: 0) + 1,
                            isPrimary = true)
                    val desired = remote.copy(values = remote.values.filterNot { it.kind == ContactValueKind.NOTE } + note)
                    journal.put("noteAttempted", true).put("expectedRemoteNote", REMOTE_NOTE)
                    persist(file, journal)
                    stage = "REMOTE_NOTE_WRITE"
                    audit.allow(id.value, GateCDataMutationClass.CONTACT_UPDATE)
                    runtime.gateD.contactMutations.apply(runtime.accountScope,
                        ContactMutation.Update(id, expectedVersion = null, contact = desired)).checked()
                    stage = "REMOTE_NOTE_READBACK"
                    val readback = runtime.gateD.verifiedCards.fetch(runtime.accountScope, id).checked().contact
                    compare(semantic(desired), semantic(readback), "NOTE_READBACK")
                } else {
                    stage = "ROOM_READBACK"
                    val local = repository.getContact(runtime.accountScope.value, journal.getString("id")) ?: fail()
                    ensure(local.remoteContactId == id.value && !local.isDeleted)
                    val changed = args.getString("qaChangedField") ?: "NONE"
                    ensure(changed in setOf("NONE", "LAST_NAME", "DISPLAY_NAME", "PHONE", "NOTE"))
                    val expected = JSONObject(journal.getJSONObject("expected").toString())
                    if (phase == "verify" && changed != "NONE") {
                        val value = args.getString("qaExpectedValue") ?: fail()
                        ensure(value.length <= 4096)
                        stage = "INTENDED_FIELD"
                        when (changed) {
                            "LAST_NAME" -> { ensure(local.lastName == value); expected.put("last", value) }
                            "DISPLAY_NAME" -> { ensure(local.displayName == value); expected.put("display", value) }
                            else -> {
                                val kind = ContactValueKind.valueOf(changed)
                                ensure(local.valuesOf(kind).map { it.value } == listOf(value))
                                expected.put(changed, semantic(local).getJSONArray(changed))
                            }
                        }
                    }
                    stage = "ROOM_UNTOUCHED_FIELDS"
                    compare(expected, semantic(local), "ROOM")
                    stage = "REMOTE_ALL_FIELDS"
                    compare(expected, semantic(remote), "REMOTE")
                    stage = "ANDROID_FIELDS"
                    verifyAndroid(local, address, journal)
                    stage = "GROUP_ASSIGNMENTS"
                    val groups = repository.observeGroups(runtime.accountScope.value).first().filterNot { it.isDeleted }
                    ensure(groups.size <= 32)
                    val localEmails = local.valuesOf(ContactValueKind.EMAIL)
                    val remoteEmails = remote.valuesOf(ContactValueKind.EMAIL)
                    ensure(localEmails.size == remoteEmails.size)
                    val assignments = JSONArray()
                    localEmails.forEach { emailValue ->
                        assignments.put(JSONArray(groups.filter { group -> group.memberships.any {
                            it.contactId == local.id && it.emailValueId == emailValue.id
                        } }.map { checkNotNull(it.remoteLabelId) }.sorted()))
                    }
                    if (journal.has("expectedGroups")) {
                        stage = "UNCHANGED_GROUP_ASSIGNMENTS"
                        ensure(assignments.toString() == journal.getJSONArray("expectedGroups").toString())
                    }
                    groups.forEach { group ->
                        if (group.remoteLabelId != null) {
                            val members = runtime.gateD.membershipReader.members(runtime.accountScope,
                                RemoteGroupId(checkNotNull(group.remoteLabelId))).checked().emailIds.map { it.value }.toSet()
                            localEmails.zip(remoteEmails).forEach { (left, right) ->
                                val remoteEmailId = checkNotNull(right.metadata["protonEmailId"])
                                ensure(left.metadata["protonEmailId"] == remoteEmailId)
                                ensure((remoteEmailId in members) == group.memberships.any {
                                    it.contactId == local.id && it.emailValueId == left.id })
                            }
                        }
                    }
                    stage = "ANDROID_GROUP_ASSIGNMENTS"
                    verifyAndroidGroups(local, address, groups.filter { group -> group.memberships.any { it.contactId == local.id } })
                    journal.put("expected", expected)
                    journal.put("expectedGroups", assignments)
                    persist(file, journal)
                }
                stage = "COMPLETE"
            } }
            report.putString("qa_real_peer_result", "PASS")
        } catch (_: Throwable) {
            // Never expose exception messages or payload-bearing assertion representations.
            report.putString("qa_real_peer_result", "FAIL")
            throw AssertionError("REAL_PEER_$stage")
        } finally {
            report.putString("qa_real_peer_stage", stage)
            report.putInt("qa_real_peer_requests", audit.requests)
            report.putInt("qa_real_peer_writes", audit.writes)
            instrumentation.sendStatus(0, report)
        }
    }

    private fun semantic(contact: CanonicalContact): JSONObject = JSONObject()
        .put("first", contact.firstName).put("last", contact.lastName).put("display", contact.displayName)
        .also { result -> ContactValueKind.entries.filterNot {
            it in setOf(ContactValueKind.STRUCTURED_NAME, ContactValueKind.CATEGORY)
        }.forEach { kind ->
            val values = contact.valuesOf(kind)
            result.put(kind.name, JSONArray().also { array -> values.forEach { value ->
                array.put(JSONObject().put("value", value.value)
                    .put("label", value.label?.lowercase(Locale.ROOT).orEmpty())
                    .put("primary", value.isPrimary || (kind == ContactValueKind.EMAIL && values.size == 1))
                    .put("components", JSONObject().also { components ->
                        value.components.toSortedMap().forEach { (key, item) -> components.put(key, item) }
                    }))
            } })
        } }

    private fun compare(expected: JSONObject, actual: JSONObject, peer: String) {
        ensure(expected.keys().asSequence().toSet() == actual.keys().asSequence().toSet())
        expected.keys().forEach { key ->
            // Keys are closed field families produced by semantic(), never contact data.
            stage = "${peer}_${key.uppercase(Locale.ROOT)}"
            ensure(expected.get(key).toString() == actual.get(key).toString())
        }
    }

    private suspend fun verifyAndroidGroups(local: CanonicalContact, address: String,
        groups: List<com.patmanak.contako.domain.model.ContactGroup>) {
        val database = ContakoDatabase.create(context)
        try {
            val raw = database.androidProjectionLedgerDao().get(local.accountId, local.id)?.rawContactLocator ?: fail()
            val actual = rows(ContactsContract.Data.CONTENT_URI, arrayOf("data1"),
                "raw_contact_id=? AND mimetype=?", arrayOf(raw.toString(),
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE))
                .map { checkNotNull(it["data1"]) }.toSet()
            val expected = groups.map { group ->
                val owned = rows(ContactsContract.Groups.CONTENT_URI, arrayOf("_id", "title", "sourceid"),
                    "sync1=? AND account_name=? AND account_type=? AND deleted=0",
                    arrayOf(group.id, address, BuildConfig.APPLICATION_ID))
                ensure(owned.size == 1)
                ensure(owned.single()["title"] == group.name && owned.single()["sourceid"] == group.remoteLabelId)
                checkNotNull(owned.single()["_id"])
            }.toSet()
            ensure(actual == expected)
        } finally { database.close() }
    }

    private suspend fun verifyAndroid(local: CanonicalContact, address: String, journal: JSONObject) {
        stage = "ANDROID_FIXTURE_SCOPE"
        ensure(local.values.all { it.kind in setOf(ContactValueKind.STRUCTURED_NAME,
            ContactValueKind.CATEGORY, ContactValueKind.EMAIL, ContactValueKind.PHONE,
            ContactValueKind.NOTE, ContactValueKind.URL) })
        val database = ContakoDatabase.create(context)
        try {
            val ledger = database.androidProjectionLedgerDao().get(local.accountId, local.id) ?: fail()
            val raw = ledger.rawContactLocator ?: fail()
            val owners = rows(ContactsContract.RawContacts.CONTENT_URI,
                arrayOf("account_name", "account_type", "sync1", "sourceid", "deleted"),
                "_id=?", arrayOf(raw.toString()))
            ensure(owners.size == 1)
            val owner = owners.single()
            ensure(owner["account_name"] == address && owner["account_type"] == BuildConfig.APPLICATION_ID &&
                owner["sync1"] == local.id && owner["sourceid"] == ledger.sourceIdentity && owner["deleted"] == "0")
            val data = rows(ContactsContract.Data.CONTENT_URI,
                arrayOf("mimetype", "data1", "data2", "data3"), "raw_contact_id=?", arrayOf(raw.toString()))
            val names = data.filter { it["mimetype"] == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }
            ensure(names.size == 1)
            ensure(names.single()["data1"].orEmpty() == local.displayName &&
                names.single()["data2"].orEmpty() == local.firstName && names.single()["data3"].orEmpty() == local.lastName)
            mapOf(ContactValueKind.EMAIL to ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactValueKind.PHONE to ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactValueKind.NOTE to ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                ContactValueKind.URL to ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE).forEach { (kind, mime) ->
                stage = "ANDROID_${kind.name}"
                ensure(data.filter { it["mimetype"] == mime }.map { it["data1"].orEmpty() }.sorted() ==
                    local.valuesOf(kind).map { it.value }.sorted())
            }
            // Exact owned raw locator, never an aggregate lookup or a linked foreign source.
            journal.put("raw", raw)
        } finally { database.close() }
    }

    private fun rows(uri: Uri, columns: Array<String>, selection: String, args: Array<String>): List<Map<String, String?>> =
        checkNotNull(context.contentResolver.query(uri, columns, selection, args, null)).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                ensure(size < 128)
                add(columns.indices.associate { columns[it] to cursor.getString(it) })
            }
        } }

    private fun persist(file: AtomicFile, journal: JSONObject) {
        val output = file.startWrite()
        try { output.write(journal.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
    private fun ensure(condition: Boolean) { if (!condition) fail() }
    private fun fail(): Nothing = throw IllegalStateException("REAL_PEER_GUARD")
    private fun <T> GatewayOutcome<T>.checked(): T = when (this) {
        is GatewayOutcome.Success -> value
        is GatewayOutcome.Failure -> { stage = "GATEWAY_${category.name}"; fail() }
    }
    private companion object { const val REMOTE_NOTE = "Real peer qualification — private note ß ë" }
}

private class RealPeerAudit : GateCRequestAudit {
    var requests = 0
        private set
    var writes = 0
        private set
    private var allowedId: String? = null
    private var allowedMutation: GateCDataMutationClass? = null
    private var targetWrites = 0
    private var stopped = false
    var rejectedBeforeWrite = false
        private set
    @Synchronized fun allow(id: String, mutation: GateCDataMutationClass) {
        check(allowedId == null)
        allowedId = id
        allowedMutation = mutation
    }
    @Synchronized override fun onRequest(requestClass: GateCRequestClass) {
        if (stopped || requests++ >= 100 || requestClass == GateCRequestClass.SESSION_REVOKE) reject()
    }
    @Synchronized override fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) {
        // Proton Core 36.6.2 uses PUT contacts/v4/contacts/delete with a one-ID
        // body, which the generic network classifier categorizes as UPDATE.
        val expected = if (allowedMutation == GateCDataMutationClass.CONTACT_DELETE)
            GateCDataMutationClass.CONTACT_UPDATE else allowedMutation
        if (stopped || allowedId == null || mutationClass != expected || writes++ != 0) reject()
    }
    @Synchronized override fun onRequestTarget(host: String, method: String, pathSegments: List<String>) {
        if (stopped || host != "api.protonmail.ch") reject()
        val read = method == "GET" && pathSegments.firstOrNull() in setOf("contacts", "core", "keys")
        val refresh = method == "POST" && pathSegments == listOf("auth", "v4", "refresh")
        val mutation = method == "PUT" && allowedId != null && pathSegments ==
            listOf("contacts", "v4", "contacts", if (allowedMutation == GateCDataMutationClass.CONTACT_DELETE) "delete" else allowedId)
        if (mutation) { if (targetWrites++ != 0) reject() }
        else if (!read && !refresh) reject()
    }
    @Synchronized override fun onRequestComplete(method: String, statusCode: Int?) { if (statusCode == 429) stopped = true }
    private fun reject(): Nothing {
        if (targetWrites == 0 && writes == 0) rejectedBeforeWrite = true
        stopped = true
        throw IOException("REAL_PEER_REQUEST_GUARD")
    }
}
