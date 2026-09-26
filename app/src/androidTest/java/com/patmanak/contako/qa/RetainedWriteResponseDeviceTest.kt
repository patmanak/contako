package com.patmanak.contako.qa

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactCard
import me.proton.core.contact.domain.entity.ContactWithCards
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A26-029 diagnostic: one identical-card PUT on the retained owned replay fixture only. */
@RunWith(AndroidJUnit4::class)
class RetainedWriteResponseDeviceTest {
    @Test fun compareAcknowledgementWithReadback() {
        val i = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("ownedWriteResponseDiagnostic")
        assumeTrue(mode in setOf("RF09_REPLAY", "RF09_REPROTECT", "RF09_FINAL_CHANGE"))
        val fixtureName = if (mode == "RF09_FINAL_CHANGE") "Contako RF09 Final" else "Contako RF09 Replay"
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.patmanak.contako.candidate")
        val app = i.targetContext.applicationContext as ContakoApplication
        val audit = OwnedIdenticalCardAudit()
        val runtime = ProtonGateCRuntime.createForInstrumentedGateC(i.targetContext, audit,
            object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) = Unit
                override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
            }, app.humanVerification.hooks)
        val db = ContakoDatabase.create(i.targetContext)
        val report = Bundle()
        try { runBlocking { withTimeout(30_000) {
            check((runtime.session.restore(runtime.accountScope) as? GatewayOutcome.Success)?.value == SessionState.READY)
            val id = db.openHelper.readableDatabase.query(
                "SELECT remote_contact_id FROM contacts WHERE account_id=? AND display_name=? AND is_deleted=1",
                arrayOf(runtime.accountScope.value, fixtureName),
            ).use { c -> check(c.count == 1); check(c.moveToFirst()); c.getString(0) }
            // Read-only reflection stays in this debug diagnostic, avoiding a production payload hook.
            val gateway = runtime.gateD.contactMutations as ProtonPublicContactGateway
            val field = gateway.javaClass.getDeclaredField("remote").apply { isAccessible = true }
            val remote = field.get(gateway) as ProtonContactRemotePort
            val userField = gateway.javaClass.getDeclaredField("userProvider").apply { isAccessible = true }
            val user = checkNotNull((userField.get(gateway) as ProtonReadyUserProvider).currentUserId())
            val before = remote.hydrate(user, ContactId(id))
            check(before.contact.name == fixtureName)
            val submitted = if (mode != "RF09_REPLAY") {
                val cryptoField = gateway.javaClass.getDeclaredField("cardCrypto").apply { isAccessible = true }
                val crypto = cryptoField.get(gateway) as ProtonContactCardCrypto
                val plain = crypto.decryptAndVerify(user, before.contactCards)
                val canonical = runtime.gateD.vCardCodec.decode(runtime.accountScope.value, before.contact, plain)
                val desired = if (mode == "RF09_FINAL_CHANGE") canonical.copy(values = canonical.values.map {
                    if (it.kind == ContactValueKind.EMAIL) it.copy(value = "rf09-diagnostic@example.test") else it
                }) else canonical
                crypto.protect(user, runtime.gateD.vCardCodec.encode(desired))
            } else before.contactCards
            audit.ownedContactId = id
            val ack = remote.update(user, ContactId(id), submitted)
            val after = remote.hydrate(user, ContactId(id))
            val verifierField = gateway.javaClass.getDeclaredField("cardCrypto").apply { isAccessible = true }
            val verifier = verifierField.get(gateway) as ProtonContactCardCrypto
            val sentPlain = verifier.decryptAndVerify(user, submitted)
            val readPlain = verifier.decryptAndVerify(user, after.contactCards)
            report.putBoolean("rf_verified_plain_content_matches", verifiedContentFingerprint(ack, sentPlain) == verifiedContentFingerprint(after.contact, readPlain))
            report.putBoolean("rf_verified_plain_cards_match", sentPlain.toSet() == readPlain.toSet())
            for (type in sentPlain.map { it.type }.distinct()) {
                report.putBoolean("rf_plain_${type.name}_matches", sentPlain.filter { it.type == type }.map { it.vCard } == readPlain.filter { it.type == type }.map { it.vCard })
            }
            report.putBoolean("rf_cards_unchanged", submitted.toSet() == after.contactCards.toSet())
            fun parts(cards: List<ContactCard>): List<List<String>> = cards.map { when(it) {
                is ContactCard.ClearText -> listOf("CLEAR", it.data, "")
                is ContactCard.Signed -> listOf("SIGNED", it.data, it.signature)
                is ContactCard.Encrypted -> listOf("ENCRYPTED", it.data, it.signature.orEmpty())
            } }.sortedBy { it.first() }
            val left = parts(submitted)
            val right = parts(after.contactCards)
            report.putBoolean("rf_card_types_match", left.map { it[0] } == right.map { it[0] })
            for (type in listOf("CLEAR", "SIGNED", "ENCRYPTED")) {
                val l = left.singleOrNull { it[0] == type }
                val r = right.singleOrNull { it[0] == type }
                report.putBoolean("rf_${type}_data_matches", l?.get(1) == r?.get(1))
                report.putBoolean("rf_${type}_signature_matches", l?.get(2) == r?.get(2))
                report.putBoolean("rf_${type}_trimmed_lf_data_matches", l?.get(1)?.replace("\r\n", "\n")?.trimEnd() == r?.get(1)?.replace("\r\n", "\n")?.trimEnd())
            }
            report.putBoolean("rf_ack_content_matches_read", contentFingerprint(ContactWithCards(ack, submitted)) == contentFingerprint(after))
            report.putBoolean("rf_ack_name_matches", ack.name == after.contact.name)
            report.putBoolean("rf_ack_id_matches", ack.id == after.contact.id)
            report.putBoolean("rf_ack_email_ids_match", ack.contactEmails.map { it.id } == after.contact.contactEmails.map { it.id })
            report.putBoolean("rf_ack_email_names_match", ack.contactEmails.map { it.name } == after.contact.contactEmails.map { it.name })
            report.putBoolean("rf_ack_email_orders_match", ack.contactEmails.map { it.order } == after.contact.contactEmails.map { it.order })
            report.putBoolean("rf_ack_email_values_match", ack.contactEmails.map { it.email } == after.contact.contactEmails.map { it.email })
        } } } finally {
            db.close()
            report.putInt("rf_identical_card_writes", audit.writes)
            report.putInt("rf_requests", audit.reads.requests)
            i.sendStatus(0, report)
        }
    }
}

private class OwnedIdenticalCardAudit : GateCRequestAudit {
    val reads = RetainedReadOnlyAudit()
    var ownedContactId: String? = null
    var writes = 0
    private var targets = 0
    override fun onRequest(requestClass: GateCRequestClass) = reads.onRequest(requestClass)
    @Synchronized override fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) {
        check(ownedContactId != null && mutationClass == GateCDataMutationClass.CONTACT_UPDATE && writes == 0) { "RF_WRITE_BOUND" }
        writes++
    }
    @Synchronized override fun onRequestTarget(host: String, method: String, pathSegments: List<String>) {
        if (host == "api.protonmail.ch" && method == "PUT" && ownedContactId != null &&
            pathSegments == listOf("contacts", "v4", "contacts", ownedContactId)) {
            check(targets++ == 0) { "RF_TARGET_BOUND" }
        } else reads.onRequestTarget(host, method, pathSegments)
    }
    override fun onRequestComplete(method: String, statusCode: Int?) = reads.onRequestComplete(method, statusCode)
}
