package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.repository.SaveResult
import com.patmanak.contako.domain.sync.SyncMutationRetryReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated Room plus controlled gateways. No Android account, provider or Proton access. */
@RunWith(AndroidJUnit4::class)
class RoomMutationRetryRequestDeviceTest {
    private val application: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ContakoDatabase
    private lateinit var repository: RoomContactRepository
    private lateinit var retries: RoomMutationRetryRequestStore
    private val account = AccountScope("synthetic-retry-account")

    @Before fun setup() {
        application.deleteDatabase(NAME)
        open()
    }
    @After fun cleanup() { database.close(); application.deleteDatabase(NAME) }
    private fun open() {
        database = ContakoDatabase.create(application, NAME)
        repository = RoomContactRepository(database)
        retries = RoomMutationRetryRequestStore(database)
    }

    @Test fun oneExplicitCreditAllowsOneExhaustedAttemptAndNeverResetsItsHistory() = runBlocking {
        val original = blocked("contact")
        assertTrue(retries.queue(account, "CONTACT", "contact", original.revision, NOW))
        val grant = database.mutationRetryRequestDao().get(account.value, "CONTACT", "contact")
        assertTrue(retries.queue(account, "CONTACT", "contact", original.revision, NOW + 1))
        assertEquals(grant, database.mutationRetryRequestDao().get(account.value, "CONTACT", "contact"))
        assertEquals(1, retries.consume(account, NOW))
        val pending = intent("contact")
        assertEquals(original.copy(state = "PENDING", blockedReason = null), pending)
        var attempts = 0
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            preparationGateway = MutationPreparationGateway { GatewayOutcome.Success(MutationPreparation.UploadAllowed) },
            uploadGateway = MutationUploadGateway { attempts++; GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT) })
        runner.drain(account.value, NOW)
        runner.drain(account.value, NOW + 1)
        assertEquals(1, attempts)
        assertEquals(8, intent("contact").attemptCount)
        assertEquals("ACTION_REQUIRED", intent("contact").state)
        assertTrue(intent("contact").requiresReconciliation)
        assertFalse(retries.queue(account, "CONTACT", "contact", original.revision, NOW + 2))
        assertTrue(retries.queue(account, "CONTACT", "contact", original.revision,
            NOW + RoomMutationRetryRequestStore.COOLDOWN_MILLIS))
    }

    @Test fun queuedCreditAndCooldownSurviveDatabaseReopen() = runBlocking {
        val original = blocked("contact")
        assertTrue(retries.queue(account, "CONTACT", "contact", original.revision, NOW))
        database.close(); open()
        assertTrue(requireNotNull(retries.availability(account, "CONTACT", "contact")).queued)
        assertEquals(1, retries.consume(account, NOW))
        database.outboxDao().upsert(original)
        database.close(); open()
        assertFalse(retries.queue(account, "CONTACT", "contact", original.revision, NOW + 2))
        assertEquals(original, intent("contact"))
    }

    @Test fun aLaterServerDeadlineIsNeverShortenedOrConsumedEarly() = runBlocking {
        val original = blocked("contact")
        database.outboxDao().upsert(original.copy(nextAttemptAtEpochMillis = NOW + 100))
        assertFalse(retries.queue(account, "CONTACT", "contact", original.revision, NOW))
        assertTrue(retries.queue(account, "CONTACT", "contact", original.revision, NOW + 100))
        database.outboxDao().upsert(intent("contact").copy(nextAttemptAtEpochMillis = NOW + 500))
        assertEquals(0, retries.consume(account, NOW + 200))
        assertNull(database.mutationRetryRequestDao().get(account.value, "CONTACT", "contact")?.consumedAtEpochMillis)
        assertEquals(1, retries.consume(account, NOW + 500))
        assertEquals(NOW + 500, intent("contact").nextAttemptAtEpochMillis)
    }

    @Test fun staleRevisionReboundIdentityAndForeignAccountCannotConsumeARequest() = runBlocking {
        val original = blocked("contact")
        assertFalse(retries.queue(AccountScope("foreign-account"), "CONTACT", "contact", original.revision, NOW))
        assertFalse(retries.queue(account, "CONTACT", "contact", original.revision + 1, NOW))
        assertTrue(retries.queue(account, "CONTACT", "contact", original.revision, NOW))
        val entity = requireNotNull(database.contactDao().getEntity(account.value, "contact"))
        database.contactDao().upsert(entity.copy(remoteContactId = "rebound"))
        assertEquals(0, retries.consume(account, NOW))
        assertEquals(original, intent("contact"))
    }

    @Test fun authenticationValidationCryptoAndRealConflictsCannotReceiveCredits() = runBlocking {
        for (category in listOf("AUTHENTICATION_REQUIRED", "VALIDATION_REJECTED", "CRYPTOGRAPHIC_VERIFICATION_FAILED", "CONFLICT")) {
            val original = blocked(category, category)
            assertNull(retries.availability(account, "CONTACT", category))
            assertFalse(retries.queue(account, "CONTACT", category, original.revision, NOW))
        }
        val original = blocked("conflicted")
        val canonical = requireNotNull(database.contactDao().getEntity(account.value, "conflicted"))
        database.contactDao().upsert(canonical.copy(conflictState = "REMOTE_UPDATE_CONFLICT"))
        assertFalse(retries.queue(account, "CONTACT", "conflicted", original.revision, NOW))
    }

    @Test fun deletionAndGroupIntentsCanRetryWithoutOpeningAnEditor() = runBlocking {
        blocked("deleted")
        repository.deleteContact(account.value, "deleted")
        val deletion = intent("deleted").copy(state = "ACTION_REQUIRED", blockedReason = "REMOTE_ACTION_REQUIRED",
            errorCategory = "NETWORK_UNAVAILABLE", attemptCount = 8)
        database.outboxDao().upsert(deletion)
        assertTrue(requireNotNull(database.contactDao().getEntity(account.value, "deleted")).isDeleted)
        assertTrue(retries.queue(account, "CONTACT", "deleted", deletion.revision, NOW))
        assertTrue(repository.saveGroup(ContactGroup(account.value, "group", "Synthetic group", remoteLabelId = "remote-group",
            remoteVersion = "baseline")) is SaveResult.Saved)
        val group = requireNotNull(database.outboxDao().get(account.value, "GROUP", "group")).copy(state = "ACTION_REQUIRED",
            blockedReason = "REMOTE_ACTION_REQUIRED", errorCategory = "TIMEOUT", attemptCount = 8, requiresReconciliation = true)
        database.outboxDao().upsert(group)
        assertTrue(retries.queue(account, "GROUP", "group", group.revision, NOW))
        assertEquals(2, retries.consume(account, NOW))
        assertEquals("DELETE", intent("deleted").operation)
        assertEquals(group.copy(state = "PENDING", blockedReason = null), database.outboxDao().get(account.value, "GROUP", "group"))
    }

    @Test fun capturedCreateVerificationCanRetryWithoutClearingTheUnderlyingConflict() = runBlocking {
        blocked("captured", "RECONCILIATION_INCOMPLETE")
        val local = requireNotNull(database.contactDao().get(account.value, "captured")).toDomain()
            .copy(remoteVCardUid = "synthetic-captured-uid")
        val unverified = intent("captured").copy(remoteVersion = null, requiresReconciliation = true)
        database.contactDao().upsert(local.copy(remoteVersion = null).toEntity())
        database.outboxDao().upsert(unverified)
        val verified = VerifiedContactCard(RemoteContactId(requireNotNull(local.remoteContactId)), RemoteVersion("verified-create"),
            local.copy(id = requireNotNull(local.remoteContactId), remoteVersion = "verified-create"))
        RoomContactConflictStore(database).capture(local.copy(remoteVersion = null), unverified, verified)
        database.outboxDao().upsert(intent("captured").copy(blockedReason = "REMOTE_ACTION_REQUIRED",
            errorCategory = "RECONCILIATION_INCOMPLETE"))
        assertEquals(SyncMutationRetryReason.VERIFICATION, retries.availability(account, "CONTACT", "captured")?.reason)
        assertTrue(retries.queue(account, "CONTACT", "captured", local.revision, NOW))
        assertEquals(1, retries.consume(account, NOW))
        assertEquals("REMOTE_UPDATE_CONFLICT", database.contactDao().getEntity(account.value, "captured")?.conflictState)
        assertTrue(intent("captured").requiresReconciliation)
        assertNull(intent("captured").remoteVersion)
        assertEquals(8, intent("captured").attemptCount)
        assertNotNull(database.contactConflictDao().get(account.value, "captured"))
        val execution = RoomMutationExecutionStore(database)
        val command = execution.eligible(account.value, NOW, 100).single()
        assertTrue(execution.claim(command, NOW))
        val recovery = RoomAmbiguousContactCreateRecovery(account, database,
            ProtonContactInventoryGateway { _, _ -> GatewayOutcome.Success(ContactInventoryPage(listOf(ContactInventoryMetadata(
                verified.id, null, RemoteVersion("public-baseline"), null, null, emptyList(), emptyList(),
                ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT, ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY)),
                null, null, 1, ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY)) },
            ProtonVerifiedContactCardGateway { _, _ -> GatewayOutcome.Success(verified) })
        assertEquals(MutationPreparation.ResolvedWithoutUpload, (recovery.prepare(command) as GatewayOutcome.Success).value)
        assertNull(database.outboxDao().get(account.value, "CONTACT", "captured"))
        assertNull(database.contactConflictDao().get(account.value, "captured"))
        assertNull(database.contactDao().getEntity(account.value, "captured")?.pendingMutationRevision)
    }

    @Test fun exhaustedNetworkFailureAllowsOneDailyProbeAcrossRestartAndPreservesAmbiguity() = runBlocking {
        val original = blocked("daily")
        val execution = RoomMutationExecutionStore(database)
        database.outboxDao().upsert(original.copy(state = "IN_FLIGHT", blockedReason = null))
        val command = DurableMutationCommand(account.value, "CONTACT", "daily", original.revision,
            RemoteMutationOperation.UPDATE, 8, NOW - 1, true, original.remoteIdentity, original.remoteVersion)
        assertTrue(execution.recordFailure(command, GatewayFailureCategory.TIMEOUT,
            RetryDecision.Exhausted(NOW, NOW, true)))
        database.close(); open()
        val day = MutationRetryPolicy.DAILY_RETRY_MILLIS
        assertEquals(0, retries.consume(account, NOW + day - 1))
        assertEquals(1, retries.consume(account, NOW + day))
        assertEquals(0, retries.consume(account, NOW + day))
        assertEquals(8, intent("daily").attemptCount)
        assertTrue(intent("daily").requiresReconciliation)
        assertEquals(original.remoteIdentity, intent("daily").remoteIdentity)
        database.outboxDao().upsert(intent("daily").copy(state = "ACTION_REQUIRED", blockedReason = "REMOTE_ACTION_REQUIRED"))
        assertEquals(0, retries.consume(account, NOW + day + 1))
        assertEquals(1, retries.consume(account, NOW + day * 2))
    }

    @Test fun dailyProbesHonorLongServerDelayAndNeverReopenUnverifiedOrPermanentFailures() = runBlocking {
        val day = MutationRetryPolicy.DAILY_RETRY_MILLIS
        val original = blocked("server")
        database.outboxDao().upsert(original.copy(nextAttemptAtEpochMillis = NOW + day * 3))
        retries.consume(account, NOW) // Legacy row gets a durable conservative anchor.
        assertEquals(0, retries.consume(account, NOW + day))
        assertEquals(0, retries.consume(account, NOW + day * 3 - 1))
        assertEquals(1, retries.consume(account, NOW + day * 3))
        for (category in listOf("RECONCILIATION_INCOMPLETE", "RECONCILIATION_MATCH_UNVERIFIED", "VALIDATION_REJECTED", "CONFLICT", "AUTHENTICATION_REQUIRED")) {
            blocked(category, category)
        }
        assertEquals(0, retries.consume(account, NOW + day * 4))
        assertEquals(0, retries.consume(account, NOW + day * 5))
        assertTrue(intent("RECONCILIATION_INCOMPLETE").requiresReconciliation)
    }

    @Test fun dependencyDeferralRetainsLastFailureAndDoesNotSpendRetryBudget() = runBlocking {
        val original = blocked("deferred").copy(state = "IN_FLIGHT", blockedReason = null)
        database.outboxDao().upsert(original)
        val command = DurableMutationCommand(account.value, "CONTACT", "deferred", original.revision,
            RemoteMutationOperation.UPDATE, original.attemptCount, original.nextAttemptAtEpochMillis, true,
            original.remoteIdentity, original.remoteVersion)
        assertTrue(RoomMutationExecutionStore(database).defer(command))
        assertEquals(original.copy(state = "PENDING"), intent("deferred"))
        assertFalse(RoomMutationExecutionStore(database).defer(command))
    }

    @Test fun visibleMatchRetryKeepsItsTypedCauseServerDeadlineAndManualCooldownAcrossRestart() = runBlocking {
        val original = blocked("visible", "RECONCILIATION_MATCH_UNVERIFIED")
        val canonical = requireNotNull(database.contactDao().getEntity(account.value, "visible"))
        database.contactDao().upsert(canonical.copy(remoteContactId = null, remoteVersion = null,
            remoteVCardUid = "synthetic-visible-match-uid"))
        database.outboxDao().upsert(original.copy(remoteIdentity = null, remoteVersion = null,
            nextAttemptAtEpochMillis = NOW + 100))
        assertEquals(SyncMutationRetryReason.MATCH_VERIFICATION, retries.availability(account, "CONTACT", "visible")?.reason)
        assertFalse(retries.queue(account, "CONTACT", "visible", original.revision, NOW))
        assertTrue(retries.queue(account, "CONTACT", "visible", original.revision, NOW + 100))
        database.outboxDao().upsert(intent("visible").copy(nextAttemptAtEpochMillis = NOW + 500))
        database.close(); open()
        assertTrue(requireNotNull(retries.availability(account, "CONTACT", "visible")).queued)
        assertEquals(0, retries.consume(account, NOW + 200))
        assertNull(database.mutationRetryRequestDao().get(account.value, "CONTACT", "visible")?.consumedAtEpochMillis)
        assertEquals(1, retries.consume(account, NOW + 500))
        var uploads = 0
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            MutationPreparationGateway { GatewayOutcome.Failure(GatewayFailureCategory.RECONCILIATION_MATCH_UNVERIFIED) },
            MutationUploadGateway { uploads++; error("Visible unverified match cannot authorize a POST") })
        assertEquals(1, runner.drain(account.value, NOW + 500).actionRequired)
        assertEquals(0, uploads)
        assertEquals(8, intent("visible").attemptCount)
        assertTrue(intent("visible").requiresReconciliation)
        assertEquals("RECONCILIATION_MATCH_UNVERIFIED", intent("visible").errorCategory)
        assertNull(database.contactConflictDao().get(account.value, "visible"))
        assertNull(RoomAmbiguousContactCreateChoiceStore(database).offerRevision(account, "visible"))
        assertFalse(retries.queue(account, "CONTACT", "visible", original.revision, NOW + 501))
        assertEquals(NOW + 100 + RoomMutationRetryRequestStore.COOLDOWN_MILLIS,
            retries.availability(account, "CONTACT", "visible")?.nextEligibleAtEpochMillis)
        val terminal = intent("visible")
        assertEquals(0, retries.consume(account, NOW + MutationRetryPolicy.DAILY_RETRY_MILLIS * 2))
        assertEquals(terminal, intent("visible"))
    }

    private suspend fun blocked(id: String, category: String = "NETWORK_UNAVAILABLE"): OutboxMutationEntity {
        assertTrue(repository.saveContact(CanonicalContact(account.value, id, displayName = "Synthetic retry", remoteContactId = "remote-$id",
            remoteVersion = "baseline")) is SaveResult.Saved)
        return intent(id).copy(state = "ACTION_REQUIRED", blockedReason = "REMOTE_ACTION_REQUIRED", errorCategory = category,
            attemptCount = 8, nextAttemptAtEpochMillis = NOW - 1, requiresReconciliation = true).also { database.outboxDao().upsert(it) }
    }
    private suspend fun intent(id: String) = requireNotNull(database.outboxDao().get(account.value, "CONTACT", id))
    private companion object { const val NAME = "mutation-explicit-retry.db"; const val NOW = 100_000L }
}
