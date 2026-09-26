package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.EmailLabelMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

class ProtonProductionCompositionTest {
    @Test
    fun `Gate D composition exposes all production synchronization ports`() {
        val fields = ProtonGateDComposition::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(
            fields.containsAll(
                setOf(
                    "inventory",
                    "verifiedCards",
                    "contactMutations",
                    "contactCreateStages",
                    "groups",
                    "emailLabels",
                    "membershipReader",
                    "vCardCodec",
                    "richInventoryUnitStatus",
                ),
            ),
        )
        val payload = "BEGIN:VCARD\nFN:DO-NOT-DISCLOSE\nEND:VCARD"
        assertFalse(
            VerifiedWirePageSet(ACCOUNT, 1, listOf(payload), nextPage = 0)
                .toString().contains(payload),
        )
        assertFalse(CompleteWireSnapshot(listOf(payload), "digest").toString().contains(payload))
    }

    @Test
    fun `Gate C audit classifies exact email label mutations as group traffic`() {
        assertEquals(
            GateCRequestClass.GROUP,
            classifyGateCRequest(
                method = "PUT",
                pathSegments = listOf("contacts", "v4", "contacts", "emails", "label"),
            ),
        )
        assertEquals(
            GateCRequestClass.GROUP,
            classifyGateCRequest(
                method = "PUT",
                pathSegments = listOf("contacts", "v4", "contacts", "emails", "unlabel"),
            ),
        )
        assertEquals(
            GateCRequestClass.CONTACT,
            classifyGateCRequest(method = "GET", pathSegments = listOf("contacts", "v4")),
        )
        assertEquals(
            GateCRequestClass.CONTACT,
            classifyGateCRequest(
                method = "GET",
                pathSegments = listOf("contacts", "v4", "contacts", "emails", "label"),
            ),
        )
        assertEquals(
            GateCDataMutationClass.EMAIL_LABEL_ASSIGN,
            classifyGateCDataMutation(
                method = "PUT",
                pathSegments = listOf("contacts", "v4", "contacts", "emails", "label"),
            ),
        )
        assertEquals(
            GateCDataMutationClass.EMAIL_LABEL_REMOVE,
            classifyGateCDataMutation(
                method = "PUT",
                pathSegments = listOf("contacts", "v4", "contacts", "emails", "unlabel"),
            ),
        )
        assertEquals(
            GateCDataMutationClass.CONTACT_CREATE,
            classifyGateCDataMutation("POST", listOf("contacts", "v4", "contacts")),
        )
        assertEquals(
            GateCDataMutationClass.GROUP_DELETE,
            classifyGateCDataMutation("DELETE", listOf("core", "v4", "labels", "group")),
        )
        assertEquals(null, classifyGateCDataMutation("GET", listOf("contacts", "v4")))
        assertEquals(null, classifyGateCDataMutation("DELETE", listOf("auth", "v4")))
    }

    @Test
    fun `Gate D wire routes distinguish maintained reads from replaceable legacy mutations`() {
        val api = ProtonGateDWireApi::class.java
        assertEquals("contacts/v4/contacts", requireNotNull(api.methods.single { it.name == "createContacts" }
            .getAnnotation(POST::class.java)).value)
        assertEquals("contacts/v4", requireNotNull(api.methods.single { it.name == "getRichContacts" }
            .getAnnotation(GET::class.java)).value)
        assertEquals("contacts/v4/contacts/emails", requireNotNull(api.methods.single { it.name == "getContactEmails" }
            .getAnnotation(GET::class.java)).value)
        assertEquals("contacts/v4/contacts/emails/label", api.methods.single { it.name == "labelContactEmails" }
            .getAnnotation(PUT::class.java).let(::requireNotNull).value)
        assertEquals("contacts/v4/contacts/emails/unlabel", api.methods.single { it.name == "unlabelContactEmails" }
            .getAnnotation(PUT::class.java).let(::requireNotNull).value)
    }

    @Test
    fun `bounded response reader rejects declared streamed and malformed UTF8 overflow`() {
        assertEquals("test", "test".toResponseBody().readBoundedUtf8(4))
        assertTrue(runCatching { "tests".toResponseBody().readBoundedUtf8(4) }.isFailure)
        val unknownLength = object : okhttp3.ResponseBody() {
            override fun contentType() = null
            override fun contentLength(): Long = -1
            override fun source() = okio.Buffer().writeUtf8("tests")
        }
        assertTrue(runCatching { unknownLength.readBoundedUtf8(4) }.isFailure)
        assertTrue(runCatching {
            byteArrayOf(0xC3.toByte(), 0x28).toResponseBody().readBoundedUtf8(4)
        }.isFailure)
    }

