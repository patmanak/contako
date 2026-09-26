package com.patmanak.contako.qa

import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.MainActivity
import com.patmanak.contako.R
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.proton.GateCAuthDiagnostic
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.domain.sync.SyncActivity
import com.patmanak.contako.domain.sync.SyncDashboardState
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** D-125: the actual manual Sync button, retained account/device, no permitted remote writes. */
class RetainedNoChangeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun inspectActualNoChangePass() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("retainedNoChange") == "RF")
        check(BuildConfig.APPLICATION_ID == "com.patmanak.contako.candidate")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as ContakoApplication
        val audit = RetainedReadOnlyAudit()
        val runtime = ProtonGateCRuntime.createForInstrumentedGateC(
            instrumentation.targetContext, audit, object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) = Unit
                override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
            }, application.humanVerification.hooks,
        )
        val account = runtime.accountScope.value
        val result = Bundle()
        var stage = "RESTORE"
        try {
            check(runBlocking { runtime.session.restore(runtime.accountScope) } == GatewayOutcome.Success(SessionState.READY))
            val address = requireNotNull(runBlocking { runtime.currentAccountAddress() })
            val status = requireNotNull(runBlocking { application.syncRecoveryDataSource.observeStatus(account).first() })
            check(status.state == SyncDashboardState.CURRENT && status.pendingMutationCount == 0 && status.actionRequiredCount == 0)
            val previousSuccess = requireNotNull(status.lastSuccessAtEpochMillis)
            check(System.currentTimeMillis() - previousSuccess in 0 until 15 * 60 * 1000) { "RF_STALE_BASELINE" }
            val contacts = runBlocking { application.contactRepository.observeContacts(account).first() }
            val groups = runBlocking { application.contactRepository.observeGroups(account).first() }
            val provider = providerDigest(application, address)
            stage = "ACTUAL_UI"
            ActivityScenario.launch(MainActivity::class.java).use {
                val syncLabel = application.getString(R.string.nav_sync)
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(syncLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                // The selected tab can also have a screen title; the final occurrence is
                // the actual bottom-navigation label, including its animation semantics.
                compose.onAllNodesWithText(syncLabel, useUnmergedTree = true).onLast().performClick()
                val beforeRequests = audit.requests
                val beforeCards = audit.cardReads
                val started = SystemClock.elapsedRealtime()
                compose.onNodeWithText(application.getString(R.string.sync_now)).performClick()
                var nextObservation = 0L
                compose.waitUntil(20_000) {
                    val now = SystemClock.elapsedRealtime()
                    if (now < nextObservation) false else {
                        nextObservation = now + 100
                        runBlocking {
                            val latest = application.syncRecoveryDataSource.observeStatus(account).first()
                            latest?.state == SyncDashboardState.CURRENT && latest.pendingMutationCount == 0 &&
                                latest.actionRequiredCount == 0 && (latest.lastSuccessAtEpochMillis ?: 0) > previousSuccess &&
                                application.syncRecoveryDataSource.observeActivity(account).first() == SyncActivity.IDLE
                        }
                    }
                }
                val duration = SystemClock.elapsedRealtime() - started
                val contactsUnchanged = contacts == runBlocking { application.contactRepository.observeContacts(account).first() }
                val groupsUnchanged = groups == runBlocking { application.contactRepository.observeGroups(account).first() }
                val providerUnchanged = provider.contentEquals(providerDigest(application, address))
                result.putLong("rf_nochange_millis", duration)
                result.putInt("rf_nochange_requests", audit.requests - beforeRequests)
                result.putInt("rf_nochange_card_reads", audit.cardReads - beforeCards)
                result.putBoolean("rf_nochange_contacts_unchanged", contactsUnchanged)
                result.putBoolean("rf_nochange_groups_unchanged", groupsUnchanged)
                result.putBoolean("rf_nochange_provider_versions_unchanged", providerUnchanged)
                stage = "COMPLETE"
                check(duration <= 5_000 && audit.cardReads == beforeCards && audit.blocked == 0 &&
                    contactsUnchanged && groupsUnchanged && providerUnchanged) { "RF_NOCHANGE_ORACLE" }
            }
        } finally {
            result.putString("rf_nochange_stage", stage)
            result.putInt("rf_nochange_guard_blocks", audit.blocked)
            result.putInt("rf_nochange_total_requests", audit.requests)
            result.putInt("rf_nochange_total_card_reads", audit.cardReads)
            runBlocking {
                val latest = application.syncRecoveryDataSource.observeStatus(account).first()
                result.putString("rf_nochange_final_state", latest?.state?.name ?: "NONE")
                result.putString("rf_nochange_final_activity", application.syncRecoveryDataSource.observeActivity(account).first().name)
                result.putInt("rf_nochange_final_pending", latest?.pendingMutationCount ?: -1)
                result.putInt("rf_nochange_final_action_required", latest?.actionRequiredCount ?: -1)
            }
            instrumentation.sendStatus(0, result)
        }
    }

    private fun providerDigest(application: ContakoApplication, address: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            ContactsContract.RawContacts.CONTENT_URI to arrayOf(
                ContactsContract.RawContacts._ID, ContactsContract.RawContacts.VERSION,
                ContactsContract.RawContacts.DIRTY, ContactsContract.RawContacts.DELETED, ContactsContract.RawContacts.SOURCE_ID,
            ),
            ContactsContract.Groups.CONTENT_URI to arrayOf(
                ContactsContract.Groups._ID, ContactsContract.Groups.VERSION,
                ContactsContract.Groups.DIRTY, ContactsContract.Groups.DELETED, ContactsContract.Groups.SOURCE_ID,
            ),
        ).forEach { (uri, columns) ->
            requireNotNull(application.contentResolver.query(
                uri, columns, "account_type = ? AND account_name = ?",
                arrayOf(BuildConfig.APPLICATION_ID, address), "_id ASC",
            )).use { cursor ->
                while (cursor.moveToNext()) columns.indices.forEach { index ->
                    digest.update((cursor.getString(index).orEmpty() + "\u0000").toByteArray(Charsets.UTF_8))
                }
            }
        }
        return digest.digest()
    }
}
