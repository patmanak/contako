package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated Room state and codec; no Android accounts, provider operations, or Proton requests. */
@RunWith(AndroidJUnit4::class)
class RoomSubmissionRoundTripRecoveryDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private val remote = mutableListOf<VerifiedContactCard>()
    private var inventoryReads = 0
    private var cardReads = 0

    @Before fun before() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        database = ContakoDatabase.create(context, DB)
    }
    @After fun after() { database.close(); context.deleteDatabase(DB) }

    @Test fun crashBeforeTheSecondPendingClaimCannotReuseTheFirstContactsAbsenceProof() = runBlocking {
        val first = seed("first", inFlight = true)
        val second = seed("second")
        assertSame(MutationPreparation.UploadAllowed, success(helper().prepare(command(first.id))))
        assertFalse(intent(first.id).requiresReconciliation)
        assertTrue("Unclaimed pending work must retain its original ambiguous delivery", intent(second.id).requiresReconciliation)
        database.close()
        database = ContakoDatabase.create(context, DB)
        remote += delivered(second, "arrived-after-first-proof")
        val store = RoomMutationExecutionStore(database)
        store.recoverInterrupted(ACCOUNT.value, 10_000)
        val current = store.eligible(ACCOUNT.value, 10_000, 100).single { it.aggregateId == second.id }
        assertTrue(current.requiresReconciliation)
        assertTrue(store.claim(current, 10_000))
        assertSame(MutationPreparation.ResolvedWithoutUpload, success(helper().prepare(current)))
        val actual = database.contactDao().getEntity(ACCOUNT.value, second.id)!!
        assertEquals("arrived-after-first-proof", actual.remoteContactId)
        assertNull(actual.pendingMutationRevision)
        assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", second.id))
        assertEquals(2, database.contactDao().observeActive(ACCOUNT.value).first().size)
        assertTrue(intent(first.id).requiresReconciliation)
    }

    @Test fun duplicateRemoteUidNeverAutoAcknowledgesAnEquivalentDeliveredContact() = runBlocking {
        val local = seed("duplicate", inFlight = true)
        remote += delivered(local, "remote-one")
        remote += delivered(local, "remote-two")
        val result = success(helper().prepare(command(local.id))) as MutationPreparation.ActionRequired
        assertEquals(MutationPreparationActionRequiredReason.RECONCILIATION_REMOTE_IDENTITY_MISSING, result.reason)
        assertNull(database.contactDao().getEntity(ACCOUNT.value, local.id)!!.remoteContactId)
        assertTrue(intent(local.id).requiresReconciliation)
        assertNotNull(database.contactDao().getEntity(ACCOUNT.value, local.id)!!.pendingMutationRevision)
    }

    @Test fun oneScanPermitPerPassDefersOtherAbsentCreatesWithoutSpendingTheirAttemptBudget() = runBlocking {
        (1..25).forEach { seed("pending-$it") }
        var scanAvailable = false
        var uploads = 0
        val recovery = helper {
            if (!scanAvailable) false else { scanAvailable = false; true }
        }
        val runner = DurableMutationOrchestrator(RoomMutationExecutionStore(database),
            RoomBackedMutationPreparationGateway(ACCOUNT, database,
                ProtonEmailGroupMembershipReader { _, _ -> error("No group request belongs to this fixture") },
                createRecovery = recovery), MutationUploadGateway { request ->
                uploads++
                val local = database.contactDao().get(ACCOUNT.value, request.aggregateId)!!.toDomain()
                val delivered = delivered(local, "created-$uploads")
                remote += delivered
                GatewayOutcome.Success(RemoteMutationAcknowledgement(delivered.id.value, delivered.version!!.value))
            })
        repeat(9) { pass ->
            scanAvailable = true
            val result = runner.drain(ACCOUNT.value, 10_000L + pass)
            assertEquals(0, result.actionRequired)
            assertEquals(pass + 1, uploads)
            assertEquals((pass + 1) * 2, inventoryReads)
            val remaining = database.outboxDao().getAll(ACCOUNT.value)
            assertEquals(24 - pass, remaining.size)
            assertTrue(remaining.all { it.requiresReconciliation && it.attemptCount == 2 && it.state == "PENDING" })
        }
        assertEquals(36, cardReads) // 0 + 1 + ... + 8; no repeated full scan within one pass.
    }

    private fun helper(permit: () -> Boolean = { true }) = RoomAmbiguousContactCreateRecovery(ACCOUNT, database,
        ProtonContactInventoryGateway { _, cursor ->
            assertNull(cursor)
            inventoryReads++
            GatewayOutcome.Success(ContactInventoryPage(remote.map { value -> ContactInventoryMetadata(
                value.id, value.contact.displayName, RemoteVersion("public-${value.id.value}"), null, null,
                emptyList(), emptyList(), ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT,
                ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY,
            ) }, null, null, remote.size, ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY))
        }, ProtonVerifiedContactCardGateway { _, id -> cardReads++; GatewayOutcome.Success(remote.single { it.id == id }) },
        scanPermit = permit)

    private suspend fun seed(id: String, inFlight: Boolean = false): CanonicalContact {
        val contact = CanonicalContact(ACCOUNT.value, id, displayName = "Synthetic $id", revision = 1,
            pendingMutationRevision = 1, remoteVCardUid = "uid-$id", values = listOf(
                ContactValue("note-$id", ContactValueKind.NOTE, "Private note ë ß 東京", order = 0)))
        database.contactDao().upsert(contact.toEntity())
        database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
        database.outboxDao().upsert(OutboxMutationEntity(ACCOUNT.value, "CONTACT", id, "UPSERT", 1, 0, 0,
            attemptCount = 2, idempotencyKey = "synthetic-create-$id", requiresReconciliation = true,
            state = if (inFlight) "IN_FLIGHT" else "PENDING"))
        return contact
    }

    private fun delivered(local: CanonicalContact, id: String): VerifiedContactCard {
        val codec = ProtonContactVCardCodec()
        val encoded = codec.encode(local)
        val dto = me.proton.core.contact.domain.entity.Contact(me.proton.core.domain.entity.UserId("synthetic-user"),
            me.proton.core.contact.domain.entity.ContactId(id), local.displayName, emptyList())
        val readback = codec.decode(ACCOUNT.value, dto, listOf(
            ProtonPlainContactCard(me.proton.core.contact.domain.entity.ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
            ProtonPlainContactCard(me.proton.core.contact.domain.entity.ContactCardType.Signed, encoded.signed),
            ProtonPlainContactCard(me.proton.core.contact.domain.entity.ContactCardType.ClearText, encoded.clear),
        ))
        return VerifiedContactCard(RemoteContactId(id), RemoteVersion("verified-$id"), readback)
    }

    private suspend fun intent(id: String) = database.outboxDao().get(ACCOUNT.value, "CONTACT", id)!!
    private suspend fun command(id: String) = intent(id).let { value -> DurableMutationCommand(ACCOUNT.value,
        "CONTACT", id, value.revision, value.remoteOperation(), value.attemptCount, value.nextAttemptAtEpochMillis,
        value.requiresReconciliation) }
    private fun success(value: GatewayOutcome<MutationPreparation>) = (value as GatewayOutcome.Success).value

    companion object {
        private val ACCOUNT = AccountScope("synthetic-roundtrip-owner")
        private const val DB = "synthetic-roundtrip-recovery.db"
    }
}
