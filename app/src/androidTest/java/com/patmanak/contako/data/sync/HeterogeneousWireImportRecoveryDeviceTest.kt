package com.patmanak.contako.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.local.RoomContactInventoryCheckpointStore
import com.patmanak.contako.data.local.RoomContactRepository
import com.patmanak.contako.data.local.RoomMutationExecutionStore
import com.patmanak.contako.data.local.RoomRemoteCanonicalReconciliationStore
import com.patmanak.contako.data.local.RoomRemoteGroupReconciliationStore
import com.patmanak.contako.data.local.RoomRemoteHydrationObligationStore
import com.patmanak.contako.data.proton.ContactEventsDelta
import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY
import com.patmanak.contako.data.proton.PersistentContactInventoryPlanner
import com.patmanak.contako.data.proton.ProtonContactCardCrypto
import com.patmanak.contako.data.proton.ProtonContactEventsGateway
import com.patmanak.contako.data.proton.ProtonContactRemotePort
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.data.proton.ProtonPreparedVCard
import com.patmanak.contako.data.proton.ProtonPublicContactGateway
import com.patmanak.contako.data.proton.ProtonReadyUserProvider
import com.patmanak.contako.data.proton.VersionedContactInventoryCheckpoint
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncPassRequest
import com.patmanak.contako.domain.sync.SyncScope
import com.patmanak.contako.domain.sync.SyncTrigger
import java.util.Collections
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCard
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactEmail
import me.proton.core.contact.domain.entity.ContactEmailId
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactWithCards
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SW-02/10: production public gateway -> real vCard decoder -> ordered pass -> file-backed Room.
 * Plaintext is supplied at the crypto seam. No real cryptography, HTTP, account or provider access.
 */
@RunWith(AndroidJUnit4::class)
class HeterogeneousWireImportRecoveryDeviceTest {
    @Test
    fun decodedImportWithRejectedCardAndUnavailableCatalogRecoversPerEmailEdgesAfterReopen() = runBlocking {
        withFixture { fixture ->
            fixture.rejectCard = true
            fixture.catalogFailure = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
            assertEquals(SyncPassOutcome.ACTION_REQUIRED, fixture.run())
            fixture.assertReads((1..51).toSet())
            fixture.assertContents((1..51).toSet() - 26, expectEdges = false)
            fixture.assertDeferred()
            fixture.assertCatalog(emptySet())
            val healthy = fixture.contacts()
            val checkpoint = requireNotNull(fixture.checkpoint())

            fixture.reopen()
            assertTrue("Healthy import changed across reopen", fixture.contacts() == healthy)
            fixture.assertCheckpointUnchanged(checkpoint)
            fixture.assertDeferred()

            // A missing catalog never means empty memberships: exact remote email metadata survives.
            fixture.catalogFailure = null
            assertEquals(SyncPassOutcome.ACTION_REQUIRED, fixture.run())
            fixture.assertReads((1..51).toSet())
            fixture.assertCatalog(setOf("synthetic-group-a", "synthetic-group-b"))
            fixture.assertContents((1..51).toSet() - 26, expectEdges = true)
            fixture.assertDeferred()
            val beforeCorrection = fixture.contacts()

            fixture.rejectCard = false
            assertEquals(SyncPassOutcome.SUCCESS, fixture.run())
            fixture.assertReads(setOf(26))
            fixture.assertUnchanged(beforeCorrection)
            fixture.assertContents((1..51).toSet(), expectEdges = true)
            fixture.assertSettledNoChange()
        }
    }

    @Test
    fun http429AfterDurableBatchRetainsDecodedCardsAndReplaysWithoutCheckpointOrDuplicate() = runBlocking {
        withFixture { fixture ->
            fixture.rateLimitedCard = true
            assertEquals(SyncPassOutcome.RETRY_WAITING, fixture.run())
            // Window 26..35 is fetched concurrently but MUST NOT be committed after a 429.
            fixture.assertReads((1..35).toSet())
            fixture.assertContents((1..25).toSet(), expectEdges = true)
            assertNull(fixture.checkpoint())
            assertTrue(fixture.pendingObligations().isEmpty())
            val durable = fixture.contacts()

            fixture.reopen()
            assertTrue("Durable batch changed after retry reopen", fixture.contacts() == durable)
            assertNull(fixture.checkpoint())
            fixture.rateLimitedCard = false
            assertEquals(SyncPassOutcome.SUCCESS, fixture.run())
            fixture.assertReads((1..51).toSet())
            fixture.assertContents((1..51).toSet(), expectEdges = true)
            fixture.assertSettledNoChange()
        }
    }