    @Test
    fun `stable rich inventory performs two complete reads then serves verified continuations`() = runTest {
        val calls = mutableListOf<Int>()
        val responses = ArrayDeque(
            listOf(
                richPage(3, richContact("one", 1), richContact("two", 1)),
                richPage(3, richContact("three", 1)),
                richPage(3, richContact("one", 1), richContact("two", 1)),
                richPage(3, richContact("three", 1)),
            ),
        )
        val stable = SnapshotVerifyingRichInventoryWireTransport { _, page, _ ->
            calls += page
            responses.removeFirst()
        }
        val gateway = ProtonRichInventoryAdapter(ACCOUNT, stable, pageSize = 2)

        val first = gateway.page(ACCOUNT, null).success()
        val second = gateway.page(ACCOUNT, requireNotNull(first.nextCursor)).success()
        val complete = ValidatedCompleteInventory.fromPages(listOf(first, second))

        assertEquals(3, complete.totalCount)
        assertEquals(listOf(0, 1, 0, 1), calls)
        assertEquals(null, second.nextCursor)
        // Wire numbers are copied exactly; seconds/bytes semantics remain a live contract check.
        assertEquals(1L, complete.contacts.single { it.id.value == "one" }.modifiedAtEpochSeconds)
        assertEquals(1L, complete.contacts.single { it.id.value == "one" }.sizeBytes)
        assertEquals(
            ContactInventoryVersionProvenance.REMOTE_SERVER_UNATTESTED,
            complete.contacts.single { it.id.value == "one" }.versionProvenance,
        )
        assertEquals(
            ContactInventoryCoverage.REMOTE_REVISION_UNATTESTED,
            complete.contacts.single { it.id.value == "one" }.coverage,
        )
        val planner = PersistentContactInventoryPlanner(object : ContactInventoryCheckpointStore {
            override suspend fun load(account: AccountScope): VersionedContactInventoryCheckpoint? = null
            override suspend fun compareAndSet(
                account: AccountScope,
                expectedGeneration: Long?,
                checkpoint: ContactInventoryCheckpoint,
            ): Boolean = error("Unattested inventory must be rejected before persistence")
        })
        assertTrue(runCatching { planner.plan(ACCOUNT, complete) }.isFailure)
    }

    @Test
    fun `stable rich inventory rejects cross-pass drift and invalid continuation`() = runTest {
        val drift = ArrayDeque(
            listOf(
                richPage(1, richContact("one", 1)),
                richPage(1, richContact("one", 2)),
            ),
        )
        val gateway = ProtonRichInventoryAdapter(
            ACCOUNT,
            SnapshotVerifyingRichInventoryWireTransport { _, _, _ -> drift.removeFirst() },
            pageSize = 2,
        )
        assertFailure(GatewayFailureCategory.MALFORMED_RESPONSE, gateway.page(ACCOUNT, null))

        val direct = SnapshotVerifyingRichInventoryWireTransport { _, _, _ ->
            richPage(1, richContact("one", 1))
        }
        assertTrue(runCatching { direct.getPage(ACCOUNT, 1, 2) }.isFailure)

        val stablePages = ArrayDeque(
            listOf(
                richPage(2, richContact("one", 1)),
                richPage(2, richContact("two", 1)),
                richPage(2, richContact("one", 1)),
                richPage(2, richContact("two", 1)),
            ),
        )
        val bound = SnapshotVerifyingRichInventoryWireTransport { _, _, _ ->
            stablePages.removeFirst()
        }
        bound.getPage(ACCOUNT, 0, 1)
        assertTrue(runCatching { bound.getPage(AccountScope("other"), 1, 1) }.isFailure)
        assertTrue(runCatching { bound.getPage(ACCOUNT, 1, 2) }.isFailure)

        val cumulativeOverflow = ProtonRichInventoryAdapter(
            ACCOUNT,
            SnapshotVerifyingRichInventoryWireTransport(
                upstream = ProtonRichInventoryWireTransport { _, _, _ ->
                    richPage(1, richContact("one", 1))
                },
                maxCompleteSnapshotBytes = 32,
            ),
            pageSize = 2,
        )
        assertFailure(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            cumulativeOverflow.page(ACCOUNT, null),
        )
    }

