package com.patmanak.contako.qa

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.android.provider.AndroidProviderProjectionReplanObserver
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidIngestActionRequiredObserver
import com.patmanak.contako.data.sync.AndroidIngestReplanObserver
import com.patmanak.contako.data.sync.AndroidExistingContactPlanRepairObserver
import com.patmanak.contako.data.sync.AndroidProjectionRepairObserver
import com.patmanak.contako.data.sync.AndroidProjectionReplanObserver
import com.patmanak.contako.data.sync.AndroidSyncStageResultObserver
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One no-change production pass with fixed, payload-free Android boundary diagnostics. */
@RunWith(AndroidJUnit4::class)
class ProtonNominalAndroidDegradationProbeDeviceTest {
    @Test
    fun reportPayloadFreeAndroidDegradation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_PROBE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as ContakoApplication
        val database = ContakoDatabase.create(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stages = Collections.synchronizedList(mutableListOf<String>())
        val androidResults = Collections.synchronizedList(mutableListOf<String>())
        val ingestActions = Collections.synchronizedList(mutableListOf<String>())
        val ingestReplans = Collections.synchronizedList(mutableListOf<String>())
        val existingPlanRepairs = Collections.synchronizedList(mutableListOf<String>())
        val projectionRepairs = Collections.synchronizedList(mutableListOf<String>())
        val projectionReplans = Collections.synchronizedList(mutableListOf<String>())
        val providerReplans = Collections.synchronizedList(mutableListOf<String>())

        try {
            val runtime = composeProductionSharedSyncRuntime(
                context = context,
                database = database,
                proton = application.protonGateCRuntime,
                runnerScope = scope,
                passStageObserver = SyncPassStageObserver { stages += it.name },
                androidStageResultObserver = AndroidSyncStageResultObserver { stage, result ->
                    androidResults += "${stage.name}:${result::class.simpleName}"
                },
                projectionReplanObserver = AndroidProjectionReplanObserver {
                    projectionReplans += it.name
                },
                providerProjectionReplanObserver = AndroidProviderProjectionReplanObserver {
                    providerReplans += it.name
                },
                androidIngestActionRequiredObserver = AndroidIngestActionRequiredObserver {
                    ingestActions += it.name
                },
                androidIngestReplanObserver = AndroidIngestReplanObserver {
                    ingestReplans += it.name
                },
                existingContactPlanRepairObserver = AndroidExistingContactPlanRepairObserver {
                    existingPlanRepairs += it.name
                },
                projectionRepairObserver = AndroidProjectionRepairObserver {
                    projectionRepairs += it.name
                },
            )
            val completion = runBlocking {
                runtime.runner.request(SyncTrigger.STARTUP_STALE_CHECK)
                runtime.runner.awaitIdle()
                runtime.runner.lastCompletion.value
            }
            val status = runBlocking {
                RoomSyncStatusStore(database).load(application.protonGateCRuntime.accountScope.value)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("pn_android_probe_result", "PASS")
                putString("pn_android_probe_outcome", completion?.outcome?.name ?: "NONE")
                putString("pn_android_probe_status", status?.state?.name ?: "NONE")
                putString("pn_android_probe_action_reason", status?.actionReason?.name ?: "NONE")
                putString("pn_android_probe_stages", stages.joinToString(",").ifEmpty { "NONE" })
                putString("pn_android_probe_stage_results", androidResults.joinToString(",").ifEmpty { "NONE" })
                putString("pn_android_probe_ingest_actions", ingestActions.joinToString(",").ifEmpty { "NONE" })
                putString("pn_android_probe_ingest_replans", ingestReplans.joinToString(",").ifEmpty { "NONE" })
                putString("pn_android_probe_existing_plan_repairs", existingPlanRepairs.joinToString(",").ifEmpty { "NONE" })
                putString("pn_android_probe_projection_repairs", projectionRepairs.joinToString(",").ifEmpty { "NONE" })
                putString("pn_android_probe_projection_replans", projectionReplans.joinToString(",").ifEmpty { "NONE" })
                putString("pn_android_probe_provider_replans", providerReplans.joinToString(",").ifEmpty { "NONE" })
            })
        } finally {
            scope.cancel()
            database.close()
        }
    }

    private companion object {
        const val ARG_MODE = "pnAndroidDegradationMode"
        const val MODE_PROBE = "payload_free_probe"
    }
}
