package com.patmanak.contako.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.android.provider.AndroidProviderEpochProofWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidProviderResetRepairCoordinatorDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
    }

    @After fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DB)
    }

    @Test fun interruptedRepairResumesSameEpochAndWritesProofAfterProjection() = runBlocking {
        val ledger = RoomAndroidProjectionLedger(database)
        val initial = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, initial.revision, ANDROID_ACCOUNT)
        var pages = 0
        var proofs = 0
        val projection = AndroidBoundedProjectionCoordinator { _, _ ->
            pages++
            AndroidProjectionPage(null, 0) to AndroidBoundedPageResult.Applied
        }
        val coordinator = AndroidProviderResetRepairCoordinator(database, projection) { _, epoch ->
            assertEquals(1L, epoch)
            proofs++
            AndroidProviderEpochProofWriteResult.Written
        }
        val context = AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT, 1, 0)

        assertEquals(AndroidInteroperabilityStageResult.Success, coordinator.repair(context) { false })
        val after = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        assertEquals(1L, after.providerEpoch)
        assertEquals(false, after.providerRepairPending)
        assertEquals(1, pages)
        assertEquals(1, proofs)
    }

    @Test fun cancellationAfterEpochAdvanceResumesWithoutAdvancingAgain() = runBlocking {
        val ledger = RoomAndroidProjectionLedger(database)
        val initial = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, initial.revision, ANDROID_ACCOUNT)
        var cancelled = false
        var projections = 0
        var proofs = 0
        val coordinator = AndroidProviderResetRepairCoordinator(
            database,
            AndroidBoundedProjectionCoordinator { _, _ ->
                projections++
                AndroidProjectionPage(null, 0) to AndroidBoundedPageResult.Applied
            },
        ) { _, epoch ->
            assertEquals(1L, epoch)
            proofs++
            AndroidProviderEpochProofWriteResult.Written
        }
        val stale = AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT, 1, 0)

        val interrupted = coordinator.repair(stale) { cancelled.also { cancelled = true } }
        val pending = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        assertEquals(AndroidInteroperabilityStageResult.Cancelled, interrupted)
        assertEquals(1L, pending.providerEpoch)
        assertTrue(pending.providerRepairPending)
        assertEquals(0, projections)
        assertEquals(0, proofs)

        assertEquals(AndroidInteroperabilityStageResult.Success, coordinator.repair(stale) { false })
        val completed = requireNotNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
        assertEquals(1L, completed.providerEpoch)
        assertEquals(false, completed.providerRepairPending)
        assertEquals(1, projections)
        assertEquals(1, proofs)
    }

    @Test fun providerResetReconstructsCanonicalContactsWithoutChangingCanonicalOrOutboxRows() = runBlocking {
        val repository = com.patmanak.contako.data.local.RoomContactRepository(
            database,
            clock = { 1_000L },
        )
        val canonical = com.patmanak.contako.domain.model.CanonicalContact(
            accountId = ACCOUNT.value,
            id = "synthetic-contact",
            firstName = "Synthetic",
            lastName = "Person",
            displayName = "Synthetic Person",
            values = listOf(
                com.patmanak.contako.domain.model.ContactValue(
                    id = "synthetic-email",
                    kind = com.patmanak.contako.domain.model.ContactValueKind.EMAIL,
                    value = "person@example.test",
                    order = 0,
                    isPrimary = true,
                ),
            ),
        )
        assertTrue(repository.saveContact(canonical) is com.patmanak.contako.domain.repository.SaveResult.Saved)
        val beforeContact = requireNotNull(database.contactDao().get(ACCOUNT.value, canonical.id))
        val beforeOutbox = database.outboxDao().getAll(ACCOUNT.value)
        val ledger = RoomAndroidProjectionLedger(database)
        val initial = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, initial.revision, ANDROID_ACCOUNT)
        var projected = 0
        val coordinator = AndroidProviderResetRepairCoordinator(
            database,
            AndroidBoundedProjectionCoordinator { page, _ ->
                projected++
                assertEquals(null, page)
                AndroidProjectionPage(null, 1) to AndroidBoundedPageResult.Applied
            },
        ) { _, _ -> AndroidProviderEpochProofWriteResult.Written }

        assertEquals(
            AndroidInteroperabilityStageResult.Success,
            coordinator.repair(AndroidInteroperabilityContext(ACCOUNT, ANDROID_ACCOUNT, 1, 0)) { false },
        )

        assertEquals(1, projected)
        assertEquals(beforeContact, database.contactDao().get(ACCOUNT.value, canonical.id))
        assertEquals(beforeOutbox, database.outboxDao().getAll(ACCOUNT.value))
        assertEquals(false, database.androidProjectionLedgerDao().getAccount(ACCOUNT.value)?.providerRepairPending)
    }

    private companion object {
        const val DB = "provider-reset-repair.db"
        val ACCOUNT = AccountScope("account")
        const val ANDROID_ACCOUNT = "android-account"
    }
}
