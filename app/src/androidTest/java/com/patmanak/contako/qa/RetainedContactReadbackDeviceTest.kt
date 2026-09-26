package com.patmanak.contako.qa

import android.os.Bundle
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.proton.GateCAuthDiagnostic
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.proton.GateCDataMutationClass
import com.patmanak.contako.data.proton.GateCRequestAudit
import com.patmanak.contako.data.proton.GateCRequestClass
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.domain.model.ContactValueKind
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** D-125: one real remote rich-card read plus two group reads; no contact/account writes. */
@RunWith(AndroidJUnit4::class)
class RetainedContactReadbackDeviceTest {
    @Test fun diagnoseRetainedRichContact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("retainedContactReadback")
        assumeTrue(mode in setOf("RF04", "RF_CLEANUP", "RF_LOCAL"))
        val cleanup = mode == "RF_CLEANUP"
        check(BuildConfig.APPLICATION_ID == "com.patmanak.contako.candidate")
        val application = instrumentation.targetContext.applicationContext as ContakoApplication
        val audit = RetainedReadOnlyAudit()
        val runtime = ProtonGateCRuntime.createForInstrumentedGateC(
            instrumentation.targetContext, audit,
            object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) = Unit
                override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
            }, application.humanVerification.hooks,
        )
        val result = Bundle()
        var stage = "RESTORE"
        try {
            runBlocking {
                check(runtime.session.restore(runtime.accountScope).readbackValue() == SessionState.READY) { "RF_SESSION_NOT_READY" }
                stage = "LOCAL"
                val repository = application.contactRepository
                val listed = repository.observeContacts(runtime.accountScope.value).first()
                    .single { it.displayName == "ctk-pn-base-001" }
                val local = requireNotNull(repository.getContact(runtime.accountScope.value, listed.id))
                if (mode == "RF_LOCAL") {
                    val database = ContakoDatabase.create(instrumentation.targetContext)
                    try {
                        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(local.accountId, local.id))
                        val baseline = requireNotNull(database.androidProjectionLedgerDao().getBaseline(local.accountId, local.id))
                        val mapper = CanonicalAndroidContactMapper()
                        val desired = mapper.project(local)
                        val prior = AndroidContactSnapshotBinaryCodec.decode(baseline.encodedSnapshot)
                        result.putString("rf_phone_desired_types", desired.rows.filter { it.kind == AndroidRowKind.PHONE }.joinToString(",") { it.semanticType.name })
                        result.putString("rf_phone_baseline_types", prior.rows.filter { it.kind == AndroidRowKind.PHONE }.joinToString(",") { it.semanticType.name })
                        result.putBoolean("rf_current_canonical_fingerprint_matches", mapper.fingerprint(desired).sha256Hex == ledger.canonicalProjectionFingerprint)
                        result.putBoolean("rf_provider_baseline_fingerprint_matches", mapper.fingerprint(prior).sha256Hex == ledger.androidBaselineFingerprint)
                        result.putString("rf_projection_state", ledger.projectionState)
                        val bindings = database.androidProviderIdentityDao().getAllForContact(local.accountId, local.id)
                        val identityMismatches = mutableListOf<String>()
                        instrumentation.targetContext.contentResolver.query(
                            ContactsContract.Data.CONTENT_URI,
                            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE, "data_sync1"),
                            "${ContactsContract.Data.RAW_CONTACT_ID}=?",
                            arrayOf(requireNotNull(ledger.rawContactLocator).toString()), null,
                        )?.use { cursor ->
                            var count = 0
                            while (cursor.moveToNext()) {
                                check(++count <= 100) { "RF_DATA_ROW_BOUND" }
                                val rowId = cursor.getLong(0)
                                val claim = cursor.getString(2)
                                val attached = bindings.filter { it.dataRowLocator == rowId && it.state == "ATTACHED" && it.role == "PRIMARY" }
                                if (attached.size > 1) identityMismatches += "MULTIPLE_ATTACHED"
                                if (attached.size == 1 && claim != null && claim != attached.single().canonicalValueId) {
                                    identityMismatches += "${attached.single().kind}_CLAIM_MISMATCH"
                                }
                                if (attached.isEmpty() && !claim.isNullOrBlank() && cursor.getString(1) != ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE) {
                                    val claimed = bindings.filter { it.canonicalValueId == claim && it.role == "PRIMARY" }
                                    identityMismatches += claimed.singleOrNull()?.let { "${it.kind}_CLAIM_${it.state}" } ?: "UNBOUND_CLAIM"
                                }
                            }
                        }
                        result.putString("rf_provider_identity_differences", identityMismatches.joinToString(",").ifEmpty { "NONE" })
                    } finally { database.close() }
                    stage = "LOCAL_COMPLETE"
                    return@runBlocking
                }
                stage = "REMOTE_CARD"
                val remote = runtime.gateD.verifiedCards.fetch(runtime.accountScope, RemoteContactId(requireNotNull(local.remoteContactId))).readbackValue().contact
                val localEmails = local.valuesOf(ContactValueKind.EMAIL)
                val remoteEmails = remote.valuesOf(ContactValueKind.EMAIL)
                result.putInt("rf_remote_email_count", remoteEmails.size)
                localEmails.forEachIndexed { index, value ->
                    val matching = remoteEmails.singleOrNull { it.value == value.value }
                    result.putBoolean("rf_email_${index}_identity_matches", matching != null &&
                        matching.metadata["protonEmailId"] == value.metadata["protonEmailId"])
                }
                ContactValueKind.entries.filterNot { it == ContactValueKind.CATEGORY }.forEach { kind ->
                    val left = local.valuesOf(kind)
                    val right = remote.valuesOf(kind)
                    result.putBoolean("rf_${kind.name.lowercase()}_values_match", left.map { it.value } == right.map { it.value })
                    if (cleanup) result.putBoolean("rf_${kind.name.lowercase()}_decoration_matches",
                        left.map { listOf(it.label, it.order, it.isPrimary, it.components) } ==
                            right.map { listOf(it.label, it.order, it.isPrimary, it.components) })
                }
                val groups = repository.observeGroups(runtime.accountScope.value).first()
                if (cleanup) {
                    // A real system-editor journey converted the formerly preferred PNG to JPEG.
                    // Retained cleanup permits that encoding only; all dimensions/pixels and
                    // every field expectation remain exact. Initial-import PNG checks stay strict.
                    val failures = ProtonNominalCandidateStateProbeDeviceTest().richOracleFailures(
                        listOf(local), groups, allowProviderImageEncoding = true,
                    )
                    result.putString("rf_cleanup_rich_baseline", failures.joinToString(",").ifEmpty { "PASS" })
                    check(failures.isEmpty()) { "RF_CLEANUP_RICH_BASELINE" }
                    val database = ContakoDatabase.create(instrumentation.targetContext)
                    try {
                        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(local.accountId, local.id))
                        val observedPhones = instrumentation.targetContext.contentResolver.query(
                            ContactsContract.Data.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
                            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                            arrayOf(requireNotNull(ledger.rawContactLocator).toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                            null,
                        )?.use { cursor -> buildList {
                            while (cursor.moveToNext()) {
                                check(size < 3) { "RF_PHONE_ROW_BOUND" }
                                add(cursor.getString(0).replace(Regex("[^+0-9]"), "") to cursor.getInt(1))
                            }
                        } }
                        val expectedPhones = local.valuesOf(ContactValueKind.PHONE).mapIndexed { index, phone ->
                            phone.value to if (index == 0) ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                            else ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                        }
                        result.putBoolean("rf_android_phone_types_match", observedPhones?.toSet() == expectedPhones.toSet())
                    } finally { database.close() }
                }
                val checkedGroups = if (cleanup) (1..6).map { "ctk-pn-label-%02d".format(it) }
                    else listOf("ctk-pn-label-03", "ctk-pn-label-04")
                checkedGroups.forEachIndexed { index, name ->
                    stage = "GROUP_$index"
                    val group = groups.single { it.name == name }
                    when (val members = runtime.gateD.membershipReader.members(runtime.accountScope, RemoteGroupId(requireNotNull(group.remoteLabelId)))) {
                        is GatewayOutcome.Failure -> result.putString("rf_group_${index}_read", members.category.name)
                        is GatewayOutcome.Success -> {
                            result.putString("rf_group_${index}_read", "SUCCESS")
                            result.putInt("rf_group_${index}_count", members.value.emailIds.size)
                            val actual = remoteEmails.map { email -> members.value.emailIds.any {
                                it.value == email.metadata["protonEmailId"]
                            } }
                            result.putBoolean("rf_group_${index}_has_expected_email", if (cleanup)
                                actual == listOf(index < 2, false) else actual[index])
                        }
                    }
                }
                check(result.keySet().filter { result.get(it) is Boolean }.all { result.getBoolean(it) } &&
                    result.keySet().filter { it.endsWith("_read") }.all { result.getString(it) == "SUCCESS" }) {
                    "RF_READBACK_EXPECTATION_MISMATCH"
                }
                stage = "COMPLETE"
            }
        } finally {
            result.putString("rf_readback_stage", stage)
            result.putInt("rf_readback_requests", audit.requests)
            result.putInt("rf_readback_blocked", audit.blocked)
            instrumentation.sendStatus(0, result)
        }
    }
}

