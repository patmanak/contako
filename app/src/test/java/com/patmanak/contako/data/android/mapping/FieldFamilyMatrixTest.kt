package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.policy.ContactValidation
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldFamilyMatrixTest {
    private val vCardCodec = ProtonContactVCardCodec()
    private val androidMapper = CanonicalAndroidContactMapper()

    @Test
    fun `05-FIELD FX-C-001 through FX-C-012 execute the deterministic ten-stage matrix`() {
        val fixtures = listOf(
            fixture("FX-C-001", signed = true, private = true),
            fixture("FX-C-002", signed = true, private = true),
            fixture("FX-C-003", signed = true, private = true),
            fixture("FX-C-004", signed = true, private = true),
            fixture("FX-C-005", signed = true, private = true),
            fixture("FX-C-006", signed = true, private = true),
            fixture("FX-C-007", signed = true, private = true),
            fixture("FX-C-008", signed = true, private = true),
            fixture("FX-C-009", signed = true, private = true),
            fixture("FX-C-010", signed = true),
            fixture("FX-C-011", signed = true, private = true),
            fixture("FX-C-012", signed = true, private = true, clear = true),
        )
        assertEquals((1..12).map { "FX-C-${it.toString().padStart(3, '0')}" }, fixtures.map(FieldFixture::id))

        fixtures.forEach { fixture ->
            // 1 parse; 2 canonical create; 3 simulated remote merge.
            val parsed = decode(fixture)
            assertEquals(fixture.id.lowercase(), parsed.remoteVCardUid)
            val created = parsed.copy(id = "${parsed.id}-local", remoteContactId = parsed.remoteContactId)
            val firstWire = vCardCodec.encode(created)
            val merged = decodePrepared(created.id, firstWire)

            // 4 offline restart/upload through the deterministic provider-neutral snapshot codec.
            val projected = androidMapper.project(merged)
            val restartedProjection = AndroidContactSnapshotBinaryCodec.decode(
                AndroidContactSnapshotBinaryCodec.encode(projected),
            )
            assertEquals(projected, restartedProjection)

            // 5 Android delta; 6 Android reprojection; 7 a second pass with zero semantic delta.
            val delta = androidMapper.applyControlledDelta(merged, restartedProjection, restartedProjection)
            assertSame(merged, delta.contact)
            assertFalse(delta.hasChanges)
            val reprojected = androidMapper.project(delta.contact)
            assertEquals(restartedProjection, reprojected)
            assertFalse(androidMapper.applyControlledDelta(delta.contact, reprojected, reprojected).hasChanges)

            // 8 unknown/preservation envelope; 9 cardinality; 10 deterministic second serialization.
            assertEquals(
                parsed.values.filter { it.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY }
                    .map { it.label to it.value }.toSet(),
                merged.values.filter { it.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY }
                    .map { it.label to it.value }.toSet(),
            )
            assertTrue(merged.values.groupBy { it.kind }.all { (kind, values) ->
                kind !in SINGLETON_KINDS || values.size == 1
            })
            assertEquals(firstWire, vCardCodec.encode(merged))
        }
    }

    @Test
    fun `R-MAP-007 drafts survive validation while existing nameless contacts remain uploadable`() {
        val emailOnly = decode(fixture("FX-C-004", signed = true, private = true))
            .copy(firstName = "", lastName = "", displayName = "", remoteContactId = null)
        val invalidEmailOnly = decode(FieldFixture(
            id = "FX-C-004-invalid",
            cards = listOf(ContactCardType.Signed to resource("fx-c-004-invalid-signed.vcf")),
        )).copy(firstName = "", lastName = "", displayName = "", remoteContactId = null)

        assertEquals(setOf("MISSING_NAME"), ContactValidation.actionRequiredReasons(emailOnly, true))
        assertEquals(
            setOf("MISSING_NAME", "INVALID_EMAIL"),
            ContactValidation.actionRequiredReasons(invalidEmailOnly, true),
        )
        val corrected = emailOnly.copy(firstName = "Corrected")
        assertTrue(ContactValidation.actionRequiredReasons(corrected, true).isEmpty())

        val existingNameless = emailOnly.copy(remoteContactId = "synthetic-existing")
        assertTrue(ContactValidation.actionRequiredReasons(existingNameless, false).isEmpty())
        assertTrue(ContactValidation.actionRequiredReasons(existingNameless.copy(firstName = "Restored"), false).isEmpty())
    }

    @Test
    fun `FX-C-005 preserves Unicode and rejects bidi controls at import and edit boundaries`() {
        val unicode = decode(fixture("FX-C-005", signed = true, private = true))
        assertTrue(unicode.displayName.contains("山田"))
        assertTrue(unicode.displayName.contains("🙂"))
        assertTrue(runCatching {
            decode(FieldFixture(
                "FX-C-005-bidi",
                listOf(ContactCardType.Signed to resource("fx-c-005-bidi-rejected.vcf")),
            ))
        }.isFailure)
        assertTrue(runCatching { vCardCodec.encode(unicode.copy(displayName = "safe\u202Eunsafe")) }.isFailure)
    }

    @Test
    fun `FX-C-007 unknown types cardinality primary deletion and zero delta remain scoped`() {
        val contact = decode(fixture("FX-C-007", signed = true, private = true))
        val emails = contact.valuesOf(ContactValueKind.EMAIL)
        assertEquals(listOf("8", "1", "3"), emails.map { it.metadata["vcardPref"] })
        assertTrue(contact.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY).any {
            it.label == "X-CONTAKO-MULTI-CANARY"
        })
        val baseline = androidMapper.project(contact)
        val primaryEmail = baseline.rows.single { it.kind == AndroidRowKind.EMAIL && it.isPrimary }
        val deleted = androidMapper.applyControlledDelta(
            contact,
            baseline,
            baseline.copy(rows = baseline.rows.filterNot { it.identity.canonicalValueId == primaryEmail.identity.canonicalValueId }),
        )
        assertEquals(2, deleted.contact.valuesOf(ContactValueKind.EMAIL).size)
        assertTrue(deleted.contact.values.any { it.label == "X-CONTAKO-MULTI-CANARY" })
        val second = androidMapper.project(deleted.contact)
        assertFalse(androidMapper.applyControlledDelta(deleted.contact, second, second).hasChanges)
    }

    @Test
    fun `FX-C-013 committed malformed oversized encoding parameter and crypto-context vectors fail closed`() {
        val artifacts = listOf(
            resource("fx-c-013-malformed.vcf"),
            resource("fx-c-013-invalid-utf8.vcf"),
            resource("fx-c-013-duplicate-parameters.vcf"),
        )
        artifacts.forEachIndexed { index, raw ->
            assertTrue(runCatching {
                decode(FieldFixture("FX-C-013-$index", listOf(ContactCardType.Signed to raw)))
            }.isFailure)
        }
        val recipe = resource("fx-c-013-oversized.seed")
        assertTrue(recipe.contains("10485761"))
        val oversized = card("fx-c-013-oversized", "NOTE:${"x".repeat(10 * 1_024 * 1_024 + 1)}")
        assertTrue(runCatching {
            decode(FieldFixture("FX-C-013-oversized", listOf(ContactCardType.Signed to oversized)))
        }.isFailure)
        val cryptoVector = resource("fx-c-013-forged-signature.json")
        assertTrue(cryptoVector.contains("\"verification\":\"invalid\""))
        assertTrue(cryptoVector.contains("\"keyContext\":\"synthetic-wrong-key\""))
        assertTrue(cryptoVector.contains("\"expected\":\"fail-closed-by-maintained-proton-crypto\""))
    }

    private fun fixture(id: String, signed: Boolean = false, private: Boolean = false, clear: Boolean = false): FieldFixture {
        val stem = id.lowercase()
        return FieldFixture(id, buildList {
            if (signed) add(ContactCardType.Signed to resource("$stem-signed.vcf"))
            if (private) add(ContactCardType.EncryptedAndSigned to resource("$stem-private.vcf"))
            if (clear) add(ContactCardType.ClearText to resource("$stem-clear.vcf"))
        })
    }

    private fun decode(fixture: FieldFixture): CanonicalContact = vCardCodec.decode(
        "matrix-account",
        remote(fixture.id.lowercase()),
        fixture.cards.map { (type, raw) -> ProtonPlainContactCard(type, raw) },
    )

    private fun decodePrepared(id: String, prepared: com.patmanak.contako.data.proton.ProtonPreparedVCard) =
        vCardCodec.decode(
            "matrix-account",
            remote(id),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, prepared.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, prepared.encryptedPrivate),
                ProtonPlainContactCard(ContactCardType.ClearText, prepared.clear),
            ),
        )

    private fun resource(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/vcard/$name"),
    ).readText()

    private fun remote(id: String) = Contact(
        userId = UserId("matrix-user"),
        id = ContactId(id),
        name = "Matrix Fixture",
        contactEmails = emptyList(),
    )

    private fun card(uid: String, property: String) =
        "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:$uid\r\n$property\r\nEND:VCARD\r\n"

    private data class FieldFixture(
        val id: String,
        val cards: List<Pair<ContactCardType, String>>,
    )

    private companion object {
        val SINGLETON_KINDS = setOf(
            ContactValueKind.BIRTHDAY,
            ContactValueKind.ANNIVERSARY,
            ContactValueKind.GENDER,
        )
    }
}
