package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.Base64
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonVCardMappingContractTest {
    private val codec = ProtonContactVCardCodec()

    @Test
    fun `note edit upgrades clear email provenance to signed output without losing email groups`() {
        val imported = decodeFixture("legacy-note",
            ContactCardType.ClearText to card("legacy-note", "FN:Legacy fixture\r\n" +
                "ITEM1.EMAIL;TYPE=home;X-IMPORTED=keep:first@example.test\r\nITEM1.CATEGORIES:Fixture group\r\n" +
                "ITEM2.EMAIL;TYPE=work:second@example.test\r\nITEM2.CATEGORIES:Second group"),
            ContactCardType.Encrypted to card("legacy-note", "NOTE:Before"))
        val changed = imported.copy(values = imported.values.map {
            if (it.kind == ContactValueKind.NOTE) it.copy(value = "After") else it
        })
        val encoded = codec.encode(changed)
        assertTrue(encoded.signed.contains("EMAIL;TYPE=home;X-IMPORTED=keep;PREF=1:first@example.test"))
        assertTrue(encoded.clear.contains("ITEM1.CATEGORIES:Fixture group"))
        assertFalse(encoded.clear.contains("EMAIL"))
        assertTrue(encoded.encryptedPrivate.contains("NOTE:After"))
        val reordered = codec.encode(changed.copy(values = changed.values.map {
            if (it.kind == ContactValueKind.EMAIL) it.copy(order = 1 - it.order) else it
        }))
        assertTrue(reordered.clear.contains("ITEM2.CATEGORIES:Fixture group"))
        assertTrue(reordered.clear.contains("ITEM1.CATEGORIES:Second group"))
        val email = changed.valuesOf(ContactValueKind.EMAIL).first()
        assertTrue(runCatching { codec.encode(changed.copy(values = changed.values.map {
            if (it.id == email.id) it.copy(id = "forged") else it
        })) }.isFailure)
    }

    @Test
    fun `public key clear provenance is upgraded but private provenance cannot become public`() {
        val clear = decodeFixture("clear-key", ContactCardType.ClearText to
            card("clear-key", "FN:Key fixture\r\nKEY;VALUE=uri;X-KEY=keep:https://keys.example.test/fixture"))
        val encoded = codec.encode(clear)
        assertTrue(encoded.signed.contains("KEY;VALUE=uri;X-KEY=keep;PREF=1:https://keys.example.test/fixture"))
        assertFalse(encoded.clear.contains("KEY"))
        listOf(ContactCardType.Encrypted, ContactCardType.EncryptedAndSigned).forEach { type ->
            listOf("EMAIL:private@example.test", "KEY;VALUE=uri:https://keys.example.test/private").forEach { property ->
                val private = decodeFixture("private-source",
                    ContactCardType.Signed to card("private-source", "FN:Private fixture"),
                    type to card("private-source", property))
                assertTrue(runCatching { codec.encode(private) }.isFailure)
            }
        }
    }

    @Test
    fun `note edits on signed and unsigned encrypted imports preserve other fields`() {
        listOf(ContactCardType.Encrypted, ContactCardType.EncryptedAndSigned).forEach { type ->
            val imported = decodeFixture("note-edit",
                ContactCardType.Signed to card("note-edit", "FN:Note fixture"),
                type to card("note-edit", "NOTE;X-ORIGIN=fixture:Initial note\r\n" +
                    "BDAY;VALUE=text:invented season\r\nX-FIXTURE:unchanged"))
            listOf("Plain replacement", "Line one\nLine two; comma, slash\\ and é 😀", "").forEach { note ->
                val edited = imported.copy(values = imported.values.mapNotNull { value ->
                    if (value.kind != ContactValueKind.NOTE) value
                    else if (note.isEmpty()) null else value.copy(value = note)
                })
                val encoded = codec.encode(edited)
                val actual = decodeFixture("note-edit", ContactCardType.Signed to encoded.signed,
                    ContactCardType.EncryptedAndSigned to encoded.encryptedPrivate)
                assertEquals(note.takeIf(String::isNotEmpty), actual.valuesOf(ContactValueKind.NOTE).singleOrNull()?.value)
                assertTrue(encoded.encryptedPrivate.contains("BDAY;VALUE=text:invented season"))
                assertTrue(encoded.encryptedPrivate.contains("X-FIXTURE:unchanged"))
                assertFalse(encoded.signed.contains("NOTE"))
            }
        }
    }

    @Test
    fun `unchanged imported noncalendar dates retain their wire decoration after an unrelated edit`() {
        listOf("BDAY;VALUE=text:circa spring 2000", "BDAY;VALUE=date:2000-02",
            "BDAY;VALUE=date-time:2000-02-29T12:30:00Z").forEach { property ->
            val imported = decodeFixture("date-readback",
                ContactCardType.Signed to card("date-readback", "FN:Date readback"),
                ContactCardType.EncryptedAndSigned to card("date-readback", property))
            val encoded = codec.encode(imported.copy(displayName = "Edited name"))
            assertTrue(encoded.encryptedPrivate.contains(property))
            val reparsed = decodeFixture("date-readback",
                ContactCardType.Signed to encoded.signed,
                ContactCardType.EncryptedAndSigned to encoded.encryptedPrivate)
            assertEquals(imported.valuesOf(ContactValueKind.BIRTHDAY).single().value,
                reparsed.valuesOf(ContactValueKind.BIRTHDAY).single().value)
            val fresh = CanonicalContact(accountId = "primary", id = "new-date", displayName = "New date",
                values = listOf(ContactValue("birthday", ContactValueKind.BIRTHDAY,
                    imported.valuesOf(ContactValueKind.BIRTHDAY).single().value, order = 0)))
            assertTrue(runCatching { codec.encode(fresh) }.isFailure)
        }
    }

    @Test
    fun `new inline photo and logo serialize as parseable image data URIs`() {
        val png = "data:image/png;base64," + Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            ),
        )
        val contact = CanonicalContact(
            accountId = "account",
            id = "inline-image",
            displayName = "Inline image",
            values = listOf(
                ContactValue("photo", ContactValueKind.PHOTO, png, order = 0, isPrimary = true),
                ContactValue("logo", ContactValueKind.LOGO, png, order = 0),
            ),
        )

        val encoded = codec.encode(contact)

        assertTrue(encoded.encryptedPrivate.contains("PHOTO;VALUE=uri;MEDIATYPE=image/png;PREF=1:data:image/png;base64,"))
        assertTrue(encoded.encryptedPrivate.contains("LOGO;VALUE=uri;MEDIATYPE=image/png:data:image/png;base64,"))
        parseSingleCompleteVCard(encoded.encryptedPrivate)
    }

    @Test
    fun `FX-C-001 display-name-only parse build reparse is stable`() {
        val decoded = decodeFixture(
            "fx-c-001",
            ContactCardType.Signed to fixture("fx-c-001-signed.vcf"),
            ContactCardType.EncryptedAndSigned to fixture("fx-c-001-private.vcf"),
        )

        assertEquals("Display Name Only", decoded.displayName)
        assertTrue(decoded.firstName.isEmpty())
        assertTrue(decoded.lastName.isEmpty())

        val first = codec.encode(decoded)
        val reparsed = codec.decode(
            "primary",
            remote("fx-c-001"),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, first.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, first.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, first.clear),
            ),
        )
        val second = codec.encode(reparsed)

        assertEquals(first, second)
    }

    @Test
    fun `stale boolean primary cannot override stable canonical order without PREF`() {
        val contact = com.patmanak.contako.domain.model.CanonicalContact(
            accountId = "account",
            id = "contact",
            displayName = "Synthetic",
            values = listOf(
                com.patmanak.contako.domain.model.ContactValue(
                    id = "ordered-first",
                    kind = ContactValueKind.EMAIL,
                    value = "first@example.test",
                    order = 0,
                ),
                com.patmanak.contako.domain.model.ContactValue(
                    id = "stale-primary",
                    kind = ContactValueKind.EMAIL,
                    value = "second@example.test",
                    order = 1,
                    isPrimary = true,
                ),
            ),
        )

        val encoded = codec.encode(contact)

        assertTrue(encoded.signed.contains("ITEM1.EMAIL;PREF=1:first@example.test"))
        assertTrue(encoded.signed.contains("ITEM2.EMAIL;PREF=2:second@example.test"))
    }

    @Test
    fun `text-only organization uses its canonical value when structured components are absent`() {
        val contact = com.patmanak.contako.domain.model.CanonicalContact(
            accountId = "account",
            id = "contact",
            displayName = "Synthetic",
            values = listOf(
                com.patmanak.contako.domain.model.ContactValue(
                    id = "organization",
                    kind = ContactValueKind.ORGANIZATION,
                    value = "Contako QA",
                    order = 0,
                    isPrimary = true,
                ),
            ),
        )

        val encoded = codec.encode(contact)
        assertTrue(encoded.encryptedPrivate.contains("ORG:Contako QA"))

        val reparsed = codec.decode(
            "primary",
            remote("organization-text-only"),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, encoded.clear),
            ),
        )
        assertEquals("Contako QA", reparsed.valuesOf(ContactValueKind.ORGANIZATION).single().value)
    }

    @Test
    fun `legacy text-only postal address is retained as street and round trips`() {
        val contact = com.patmanak.contako.domain.model.CanonicalContact(
            accountId = "account",
            id = "contact",
            displayName = "Synthetic",
            values = listOf(
                com.patmanak.contako.domain.model.ContactValue(
                    id = "address",
                    kind = ContactValueKind.POSTAL_ADDRESS,
                    value = "1 Legacy Street",
                    order = 0,
                    isPrimary = true,
                ),
            ),
        )

        val encoded = codec.encode(contact)
        assertTrue(encoded.encryptedPrivate.contains("ADR;PREF=1:;;1 Legacy Street;;;;"))

        val reparsed = codec.decode(
            "primary",
            remote("address-text-only"),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, encoded.clear),
            ),
        ).valuesOf(ContactValueKind.POSTAL_ADDRESS).single()
        assertEquals("1 Legacy Street", reparsed.value)
        assertEquals("1 Legacy Street", reparsed.components["street"])
    }

    @Test
    fun `FX-C-006 preserves structured components ranked preferences and type combinations`() {
        val decoded = decodeFixture(
            "fx-c-006",
            ContactCardType.Signed to fixture("fx-c-006-signed.vcf"),
            ContactCardType.EncryptedAndSigned to fixture("fx-c-006-private.vcf"),
        )
        val structured = decoded.valuesOf(ContactValueKind.STRUCTURED_NAME).single()
        val address = decoded.valuesOf(ContactValueKind.POSTAL_ADDRESS).single()
        val organization = decoded.valuesOf(ContactValueKind.ORGANIZATION).single()
        val emails = decoded.valuesOf(ContactValueKind.EMAIL)
        val phones = decoded.valuesOf(ContactValueKind.PHONE)

        assertEquals("Dr", structured.components["prefix"])
        assertEquals("Ada", structured.components["given"])
        assertEquals("M", structured.components["additional"])
        assertEquals("Lovelace", structured.components["family"])
        assertEquals("PhD", structured.components["suffix"])
        assertEquals(
            mapOf(
                "po_box" to "Box 1",
                "extended" to "Building A",
                "street" to "1 Main Street",
                "locality" to "Paris",
                "region" to "Ile-de-France",
                "postal_code" to "75001",
                "country" to "France",
            ),
            address.components,
        )
        assertEquals("Analytical Engines", organization.components["component_0"])
        assertEquals("Research", organization.components["component_1"])
        assertEquals("Algorithms", organization.components["component_2"])
        assertFalse(emails[0].isPrimary)
        assertTrue(emails[1].isPrimary)
        assertEquals("5", emails[0].metadata["vcardPref"])
        assertEquals("2", emails[1].metadata["vcardPref"])
        assertEquals("WORK\u001fCELL", phones[0].metadata["vcardTypeTokens"])
        assertEquals("HOME\u001fFAX", phones[1].metadata["vcardTypeTokens"])

        val encoded = codec.encode(decoded)
        assertTrue(encoded.signed.contains("ITEM1.EMAIL;TYPE=WORK;PREF=5:work@example.test"))
        assertTrue(encoded.signed.contains("ITEM2.EMAIL;TYPE=HOME;PREF=2:home@example.test"))
        assertTrue(encoded.encryptedPrivate.contains("TEL;TYPE=WORK;TYPE=CELL;PREF=1:+33111111111"))
        assertTrue(encoded.encryptedPrivate.contains("TEL;TYPE=HOME,FAX;PREF=2:+33222222222"))
        assertTrue(
            encoded.encryptedPrivate.contains(
                "ADR;TYPE=WORK;PREF=1:Box 1;Building A;1 Main Street;Paris;Ile-de-France;75001;France",
            ),
        )
        assertTrue(encoded.encryptedPrivate.contains("ORG:Analytical Engines;Research;Algorithms"))
    }

    @Test
    fun `05-PRIMARY-NOTE FX-C-008 preserves note PREF unknown parameters and canary through rebuild`() {
        val decoded = decodeFixture(
            "fx-c-008",
            ContactCardType.Signed to fixture("fx-c-008-signed.vcf"),
            ContactCardType.EncryptedAndSigned to fixture("fx-c-008-private.vcf"),
        )
        val notes = decoded.valuesOf(ContactValueKind.NOTE)

        assertEquals(listOf("7", "2", null), notes.map { it.metadata["vcardPref"] })
        assertEquals("Projected primary note", notes.single { it.isPrimary }.value)
        assertTrue(decoded.values.any {
            it.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY &&
                it.label == "X-CONTAKO-NOTE-CANARY"
        })

        val first = codec.encode(decoded)
        assertTrue(first.encryptedPrivate.contains("NOTE;X-ORIGIN=archive;PREF=7:Hidden archive note"))
        assertTrue(first.encryptedPrivate.contains("NOTE;X-ORIGIN=android;PREF=2:Projected primary note"))
        assertTrue(first.encryptedPrivate.contains("NOTE;X-ORIGIN=plain:Hidden plain note"))
        assertTrue(first.encryptedPrivate.contains(
            "X-CONTAKO-NOTE-CANARY;X-KEEP=yes:preserve-opaque-note-marker",
        ))

        val reparsed = codec.decode(
            "primary",
            remote("fx-c-008"),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, first.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, first.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, first.clear),
            ),
        )
        assertEquals(first, codec.encode(reparsed))
        assertEquals("ProtonPreparedVCard(REDACTED)", first.toString())
        assertFalse(first.toString().contains("preserve-opaque-note-marker"))
    }

    @Test
    fun `05-DATE FX-C-009 and FX-C-010 complete deterministic ten-step contract`() {
        // 1. Parse full/yearless standards, duplicate standards, relations, and unknown canaries.
        val decoded = decodeFixture(
            "fx-c-009",
            ContactCardType.Signed to fixture("fx-c-009-signed.vcf"),
            ContactCardType.EncryptedAndSigned to fixture("fx-c-009-private.vcf"),
        )
        assertEquals("2000-02-29", decoded.valuesOf(ContactValueKind.BIRTHDAY).single().value)
        assertEquals("--02-29", decoded.valuesOf(ContactValueKind.ANNIVERSARY).single().value)

        // 2. Quarantine duplicate singleton imports as opaque values instead of losing them.
        val duplicates = decoded.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY)
            .filter { it.metadata["duplicateStandardDate"] == "true" }
        assertEquals(setOf("BDAY", "ANNIVERSARY"), duplicates.mapNotNull { it.label }.toSet())

        // 3. Preserve known and unknown relationship types with exact provenance.
        val relations = decoded.valuesOf(ContactValueKind.RELATIONSHIP)
        assertEquals(listOf("friend", "X-REMOTE-REL"), relations.map { it.label })

        // 4. Rebuild without emitting a type outside D-066 maintained allowlists.
        val first = codec.encode(decoded.copy(values = decoded.values.map { value ->
            if (value == relations.first()) value.copy(value = "Relation One Edited") else value
        }))
        assertTrue(first.encryptedPrivate.contains("RELATED;TYPE=friend:Relation One Edited"))
        assertTrue(first.encryptedPrivate.contains("RELATED;TYPE=X-REMOTE-REL:Relation Two"))
        assertFalse(first.encryptedPrivate.contains("TYPE=FRIEND"))

        // 5. Keep duplicate standard lines and their unknown parameters opaque.
        assertTrue(first.encryptedPrivate.contains("BDAY;X-DUPLICATE=keep:2001-03-01"))
        assertTrue(first.encryptedPrivate.contains("ANNIVERSARY;X-DUPLICATE=keep:2010-06-15"))

        // 6. Keep an unrelated signed-card unknown canary.
        assertTrue(first.signed.contains("X-CONTAKO-DATE-CANARY;X-KEEP=yes:opaque-date-marker"))

        // 7. Reparse the rebuilt signed/encrypted partition.
        val reparsed = codec.decode(
            "primary",
            remote("fx-c-009"),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, first.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, first.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, first.clear),
            ),
        )
        assertEquals("--02-29", reparsed.valuesOf(ContactValueKind.ANNIVERSARY).single().value)

        // 8. A second serialization is byte-for-text deterministic.
        assertEquals(first, codec.encode(reparsed))

        // 9. Unknown custom-date extensions, including Unicode labels, remain opaque canaries.
        val customImport = decodeFixture(
            "fx-c-010",
            ContactCardType.Signed to fixture("fx-c-010-signed.vcf"),
        )
        assertTrue(customImport.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY).any {
            it.label == "X-CONTAKO-CUSTOM-DATE" && it.value == "--07-14"
        })
        assertTrue(codec.encode(customImport).signed.contains("X-LABEL=Jour-spécial"))

        // 10. Generated local custom dates stay local-only while standards still serialize.
        val withLocalOnly = decoded.copy(values = decoded.values +
            com.patmanak.contako.domain.model.ContactValue(
                id = "local-date",
                kind = ContactValueKind.CUSTOM_DATE,
                value = "--12-31",
                label = "Jour spécial",
                order = 0,
                metadata = mapOf("syncDisposition" to "LOCAL_ONLY"),
            ))
        val localOnlyEncoded = codec.encode(withLocalOnly)
        assertTrue(localOnlyEncoded.encryptedPrivate.contains("BDAY;VALUE=date:2000-02-29"))
        assertFalse(localOnlyEncoded.toString().contains("Jour spécial"))
        assertFalse(localOnlyEncoded.encryptedPrivate.contains("--12-31"))
    }

    @Test
    fun `05-DATE rejects impossible malformed and duplicate generated standard dates without inventing a year`() {
        fun date(value: String) = com.patmanak.contako.domain.model.ContactValue(
            id = "date-$value",
            kind = ContactValueKind.BIRTHDAY,
            value = value,
            order = 0,
        )
        listOf("2023-02-29", "--02-30", "02-29", "0000-01-01", "2020-2-01").forEach { invalid ->
            assertTrue(invalid, runCatching {
                codec.encode(com.patmanak.contako.domain.model.CanonicalContact(
                    accountId = "primary",
                    id = "invalid-date",
                    displayName = "Invalid Date",
                    values = listOf(date(invalid)),
                ))
            }.isFailure)
        }
        val yearless = com.patmanak.contako.domain.model.CanonicalContact(
            accountId = "primary",
            id = "yearless",
            displayName = "Yearless",
            values = listOf(date("--02-29")),
        )
        val encoded = codec.encode(yearless)
        assertTrue(encoded.encryptedPrivate.contains("BDAY:--0229"))
        assertFalse(encoded.encryptedPrivate.contains("2000-02-29"))
    }

    @Test
    fun `generated full and yearless dates use basic wire form and survive another encode`() {
        val contact = com.patmanak.contako.domain.model.CanonicalContact(
            accountId = "primary",
            id = "basic-dates",
            displayName = "Basic Dates",
            values = listOf(
                com.patmanak.contako.domain.model.ContactValue(
                    id = "birthday", kind = ContactValueKind.BIRTHDAY, value = "2000-02-29", order = 0,
                ),
                com.patmanak.contako.domain.model.ContactValue(
                    id = "anniversary", kind = ContactValueKind.ANNIVERSARY, value = "--03-14", order = 1,
                ),
            ),
        )
        val encoded = codec.encode(contact)
        assertTrue(encoded.encryptedPrivate.contains("BDAY:20000229"))
        assertTrue(encoded.encryptedPrivate.contains("ANNIVERSARY:--0314"))
        val decoded = codec.decode("primary", remote("basic-dates"), listOf(
            ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
        ))
        assertEquals(encoded, codec.encode(decoded))
    }

    @Test
    fun `FX-C-012 round-trips every advanced family in its required card partition`() {
        val decoded = decodeFixture(
            "fx-c-012",
            ContactCardType.Signed to fixture("fx-c-012-signed.vcf"),
            ContactCardType.EncryptedAndSigned to fixture("fx-c-012-private.vcf"),
            ContactCardType.ClearText to fixture("fx-c-012-clear.vcf"),
        )
        val kinds = decoded.values.map { it.kind }.toSet()

        assertTrue(kinds.containsAll(setOf(
            ContactValueKind.PUBLIC_KEY,
            ContactValueKind.LANGUAGE,
            ContactValueKind.TIME_ZONE,
            ContactValueKind.GENDER,
            ContactValueKind.MEMBER,
            ContactValueKind.CATEGORY,
            ContactValueKind.ROLE,
            ContactValueKind.LOGO,
            ContactValueKind.ANNIVERSARY,
            ContactValueKind.UNKNOWN_VCARD_PROPERTY,
        )))

        val first = codec.encode(decoded.copy(displayName = "Advanced Fixture Edited"))
        assertTrue(first.signed.contains("KEY;VALUE=uri;PREF=1:https://keys.example.test/public.asc"))
        assertTrue(first.encryptedPrivate.contains("LANG:fr-FR"))
        assertTrue(first.encryptedPrivate.contains("TZ:Europe/Paris"))
        assertTrue(first.encryptedPrivate.contains("GENDER:F;nonbinary"))
        assertTrue(first.encryptedPrivate.contains("MEMBER:urn:uuid:"))
        assertTrue(first.encryptedPrivate.contains("LOGO;VALUE=uri:"))
        assertTrue(first.encryptedPrivate.contains("X-CONTako-CANARY;X-KEEP=yes:unknown-value"))
        assertTrue(first.clear.contains("CATEGORIES:Friends"))
        assertTrue(first.clear.contains("CATEGORIES:Open Source"))

        val reparsed = codec.decode(
            "primary",
            remote("fx-c-012"),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, first.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, first.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, first.clear),
            ),
        )
        assertEquals(first, codec.encode(reparsed))
    }

    @Test
    fun `05-IMAGE FX-C-011 preserves ordered galleries preferences corrupt import and canary`() {
        val decoded = decodeFixture(
            "fx-c-011",
            ContactCardType.Signed to fixture("fx-c-011-signed.vcf"),
            ContactCardType.EncryptedAndSigned to fixture("fx-c-011-private.vcf"),
        )
        val photos = decoded.valuesOf(ContactValueKind.PHOTO)
        val logos = decoded.valuesOf(ContactValueKind.LOGO)

        assertEquals(3, photos.size)
        assertEquals(2, logos.size)
        assertEquals("https://img.example.test/photo-large.jpg", photos.single { it.isPrimary }.value)
        assertEquals("https://img.example.test/logo-primary.png", logos.single { it.isPrimary }.value)
        assertTrue(photos.single { it.value.startsWith("data:") }.metadata["vcardPref"] == "8")

        val reordered = decoded.copy(values = decoded.values.map { value ->
            when (value.value) {
                "https://img.example.test/photo-small.png" -> value.copy(
                    metadata = value.metadata + ("vcardPref" to "1"),
                    isPrimary = true,
                )
                "https://img.example.test/photo-large.jpg" -> value.copy(
                    metadata = value.metadata + ("vcardPref" to "2"),
                    isPrimary = false,
                )
                else -> value
            }
        })
        val first = codec.encode(reordered)
        assertTrue(first.encryptedPrivate.contains("X-CONTAKO-IMAGE-CANARY;X-KEEP=yes:opaque-image-marker"))
        assertEquals("ProtonPreparedVCard(REDACTED)", first.toString())
        assertFalse(first.toString().contains("photo-small"))

        val reparsed = codec.decode(
            "primary",
            remote("fx-c-011"),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, first.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, first.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, first.clear),
            ),
        )
        assertEquals("https://img.example.test/photo-small.png", reparsed.valuesOf(ContactValueKind.PHOTO)
            .single { it.isPrimary }.value)
        assertEquals(2, reparsed.valuesOf(ContactValueKind.LOGO).size)
        assertEquals(first, codec.encode(reparsed))
    }

    @Test
    fun `equivalent repeated parameters import losslessly and managed output emits one declaration`() {
        val privateCard = card("repeat-parameters", "BDAY;VALUE=text;value=\"TEXT\";X-CANARY=keep:around spring\r\n" +
            "NOTE;VALUE=text;VALUE=text:original note")
        val signedCard = card("repeat-parameters", "FN:Fixture\r\n" +
            "item1.EMAIL;PREF=1;pref=01;TYPE=home;TYPE=work;X-KEEP=a;X-KEEP=b:one@example.test\r\n" +
            "item2.EMAIL;PREF=2:two@example.test")
        val imported = decodeFixture("repeat-parameters",
            ContactCardType.Signed to signedCard,
            ContactCardType.EncryptedAndSigned to privateCard)
        assertTrue(imported.preservationEnvelope!!.rawProperties.values.contains(privateCard))
        assertTrue(imported.preservationEnvelope!!.rawProperties.values.contains(signedCard))
        assertEquals("around spring", imported.valuesOf(ContactValueKind.BIRTHDAY).single().value)
        assertEquals("one@example.test", imported.valuesOf(ContactValueKind.EMAIL).single { it.isPrimary }.value)
        val updated = imported.copy(values = imported.values.map {
            if (it.kind == ContactValueKind.NOTE) it.copy(value = "edited note") else it
        })
        val encoded = codec.encode(updated)
        val birthday = encoded.encryptedPrivate.lineSequence().single { it.substringBefore(':').contains("BDAY;") }
        assertEquals(1, Regex("(?i)(?:;VALUE=)").findAll(birthday.substringBefore(':')).count())
        assertTrue(birthday.contains("X-CANARY=keep"))
        assertTrue(encoded.signed.contains("TYPE=home;TYPE=work;X-KEEP=a;X-KEEP=b"))
        val decoded = decodeFixture("repeat-parameters", ContactCardType.Signed to encoded.signed,
            ContactCardType.EncryptedAndSigned to encoded.encryptedPrivate)
        assertEquals("edited note", decoded.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals("around spring", decoded.valuesOf(ContactValueKind.BIRTHDAY).single().value)
    }

    @Test
    fun `conflicting or invalid repeated singleton parameters remain rejected`() {
        listOf("PREF=1;PREF=2", "PREF=0;PREF=0", "PREF=101;PREF=101", "PREF=x;PREF=x",
            "VALUE=text;VALUE=uri", "VALUE=;VALUE=", "VALUE=text,uri;VALUE=text,uri").forEach { parameters ->
            val failure = runCatching {
                decodeFixture("repeat-rejected", ContactCardType.Signed to
                    card("repeat-rejected", "EMAIL;$parameters:fixture@example.test"))
            }.exceptionOrNull()
            assertTrue(failure is ProtonVCardParseFailure)
            assertEquals(com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.VCARD_PARSE_DUPLICATE_PARAMETER,
                (failure as ProtonVCardParseFailure).category)
        }
    }

    @Test
    fun `FX-C-013 rejects malformed structure mixed UID duplicate managed parameters and UTF8 overflow`() {
        assertTrue(runCatching {
            decodeFixture(
                "mixed",
                ContactCardType.Signed to fixture("fx-c-001-signed.vcf"),
                ContactCardType.EncryptedAndSigned to fixture("fx-c-006-private.vcf"),
            )
        }.isFailure)
        assertTrue(runCatching {
            decodeFixture(
                "duplicate-pref",
                ContactCardType.Signed to card("duplicate-pref", "EMAIL;PREF=1;PREF=2:a@example.test"),
            )
        }.isFailure)
        assertTrue(runCatching {
            decodeFixture(
                "multiple",
                ContactCardType.Signed to (card("multiple", "FN:One") + card("multiple", "FN:Two")),
            )
        }.isFailure)
        val utf8Overflow = card("oversized", "NOTE:${"😀".repeat(2_621_441)}")
        assertTrue(runCatching {
            decodeFixture("oversized", ContactCardType.Signed to utf8Overflow)
        }.isFailure)
    }

    private fun decodeFixture(id: String, vararg cards: Pair<ContactCardType, String>) = codec.decode(
        "primary",
        remote(id),
        cards.map { (type, raw) -> ProtonPlainContactCard(type, raw) },
    )

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/fixtures/vcard/$name")).readText()

    private fun remote(id: String) = Contact(
        userId = UserId("fixture-user"),
        id = ContactId(id),
        name = "Fixture",
        contactEmails = emptyList(),
    )

    private fun card(uid: String, property: String): String =
        "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:$uid\r\n$property\r\nEND:VCARD\r\n"
}