private fun <T> GatewayOutcome<T>.readbackValue(): T = when (this) {
    is GatewayOutcome.Success -> value
    is GatewayOutcome.Failure -> throw IllegalStateException("RF_READBACK_${category.name}")
}

internal class RetainedReadOnlyAudit : GateCRequestAudit {
    var requests = 0
        private set
    var blocked = 0
        private set
    var cardReads = 0
        private set
    private var stopped = false
    @Synchronized override fun onRequest(requestClass: GateCRequestClass) {
        if (stopped || requests >= 24 || requestClass == GateCRequestClass.SESSION_REVOKE) reject()
        requests++
    }
    @Synchronized override fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) = reject()
    @Synchronized override fun onRequestTarget(host: String, method: String, pathSegments: List<String>) {
        if (method == "GET" && pathSegments.take(3) == listOf("contacts", "v4", "contacts") &&
            pathSegments.size == 4 && pathSegments.last() != "emails"
        ) cardReads++
        val read = method == "GET" && pathSegments.firstOrNull() in setOf("contacts", "core", "keys")
        val refresh = method == "POST" && pathSegments == listOf("auth", "v4", "refresh")
        if (stopped || host != "api.protonmail.ch" || (!read && !refresh)) reject()
    }
    @Synchronized override fun onRequestComplete(method: String, statusCode: Int?) {
        if (statusCode == 429) stopped = true
    }
    private fun reject(): Nothing {
        stopped = true
        blocked++
        throw IOException("RF_READ_ONLY_GUARD")
    }
}
