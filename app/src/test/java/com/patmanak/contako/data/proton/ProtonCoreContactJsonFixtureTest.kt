package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.GatewayMalformedResponseCategory
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.proton.core.contact.domain.entity.ContactCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonCoreContactJsonFixtureTest {
    @Test
    fun `36_6_2 create request uses contacts cards overwrite and labels`() {
        val root = Json.parseToJsonElement(fixture("create-contacts-request.json")).jsonObject
        val cards = root.getValue("Contacts").jsonArray.single().jsonObject
            .getValue("Cards").jsonArray.map { it.jsonObject }

        assertEquals("0", root.getValue("Overwrite").jsonPrimitive.content)
        assertEquals("0", root.getValue("Labels").jsonPrimitive.content)
        assertEquals(listOf("3", "2"), cards.map { it.getValue("Type").jsonPrimitive.content })
        assertFalse(cards.any { it.getValue("Type").jsonPrimitive.content == "0" })
    }

    @Test
    fun `36_6_2 create response is composite and mutation contact has no cards`() {
        val root = Json.parseToJsonElement(fixture("create-contacts-response.json")).jsonObject
        val item = root.getValue("Responses").jsonArray.single().jsonObject
        val response = item.getValue("Response").jsonObject
        val contact = response.getValue("Contact").jsonObject

        assertEquals("0", item.getValue("Index").jsonPrimitive.content)
        assertEquals("1000", response.getValue("Code").jsonPrimitive.content)
        assertEquals("fixture-contact-id", contact.getValue("ID").jsonPrimitive.content)
        assertEquals("Fixture Contact", contact.getValue("Name").jsonPrimitive.content)
        assertEquals(0, contact.getValue("ContactEmails").jsonArray.size)
        assertFalse(contact.containsKey("Cards"))
    }

    @Test
    fun `raw create serializes exact public model including optional signature`() {
        val raw = serializeRawCreateRequest(
            listOf(
                ContactCard.Encrypted("encrypted", "detached"),
                ContactCard.Encrypted("unsigned-encrypted", null),
                ContactCard.Signed("signed", "signature"),
                ContactCard.ClearText("clear"),
            ),
        )
        val root = Json.parseToJsonElement(raw).jsonObject
        val cards = root.getValue("Contacts").jsonArray.single().jsonObject
            .getValue("Cards").jsonArray.map { it.jsonObject }

        assertEquals(listOf("3", "1", "2", "0"), cards.map { it.getValue("Type").jsonPrimitive.content })
        assertFalse(cards[1].containsKey("Signature"))
        assertFalse(cards[3].containsKey("Signature"))
        assertEquals("0", root.getValue("Overwrite").jsonPrimitive.content)
        assertEquals("0", root.getValue("Labels").jsonPrimitive.content)
    }

    @Test
    fun `raw create serialization exactly matches pinned public source fixture`() {
        val serialized = serializeRawCreateRequest(
            listOf(
                ContactCard.Encrypted("encrypted-card", "encrypted-signature"),
                ContactCard.Signed("signed-card", "signed-signature"),
            ),
        )

        assertEquals(
            Json.parseToJsonElement(fixture("create-contacts-request.json")),
            Json.parseToJsonElement(serialized),
        )
    }

    @Test
    fun `raw create accepts sparse email metadata then returns ID for hydration`() {
        assertEquals("fixture-contact-id", parseRawCreateContactId(fixture("create-contacts-email-sparse-response.json")))
        assertEquals("fixture-contact-id", parseRawCreateContactId(fixture("create-contacts-android-public-response.json")))
        assertEquals("fixture-contact-id", parseRawCreateContactId(fixture("create-contacts-go-public-response.json")))
    }

    @Test
    fun `raw create ignores absent numeric and string root code like public Core model`() {
        val valid = fixture("create-contacts-android-public-response.json")

        assertEquals("fixture-contact-id", parseRawCreateContactId(valid))
        assertEquals("fixture-contact-id", parseRawCreateContactId(valid.replaceFirst("{", "{\"Code\":1001,")))
        assertEquals("fixture-contact-id", parseRawCreateContactId(valid.replaceFirst("{", "{\"Code\":\"failure\",")))
    }

    @Test
    fun `raw create rejects wrong nested code index cardinality missing ID and oversize`() {
        val valid = fixture("create-contacts-email-sparse-response.json")
        val wrongNestedCode = Regex(
            "(\\\"Response\\\"\\s*:\\s*\\{\\s*\\\"Code\\\"\\s*:\\s*)1000",
        ).replace(valid) { match -> match.groupValues[1] + "1001" }
        val malformed = listOf(
            wrongNestedCode,
            valid.replace("\"Index\": 0", "\"Index\": 1"),
            valid.replace("\"Responses\": [", "\"Responses\": [{\"Index\":0,\"Response\":{\"Code\":1000,\"Contact\":{\"ID\":\"other\"}}},"),
            valid.replace("\"ID\": \"fixture-contact-id\",", ""),
            valid + " ".repeat(64 * 1_024),
        )

        malformed.forEach { assertTrue(runCatching { parseRawCreateContactId(it) }.isFailure) }
    }

    @Test
    fun `raw create failures expose only closed structural categories`() {
        val valid = fixture("create-contacts-android-public-response.json")
        val cases = listOf(
            "not-json" to GatewayMalformedResponseCategory.RAW_CREATE_ROOT_OBJECT,
            "{\"Responses\":{}}" to GatewayMalformedResponseCategory.RAW_CREATE_RESPONSES_TYPE,
            "{\"Responses\":[]}" to GatewayMalformedResponseCategory.RAW_CREATE_RESPONSES_COUNT,
            "{\"Responses\":[0]}" to GatewayMalformedResponseCategory.RAW_CREATE_RESPONSE_ITEM,
            valid.replace("\"Index\":0", "\"Index\":\"0\"") to
                GatewayMalformedResponseCategory.RAW_CREATE_INDEX,
            valid.replace("\"Response\":{", "\"Response\":null,\"Ignored\":{") to
                GatewayMalformedResponseCategory.RAW_CREATE_NESTED_RESPONSE,
            valid.replace("\"Code\":1000", "\"Code\":\"1000\"") to
                GatewayMalformedResponseCategory.RAW_CREATE_NESTED_CODE,
            valid.replace("\"Contact\":{", "\"Contact\":null,\"Ignored\":{") to
                GatewayMalformedResponseCategory.RAW_CREATE_CONTACT_OBJECT,
            valid.replace("\"ID\":\"fixture-contact-id\"", "\"ID\":\"\"") to
                GatewayMalformedResponseCategory.RAW_CREATE_CONTACT_ID,
            valid + " ".repeat(64 * 1_024) to GatewayMalformedResponseCategory.RAW_CREATE_RESPONSE_SIZE,
        )

        cases.forEach { (raw, expected) ->
            val failure = runCatching { parseRawCreateContactId(raw) }.exceptionOrNull()
            assertEquals(expected, (failure as ProtonRawCreateResponseException).category)
            assertFalse(failure.toString().contains("fixture-contact-id"))
        }
    }

    @Test
    fun `raw create retains only bounded numeric nested failure code`() {
        val valid = fixture("create-contacts-android-public-response.json")
        val known = runCatching {
            parseRawCreateContactId(valid.replace("\"Code\":1000", "\"Code\":2001"))
        }.exceptionOrNull() as ProtonRawCreateResponseException
        val outOfRange = runCatching {
            parseRawCreateContactId(valid.replace("\"Code\":1000", "\"Code\":10000"))
        }.exceptionOrNull() as ProtonRawCreateResponseException

        assertEquals(2001, known.protonResponseCode)
        assertEquals(null, outOfRange.protonResponseCode)
        assertFalse(known.toString().contains("2001"))
    }

    private fun fixture(name: String): String = File(
        "src/test/resources/fixtures/proton-core-36.6.2/$name",
    ).readText()
}