    @Test
    fun unavailableCatalogRetainsKnownGroupsWhileDecodedContactsImportThenNoChangeRecovers() = runBlocking {
        withFixture { fixture ->
            fixture.seedCatalog()
            val groups = fixture.groupSnapshot()
            fixture.catalogFailure = GatewayFailureCategory.RATE_LIMITED
            assertEquals(SyncPassOutcome.RETRY_WAITING, fixture.run())
            fixture.assertReads((1..51).toSet())
            fixture.assertContents((1..51).toSet(), expectEdges = true)
            assertTrue("Unavailable catalog erased or rewrote known groups", fixture.groupSnapshot() == groups)
            val imported = fixture.contacts()
            fixture.reopen()
            assertTrue("Catalog changed across reopen", fixture.groupSnapshot() == groups)
            fixture.catalogFailure = null
            assertEquals(SyncPassOutcome.SUCCESS, fixture.run())
            fixture.assertReads(emptySet())
            fixture.assertUnchanged(imported)
            fixture.assertSettledNoChange()
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { withContext(Dispatchers.IO) { block(fixture) } }
        finally { fixture.closeAndDelete() }
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "synthetic-wire-import-${UUID.randomUUID()}.db"
        private var database = ContakoDatabase.create(context, name)
        private val reads = Collections.synchronizedList(mutableListOf<Int>())
        private var events = 0
        var rejectCard = false
        var rateLimitedCard = false
        var catalogFailure: GatewayFailureCategory? = null
        private var catalogAvailable = false

        private val remote = object : ProtonContactRemotePort {
            override suspend fun inventory(userId: UserId): List<Contact> {
                assertEquals(USER, userId)
                return (51 downTo 1).map(::remoteContact)
            }
            override suspend fun hydrate(userId: UserId, contactId: ContactId): ContactWithCards {
                assertEquals(USER, userId)
                val index = (1..51).single { remoteContact(it).id == contactId }
                reads += index
                if (index == 26 && rateLimitedCard) throw ApiException(ApiResult.Error.Http(
                    httpCode = 429, message = "synthetic", retryAfter = 7.seconds))
                val source = card(index, malformed = index == 26 && rejectCard)
                return ContactWithCards(remoteContact(index), listOf(ContactCard.Signed(source, "synthetic-signature")))
            }
            override suspend fun create(userId: UserId, cards: List<ContactCard>): Contact = error("Unexpected import write")
            override suspend fun update(userId: UserId, contactId: ContactId, cards: List<ContactCard>): Contact =
                error("Unexpected import write")
            override suspend fun delete(userId: UserId, contactId: ContactId): Unit = error("Unexpected import delete")
        }

        private fun gateway() = ProtonPublicContactGateway(ACCOUNT, ProtonReadyUserProvider { USER }, remote,
            object : ProtonContactCardCrypto {
                override suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>): List<ProtonPlainContactCard> {
                    assertEquals(USER, userId)
                    return cards.map { ProtonPlainContactCard(ContactCardType.Signed, (it as ContactCard.Signed).data) }
                }
                override suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard): List<ContactCard> =
                    error("Unexpected import encryption")
            })

