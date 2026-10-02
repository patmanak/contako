package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.RemoteContactPresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.contact.data.api.resource.*
import me.proton.core.contact.data.api.response.*
import me.proton.core.contact.domain.entity.*
import me.proton.core.contact.domain.repository.ContactRemoteDataSource
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.*
import org.junit.Assert.*
import org.junit.Test

class ProtonInventorySafetyTest {
    private val user = UserId("synthetic")
    @Test fun completePagesPreserveEmailIdentityAndLabels() = runTest {
        val reader = ProtonBoundedContactInventory(
            { _, _, _ -> GetContactsResponse(1, listOf(ShortContactResource("c", "Élodie"))) },
            { _, _, _ -> GetContactEmailsResponse(1, listOf(email())) },
        )
        val contact = reader.read(user).single()
        assertEquals("Élodie", contact.name)
        assertEquals(listOf("group"), contact.contactEmails.single().labelIds)
    }

    @Test fun incoherentTotalsDuplicatesAndTruncatedPagesNeverBecomeCompleteInventories() = runTest {
        listOf(
            GetContactsResponse(0, listOf(ShortContactResource("c", ""))),
            GetContactsResponse(500, emptyList()),
            GetContactsResponse(2, List(2) { ShortContactResource("c", "") }),
            GetContactsResponse(100001, emptyList()),
        ).forEach { page ->
            val reader = ProtonBoundedContactInventory({ _, _, _ -> page }, { _, _, _ -> error("EMAILS_MUST_NOT_LOAD") })
            assertTrue(runCatching { reader.read(user) }.exceptionOrNull() is ProtonMalformedContactResponse)
        }
        val reader = ProtonBoundedContactInventory({ _, page, _ ->
            GetContactsResponse(if (page == 0) 1001 else 1002, List(if (page == 0) 1000 else 2) { ShortContactResource("$page-$it", "") })
        }, { _, _, _ -> error("EMAILS_MUST_NOT_LOAD") })
        assertTrue(runCatching { reader.read(user) }.exceptionOrNull() is ProtonMalformedContactResponse)
    }

    @Test fun labelObjectBudgetAndOrphanEmailsFailBeforeRetention() = runTest {
        for (candidate in listOf(email(labels = List(1001) { "g$it" }), email(labels = listOf("", "")), email(contact = "orphan"))) {
            val reader = ProtonBoundedContactInventory({ _, _, _ -> GetContactsResponse(1, listOf(ShortContactResource("c", ""))) },
                { _, _, _ -> GetContactEmailsResponse(1, listOf(candidate)) })
            assertTrue(runCatching { reader.read(user) }.exceptionOrNull() is ProtonMalformedContactResponse)
        }
        var emailPages = 0
        val reader = ProtonBoundedContactInventory({ _, _, _ -> GetContactsResponse(1, listOf(ShortContactResource("c", ""))) },
            { _, page, _ -> emailPages++; GetContactEmailsResponse(2000,
                List(1000) { email(id = "$page-$it", labels = List(1000) { index -> "g$index" }) }) })
        assertTrue(runCatching { reader.read(user) }.exceptionOrNull() is ProtonMalformedContactResponse)
        assertEquals(1, emailPages)
    }

    @Test fun cumulativeContactLimitRejectsAnExtraFullPageEvenWithUnchangedTotal() = runTest {
        var pages = 0
        val reader = ProtonBoundedContactInventory({ _, page, _ ->
            pages++; GetContactsResponse(100000, List(1000) { ShortContactResource("$page-$it", "") })
        }, { _, _, _ -> error("EMAILS_MUST_NOT_LOAD") })
        assertTrue(runCatching { reader.read(user) }.exceptionOrNull() is ProtonMalformedContactResponse)
        assertEquals(101, pages)
    }

    @Test fun onlyStructuredTargetedAbsenceAllowsDeletionAndCancellationPropagates() = runTest {
        for ((http, proton, absent) in listOf(Triple(400, 2501, true), Triple(404, 2501, true),
            Triple(422, 2501, true), Triple(422, 2001, false), Triple(422, null, false),
            Triple(404, null, false), Triple(403, 2501, false), Triple(500, 2501, false))) {
            val failure = ApiException(ApiResult.Error.Http(http, "synthetic", proton?.let { ApiResult.Error.ProtonData(it, "synthetic") }))
            val port = port { throw failure }
            val result = runCatching { port.presence(user, ContactId("c")) }
            if (absent) assertEquals(RemoteContactPresence.CONFIRMED_ABSENT, result.getOrThrow()) else assertSame(failure, result.exceptionOrNull())
        }
        val wrong = port { ContactWithCards(Contact(user, ContactId("other"), "Synthetic", emptyList()), emptyList()) }
        assertTrue(runCatching { wrong.presence(user, ContactId("c")) }.exceptionOrNull() is ProtonMalformedContactResponse)
        val cancelled = CancellationException()
        assertSame(cancelled, runCatching { port { throw cancelled }.presence(user, ContactId("c")) }.exceptionOrNull())
    }

    private fun email(id: String = "e", contact: String = "c", labels: List<String> = listOf("group")) =
        ContactEmailResource(id, "Élodie", "synthetic@example.test", 0, 0, contact, null, labels, null, 0)
    private fun port(read: suspend () -> ContactWithCards) = ProtonCoreContactRemotePort(object : ContactRemoteDataSource {
        override suspend fun getContactWithCards(userId: UserId, contactId: ContactId) = read()
        override suspend fun getAllContacts(userId: UserId): List<Contact> = error("UNUSED")
        override suspend fun createContacts(userId: UserId, contactCards: List<List<ContactCard>>): List<Contact> = error("UNUSED")
        override suspend fun deleteContacts(userId: UserId, contactIds: List<ContactId>) = error("UNUSED")
        override suspend fun updateContact(userId: UserId, contactId: ContactId, contactCards: List<ContactCard>): Contact = error("UNUSED")
    }, ProtonRawContactCreateTransport { _, _ -> error("UNUSED") })
}
