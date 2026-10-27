package com.patmanak.contako.data.sync

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipAvailability
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshot
import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidStableRawContactObservationPage
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.local.*
import com.patmanak.contako.data.proton.ContactInventoryBaseline
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.domain.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queued scoped requests through the production pass executor, recovery coordinator and file Room.
 * Provider readers/item receipts and the verified-card port are controlled; no Android account,
 * system contact, authenticated crypto, real HTTP, process death or OEM photo write is exercised.
 */
@RunWith(AndroidJUnit4::class)
class TargetedRecoveryCompositionDeviceTest {
    private val application: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "targeted-composition-${SystemClock.elapsedRealtimeNanos()}.db"
    private lateinit var database: ContakoDatabase
    private var now = 0L
    private var permissionDenied = false
    private var loseAndroidReceiptReturn = false
    private var loseVerifiedCommitReturn = false
    private var membershipFault = MembershipFault.NONE
    private var providerWrites = 0
    private var providerGroupReads = 0
    private var remoteReads = 0
    private val projectedIds = mutableListOf<String>()
    private val providerRows = linkedMapOf<String, String>()
    private var cardOutcome: GatewayOutcome<VerifiedContactCard> = GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN)
    private val store get() = RoomTargetedRecoveryStore(database)

    @Before fun setup() = runBlocking<Unit> {
        database = ContakoDatabase.create(application, databaseName)
        val ledger = RoomAndroidProjectionLedger(database)
        val binding = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, binding.revision, ANDROID)
        insert(fixture(WITNESS))
        ledger.attachCanonicalContact(ACCOUNT, WITNESS, "remote-$WITNESS")
        writeControlledReceipt(WITNESS, 43)
        providerRows[WITNESS] = "owned-witness-unchanged"
        RoomSyncStatusStore(database).publish(ACCOUNT.value, 42, SyncStatusUpdate(SyncPassOutcome.SUCCESS, 0, 0))
    }

    @After fun cleanup() {
        if (::database.isInitialized && database.isOpen) database.close()
        application.deleteDatabase(databaseName)
    }

    @Test fun permissionRefusalReopensAndExplicitEligibleRetryProjectsOnlyItsQueuedTarget() = runBlocking {
        insert(fixture(TARGET))
        insert(fixture(DELAYED))
        queue(TargetedRecoveryKind.ANDROID_COPY, DELAYED)
        val delayedClaim = requireNotNull(store.claim(request(TargetedRecoveryKind.ANDROID_COPY, DELAYED), now))
        store.finish(delayedClaim, TargetedRecoveryWorkResult.Failed(GatewayFailureCategory.RATE_LIMITED,
            retryAfterMillis = 120_000), now) { false }
        val delayedBefore = request(TargetedRecoveryKind.ANDROID_COPY, DELAYED)
        queue(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        val witnessBefore = witness()
        val statusBefore = RoomSyncStatusStore(database).load(ACCOUNT.value)
        val original = RoomContactRepository(database).getContact(ACCOUNT.value, TARGET)
        permissionDenied = true

        assertEquals(SyncPassOutcome.ACTION_REQUIRED, executor().execute(targetedRequest()))
        val refused = request(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        assertEquals("ACTION_REQUIRED", refused.state)
        assertEquals(TargetedRecoveryPrerequisite.ANDROID_PERMISSION.name, refused.prerequisite)
        assertEquals(1, refused.attemptCount)
        assertEquals(0, providerGroupReads)
        assertEquals(0, providerWrites)
        assertEquals(delayedBefore, request(TargetedRecoveryKind.ANDROID_COPY, DELAYED))
        assertEquals(witnessBefore, witness())
        assertEquals(original, RoomContactRepository(database).getContact(ACCOUNT.value, TARGET))

        reopen()
        permissionDenied = false
        now = refused.nextEligibleAt - 1
        assertEquals(TargetedRecoveryQueueResult.ALREADY_PENDING,
            store.queue(ACCOUNT, store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, TARGET), now))
        executor().execute(targetedRequest())
        assertEquals(refused, request(TargetedRecoveryKind.ANDROID_COPY, TARGET))
        assertTrue(projectedIds.isEmpty())

        now = refused.nextEligibleAt
        queue(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        assertEquals(SyncPassOutcome.SUCCESS, executor().execute(targetedRequest()))
        val completed = request(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        assertEquals("RESOLVED", completed.state)
        assertEquals(2, completed.attemptCount)
        assertEquals(listOf(TARGET), projectedIds)
        assertEquals(1, providerWrites)
        assertEquals(1, providerGroupReads)
        assertEquals("CLEAN", database.androidProjectionLedgerDao().get(ACCOUNT.value, TARGET)?.projectionState)
        assertEquals(TargetedRecoveryPrerequisite.NO_OBLIGATION,
            store.preview(ACCOUNT, TargetedRecoveryKind.ANDROID_COPY, TARGET).prerequisite)
        assertEquals(original, RoomContactRepository(database).getContact(ACCOUNT.value, TARGET))
        assertEquals(delayedBefore, request(TargetedRecoveryKind.ANDROID_COPY, DELAYED))
        assertEquals(witnessBefore, witness())
        assertEquals(statusBefore, RoomSyncStatusStore(database).load(ACCOUNT.value))
        assertEquals(0, remoteReads)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test fun lostReturnAfterControlledAndroidReceiptsResolvesAfterReopenWithoutRepeatingTheWrite() = runBlocking {
        insert(fixture(TARGET))
        queue(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        val witnessBefore = witness()
        val statusBefore = RoomSyncStatusStore(database).load(ACCOUNT.value)
        val original = RoomContactRepository(database).getContact(ACCOUNT.value, TARGET)
        loseAndroidReceiptReturn = true
        assertEquals(SyncPassOutcome.CANCELLED, executor().execute(targetedRequest()))
        val interrupted = request(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        assertEquals("RUNNING", interrupted.state)
        assertNotNull(interrupted.claimToken)
        assertEquals(1, interrupted.attemptCount)
        assertEquals(1, providerWrites)
        assertEquals("CLEAN", database.androidProjectionLedgerDao().get(ACCOUNT.value, TARGET)?.projectionState)
        val receipt = database.androidProjectionLedgerDao().get(ACCOUNT.value, TARGET)
        val membershipReceipt = membershipProof(TARGET)
        assertCurrentMembershipProof(TARGET)
        val groupsReadBefore = providerGroupReads

        reopen()
        assertEquals(membershipReceipt, membershipProof(TARGET))
        assertCurrentMembershipProof(TARGET)
        now = interrupted.nextEligibleAt - 1
        executor().execute(targetedRequest())
        val deferred = request(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        assertEquals("DEFERRED", deferred.state)
        assertNull(deferred.claimToken)
        assertEquals(interrupted.nextEligibleAt, deferred.nextEligibleAt)
        assertEquals(interrupted.attemptCount, deferred.attemptCount)
        assertEquals(1, providerWrites)

        now = deferred.nextEligibleAt
        assertEquals(SyncPassOutcome.SUCCESS, executor().execute(targetedRequest()))
        assertEquals("RESOLVED", request(TargetedRecoveryKind.ANDROID_COPY, TARGET).state)
        assertEquals(2, request(TargetedRecoveryKind.ANDROID_COPY, TARGET).attemptCount)
        repeat(2) { executor().execute(targetedRequest()) }
        assertEquals(listOf(TARGET), projectedIds)
        assertEquals(1, providerWrites)
        assertEquals(groupsReadBefore, providerGroupReads)
        assertEquals(receipt, database.androidProjectionLedgerDao().get(ACCOUNT.value, TARGET))
        assertEquals(membershipReceipt, membershipProof(TARGET))
        assertCurrentMembershipProof(TARGET)
        assertEquals(original, RoomContactRepository(database).getContact(ACCOUNT.value, TARGET))
        assertEquals(witnessBefore, witness())
        assertEquals(statusBefore, RoomSyncStatusStore(database).load(ACCOUNT.value))
        assertEquals(0, remoteReads)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test fun interruptedCleanContactWithoutMembershipBaselineCannotFalselyResolveAfterReopen() = runBlocking {
        incompleteMembershipProofCannotResolve(MembershipFault.MISSING_BASELINE)
    }

    @Test fun interruptedCleanContactWithStaleMembershipReadbackCannotFalselyResolveAfterReopen() = runBlocking {
        incompleteMembershipProofCannotResolve(MembershipFault.STALE_READBACK)
    }

    private suspend fun incompleteMembershipProofCannotResolve(fault: MembershipFault) {
        insert(fixture(TARGET))
        queue(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        val witnessBefore = witness()
        val original = RoomContactRepository(database).getContact(ACCOUNT.value, TARGET)
        val statusBefore = RoomSyncStatusStore(database).load(ACCOUNT.value)
        membershipFault = fault
        loseAndroidReceiptReturn = true
        assertEquals(SyncPassOutcome.CANCELLED, executor().execute(targetedRequest()))
        val interrupted = request(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        assertEquals("RUNNING", interrupted.state)
        assertEquals(1, interrupted.attemptCount)
        assertEquals("CLEAN", database.androidProjectionLedgerDao().get(ACCOUNT.value, TARGET)?.projectionState)
        val incomplete = membershipProof(TARGET)
        assertIncompleteMembershipProof(fault)

        reopen()
        assertEquals(incomplete, membershipProof(TARGET))
        now = interrupted.nextEligibleAt
        executor().execute(targetedRequest())
        val after = request(TargetedRecoveryKind.ANDROID_COPY, TARGET)
        assertFalse("Contact CLEAN without current membership proof must not resolve an interrupted request ($fault)",
            after.state == "RESOLVED")
        assertTrue("The missing/stale proof remains an obligation", store.preview(ACCOUNT,
            TargetedRecoveryKind.ANDROID_COPY, TARGET).prerequisite != TargetedRecoveryPrerequisite.NO_OBLIGATION)
        assertIncompleteMembershipProof(fault)
        assertEquals(original, RoomContactRepository(database).getContact(ACCOUNT.value, TARGET))
        assertEquals(witnessBefore, witness())
        assertEquals(statusBefore, RoomSyncStatusStore(database).load(ACCOUNT.value))
        assertTrue(projectedIds.all { it == TARGET })
        assertEquals(0, remoteReads)
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    @Test fun refusedProtonRecheckThenLostCommitReturnRetainsVerifiedFieldsWithoutAnotherFetch() = runBlocking {
        RoomRemoteHydrationObligationStore(database).defer(ACCOUNT,
            ContactInventoryBaseline(RemoteContactId(TARGET), "Synthetic rejected", RemoteVersion("index-version"),
                null, null, emptyList(), emptyList()), GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE))
        queue(TargetedRecoveryKind.PROTON_CARD, TARGET)
        val witnessBefore = witness()
        val statusBefore = RoomSyncStatusStore(database).load(ACCOUNT.value)
        cardOutcome = GatewayOutcome.Failure(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED)
        assertEquals(SyncPassOutcome.ACTION_REQUIRED, executor().execute(targetedRequest()))
        val refused = request(TargetedRecoveryKind.PROTON_CARD, TARGET)
        assertEquals("ACTION_REQUIRED", refused.state)
        assertEquals(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED.name, refused.failureCategory)
        assertNull(database.contactDao().getEntity(ACCOUNT.value, TARGET))
        assertEquals(1, remoteReads)
        assertEquals(0, providerWrites)

        reopen()
        now = refused.nextEligibleAt
        queue(TargetedRecoveryKind.PROTON_CARD, TARGET)
        val verified = verifiedCard()
        cardOutcome = GatewayOutcome.Success(verified)
        loseVerifiedCommitReturn = true
        assertEquals(SyncPassOutcome.CANCELLED, executor().execute(targetedRequest()))
        val committed = request(TargetedRecoveryKind.PROTON_CARD, TARGET)
        assertEquals("RESOLVED", committed.state)
        assertNull(committed.failureCategory)
        assertNull(database.remoteHydrationObligationDao().get(ACCOUNT.value, TARGET))
        val adopted = requireNotNull(RoomContactRepository(database).getContact(ACCOUNT.value, TARGET))
        // A new adoption sets its local revision and observation time. Identity/version come
        // from the verified gateway result; every other canonical header and field stays exact.
        assertEquals(verified.contact.copy(revision = 1, updatedAtEpochMillis = adopted.updatedAtEpochMillis,
            remoteContactId = verified.id.value, remoteVersion = verified.version?.value), adopted)
        assertEquals(verified.contact.firstName, adopted.firstName)
        assertEquals(verified.contact.lastName, adopted.lastName)
        assertEquals(verified.contact.displayName, adopted.displayName)
        assertEquals(verified.id.value, adopted.remoteContactId)
        assertEquals(verified.version?.value, adopted.remoteVersion)
        assertEquals(verified.contact.remoteVCardUid, adopted.remoteVCardUid)
        assertEquals(verified.contact.values, adopted.values)
        assertEquals(verified.contact.preservationEnvelope, adopted.preservationEnvelope)
        assertEquals("Recovered note: Zoë Groß\nKeep punctuation, and semicolon;", adopted.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals(2, remoteReads)

        reopen()
        repeat(2) { assertEquals(SyncPassOutcome.SUCCESS, executor().execute(targetedRequest())) }
        assertEquals(committed, request(TargetedRecoveryKind.PROTON_CARD, TARGET))
        assertEquals(adopted, RoomContactRepository(database).getContact(ACCOUNT.value, TARGET))
        assertEquals(2, remoteReads)
        assertEquals(0, providerWrites)
        assertEquals(0, providerGroupReads)
        assertEquals(witnessBefore, witness())
        assertEquals(statusBefore, RoomSyncStatusStore(database).load(ACCOUNT.value))
        assertTrue(database.outboxDao().getAll(ACCOUNT.value).isEmpty())
    }

    private suspend fun queue(kind: TargetedRecoveryKind, id: String) {
        val preview = store.preview(ACCOUNT, kind, id)
        assertTrue(preview.canQueue)
        assertEquals(TargetedRecoveryQueueResult.QUEUED, store.queue(ACCOUNT, preview, now))
    }

    private suspend fun request(kind: TargetedRecoveryKind, id: String) =
        requireNotNull(database.targetedRecoveryRequestDao().get(ACCOUNT.value, kind.name, id))

    private fun executor(): ContakoSyncPassExecutor {
        val preflight = AndroidInteroperabilityPreflight { account, _ ->
            assertEquals(ACCOUNT, account)
            if (permissionDenied) AndroidInteroperabilityPreflightResult.PermissionDenied else {
                val binding = requireNotNull(database.androidProjectionLedgerDao().getAccount(account.value))
                AndroidInteroperabilityPreflightResult.Ready(AndroidInteroperabilityContext(account, ANDROID,
                    binding.revision, binding.providerEpoch))
            }
        }
        val projection = RoomBoundedAndroidProjectionCoordinator(database, itemExecutor = { context, entry ->
            assertEquals(ACCOUNT, context.account)
            assertEquals(TARGET, entry.canonicalContactId)
            assertEquals("RUNNING", request(TargetedRecoveryKind.ANDROID_COPY, TARGET).state)
            projectedIds += entry.canonicalContactId
            providerWrites++
            providerRows[entry.canonicalContactId] = "controlled-current-target"
            writeControlledReceipt(entry.canonicalContactId, 42)
            when (membershipFault) {
                MembershipFault.NONE -> Unit
                MembershipFault.MISSING_BASELINE -> database.androidGroupProjectionDao()
                    .deleteMembershipBaseline(ACCOUNT.value, entry.canonicalContactId)
                MembershipFault.STALE_READBACK -> {
                    val receipt = requireNotNull(database.androidGroupProjectionDao()
                        .getMembership(ACCOUNT.value, entry.canonicalContactId))
                    database.androidGroupProjectionDao().updateMembership(receipt.copy(androidBaselineFingerprint = "0".repeat(64)))
                }
            }
            if (loseAndroidReceiptReturn) {
                loseAndroidReceiptReturn = false
                throw CancellationException("Synthetic interruption after controlled receipt")
            }
            AndroidBoundedPageResult.Applied
        }, groupProjectionCoordinator = { error("Scoped recovery must not project the group catalogue") },
            presenceVerifier = { _, _ -> error("Scoped recovery must not traverse clean copies") })
        val stage = BoundedAndroidInteroperabilityStage(
            contactsReader = { _, _ -> error("No account-wide provider traversal") },
            groupsReader = { account, after -> providerGroupReads++; AndroidOwnedGroupRowPage(account, after, emptyList(), null) },
            observationCoordinator = object : AndroidBoundedObservationCoordinator {
                override suspend fun ingestGroups(context: AndroidInteroperabilityContext,
                    pages: List<AndroidOwnedGroupRowPage>) = AndroidBoundedPageResult.Applied
                override suspend fun ingestContacts(context: AndroidInteroperabilityContext,
                    page: AndroidStableRawContactObservationPage): AndroidBoundedPageResult =
                    error("No native source existed before this first scoped projection")
            }, projectionCoordinator = projection,
            exactContactsReader = { _, _ -> error("No unrelated native read") },
        )
        val durableStore = store
        val completion = object : TargetedRecoveryExecutionStore by durableStore {
            override suspend fun completeVerified(request: TargetedRecoveryRequestEntity, card: VerifiedContactCard,
                now: Long, isCancelled: () -> Boolean): TargetedRecoveryWorkResult {
                val result = durableStore.completeVerified(request, card, now, isCancelled)
                if (loseVerifiedCommitReturn) {
                    loseVerifiedCommitReturn = false
                    throw CancellationException("Synthetic interruption after verified Room commit")
                }
                return result
            }
        }
        val targeted = TargetedContactRecovery(completion, ProtonVerifiedContactCardGateway { account, id ->
            assertEquals(ACCOUNT, account)
            assertEquals(TARGET, id.value)
            assertEquals("RUNNING", request(TargetedRecoveryKind.PROTON_CARD, TARGET).state)
            remoteReads++
            cardOutcome
        }, ProductionTargetedAndroidRecovery(database, preflight, stage, projection),
            prepareProton = { request, cancelled -> prepareTargetedProtonRead(database, preflight, stage, request, cancelled) },
            clock = { now })
        val normalRemote = IncrementalRemoteContactStage(
            ProtonContactInventoryGateway { _, _ -> error("No full inventory in a scoped request") },
            ProtonVerifiedContactCardGateway { _, _ -> error("No ordinary hydration") },
            PersistentContactInventoryPlanner(RoomContactInventoryCheckpointStore(database)), RoomRemoteCanonicalReconciliationStore(database))
        return ContakoSyncPassExecutor(ACCOUNT, SyncPrerequisiteChecker { SyncPrerequisiteState.READY }, normalRemote,
            DurableMutationOrchestrator(RoomMutationExecutionStore(database),
                { error("Scoped recovery must not upload unrelated mutations") }, { error("No unrelated preparation") }),
            wallClock = { now }, androidPreflight = preflight, androidStage = stage,
            statusPublisher = { error("A targeted result cannot replace global account health") }, targetedRecovery = targeted)
    }

    /** Provider-boundary double: the production coordinator still verifies both current receipts. */
    private suspend fun writeControlledReceipt(id: String, raw: Long) = database.withTransaction {
        val canonical = requireNotNull(database.contactDao().get(ACCOUNT.value, id)).toDomain()
        val mapper = CanonicalAndroidContactMapper()
        val fingerprint = mapper.fingerprint(mapper.project(canonical)).sha256Hex
        val ledger = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, id))
        database.androidProjectionLedgerDao().update(ledger.copy(rawContactLocator = raw, projectionState = "CLEAN",
            canonicalProjectionFingerprint = fingerprint, androidBaselineFingerprint = fingerprint,
            pendingProjectionFingerprint = null, adoptionState = "ADOPTED"))
        val membership = AndroidGroupMembershipSnapshot.create(ACCOUNT.value, id, null,
            AndroidGroupMembershipAvailability.NO_EMAIL, emptyList())
        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(membership)
        val groupFingerprint = membership.semanticFingerprint().sha256Hex
        database.androidGroupProjectionDao().insertMembership(AndroidGroupMembershipProjectionLedgerEntity(
            ACCOUNT.value, id, 0, ledger.providerEpoch, raw, null, groupFingerprint, groupFingerprint, null, "CLEAN", "NONE"))
        database.androidGroupProjectionDao().upsertMembershipBaseline(AndroidGroupMembershipBaselineEntity(
            ACCOUNT.value, id, AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(encoded).sha256Hex, encoded))
    }

    private suspend fun witness(): List<Any?> {
        val groupBaseline = database.androidGroupProjectionDao().getMembershipBaseline(ACCOUNT.value, WITNESS)
        return listOf(RoomContactRepository(database).getContact(ACCOUNT.value, WITNESS),
            database.androidProjectionLedgerDao().get(ACCOUNT.value, WITNESS),
            database.androidGroupProjectionDao().getMembership(ACCOUNT.value, WITNESS), groupBaseline?.fingerprint,
            groupBaseline?.encodedSnapshot?.toList(), database.contactContentChangesDao().get(ACCOUNT.value, WITNESS),
            providerRows[WITNESS])
    }

    private suspend fun membershipProof(id: String): List<Any?> {
        val receipt = database.androidGroupProjectionDao().getMembership(ACCOUNT.value, id)
        val baseline = database.androidGroupProjectionDao().getMembershipBaseline(ACCOUNT.value, id)
        return listOf(receipt, baseline?.fingerprint, baseline?.encodedSnapshot?.toList())
    }

    private suspend fun assertCurrentMembershipProof(id: String) {
        val contactReceipt = requireNotNull(database.androidProjectionLedgerDao().get(ACCOUNT.value, id))
        val receipt = requireNotNull(database.androidGroupProjectionDao().getMembership(ACCOUNT.value, id))
        val baseline = requireNotNull(database.androidGroupProjectionDao().getMembershipBaseline(ACCOUNT.value, id))
        val decoded = AndroidGroupMembershipSnapshotBinaryCodec.decode(baseline.encodedSnapshot)
        assertEquals("CLEAN", receipt.projectionState)
        assertEquals(contactReceipt.providerEpoch, receipt.providerEpoch)
        assertEquals(contactReceipt.rawContactLocator, receipt.rawContactLocator)
        assertNull(receipt.pendingProjectionFingerprint)
        assertEquals(baseline.fingerprint,
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(baseline.encodedSnapshot).sha256Hex)
        assertEquals(receipt.androidBaselineFingerprint, decoded.semanticFingerprint().sha256Hex)
        assertEquals(ACCOUNT.value, decoded.accountId)
        assertEquals(id, decoded.canonicalContactId)
        assertEquals(AndroidGroupMembershipAvailability.NO_EMAIL, decoded.membershipAvailability)
        assertTrue(decoded.canonicalGroupIds.isEmpty())
    }

    private suspend fun assertIncompleteMembershipProof(fault: MembershipFault) {
        val receipt = requireNotNull(database.androidGroupProjectionDao().getMembership(ACCOUNT.value, TARGET))
        val baseline = database.androidGroupProjectionDao().getMembershipBaseline(ACCOUNT.value, TARGET)
        when (fault) {
            MembershipFault.MISSING_BASELINE -> assertNull(baseline)
            MembershipFault.STALE_READBACK -> assertNotEquals(receipt.androidBaselineFingerprint,
                AndroidGroupMembershipSnapshotBinaryCodec.decode(requireNotNull(baseline).encodedSnapshot).semanticFingerprint().sha256Hex)
            MembershipFault.NONE -> error("Negative case requires an incomplete membership proof")
        }
    }

    private enum class MembershipFault { NONE, MISSING_BASELINE, STALE_READBACK }

    private suspend fun insert(contact: CanonicalContact) = database.withTransaction {
        database.contactDao().upsert(contact.toEntity())
        database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
        contact.preservationEnvelope?.let { database.contactPayloadDao().upsert(it.toEntity(contact.accountId, contact.id)) }
    }

    private fun reopen() {
        database.close()
        database = ContakoDatabase.create(application, databaseName)
    }

    private fun fixture(id: String) = CanonicalContact(ACCOUNT.value, id, displayName = "Synthetic Zoë $id", revision = 1,
        remoteContactId = "remote-$id", values = listOf(
            ContactValue("phone", ContactValueKind.PHONE, "+12025550123", order = 0),
            ContactValue("note", ContactValueKind.NOTE, "Untouched: Straße; ë\nSecond line", order = 0),
            ContactValue("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-SYNTHETIC:exact", order = 0,
                metadata = mapOf("origin" to "synthetic"), preservationKey = "synthetic-opaque"),
        ), preservationEnvelope = PreservationEnvelope(mapOf("X-SYNTHETIC" to "Retained source"), "Synthetic baseline"))

    private fun verifiedCard(): VerifiedContactCard {
        val decoded = ProtonContactVCardCodec().decode(ACCOUNT.value,
            Contact(UserId("synthetic-user"), ContactId(TARGET), "Synthetic recovered", emptyList()),
            listOf(ProtonPlainContactCard(ContactCardType.EncryptedAndSigned,
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:synthetic-recovered\r\nFN:Synthetic recovered\r\n" +
                    "TEL;TYPE=CELL:+12025550124\r\nNOTE:Recovered note: Zoë Groß\\nKeep punctuation\\, and semicolon\\;\r\n" +
                    "X-SYNTHETIC:exact retained source\r\nEND:VCARD\r\n")))
        return VerifiedContactCard(RemoteContactId(TARGET), RemoteVersion("verified-version"), decoded)
    }

    private fun targetedRequest() = SyncPassRequest(setOf(SyncTrigger.TARGETED_RECOVERY), SyncScope.INCREMENTAL,
        0, { 0 }, 0, { 0 })

    companion object {
        private const val WITNESS = "healthy-witness"
        private const val TARGET = "scoped-target"
        private const val DELAYED = "delayed-copy"
        private const val ANDROID = "synthetic-targeted-android"
        private val ACCOUNT = AccountScope("targeted-composition-owner")
    }
}
