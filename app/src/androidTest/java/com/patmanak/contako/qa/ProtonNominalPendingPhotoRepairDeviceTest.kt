package com.patmanak.contako.qa

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.sync.MutationActionRequiredObserver
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.data.sync.composeProductionSharedSyncRuntime
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.SyncTrigger
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Repairs only the single known pre-correction empty PHOTO value, then drains it through Proton. */
@RunWith(AndroidJUnit4::class)
class ProtonNominalPendingPhotoRepairDeviceTest {
    @Test
    fun repairSingleCapturedPhotoAndDrain() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_REPAIR)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as ContakoApplication
        val accountScope = application.protonGateCRuntime.accountScope
        val database = ContakoDatabase.create(context)
        val repository = RoomContactRepository(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutationActions = Collections.synchronizedList(mutableListOf<String>())

        try {
            val summaries = runBlocking { repository.observeContacts(accountScope.value).first() }
            val candidates = runBlocking {
                summaries.filter { it.pendingMutationRevision != null }
                    .mapNotNull { repository.getContact(accountScope.value, it.id) }
                    .filter { contact ->
                        contact.valuesOf(ContactValueKind.PHOTO).count { photo ->
                            photo.value.isBlank() &&
                                photo.binaryReference?.startsWith("data:image/", ignoreCase = true) == true
                        } == 1
                    }
            }
            assertEquals(300, summaries.size)
            assertEquals(1, runBlocking { repository.observePendingMutationCount(accountScope.value).first() })
            assertEquals(1, candidates.size)
            val candidate = candidates.single()
            val repaired = candidate.copy(
                values = candidate.values.map { value ->
                    if (value.kind == ContactValueKind.PHOTO && value.value.isBlank()) {
                        value.copy(value = requireNotNull(value.binaryReference))
                    } else {
                        value
                    }
                },
            )
            val saved = runBlocking { repository.saveContact(repaired) }
            check(saved is SaveResult.Saved) { "PN_PENDING_PHOTO_REPAIR_SAVE_REJECTED" }

            val runtime = composeProductionSharedSyncRuntime(
                context = context,
                database = database,
                proton = application.protonGateCRuntime,
                runnerScope = scope,
                mutationActionRequiredObserver = MutationActionRequiredObserver { diagnostic ->
                    mutationActions += listOfNotNull(
                        diagnostic.source.name,
                        diagnostic.operation.name,
                        diagnostic.failureCategory?.name,
                        diagnostic.preparationReason?.name,
                    ).joinToString(":")
                },
            )
            val completion = runBlocking {
                runtime.runner.request(SyncTrigger.RETRY)
                runtime.runner.awaitIdle()
                runtime.runner.lastCompletion.value
            }
            val pending = runBlocking { repository.observePendingMutationCount(accountScope.value).first() }
            val status = runBlocking { RoomSyncStatusStore(database).load(accountScope.value) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("pn_photo_repair_result", "PASS")
                putString("pn_photo_repair_outcome", completion?.outcome?.name ?: "NONE")
                putInt("pn_photo_repair_pending", pending)
                putString("pn_photo_repair_status", status?.state?.name ?: "NONE")
                putString("pn_photo_repair_action_reason", status?.actionReason?.name ?: "NONE")
                putString("pn_photo_repair_mutation_actions", mutationActions.joinToString(",").ifEmpty { "NONE" })
            })
            assertEquals("SUCCESS", completion?.outcome?.name)
            assertEquals(0, pending)
            assertEquals("IDLE", status?.state?.name)
        } finally {
            scope.cancel()
            database.close()
        }
    }

    private companion object {
        const val ARG_MODE = "pnPendingPhotoRepairMode"
        const val MODE_REPAIR = "single_captured_photo"
    }
}
