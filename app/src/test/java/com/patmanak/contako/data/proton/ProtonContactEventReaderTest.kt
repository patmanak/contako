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
        assertEquals(setOf(RemoteContactId("two")), result.deletedContacts)
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

    @Test fun groupDeletionWithoutContactEventsIsRetained() = runTest {
        val raw = page("end").replace("\"ContactEmails\":null",
            "\"Labels\":[{\"ID\":\"group\",\"Action\":0}]")
        val result = (reader { raw }.read(account, "start") as GatewayOutcome.Success).value
        assertEquals(setOf(RemoteGroupId("group")), result.deletedGroups)
        assertTrue(result.changedGroups.isEmpty())
        assertTrue(result.changedContacts.isEmpty())
        assertTrue(result.changedEmails.isEmpty())
    }

    @Test fun lastActionWinsIndependentlyForAllCollectionsAcrossPages() = runTest {
        val reader = reader { cursor -> when (cursor) {
            "start" -> """{"Code":1000,"EventID":"middle","More":true,"Refresh":false,
                "Contacts":[{"ID":"contact","Action":0}],
                "ContactEmails":[{"ID":"old-email","Action":2},{"ID":"new-email","Action":1}],
                "Labels":[{"ID":"group","Action":1},{"ID":"gone-group","Action":2}]}"""
            "middle" -> """{"Code":1000,"EventID":"end","More":false,"Refresh":true,
                "Contacts":[{"ID":"contact","Action":1}],
                "ContactEmails":[{"ID":"old-email","Action":0},{"ID":"new-email","Action":2}],
                "Labels":[{"ID":"group","Action":2},{"ID":"gone-group","Action":0}]}"""
            else -> error("UNEXPECTED_CURSOR")
        } }
        val result = (reader.read(account, "start") as GatewayOutcome.Success).value
        assertEquals(setOf(RemoteContactId("contact")), result.changedContacts)
        assertTrue(result.deletedContacts.isEmpty())
        assertEquals(setOf(RemoteEmailId("old-email")), result.deletedEmails)
        assertEquals(setOf(RemoteEmailId("new-email")), result.changedEmails)
        assertEquals(setOf(RemoteGroupId("group")), result.changedGroups)
        assertEquals(setOf(RemoteGroupId("gone-group")), result.deletedGroups)
        assertTrue(result.refreshAll)
        assertEquals("end", result.nextCursor)
    }

    @Test fun malformedLabelsPreventAcknowledgingTheWholeDelta() = runTest {
        val labels = listOf("{}", "[{}]", "[{\"ID\":\"group\",\"Action\":3}]",
            "[{\"ID\":\"group\",\"Action\":\"2\"}]", "[{\"ID\":\"\",\"Action\":1}]")
        labels.forEach { collection ->
            val raw = page("end", contacts = """[{"ID":"contact","Action":2}]""")
                .replace("\"ContactEmails\":null", "\"Labels\":$collection")
            val result = reader { raw }.read(account, "start") as GatewayOutcome.Failure
            assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, result.category)
        }
    }

    @Test fun oversizedLabelCollectionCannotBecomeACompletedDelta() = runTest {
        val labels = (0..10_000).joinToString(prefix = "[", postfix = "]") { """{"ID":"g$it","Action":2}""" }
        val raw = page("end").replace("\"ContactEmails\":null", "\"Labels\":$labels")
        val result = reader { raw }.read(account, "start") as GatewayOutcome.Failure
        assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, result.category)
    }

    private fun reader(response: (String) -> String) = ProtonContactEventReader(object : ProtonContactEventsTransport {
        override suspend fun latest(account: AccountScope): String = error("NO_BOOTSTRAP")
        override suspend fun events(account: AccountScope, cursor: String) = response(cursor)
    })
    private fun page(id: String, more: Boolean = false, refresh: Boolean = false, contacts: String = "null") =
        """{"Code":1000,"EventID":"$id","More":$more,"Refresh":$refresh,"Contacts":$contacts,"ContactEmails":null}"""
}
