package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactEmail
import me.proton.core.contact.domain.entity.ContactEmailId
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactWithCards
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.ResponseCodes
import org.junit.Assert.*
import org.junit.Test

class ProtonContactEventResourcesTest {
    private val account = AccountScope("synthetic-account")
    private val user = UserId("synthetic-user")

    @Test fun contactMetadataUsesTheInventoryMappingAndPreservesDuplicateEmailOccurrences() = runTest {
        val contact = contact()
        val requests = mutableListOf<ContactId>()
        val reader = reader(contactLoader = { actualUser, id ->
            assertEquals(user, actualUser)
            requests += id
            ContactWithCards(contact, emptyList())
        })
        val result = (reader.contact(account, RemoteContactId("contact")) as GatewayOutcome.Success).value
        val inventory = toPublicContactInventoryMetadata(contact)
        assertEquals(listOf(ContactId("contact")), requests)
        assertEquals(inventory.version, result.version)
        assertEquals(inventory.coverage, result.coverage)
        assertEquals(inventory.versionProvenance, result.versionProvenance)
        assertEquals(listOf("email-a", "email-b"), result.emailIds.map { it.value })
        assertEquals(listOf(listOf("group-a"), listOf("group-b")),
            result.emailGroupMemberships.map { it.groupIds.map { group -> group.value } })
    }

    @Test fun contactOrEmailFromAnotherIdentityCannotEnterTheCheckpoint() = runTest {
        val wrongId = contact().copy(id = ContactId("other"))
        val wrongUser = contact().copy(userId = UserId("other-user"))
        val wrongEmailOwner = contact().let { it.copy(contactEmails = it.contactEmails.map { email ->
            email.copy(contactId = ContactId("other"))
        }) }
        listOf(wrongId, wrongUser, wrongEmailOwner).forEach { value ->
            assertMalformed(reader(contactLoader = { _, _ -> ContactWithCards(value, emptyList()) })
                .contact(account, RemoteContactId("contact")))
        }
    }

    @Test fun emailOwnerUsesOpaqueIdRatherThanEmailText() = runTest {
        val transport = FakeTransport().apply {
            emailResponse = """{"Code":1000,"ContactEmail":{"ID":"email-a","ContactID":"contact","Email":"same@example.test"}}"""
        }
        val result = (reader(transport).emailOwner(account, RemoteEmailId("email-a")) as GatewayOutcome.Success).value
        assertEquals(RemoteContactId("contact"), result)
        assertEquals(listOf(RemoteEmailId("email-a")), transport.emails)
    }

    @Test fun missingOrMismatchingEmailIdentityIsFailureNotDeletionProof() = runTest {
        listOf(
            """{"Code":1000,"ContactEmail":{"ID":"other","ContactID":"contact"}}""",
            """{"Code":1000,"ContactEmail":{"ID":"email-a"}}""",
            """{"Code":1000,"ContactEmail":null}""",
            """{"Code":1000,"ContactEmail":{"ID":"email-a","ContactID":""}}""",
        ).forEach { raw ->
            assertMalformed(reader(FakeTransport().apply { emailResponse = raw })
                .emailOwner(account, RemoteEmailId("email-a")))
        }
    }

    @Test fun groupsReadTheIdKeyedResponseAndKeepEqualNamesAsSeparateGroups() = runTest {
        val wire = FakeTransport().apply {
            groupResponse = { ids -> groupJson(ids) }
        }
        val ids = setOf(RemoteGroupId("group-a"), RemoteGroupId("group-b"))
        val result = (reader(wire).groups(account, ids) as GatewayOutcome.Success).value
        assertEquals(ids, result.groups.map { it.id }.toSet())
        assertEquals(listOf("Same name", "Same name"), result.groups.map { it.name })
        assertEquals(listOf("#415df0", "#415df0"), result.groups.map { it.color })
        assertEquals(listOf(ids), wire.groupReads)
    }

    @Test fun targetedGroupsRejectMissingUnexpectedMismatchedAndNonContactLabels() = runTest {
        val malformed = listOf(
            """{"Code":1000,"Labels":{}}""",
            """{"Code":1000,"Labels":[]}""",
            """{"Code":1000,"Labels":{"group-a":{"ID":"other","Type":2,"Name":"Group"}}}""",
            """{"Code":1000,"Labels":{"other":{"ID":"other","Type":2,"Name":"Group"}}}""",
            """{"Code":1000,"Labels":{"group-a":{"ID":"group-a","Type":1,"Name":"Mail label"}}}""",
            """{"Code":1000,"Labels":{"group-a":{"ID":"group-a","Type":"2","Name":"Group"}}}""",
            """{"Code":1000,"Labels":{"group-a":{"ID":"group-a","Type":2,"Name":""}}}""",
        )
        malformed.forEach { raw ->
            assertMalformed(reader(FakeTransport().apply { groupResponse = { raw } })
                .groups(account, setOf(RemoteGroupId("group-a"))))
        }
    }

    @Test fun groupsAreReadInBoundedBatchesAndFailureNeverReturnsAPartialCatalog() = runTest {
        val wire = FakeTransport().apply { groupResponse = { ids -> groupJson(ids) } }
        val ids = (0..100).map { RemoteGroupId("group-$it") }.toSet()
        val success = (reader(wire).groups(account, ids) as GatewayOutcome.Success).value
        assertEquals(101, success.groups.size)
        assertEquals(listOf(100, 1), wire.groupReads.map { it.size })
        val interrupted = FakeTransport().apply {
            groupResponse = { requested ->
                if (groupReads.size == 2) throw java.io.IOException()
                groupJson(requested)
            }
        }
        val failed = reader(interrupted).groups(account, ids) as GatewayOutcome.Failure
        assertEquals(GatewayFailureCategory.NETWORK_UNAVAILABLE, failed.category)
    }

