package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.ContactValueKind
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactEmail
import me.proton.core.contact.domain.entity.ContactEmailId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test
import org.junit.Ignore

/** Shared invented inputs for host codec checks and independently observed phone/Web journeys. */
class RegressionDatasetTest {
    private val root = listOf(File("qa/dataset"), File("app/qa/dataset"))
        .first { File(it, "catalog.json").isFile }
    private val catalog = Json.parseToJsonElement(File(root, "catalog.json").readText()).jsonObject
    private val datasets = catalog.getValue("datasets").jsonArray.map {
        Json.parseToJsonElement(File(root, it.jsonPrimitive.content).readText()).jsonObject
    }
    private val codec = ProtonContactVCardCodec()
    private val profiles = datasets.flatMap { it.getValue("contacts").jsonArray }.map { it.jsonObject }

    @Test fun authoredContactsDecodeToIndependentExpectedValues() {
        profiles.forEach { profile ->
            val decoded = decode(profile)
            val expected = profile.getValue("expected").jsonObject
            val id = profile.string("id")
            assertEquals(id, profile.string("displayName"), decoded.displayName)
            val structured = decoded.valuesOf(ContactValueKind.STRUCTURED_NAME).firstOrNull()
            assertEquals(id, expected.string("firstName"), structured?.components?.get("given").orEmpty())
            assertEquals(id, expected.string("lastName"), structured?.components?.get("family").orEmpty())
            assertEquals(id, expected.strings("emails"), decoded.valuesOf(ContactValueKind.EMAIL).map { it.value })
            assertEquals(id, expected.strings("notes"), decoded.valuesOf(ContactValueKind.NOTE).map { it.value })
            expected["values"]?.jsonObject?.forEach { (kind, values) ->
                assertEquals("$id $kind", values.jsonArray.map { it.jsonPrimitive.content },
                    decoded.valuesOf(ContactValueKind.valueOf(kind)).map { it.value })
            }
        }
    }

    @Test fun compatibleEditSurvivesEncodeRereadWithoutLosingOtherValues() {
        profiles.forEach { profile ->
            val original = decode(profile)
            val noteId = original.valuesOf(ContactValueKind.NOTE).firstOrNull()?.id
            val changed = original.copy(values = original.values.map {
                if (it.id == noteId) it.copy(value = "Changed QA note\nSecond line") else it
            })
            val encoded = codec.encode(changed)
            val reread = codec.decode("fixture-account", remote(profile), listOf(
                ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, encoded.clear),
            ).filter { it.vCard.isNotBlank() })
            assertEquals(original.displayName, reread.displayName)
            assertEquals(original.valuesOf(ContactValueKind.EMAIL).map { it.value },
                reread.valuesOf(ContactValueKind.EMAIL).map { it.value })
            assertEquals(changed.valuesOf(ContactValueKind.NOTE).map { it.value },
                reread.valuesOf(ContactValueKind.NOTE).map { it.value })
            val unchangedKinds = original.values.map { it.kind }.toSet() - setOf(
                ContactValueKind.NOTE,
                ContactValueKind.UNKNOWN_VCARD_PROPERTY,
            )
            unchangedKinds.forEach { kind ->
                assertEquals("${profile.string("id")} $kind", original.valuesOf(kind).map { it.value to it.components },
                    reread.valuesOf(kind).map { it.value to it.components })
            }
            profile["preserved"]?.jsonArray?.forEach {
                assertTrue(encoded.encryptedPrivate.contains(it.jsonPrimitive.content))
            }
            assertFalse(encoded.signed.contains("Changed QA note"))
            assertFalse(encoded.clear.contains("Changed QA note"))
        }
    }

    @Test fun canonicalNamesMustNotDependOnPublicPrivateCardOrder() {
        profiles.filter { it.string("id") != "C02" }.forEach { profile ->
            val decoded = decode(profile)
            val expected = profile.getValue("expected").jsonObject
            assertEquals(profile.string("id"), expected.string("firstName"), decoded.firstName)
            assertEquals(profile.string("id"), expected.string("lastName"), decoded.lastName)
        }
    }

    @Test fun conflictingAndMalformedDatasetCardsRemainRejected() {
        datasets.flatMap { it.getValue("negative").jsonArray }.forEach { entry ->
            val fixture = entry.jsonObject
            assertThrows(fixture.string("id"), IllegalArgumentException::class.java) {
                codec.decode("fixture-account", remote(profiles.first()), listOf(
                    ProtonPlainContactCard(ContactCardType.Signed, readCard(fixture.string("vCard"))),
                ))
            }
        }
    }

    @Test fun emailSpecificGroupsAreNotFlattenedAcrossAddresses() {
        val contact = decode(profiles.single { it.string("id") == "C03" })
        val emails = contact.valuesOf(ContactValueKind.EMAIL)
        assertEquals("G01,G02", emails.first().metadata[PROTON_GROUP_IDS_KEY])
        assertEquals("G03", emails.last().metadata[PROTON_GROUP_IDS_KEY])
    }

    private fun decode(profile: JsonObject) = codec.decode("fixture-account", remote(profile),
        profile.getValue("cards").jsonArray.map { entry ->
            val card = entry.jsonObject
            val kind = when (card.string("kind")) {
                "Signed" -> ContactCardType.Signed
                "ClearText" -> ContactCardType.ClearText
                "Encrypted" -> ContactCardType.Encrypted
                "EncryptedAndSigned" -> ContactCardType.EncryptedAndSigned
                else -> error("Unsupported fixture kind")
            }
            ProtonPlainContactCard(kind, readCard(card.string("vCard")))
        })

    private fun readCard(text: String) = text.replace("\r\n", "\n").replace("\n", "\r\n")
    private fun remote(profile: JsonObject): Contact {
        val id = profile.string("id")
        val emails = profile.getValue("expected").jsonObject.strings("emails").mapIndexed { index, email ->
            val groups = datasets.flatMap { it.getValue("groups").jsonArray }.map { it.jsonObject }
                .filter { group -> group.getValue("members").jsonArray.any {
                    it.jsonArray.map { part -> part.jsonPrimitive.content } == listOf(id, email)
                } }.map { it.string("id") }.distinct()
            ContactEmail(userId = UserId("fixture-user"), id = ContactEmailId("$id-email-$index"),
                name = profile.string("displayName"), email = email, defaults = 1, order = index,
                contactId = ContactId(id), canonicalEmail = null, labelIds = groups,
                isProton = false, lastUsedTime = 0)
        }
        return Contact(UserId("fixture-user"), ContactId(id), profile.string("displayName"), emails)
    }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
}
