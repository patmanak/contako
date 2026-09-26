package com.patmanak.contako.qa

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One bounded read of the owned RF-09 failure. Never repairs or writes contacts. */
@RunWith(AndroidJUnit4::class)
class RetainedDeleteBaselineDeviceTest {
    @Test fun readOwnedDeleteBaseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val active = InstrumentationRegistry.getArguments().getString("retainedDeleteDiagnostic") == "RF09_ACTIVE_CONFIRM"
        assumeTrue(active || InstrumentationRegistry.getArguments().getString("retainedDeleteDiagnostic") == "RF09")
        check(BuildConfig.APPLICATION_ID == "com.patmanak.contako.candidate")
        val application = instrumentation.targetContext.applicationContext as ContakoApplication
        val audit = RetainedReadOnlyAudit()
        val runtime = ProtonGateCRuntime.createForInstrumentedGateC(
            instrumentation.targetContext, audit, object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) = Unit
                override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
            }, application.humanVerification.hooks,
        )
        val result = Bundle()
        val database = ContakoDatabase.create(instrumentation.targetContext)
        try {
            runBlocking { withTimeout(30_000) {
                check(runtime.session.restore(runtime.accountScope).valueOrFail() == SessionState.READY)
                val localId = database.openHelper.readableDatabase.query(
                    "SELECT id FROM contacts WHERE account_id=? AND display_name=? AND is_deleted=?",
                    arrayOf(runtime.accountScope.value, if (active) "Contako RF09 Confirm" else "Contako RF09 20260907", if (active) 0 else 1),
                ).use { cursor -> check(cursor.count == 1); check(cursor.moveToFirst()); cursor.getString(0) }
                val stored = checkNotNull(database.contactDao().get(runtime.accountScope.value, localId))
                if (active) {
                    val read = runtime.gateD.verifiedCards.fetch(runtime.accountScope, RemoteContactId(checkNotNull(stored.contact.remoteContactId))).valueOrFail()
                    result.putBoolean("rf_active_baseline_matches_verified_read", read.matchesBaseline(stored.contact.remoteVersion))
                    result.putBoolean("rf_remote_email_is_saved_value", read.contact.valuesOf(ContactValueKind.EMAIL).map { it.value } == listOf("rf09-updated@example.test"))
                    result.putBoolean("rf_local_intent_pending", stored.contact.pendingMutationRevision != null)
                    val local = checkNotNull(application.contactRepository.getContact(runtime.accountScope.value, localId))
                    for (kind in ContactValueKind.entries) {
                        result.putInt("rf_local_${kind.name}_count", local.valuesOf(kind).size)
                        result.putInt("rf_remote_${kind.name}_count", read.contact.valuesOf(kind).size)
                    }
                    val gateway = runtime.gateD.contactMutations as ProtonPublicContactGateway
                    val cryptoField = gateway.javaClass.getDeclaredField("cardCrypto").apply { isAccessible = true }
                    val crypto = cryptoField.get(gateway) as ProtonContactCardCrypto
                    val userField = gateway.javaClass.getDeclaredField("userProvider").apply { isAccessible = true }
                    val user = checkNotNull((userField.get(gateway) as ProtonReadyUserProvider).currentUserId())
                    val prepared = runCatching { runtime.gateD.vCardCodec.encode(local) }.getOrNull()
                    result.putBoolean("rf_local_preservation_encodable", prepared != null)
                    if (prepared == null) return@withTimeout
                    val expected = crypto.decryptAndVerify(user, crypto.protect(user, prepared))
                    val actual = read.contact.preservationEnvelope?.rawProperties.orEmpty()
                    val safeProperties = setOf("BEGIN", "END", "VERSION", "UID", "FN", "N", "EMAIL", "NOTE", "PRODID", "CATEGORIES", "TEL", "URL", "X-PM-EMAIL", "X-PM-GROUP")
                    for (card in expected) {
                        val observed = actual.filterKeys { it.startsWith("proton-card-${card.type.value}-") }.values.singleOrNull()
                        result.putBoolean("rf_expected_${card.type.name}_matches", card.vCard == observed)
                        val left = card.vCard.lines().toSet()
                        val right = observed.orEmpty().lines().toSet()
                        val changed = ((left - right) + (right - left)).filter { it.isNotBlank() }.map {
                            it.substringBefore(':').substringBefore(';').uppercase().let { name -> if (name in safeProperties) name else "OTHER" }
                        }.distinct().sorted()
                        result.putString("rf_${card.type.name}_different_properties", changed.joinToString(",").ifEmpty { "NONE" })
                    }
                    return@withTimeout
                }
                val outbox = checkNotNull(database.outboxDao().get(runtime.accountScope.value, "CONTACT", localId))
                check(outbox.operation == "DELETE" && outbox.state == "ACTION_REQUIRED")
                val remoteId = RemoteContactId(checkNotNull(stored.contact.remoteContactId))
                val inventory = runtime.gateD.inventory.page(runtime.accountScope, null).valueOrFail()
                val entries = inventory.contacts + if (inventory.nextCursor != null) {
                    val last = runtime.gateD.inventory.page(runtime.accountScope, inventory.nextCursor).valueOrFail()
                    check(last.nextCursor == null)
                    last.contacts
                } else emptyList()
                val index = entries.single { it.id == remoteId }
                val hydrated = runtime.gateD.verifiedCards.fetch(runtime.accountScope, remoteId).valueOrFail()
                result.putBoolean("rf_index_matches_delete_baseline", index.version?.value == outbox.remoteVersion)
                result.putBoolean("rf_card_matches_delete_baseline", hydrated.version?.value == outbox.remoteVersion)
                result.putBoolean("rf_local_matches_delete_baseline", stored.contact.remoteVersion == outbox.remoteVersion)
                result.putBoolean("rf_remote_email_is_saved_value", hydrated.contact.valuesOf(ContactValueKind.EMAIL).map { it.value } == listOf("rf09-updated@example.test"))
                result.putBoolean("rf_remote_note_is_preserved", hydrated.contact.valuesOf(ContactValueKind.NOTE).map { it.value } == listOf("RF09 preservation note"))
                result.putString("rf_delete_state", outbox.state)
                result.putInt("rf_delete_attempts", outbox.attemptCount)
            } }
        } finally {
            database.close()
            result.putInt("rf_requests", audit.requests)
            result.putInt("rf_card_reads", audit.cardReads)
            result.putInt("rf_blocked_requests", audit.blocked)
            instrumentation.sendStatus(0, result)
        }
    }
}

private fun <T> GatewayOutcome<T>.valueOrFail(): T = when (this) {
    is GatewayOutcome.Success -> value
    is GatewayOutcome.Failure -> error("RF_DELETE_READ_${category.name}")
}
