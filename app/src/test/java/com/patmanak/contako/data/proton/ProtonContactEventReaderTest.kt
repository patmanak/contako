package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProtonContactEventReaderTest {
    private val account = AccountScope("synthetic")

    @Test fun bootstrapCapturesLatestAndRequiresFullRefresh() = runTest {
        val transport = object : ProtonContactEventsTransport {
            override suspend fun latest(account: AccountScope) = """{"Code":1000,"EventID":"start"}"""
            override suspend fun events(account: AccountScope, cursor: String): String = error("NO_POLL")
        }
        val result = (ProtonContactEventReader(transport).read(account, null) as GatewayOutcome.Success).value
        assertEquals("start", result.nextCursor)
        assertTrue(result.refreshAll)
    }

    @Test fun drainsAllPagesAndCollapsesUpdatedThenDeletedContacts() = runTest {
        val reader = reader { cursor -> when (cursor) {
            "start" -> page("middle", more = true, contacts = """[{"ID":"one","Action":2},{"ID":"two","Action":1}]""")
            "middle" -> page("end", contacts = """[{"ID":"two","Action":0}]""")
            else -> error("UNEXPECTED_CURSOR")
        } }
        val result = (reader.read(account, "start") as GatewayOutcome.Success).value
        assertEquals("end", result.nextCursor)
        assertEquals(setOf(RemoteContactId("one")), result.changedContacts)
        assertFalse(result.refreshAll)
    }

    @Test fun noChangeDoesNotRequestFullCardsAndRefreshDoes() = runTest {
        val unchanged = (reader { page("start") }.read(account, "start") as GatewayOutcome.Success).value
        assertFalse(unchanged.refreshAll)
        assertTrue(unchanged.changedContacts.isEmpty())
        val refresh = (reader { page("end", refresh = true) }.read(account, "start") as GatewayOutcome.Success).value
        assertTrue(refresh.refreshAll)
    }

    @Test fun malformedOrNonAdvancingPageCannotBecomeACompletedDelta() = runTest {
        listOf(page("start", more = true), page("end", contacts = """[{"ID":"one","Action":9}]"""),
            """{"Code":1000,"EventID":"end","Refresh":false}""").forEach { raw ->
            val failure = reader { raw }.read(account, "start") as GatewayOutcome.Failure
            assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, failure.category)
        }
    }

    @Test fun sparseNoChangeResponseWithoutCollectionsIsValid() = runTest {
        val raw = """{"Code":1000,"EventID":"start","More":false,"Refresh":false}"""
        val result = (reader { raw }.read(account, "start") as GatewayOutcome.Success).value
        assertEquals("start", result.nextCursor)
        assertFalse(result.refreshAll)
        assertTrue(result.changedContacts.isEmpty())
        assertTrue(result.changedEmails.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutConsumingEvents() = runTest {
        assertTrue(runCatching { reader { throw CancellationException() }.read(account, "start") }
            .exceptionOrNull() is CancellationException)
    }

    @Test fun emailOnlyEventRetainsOpaqueIdentityForDirectoryResolution() = runTest {
        val raw = page("end").replace("\"ContactEmails\":null", "\"ContactEmails\":[{\"ID\":\"email\",\"Action\":2}]")
        val result = (reader { raw }.read(account, "start") as GatewayOutcome.Success).value
        assertFalse(result.refreshAll)
        assertTrue(result.changedContacts.isEmpty())
        assertEquals(setOf(RemoteEmailId("email")), result.changedEmails)
    }

    @Test fun laterPageFailureDoesNotReturnAPartialDelta() = runTest {
        val result = reader { cursor ->
            if (cursor == "start") page("middle", more = true, contacts = """[{"ID":"one","Action":2}]""")
            else throw java.io.IOException()
        }.read(account, "start")
        assertTrue(result is GatewayOutcome.Failure)
    }

    private fun reader(response: (String) -> String) = ProtonContactEventReader(object : ProtonContactEventsTransport {
        override suspend fun latest(account: AccountScope): String = error("NO_BOOTSTRAP")
        override suspend fun events(account: AccountScope, cursor: String) = response(cursor)
    })
    private fun page(id: String, more: Boolean = false, refresh: Boolean = false, contacts: String = "null") =
        """{"Code":1000,"EventID":"$id","More":$more,"Refresh":$refresh,"Contacts":$contacts,"ContactEmails":null}"""
}
