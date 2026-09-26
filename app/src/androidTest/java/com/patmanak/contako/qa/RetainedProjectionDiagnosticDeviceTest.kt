package com.patmanak.contako.qa

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.android.provider.AndroidProviderProjectionReplanObserver
import com.patmanak.contako.data.android.provider.AndroidRawContactLifecycleGateway
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidExpectedSourceIdentity
import com.patmanak.contako.data.android.provider.AndroidGroupsProviderReader
import com.patmanak.contako.data.sync.SyncHealthState
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.proton.GateCAuthDiagnostic
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.data.sync.AndroidProjectionRepairObserver
import com.patmanak.contako.data.sync.AndroidProjectionReplanObserver
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.SyncPassStageObserver
import com.patmanak.contako.data.sync.composeProductionSharedSyncRuntime
import com.patmanak.contako.domain.sync.SyncTrigger
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One real production pass diagnosing RF-05 adoption/RF-08 deletion; no remote writes/session reset. */
class RetainedProjectionDiagnosticDeviceTest {
    @Test fun diagnoseRetainedProjection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("retainedProjectionDiagnostic") == "RF")
        check(BuildConfig.APPLICATION_ID == "com.patmanak.contako.candidate")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as ContakoApplication
        val audit = RetainedReadOnlyAudit()
        val proton = ProtonGateCRuntime.createForInstrumentedGateC(
            context, audit, object : GateCAuthDiagnostic {
                override fun onPhase(phase: GateCAuthPhase) = Unit
                override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
            }, application.humanVerification.hooks,
        )
        val database = ContakoDatabase.create(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repairs = Collections.synchronizedList(mutableListOf<String>())
        val replans = Collections.synchronizedList(mutableListOf<String>())
        val provider = Collections.synchronizedList(mutableListOf<String>())
        val stages = Collections.synchronizedList(mutableListOf<String>())
        val started = SystemClock.elapsedRealtime()
        fun retainedLocators(): Map<String, Long> = database.openHelper.readableDatabase.query(
            "SELECT canonical_contact_id,raw_contact_locator FROM android_projection_ledger " +
                "WHERE raw_contact_locator IS NOT NULL LIMIT 501",
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1))
                check(size <= 500) { "RF_PROJECTION_BOUND" }
            }
        }
        val originalLocators = retainedLocators()
        try {
            val runtime = composeProductionSharedSyncRuntime(
                context, database, proton, runnerScope = scope,
                passStageObserver = SyncPassStageObserver {
                    stages += "${it.name}:${SystemClock.elapsedRealtime() - started}"
                },
                projectionRepairObserver = AndroidProjectionRepairObserver { repairs += it.name },
                projectionReplanObserver = AndroidProjectionReplanObserver { replans += it.name },
                providerProjectionReplanObserver = AndroidProviderProjectionReplanObserver { provider += it.name },
            )
            runBlocking {
                withTimeout(30_000) {
                    runtime.runner.request(SyncTrigger.MANUAL)
                    runtime.runner.awaitIdle()
                }
            }
            val unfinished = database.openHelper.readableDatabase.query(
                "SELECT count(*) FROM android_projection_ledger l JOIN contacts c " +
                    "ON c.account_id=l.account_id AND c.id=l.canonical_contact_id " +
                    "WHERE (c.is_deleted=0 AND (l.projection_state<>'CLEAN' OR l.adoption_state<>'ADOPTED')) " +
                    "OR (c.is_deleted=1 AND (l.tombstone_state<>'REMOTE_CONVERGED' " +
                    "OR l.projection_state<>'DETACHED' OR c.pending_mutation_revision IS NOT NULL))",
            ).use { it.moveToFirst(); it.getInt(0) }
            val deletedProviderRowsAbsent = runBlocking {
                val dao = database.androidProjectionLedgerDao()
                val account = requireNotNull(dao.getAccount(proton.accountScope.value))
                val tombstones = dao.getAll(proton.accountScope.value)
                    .filter { it.tombstoneState == "REMOTE_CONVERGED" }
                check(tombstones.size <= 20) { "RF_DELETION_BOUND" }
                val gateway = AndroidRawContactLifecycleGateway(context.contentResolver)
                tombstones.all { ledger ->
                    gateway.findOwnedRawContactForDeletion(
                        AndroidProviderAccountName(requireNotNull(account.androidAccountName)),
                        ledger.canonicalContactId,
                        ledger.sourceIdentity?.let { AndroidExpectedSourceIdentity.Present(it) }
                            ?: AndroidExpectedSourceIdentity.Missing,
                    ) == null
                }
            }
            val locatorsPreserved = originalLocators == retainedLocators()
            val groupsFinished = runBlocking {
                val dao = database.androidGroupProjectionDao()
                val account = requireNotNull(database.androidProjectionLedgerDao().getAccount(proton.accountScope.value))
                val groups = dao.getAllGroups(proton.accountScope.value)
                check(groups.size <= 100) { "RF_GROUP_BOUND" }
                val removed = groups.filter { it.tombstoneState == "REMOTE_CONVERGED" }.map { it.canonicalGroupId }.toSet()
                val page = AndroidGroupsProviderReader(context.contentResolver).readGroupPage(
                    AndroidProviderAccountName(requireNotNull(account.androidAccountName)), limit = 100, includeDeleted = true,
                )
                page.nextAfterGroupRowId == null && page.groups.none { it.canonicalGroupIdClaim in removed } &&
                    groups.all { ledger ->
                        val canonical = requireNotNull(database.contactGroupDao().get(ledger.accountId, ledger.canonicalGroupId)).group
                        if (canonical.isDeleted) ledger.canonicalGroupId in removed && ledger.groupRowLocator == null
                        else ledger.projectionState == "CLEAN" && ledger.groupRowLocator != null
                    }
            }
            val statusCurrent = runBlocking { RoomSyncStatusStore(database).load(proton.accountScope.value)?.state == SyncHealthState.IDLE }
            instrumentation.sendStatus(0, Bundle().apply {
                putInt("rf_projection_unfinished", unfinished)
                putBoolean("rf_projection_retained_locators_preserved", locatorsPreserved)
                putBoolean("rf_projection_deleted_provider_rows_absent", deletedProviderRowsAbsent)
                putBoolean("rf_projection_groups_finished_and_deleted_rows_absent", groupsFinished)
                putBoolean("rf_projection_status_current", statusCurrent)
            })
            check(unfinished == 0 && locatorsPreserved && deletedProviderRowsAbsent && groupsFinished &&
                statusCurrent && audit.blocked == 0) {
                "RF_PROJECTION_COMPLETION"
            }
        } finally {
            scope.cancel()
            val status = runBlocking { RoomSyncStatusStore(database).load(proton.accountScope.value) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("rf_projection_repairs", repairs.distinct().joinToString(",").ifEmpty { "NONE" })
                putString("rf_projection_replans", replans.distinct().joinToString(",").ifEmpty { "NONE" })
                putString("rf_projection_provider_replans", provider.distinct().joinToString(",").ifEmpty { "NONE" })
                putString("rf_projection_final_state", status?.state?.name ?: "NONE")
                putInt("rf_projection_requests", audit.requests)
                putInt("rf_projection_card_reads", audit.cardReads)
                putInt("rf_projection_guard_blocks", audit.blocked)
                putString("rf_projection_stage_start_millis", stages.joinToString(","))
                putLong("rf_projection_total_millis", SystemClock.elapsedRealtime() - started)
            })
            database.close()
        }
    }
}