    @Test
    fun `snapshot comparison ignores volatile envelope metadata and contact order`() = runTest {
        val responses = ArrayDeque(
            listOf(
                richPage(2, richContact("one", 1), richContact("two", 1))
                    .dropLast(1) + ",\"Time\":1}",
                richPage(2, richContact("two", 1), richContact("one", 1))
                    .dropLast(1) + ",\"Time\":2}",
            ),
        )
        val gateway = ProtonRichInventoryAdapter(
            ACCOUNT,
            SnapshotVerifyingRichInventoryWireTransport { _, _, _ -> responses.removeFirst() },
            pageSize = 2,
        )

        val page = gateway.page(ACCOUNT, null).success()

        assertEquals(2, page.contacts.size)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun `snapshot transport propagates cancellation and fatal errors`() = runTest {
        val cancelled = SnapshotVerifyingRichInventoryWireTransport { _, _, _ ->
            throw CancellationException("synthetic")
        }
        assertTrue(runCatching { cancelled.getPage(ACCOUNT, 0, 2) }.exceptionOrNull() is CancellationException)

        val fatal = SnapshotVerifyingRichInventoryWireTransport { _, _, _ -> throw AssertionError("synthetic") }
        assertTrue(runCatching { fatal.getPage(ACCOUNT, 0, 2) }.exceptionOrNull() is AssertionError)
    }

    @Test
    fun `authoritative membership double reads complete maintained email pages`() = runTest {
        val calls = mutableListOf<Int>()
        val responses = ArrayDeque(
            listOf(
                emailPage(3, email("e1", "group"), email("e2")),
                emailPage(3, email("e3", "group")),
                emailPage(3, email("e1", "group"), email("e2")),
                emailPage(3, email("e3", "group")),
            ),
        )
        val reader = ProtonCoreAuthoritativeEmailGroupMembershipReader(
            expectedAccount = ACCOUNT,
            transport = ProtonContactEmailPageWireTransport { _, page, _ ->
                calls += page
                responses.removeFirst()
            },
            pageSize = 2,
        )

        val membership = reader.members(ACCOUNT, RemoteGroupId("group")).success()

        assertEquals(2, membership.emailIds.size)
        assertEquals(listOf(0, 1, 0, 1), calls)
    }

    @Test
    fun `authoritative membership fails closed on drift account mismatch and cancellation`() = runTest {
        val drift = ArrayDeque(
            listOf(
                emailPage(1, email("e1", "group")),
                emailPage(1, email("e1")),
            ),
        )
        val reader = ProtonCoreAuthoritativeEmailGroupMembershipReader(
            ACCOUNT,
            ProtonContactEmailPageWireTransport { _, _, _ -> drift.removeFirst() },
            pageSize = 2,
        )
        assertFailure(GatewayFailureCategory.MALFORMED_RESPONSE, reader.members(ACCOUNT, RemoteGroupId("group")))
        assertFailure(
            GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            reader.members(AccountScope("other"), RemoteGroupId("group")),
        )

        val cancelled = ProtonCoreAuthoritativeEmailGroupMembershipReader(
            ACCOUNT,
            ProtonContactEmailPageWireTransport { _, _, _ -> throw CancellationException("synthetic") },
            pageSize = 2,
        )
        assertTrue(runCatching {
            cancelled.members(ACCOUNT, RemoteGroupId("group"))
        }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `ambiguous assignment budget is two membership passes before and two after one write`() = runTest {
        var membershipPageCalls = 0
        val responses = ArrayDeque(
            listOf(
                emailPage(1, email("e1")),
                emailPage(1, email("e1")),
                emailPage(1, email("e1", "group")),
                emailPage(1, email("e1", "group")),
            ),
        )
        val reader = ProtonCoreAuthoritativeEmailGroupMembershipReader(
            ACCOUNT,
            ProtonContactEmailPageWireTransport { _, _, _ ->
                membershipPageCalls++
                responses.removeFirst()
            },
            pageSize = 2,
        )
        var writes = 0
        val ambiguousWriter = object : ProtonContactEmailLabelGateway {
            override suspend fun apply(
                account: AccountScope,
                mutation: EmailLabelMutation,
            ): GatewayOutcome<Unit> {
                writes++
                return GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
            }
        }
        val gateway = ReconciledProtonEmailGroupAssignmentGateway(reader, ambiguousWriter)

        val result = gateway.apply(
            ACCOUNT,
            EmailLabelMutation.Assign(RemoteGroupId("group"), listOf(RemoteEmailId("e1"))),
        )

        assertTrue(result is GatewayOutcome.Success)
        assertEquals(4, membershipPageCalls)
        assertEquals(1, writes)
    }

    private companion object {
        val ACCOUNT = AccountScope("primary")
    }
}

private fun richPage(total: Int, vararg contacts: String): String =
    """{"Code":1000,"Total":$total,"Contacts":[${contacts.joinToString(",")}] }"""

private fun richContact(id: String, modifyTime: Long): String =
    """{"ID":"$id","Name":"Fixture","UID":"uid-$id","Size":1,"ModifyTime":$modifyTime,"ContactEmails":[]}"""

private fun emailPage(total: Int, vararg emails: String): String =
    """{"Code":1000,"Total":$total,"ContactEmails":[${emails.joinToString(",")}] }"""

private fun email(id: String, vararg labels: String): String =
    """{"ID":"$id","LabelIDs":[${labels.joinToString(",") { "\"$it\"" }}]}"""

private fun <T> GatewayOutcome<T>.success(): T = (this as GatewayOutcome.Success<T>).value

private fun assertFailure(expected: GatewayFailureCategory, outcome: GatewayOutcome<*>) {
    assertEquals(expected, (outcome as GatewayOutcome.Failure).category)
}