        private fun groupsGateway() = object : ProtonContactGroupGateway {
            override fun capabilities() = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE
            override suspend fun list(account: AccountScope): GatewayOutcome<AvailableContactGroups> {
                assertEquals(ACCOUNT, account)
                return catalogFailure?.let { GatewayOutcome.Failure(it, retryAfterMillis =
                    if (it == GatewayFailureCategory.RATE_LIMITED) 7_000 else null) } ?: GatewayOutcome.Success(GROUPS)
            }
            override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create): GatewayOutcome<RemoteContactGroup> =
                error("Unexpected catalog write")
            override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update): GatewayOutcome<RemoteContactGroup> =
                error("Unexpected catalog write")
            override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete): GatewayOutcome<Unit> =
                error("Unexpected catalog delete")
        }

        suspend fun run(): SyncPassOutcome {
            reads.clear()
            val gateway = gateway()
            val remoteStage = IncrementalRemoteContactStage(gateway, ProtonVerifiedContactCardGateway { account, id ->
                gateway.fetch(account, id).also { result ->
                    if (result is GatewayOutcome.Failure && result.category == GatewayFailureCategory.RATE_LIMITED) {
                        // Assert the real ApiException mapping, without another read or raw error logging.
                        assertEquals(7_000L, result.retryAfterMillis)
                    }
                }
            },
                PersistentContactInventoryPlanner(RoomContactInventoryCheckpointStore(database)),
                RoomRemoteCanonicalReconciliationStore(database), existenceGateway = gateway,
                eventsGateway = ProtonContactEventsGateway { account, cursor ->
                    assertEquals(ACCOUNT, account)
                    assertEquals(checkpoint()?.checkpoint?.eventCursor, cursor)
                    GatewayOutcome.Success(ContactEventsDelta("synthetic-wire-event-${++events}", emptySet(),
                        refreshAll = cursor == null))
                }, obligations = RoomRemoteHydrationObligationStore(database))
            val executor = ContakoSyncPassExecutor(ACCOUNT, SyncPrerequisiteChecker { SyncPrerequisiteState.READY },
                remoteStage, DurableMutationOrchestrator(RoomMutationExecutionStore(database),
                    MutationPreparationGateway { error("Import fabricated durable mutation") },
                    MutationUploadGateway { error("Import fabricated upload") }),
                groupStage = IncrementalRemoteGroupStage(groupsGateway(), RoomRemoteGroupReconciliationStore(database)),
                androidPreflight = AndroidInteroperabilityPreflight { account, _ ->
                    AndroidInteroperabilityPreflightResult.Ready(AndroidInteroperabilityContext(account,
                        "synthetic-no-provider", 0, 0))
                }, androidStage = object : AndroidInteroperabilityStage {
                    override suspend fun ingest(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                        AndroidInteroperabilityStageResult.Success
                    override suspend fun project(context: AndroidInteroperabilityContext, isCancelled: () -> Boolean) =
                        AndroidInteroperabilityStageResult.Success
                }, onGroupCatalogAvailability = { catalogAvailable = it })
            return executor.execute(SyncPassRequest(setOf(SyncTrigger.MANUAL), SyncScope.INCREMENTAL, 0, { 0 }, 0, { 0 }))
                .also {
                    assertEquals(catalogFailure == null, catalogAvailable)
                    assertTrue("Import created outbound intent", database.outboxDao().getAll(ACCOUNT.value).isEmpty())
                }
        }

        fun assertReads(expected: Set<Int>) {
            val actual = synchronized(reads) { reads.toList() }
            assertEquals(expected.associateWith { 1 }, actual.groupingBy { it }.eachCount())
        }

        suspend fun contacts(): Map<String, CanonicalContact> {
            val repository = RoomContactRepository(database)
            return repository.observeContacts(ACCOUNT.value).first().associate { summary ->
                summary.id to requireNotNull(repository.getContact(ACCOUNT.value, summary.id))
            }
        }

        suspend fun assertContents(expected: Set<Int>, expectEdges: Boolean) {
            val all = contacts()
            assertEquals(expected.map { remoteContact(it).id.id }.toSet(), all.keys)
            assertEquals(expected.size, all.values.map { it.remoteContactId }.distinct().size)
            val groupRows = database.contactGroupDao().getAll(ACCOUNT.value)
            expected.forEach { index ->
                val contact = all.getValue(remoteContact(index).id.id)
                assertTrue("Decoded display name differs", contact.displayName == display(index))
                assertTrue("Decoded UID differs", contact.remoteVCardUid == "synthetic-wire-uid-$index")
                assertTrue("Private Unicode note differs", contact.valuesOf(ContactValueKind.NOTE).single().value == note(index))
                assertTrue("Retained source card differs", contact.preservationEnvelope?.rawProperties?.values?.toList() == listOf(card(index)))
                val emails = contact.valuesOf(ContactValueKind.EMAIL)
                assertEquals(2, emails.size)
                assertTrue("Repeated email values changed", emails.all { it.value == email(index) })
                emails.forEachIndexed { occurrence, value ->
                    val groupId = if (occurrence == 0) "synthetic-group-a" else "synthetic-group-b"
                    assertTrue("Remote email identity differs", value.metadata[PROTON_EMAIL_ID_KEY] == "synthetic-email-$index-$occurrence")
                    assertTrue("Remote group metadata lost", value.metadata[PROTON_GROUP_IDS_KEY] == groupId)
                    val edges = groupRows.flatMap { group -> group.memberships.filter { edge ->
                        edge.contactId == contact.id && edge.emailValueId == value.id
                    }.map { group.group.remoteLabelId } }
                    assertTrue("Per-email canonical group edge differs", edges == if (expectEdges) listOf(groupId) else emptyList<String>())
                }
            }
        }

        suspend fun assertDeferred() {
            val obligations = database.remoteHydrationObligationDao().getAll(ACCOUNT.value)
            assertEquals(1, obligations.size)
            assertTrue("Wrong rejected contact", obligations.single().remoteContactId == remoteContact(26).id.id)
            assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE.name, obligations.single().category)
            assertEquals(GatewayContactHydrationCategory.VCARD_PARSE_CHARACTERS.name, obligations.single().hydrationCategory)
            assertFalse(contacts().containsKey(remoteContact(26).id.id))
        }

        suspend fun pendingObligations() = database.remoteHydrationObligationDao().getAll(ACCOUNT.value)
        suspend fun checkpoint() = RoomContactInventoryCheckpointStore(database).load(ACCOUNT)
        suspend fun assertCheckpointUnchanged(before: VersionedContactInventoryCheckpoint) {
            val after = requireNotNull(checkpoint())
            // Checkpoint models deliberately have reference equality. Compare every persisted field,
            // including per-email edges, without reusing the production planner's equality helpers.
            assertEquals(before.generation, after.generation)
            assertTrue("Import cursor changed across reopen", before.checkpoint.eventCursor == after.checkpoint.eventCursor)
            val expected = before.checkpoint.entries
            val actual = after.checkpoint.entries
            assertTrue("Checkpoint identities changed", expected.map { it.id } == actual.map { it.id })
            expected.zip(actual).forEach { (old, current) ->
                assertTrue("Checkpoint display name changed", old.displayName == current.displayName)
                assertTrue("Checkpoint version changed", old.version == current.version)
                assertTrue("Checkpoint size changed", old.sizeBytes == current.sizeBytes)
                assertTrue("Checkpoint modification date changed", old.modifiedAtEpochSeconds == current.modifiedAtEpochSeconds)
                assertTrue("Checkpoint groups changed", old.groupIds == current.groupIds)
                val oldEdges = old.emailGroupMemberships.map { it.emailId to it.groupIds.sortedBy { group -> group.value } }
                val currentEdges = current.emailGroupMemberships.map { it.emailId to it.groupIds.sortedBy { group -> group.value } }
                assertTrue("Checkpoint per-email groups changed", oldEdges == currentEdges)
            }
        }
        suspend fun groupSnapshot() = database.contactGroupDao().getAll(ACCOUNT.value).map { it.group }
        suspend fun seedCatalog() = RoomRemoteGroupReconciliationStore(database).commit(ACCOUNT, GROUPS)
        suspend fun assertCatalog(ids: Set<String>) = assertEquals(ids,
            groupSnapshot().mapNotNull { it.remoteLabelId }.toSet())
        suspend fun assertUnchanged(before: Map<String, CanonicalContact>) {
            val after = contacts()
            before.forEach { (id, contact) -> assertTrue("Unchanged witness differs", after[id] == contact) }
        }
        suspend fun assertSettledNoChange() {
            assertTrue(pendingObligations().isEmpty())
            val before = contacts()
            repeat(2) {
                assertEquals(SyncPassOutcome.SUCCESS, run())
                assertReads(emptySet())
                assertTrue("No-change import rewrote canonical data", before == contacts())
                assertContents((1..51).toSet(), expectEdges = true)
                assertTrue(pendingObligations().isEmpty())
            }
        }
        fun reopen() { database.close(); database = ContakoDatabase.create(context, name) }
        fun closeAndDelete() {
            try { database.close() }
            finally { assertTrue("Isolated wire fixture cleanup failed", context.deleteDatabase(name)) }
        }
    }

    private companion object {
        val ACCOUNT = AccountScope("synthetic-wire-import-owner")
        val USER = UserId("synthetic-wire-user")
        val GROUPS = AvailableContactGroups(listOf(
            RemoteContactGroup(RemoteGroupId("synthetic-group-a"), "Synthetic group A", "#8080FF"),
            RemoteContactGroup(RemoteGroupId("synthetic-group-b"), "Synthetic group B", "#8080FF")))
        fun display(index: Int) = "Synthetic $index Groß Chloë 東京"
        fun email(index: Int) = "synthetic-$index@example.invalid"
        fun note(index: Int) = "Synthetic note $index\nStraße, Chloë; 東京"
        fun remoteContact(index: Int): Contact {
            val id = ContactId("synthetic-wire-${index.toString().padStart(3, '0')}")
            return Contact(userId = USER, id = id, name = display(index), contactEmails = (0..1).map { occurrence -> ContactEmail(
                userId = USER, id = ContactEmailId("synthetic-email-$index-$occurrence"),
                name = display(index), email = email(index), defaults = 1, order = occurrence,
                contactId = id, canonicalEmail = null,
                labelIds = listOf(if (occurrence == 0) "synthetic-group-a" else "synthetic-group-b"),
                isProton = false, lastUsedTime = 0) })
        }
        fun card(index: Int, malformed: Boolean = false) = listOf("BEGIN:VCARD", "VERSION:4.0",
            "UID:synthetic-wire-uid-$index", "FN:${display(index)}", "N:Groß;Chloë;;;",
            "EMAIL;TYPE=HOME:${email(index)}", "EMAIL;TYPE=WORK:${email(index)}",
            "NOTE:Synthetic note $index\\nStraße\\, Chloë\\; 東京${if (malformed) "\u202E" else ""}",
            "X-SYNTHETIC;X-SCOPE=preserved:opaque-$index\\,value", "END:VCARD", "").joinToString("\r\n")
    }
}