    @Test fun scopeAndReadyUserAreCheckedBeforeAnyResourceRequest() = runTest {
        var contacts = 0
        val wire = FakeTransport()
        val reader = reader(wire, contactLoader = { _, _ -> contacts++; error("UNEXPECTED_CONTACT_READ") })
        val other = AccountScope("other-account")
        val outcomes = listOf(reader.contact(other, RemoteContactId("contact")),
            reader.emailOwner(other, RemoteEmailId("email-a")), reader.groups(other, setOf(RemoteGroupId("group-a"))))
        outcomes.forEach { assertEquals(GatewayFailureCategory.AUTHENTICATION_REQUIRED, (it as GatewayOutcome.Failure).category) }
        assertEquals(0, contacts)
        assertTrue(wire.emails.isEmpty())
        assertTrue(wire.groupReads.isEmpty())
        val noUser = ProtonContactEventResourceReader(account, ProtonReadyUserProvider { null },
            { _, _ -> error("NO_CONTACT_READ") }, wire)
        assertEquals(GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            (noUser.groups(account, setOf(RemoteGroupId("group-a"))) as GatewayOutcome.Failure).category)
    }

    @Test fun cancellationPropagatesAndEmptyGroupsNeedNoHttpRead() = runTest {
        val wire = FakeTransport().apply { groupResponse = { throw CancellationException() } }
        assertTrue((reader(wire).groups(account, emptySet()) as GatewayOutcome.Success).value.groups.isEmpty())
        assertTrue(wire.groupReads.isEmpty())
        assertTrue(runCatching { reader(wire).groups(account, setOf(RemoteGroupId("group-a"))) }
            .exceptionOrNull() is CancellationException)
    }

    @Test fun malformedResponseSizeCannotBecomeSuccessfulMetadata() = runTest {
        val wire = FakeTransport().apply {
            emailResponse = " ".repeat(ProtonContactEventResourceReader.MAX_EMAIL_RESPONSE_BYTES + 1)
        }
        assertMalformed(reader(wire).emailOwner(account, RemoteEmailId("email-a")))
    }

    @Test fun disappearedTargetedResourceIsAFailureAndRawErrorNeverEscapes() = runTest {
        val missing = ApiException(ApiResult.Error.Http(httpCode = 400, message = "private response",
            proton = ApiResult.Error.ProtonData(ResponseCodes.NOT_EXISTS, "private response")))
        val wire = object : ProtonContactEventResourceTransport {
            override suspend fun email(account: AccountScope, id: RemoteEmailId): String = throw missing
            override suspend fun groups(account: AccountScope, ids: Set<RemoteGroupId>): String = throw missing
        }
        val reader = ProtonContactEventResourceReader(account, ProtonReadyUserProvider { user },
            { _, _ -> throw missing }, wire)
        val results = listOf(reader.contact(account, RemoteContactId("contact")),
            reader.emailOwner(account, RemoteEmailId("email-a")), reader.groups(account, setOf(RemoteGroupId("group-a"))))
        results.forEach {
            assertTrue(it is GatewayOutcome.Failure)
            assertEquals(GatewayFailureCategory.NOT_FOUND, (it as GatewayOutcome.Failure).category)
            assertFalse(it.toString().contains("private response"))
        }
    }

    private fun reader(
        wire: FakeTransport = FakeTransport(),
        contactLoader: suspend (UserId, ContactId) -> ContactWithCards = { _, _ -> error("NO_CONTACT_READ") },
    ) = ProtonContactEventResourceReader(account, ProtonReadyUserProvider { user }, contactLoader, wire)

    private fun assertMalformed(outcome: GatewayOutcome<*>) {
        assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, (outcome as GatewayOutcome.Failure).category)
        assertFalse(outcome.toString().contains("same@example.test"))
    }

    private fun contact(): Contact {
        val id = ContactId("contact")
        return Contact(userId = user, id = id, name = "Synthetic contact", contactEmails =
            listOf("a", "b").mapIndexed { order, suffix ->
                ContactEmail(userId = user, id = ContactEmailId("email-$suffix"), name = "Synthetic contact",
                    email = "same@example.test", defaults = 1, order = order, contactId = id,
                    canonicalEmail = null, labelIds = listOf("group-$suffix"), isProton = false, lastUsedTime = 0)
            })
    }

    private fun groupJson(ids: Set<RemoteGroupId>) = ids.joinToString(
        prefix = "{\"Code\":1000,\"Labels\":{", postfix = "}}",
    ) { """"${it.value}":{"ID":"${it.value}","Type":2,"Name":"Same name","Color":"#415df0"}""" }

    private class FakeTransport : ProtonContactEventResourceTransport {
        val emails = mutableListOf<RemoteEmailId>()
        val groupReads = mutableListOf<Set<RemoteGroupId>>()
        var emailResponse = ""
        var groupResponse: (Set<RemoteGroupId>) -> String = { error("NO_GROUP_READ") }
        override suspend fun email(account: AccountScope, id: RemoteEmailId): String {
            emails += id
            return emailResponse
        }
        override suspend fun groups(account: AccountScope, ids: Set<RemoteGroupId>): String {
            groupReads += ids
            return groupResponse(ids)
        }
    }
}
