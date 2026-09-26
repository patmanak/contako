package com.patmanak.contako.qa

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.android.provider.*
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.data.sync.ProductionSyncDependencies
import com.patmanak.contako.data.sync.composeProductionSharedSyncRuntime
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.sync.SyncTrigger
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** RF-18: real production pass, one owned update; only DROP withholds a successful acknowledgement. */
@RunWith(AndroidJUnit4::class)
class RetainedUncertainWriteDeviceTest {
    @Test fun runOwnedRecoveryPhase() {
        val mode = InstrumentationRegistry.getArguments().getString("ownedUncertainWrite")
        assumeTrue(mode in setOf("SEED", "DROP", "RECOVER", "PROJECTION"))
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.patmanak.contako.candidate")
        val i = InstrumentationRegistry.getInstrumentation()
        val app = i.targetContext.applicationContext as ContakoApplication
        val audit = OwnedRecoveryAudit()
        val proton = ProtonGateCRuntime.createForInstrumentedGateC(i.targetContext, audit,
            object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) = Unit
                override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
            }, app.humanVerification.hooks)
        val db = ContakoDatabase.create(i.targetContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val report = Bundle()
        try { runBlocking { withTimeout(60_000) {
            check((proton.session.restore(proton.accountScope) as? GatewayOutcome.Success)?.value == SessionState.READY)
            val localId = db.openHelper.readableDatabase.query(
                "SELECT id FROM contacts WHERE account_id=? AND display_name=? AND is_deleted=0",
                arrayOf(proton.accountScope.value, "Contako RF18 Recovery"),
            ).use { c -> check(c.count == 1 && c.moveToFirst()) { "RF18_FIXTURE_SCOPE" }; c.getString(0) }
            val original = checkNotNull(app.contactRepository.getContact(proton.accountScope.value, localId))
            audit.ownedContactId = checkNotNull(original.remoteContactId)
            val gate = proton.gateD
            if (mode == "PROJECTION") {
                check(db.outboxDao().getAll(proton.accountScope.value).isEmpty())
                val account = checkNotNull(db.androidProjectionLedgerDao().getAccount(proton.accountScope.value))
                val ledger = checkNotNull(db.androidProjectionLedgerDao().get(proton.accountScope.value, localId))
                val name = AndroidProviderAccountName(checkNotNull(account.androidAccountName))
                val rawId = checkNotNull(ledger.rawContactLocator)
                val read = AndroidContactsProviderReader(i.targetContext.contentResolver).readStableRawContact(name, rawId)
                check(read is AndroidStableRawContactPageResult.Stable)
                val observation = read.page.observations.single()
                report.putString("rf18_projection_state", ledger.projectionState)
                report.putBoolean("rf18_raw_dirty", observation.rawContact.dirty)
                report.putBoolean("rf18_pending_matches_canonical", ledger.pendingProjectionFingerprint ==
                    com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper().let { it.fingerprint(it.project(original)).sha256Hex })
                val route = AndroidProviderMimeRouter().route(rawId, observation.dataRows)
                val delegate = RoomAndroidProviderIdentityResolver(db, proton.accountScope, account.providerEpoch)
                val resolver = AndroidProviderIdentityResolver { claims ->
                    var missing = 0; var primaryMismatch = 0; var linkedMismatch = 0; var contextMismatch = 0
                    claims.forEach { claim ->
                        val attached = db.androidProviderIdentityDao().getAttachedBindingGroup(
                            proton.accountScope.value, localId, account.providerEpoch, rawId, claim.providerRowId)
                        if (attached.isEmpty()) {
                            missing++
                            report.putString("rf18_missing_binding_kind", claim.kind.name)
                            val prior = claim.claimedCanonicalValueId?.let { id ->
                                db.androidProviderIdentityDao().getBindingGroup(
                                    proton.accountScope.value, localId, account.providerEpoch, rawId, id)
                            }.orEmpty()
                            report.putInt("rf18_prior_binding_rows", prior.size)
                            report.putBoolean("rf18_prior_binding_attached", prior.isNotEmpty() && prior.all { it.state == "ATTACHED" })
                            report.putBoolean("rf18_prior_locator_still_present", prior.any { binding ->
                                route.contactRows.rows.any { it.dataRowId == binding.dataRowLocator }
                            })
                        } else {
                            if (attached.any { it.kind != claim.kind.name || it.androidAccountName != name.value || it.state != "ATTACHED" }) contextMismatch++
                            val primary = attached.singleOrNull { it.role == "PRIMARY" }
                            if (primary?.canonicalValueId != claim.claimedCanonicalValueId) primaryMismatch++
                            val links = attached.filterNot { it.role == "PRIMARY" }.associate { it.role to it.canonicalValueId }
                            if (links != claim.claimedLinkedCanonicalValueIds.mapKeys { it.key.name }) linkedMismatch++
                        }
                    }
                    report.putInt("rf18_missing_attached_binding", missing)
                    report.putInt("rf18_primary_binding_mismatch", primaryMismatch)
                    report.putInt("rf18_linked_binding_mismatch", linkedMismatch)
                    report.putInt("rf18_binding_context_mismatch", contextMismatch)
                    delegate.resolve(claims)
                }
                val codec = AndroidProviderRowCodec(resolver, AndroidDurablePhotoCapture { _, _, _, _ -> error("RF18_UNEXPECTED_PHOTO") })
                try {
                    codec.decode(name, localId, rawId, route.contactRows.rows)
                    report.putString("rf18_provider_decode", "OK")
                } catch (failure: AndroidProviderRowCodecException) {
                    report.putString("rf18_provider_decode", failure.category.name)
                }
                report.putInt("rf18_provider_contact_rows", route.contactRows.rows.size)
                return@withTimeout
            }
            if (mode == "SEED") {
                check(db.outboxDao().getAll(proton.accountScope.value).isEmpty()) { "RF18_SEED_PENDING_SCOPE" }
                val fetched = gate.verifiedCards.fetch(proton.accountScope, RemoteContactId(audit.ownedContactId!!))
                check(fetched is GatewayOutcome.Success) { "RF18_SEED_READ" }
                val contact = fetched.value.contact
                check(contact.displayName == "Contako RF18 Recovery" && contact.valuesOf(ContactValueKind.EMAIL).size == 2)
                val envelope = checkNotNull(contact.preservationEnvelope)
                check(envelope.rawProperties.values.none { it.contains("X-CONTAKO-RF18:") }) { "RF18_SEED_ALREADY_PRESENT" }
                val key = envelope.rawProperties.keys.single { it.startsWith("proton-card-3-") || it.startsWith("proton-card-1-") }
                val raw = envelope.rawProperties.getValue(key)
                check(raw.contains("END:VCARD"))
                val seeded = contact.copy(preservationEnvelope = envelope.copy(rawProperties = envelope.rawProperties +
                    (key to raw.replace("END:VCARD", "X-CONTAKO-RF18:retained-canary\r\nEND:VCARD"))))
                val saved = gate.contactMutations.apply(proton.accountScope, ContactMutation.Update(
                    RemoteContactId(audit.ownedContactId!!), null, seeded))
                check(saved is GatewayOutcome.Success) { "RF18_SEED_WRITE" }
                report.putBoolean("rf18_owned_unknown_seeded", true)
                return@withTimeout
            }
            check(original.valuesOf(ContactValueKind.EMAIL).single().value == "rf18-edited@example.test")
            val before = db.outboxDao().getAll(proton.accountScope.value)
            check(before.size == 1 && before.single().aggregateId == localId) { "RF18_PENDING_SCOPE" }
            var forwarded = 0
            val wrapped = object : ProtonContactMutationGateway {
                override suspend fun apply(account: AccountScope, mutation: ContactMutation): GatewayOutcome<ContactMutationReceipt> {
                    check(account == proton.accountScope && mutation is ContactMutation.Update &&
                        mutation.id.value == audit.ownedContactId && forwarded == 0) { "RF18_WRITE_SCOPE" }
                    if (mode == "RECOVER") {
                        val merged = checkNotNull(app.contactRepository.getContact(account.value, localId))
                        check(merged.preservationEnvelope!!.rawProperties.keys.any { it.startsWith("pending-value-source/") }) {
                            "RF18_RECONCILIATION_NOT_EXERCISED"
                        }
                        val prepared = gate.vCardCodec.encode(merged)
                        check(prepared.encryptedPrivate.contains("LANG:fr")) { "RF18_PROTON_FIELD_LOST" }
                        check(prepared.encryptedPrivate.contains("X-CONTAKO-RF18:retained-canary")) { "RF18_UNKNOWN_FIELD_LOST" }
                        check(merged.valuesOf(ContactValueKind.NOTE).any { it.value == "RF18 preservation note" })
                        report.putBoolean("rf18_pinned_source_encodes", true)
                    }
                    forwarded++
                    val result = gate.contactMutations.apply(account, mutation)
                    check(result is GatewayOutcome.Success) { "RF18_REAL_WRITE_FAILED" }
                    return if (mode == "DROP") GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE) else result
                }
            }
            val runtime = composeProductionSharedSyncRuntime(i.targetContext, db, ProductionSyncDependencies(
                proton.accountScope, proton.session, gate.inventory, gate.verifiedCards, wrapped,
                gate.groups, gate.emailLabels, gate.membershipReader, proton.session as ProtonLocalSessionCleanupGateway,
            ), runnerScope = scope)
            runtime.runner.request(SyncTrigger.MANUAL)
            runtime.runner.awaitIdle()
            val remaining = db.outboxDao().getAll(proton.accountScope.value)
            report.putInt("rf18_forwarded_writes", forwarded)
            report.putInt("rf18_pending_count", remaining.size)
            check(forwarded == 1) { "RF18_EXPECTED_ONE_WRITE" }
            if (mode == "DROP") {
                check(remaining.size == 1 && remaining.single().aggregateId == localId) { "RF18_LOST_ACK_NOT_RETAINED" }
                report.putBoolean("rf18_ack_deliberately_withheld", true)
            } else check(remaining.isEmpty()) { "RF18_RECOVERY_NOT_ACKNOWLEDGED" }
        } } } finally {
            scope.cancel()
            db.close()
            report.putInt("rf18_requests", audit.reads.requests)
            report.putInt("rf18_mutation_attempts", audit.writes)
            i.sendStatus(0, report)
        }
    }
}

private class OwnedRecoveryAudit : GateCRequestAudit {
    val reads = RetainedReadOnlyAudit()
    var ownedContactId: String? = null
    var writes = 0
    private var targets = 0
    override fun onRequest(requestClass: GateCRequestClass) = reads.onRequest(requestClass)
    @Synchronized override fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) {
        check(ownedContactId != null && mutationClass == GateCDataMutationClass.CONTACT_UPDATE && writes++ == 0) { "RF18_WRITE_BOUND" }
    }
    @Synchronized override fun onRequestTarget(host: String, method: String, pathSegments: List<String>) {
        if (host == "api.protonmail.ch" && method == "PUT" && ownedContactId != null &&
            pathSegments == listOf("contacts", "v4", "contacts", ownedContactId)) {
            check(targets++ == 0) { "RF18_TARGET_BOUND" }
        } else reads.onRequestTarget(host, method, pathSegments)
    }
    override fun onRequestComplete(method: String, statusCode: Int?) = reads.onRequestComplete(method, statusCode)
}
