package com.patmanak.contako.compatibility

import com.patmanak.contako.compatibility.CompatibilityGenerator.Action
import com.patmanak.contako.data.android.mapping.AndroidGroupProjectionPolicy
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouter
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouterException
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouterFailure
import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.data.proton.ProtonPlaintextVCardStructureFailure
import com.patmanak.contako.data.proton.unfoldBoundedVCard
import com.patmanak.contako.data.proton.validateSingleCompleteHydratedVCard
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.GroupMembership
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactEmail
import me.proton.core.contact.domain.entity.ContactEmailId
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.*
import org.junit.Test

class ContactCompatibilityBenchTest {
    private val codec = ProtonContactVCardCodec()
    private val mapper = CanonicalAndroidContactMapper()
    private val policy = AndroidGroupProjectionPolicy()

    private data class Expected(val fixture: CompatibilityGenerator.Fixture, val note: String, val phone: String)

    @Test fun generatedFormatAndNativeEditSequencesPreserveIndependentExpectations() {
        val profile = CompatibilityGenerator.profile
        val evidence = CompatibilityEvidence("VCARD_ANDROID_MAPPING")
        try {
            CompatibilityGenerator.seeds(profile).forEach { seed ->
                val actual = mutableMapOf<Int, CanonicalContact>()
                val expected = mutableMapOf<Int, Expected>()
                var liveNoChangePasses = 0
                val foreign = fixture(11, 5, false)
                val foreignBefore = foreign.copy(values = foreign.values.toList())
                CompatibilityGenerator.generate(seed, profile.steps).steps.forEachIndexed { index, step ->
                    if (step.action !in setOf(Action.IMPORT, Action.NATIVE_PHONE, Action.LOCAL_NOTE,
                            Action.DELETE, Action.NO_CHANGE)) return@forEachIndexed
                    evidence.step(seed, index, step)
                    when (step.action) {
                        Action.IMPORT -> {
                            actual[step.slot] = fixture(step.slot, step.variant, seed % 2L == 0L)
                            val f = CompatibilityGenerator.fixture(step.variant)
                            expected[step.slot] = Expected(f, f.note, f.phone)
                        }
                        Action.NATIVE_PHONE -> {
                            val before = actual.getValue(step.slot)
                            val baseline = mapper.project(before).let { snapshot ->
                                snapshot.copy(rows = snapshot.rows.mapIndexed { rowIndex, row ->
                                    row.copy(identity = row.identity.copy(providerRowId = rowIndex + 1L))
                                })
                            }
                            val replacement = CompatibilityGenerator.fixture(step.variant).replacementPhone
                            val observed = baseline.copy(rows = baseline.rows.map {
                                if (it.kind == AndroidRowKind.PHONE) it.copy(value = replacement) else it
                            })
                            val result = mapper.applyControlledDelta(before, baseline, observed)
                            actual[step.slot] = result.contact
                            expected[step.slot] = expected.getValue(step.slot).copy(phone = replacement)
                            assertEquals(before.values.filter { it.kind != ContactValueKind.PHONE },
                                result.contact.values.filter { it.kind != ContactValueKind.PHONE })
                            assertTrue(mapper.planProjection(observed, result.contact).operations.isEmpty())
                            assertEquals(result.contact, mapper.applyControlledDelta(result.contact, observed, observed).contact)
                        }
                        Action.LOCAL_NOTE -> {
                            val note = "Edited note ${step.variant}\nExact comma, semicolon; slash\\"
                            actual[step.slot] = actual.getValue(step.slot).let { contact -> contact.copy(
                                values = contact.values.map { if (it.kind == ContactValueKind.NOTE) it.copy(value = note) else it },
                            ) }
                            expected[step.slot] = expected.getValue(step.slot).copy(note = note)
                        }
                        Action.DELETE -> { actual.remove(step.slot); expected.remove(step.slot) }
                        Action.NO_CHANGE -> {
                            assertTrue("No-change mapping must exercise a live contact", actual.isNotEmpty())
                            val before = actual.toMap()
                            val snapshots = actual.mapValues { (_, contact) ->
                                mapper.project(contact).let { projected -> projected.copy(rows = projected.rows.mapIndexed { i, row ->
                                    row.copy(identity = row.identity.copy(providerRowId = i + 1L))
                                }) }
                            }
                            repeat(2) {
                                actual.toMap().forEach { (slot, contact) ->
                                    val snapshot = snapshots.getValue(slot)
                                    assertTrue(mapper.planProjection(snapshot, contact).operations.isEmpty())
                                    actual[slot] = mapper.applyControlledDelta(contact, snapshot, snapshot).contact
                                    verify(slot, actual.getValue(slot), expected.getValue(slot))
                                }
                                // Full canonical equality includes value identities and revision, not just labels.
                                assertEquals(before, actual)
                                liveNoChangePasses++
                            }
                        }
                        else -> error("Unsupported mapping command")
                    }
                    assertEquals(expected.keys, actual.keys)
                    actual.forEach { (slot, contact) -> verify(slot, contact, expected.getValue(slot)) }
                    assertEquals(foreignBefore, foreign)
                    evidence.checkBudget()
                }
                assertTrue("Generated mapping must include two live no-change passes", liveNoChangePasses >= 2)
            }
            evidence.finish("PASS")
        } catch (failure: Throwable) {
            evidence.finish(if (failure is CompatibilityBudgetExhausted) "NOT RUN" else "FAIL",
                if (failure is CompatibilityBudgetExhausted) "PROFILE_BUDGET" else "VCARD_ANDROID_MAPPING")
            throw failure
        }
    }

    @Test fun generatedInputsAndSchedulesRepeatExactlyIncludingCardOrderAndFolding() {
        CompatibilityGenerator.seeds(CompatibilityGenerator.profile).forEach { seed ->
            val first = CompatibilityGenerator.generate(seed, 30)
            assertEquals(first, CompatibilityGenerator.generate(seed, 30))
            assertTrue(first.steps.map { it.slot }.distinct().size <= CompatibilityGenerator.MAX_CONTACTS)
            repeat(6) { variant ->
                val a = fixture(variant, variant, false)
                val b = fixture(variant, variant, true)
                assertEquals(a.firstName, b.firstName)
                assertEquals(a.lastName, b.lastName)
                assertEquals(a.values.groupBy { it.kind }.mapValues { it.value.map { v -> v.value } },
                    b.values.groupBy { it.kind }.mapValues { it.value.map { v -> v.value } })
            }
        }
    }

    @Test fun finalStepAndPassPublicationCannotHideAnExhaustedBudget() {
        var time = 0L
        val evidence = CompatibilityEvidence("BUDGET_GUARD", budgetMillis = 1, nanoTime = { time })
        evidence.step(1, 0, CompatibilityGenerator.Step(Action.IMPORT, 0, 0))
        time = 999_999L
        evidence.checkBudget()
        time = 1_000_000L
        assertThrows(CompatibilityBudgetExhausted::class.java) { evidence.finish("PASS") }
        val report = java.io.File("build/qa-results/compatibility/BUDGET_GUARD-${CompatibilityGenerator.profile.name}.txt").readText()
        assertTrue(report.contains("result=NOT RUN"))
        assertTrue(report.contains("boundary=PROFILE_BUDGET"))
        assertFalse(report.contains("result=PASS"))
    }

    @Test fun reducerKeepsTheCausalLostAckRestartSpineAndDetectsInjectedRegression() {
        val original = CompatibilityGenerator.generate(1, 30).steps
        // Sensitivity mutant: a hypothetical replay-without-reconciliation is enabled only
        // after the real causal command pair. A different earlier failure is not equivalent.
        fun replay(steps: List<CompatibilityGenerator.Step>): CompatibilityGenerator.Violation? {
            val lost = steps.indexOfFirst { it.action == Action.LOST_ACK }
            val restart = steps.indexOfFirst { it.action == Action.RESTART }
            return if (lost >= 0 && restart > lost) CompatibilityGenerator.Violation("REPLAY", "DUPLICATE_CREATE") else null
        }
        val failure = requireNotNull(replay(original))
        val reduced = CompatibilityGenerator.reduce(original, failure.signature, replay = ::replay)
        assertEquals(failure, replay(reduced.steps))
        assertEquals(listOf(Action.LOST_ACK, Action.RESTART), reduced.steps.map { it.action })
        assertFalse(reduced.budgetExhausted)
        val exhausted = CompatibilityGenerator.reduce(original, failure.signature, maximumReplays = 0, replay = ::replay)
        assertEquals(original, exhausted.steps)
        assertTrue(exhausted.budgetExhausted)
        val other = CompatibilityGenerator.reduce(original, failure.signature) {
            CompatibilityGenerator.Violation("OTHER", "DIFFERENT_CAUSE")
        }
        assertEquals(original, other.steps)
    }

    @Test fun invalidEnvelopeIsRejectedWithoutChangingPreviouslyVerifiedData() {
        val contact = fixture(0, 0, false)
        val before = contact.copy(values = contact.values.toList())
        // Envelope verification precedes the canonical codec in the production hydration path.
        assertThrows(ProtonPlaintextVCardStructureFailure::class.java) {
            val bounded = validateSingleCompleteHydratedVCard("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:No end")
            codec.decode(ACCOUNT, remote(0), listOf(ProtonPlainContactCard(ContactCardType.Signed, bounded)))
        }
        assertEquals(before, contact)
        assertThrows(IllegalArgumentException::class.java) {
            val bounded = validateSingleCompleteHydratedVCard("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bad\u202E\r\nEND:VCARD\r\n")
            codec.decode(ACCOUNT, remote(0), listOf(ProtonPlainContactCard(ContactCardType.Signed, bounded)))
        }
        assertEquals(before, contact)
    }

    @Test fun unicodeFoldingAndUtf8LimitsHaveIndependentBoundaryOracles() {
        repeat(6) { variant ->
            val text = CompatibilityGenerator.fixture(variant).display
            val logical = "NOTE:$text"
            val folded = "NOTE:" + text.chunked(2).joinToString("\r\n ")
            assertEquals(listOf(logical), unfoldBoundedVCard(folded).lines)
            val bytes = logical.toByteArray(Charsets.UTF_8).size
            assertEquals(listOf(logical), unfoldBoundedVCard(logical, maxBytes = bytes + 1).lines)
            assertEquals(listOf(logical), unfoldBoundedVCard(logical, maxBytes = bytes).lines)
            assertThrows(IllegalArgumentException::class.java) { unfoldBoundedVCard(logical, maxBytes = bytes - 1) }
            assertEquals(listOf(logical), unfoldBoundedVCard(logical, maxLogicalLineChars = logical.length).lines)
            assertThrows(IllegalArgumentException::class.java) {
                unfoldBoundedVCard(logical, maxLogicalLineChars = logical.length - 1)
            }
        }
    }

    @Test fun generatedOpaqueRowsRemainExactAndForeignLocatorsAreRejectedAtTheRoutingBoundary() {
        val router = AndroidProviderMimeRouter()
        repeat(6) { variant ->
            fun row(id: Long, raw: Long = 42L) = AndroidOwnedDataRow(dataRowId = id, rawContactId = raw,
                mimeType = "vnd.compatibility.synthetic/opaque-$variant", canonicalValueId = "untrusted-opaque-claim",
                canonicalOrder = variant, linkedValueIdsEncoding = null, isPrimary = false, isSuperPrimary = false,
                stringSlots = List(14) { "Synthetic slot $it" }, binarySlot = byteArrayOf(variant.toByte(), 0, 127))
            val rows = (1L..12L).map(::row)
            val before = rows.map { it.binarySlot!!.clone() }
            val routed = router.route(42L, rows)
            assertTrue(routed.contactRows.rows.isEmpty())
            assertTrue(routed.groupMembershipRows.rows.isEmpty())
            rows.forEachIndexed { i, original ->
                assertSame(original, routed.unsupportedOwnedRows.rows[i])
                assertArrayEquals(before[i], routed.unsupportedOwnedRows.rows[i].binarySlot)
            }
            val mixed = assertThrows(AndroidProviderMimeRouterException::class.java) {
                router.route(42L, rows + row(13, 43))
            }
            assertEquals(AndroidProviderMimeRouterFailure.MIXED_RAW_CONTACTS, mixed.category)
            for (count in listOf(127, 128)) assertEquals(count,
                router.route(42L, (1L..count.toLong()).map(::row)).unsupportedOwnedRows.rows.size)
            val tooMany = assertThrows(AndroidProviderMimeRouterException::class.java) {
                router.route(42L, (1L..129L).map(::row))
            }
            assertEquals(AndroidProviderMimeRouterFailure.BOUND_EXCEEDED, tooMany.category)
        }
    }

    private fun verify(slot: Int, contact: CanonicalContact, e: Expected) {
        assertEquals(ACCOUNT, contact.accountId)
        assertEquals("synthetic-$slot", contact.id)
        assertEquals(e.fixture.given, contact.firstName)
        assertEquals(e.fixture.family, contact.lastName)
        assertEquals(e.fixture.display, contact.displayName)
        assertEquals(e.note, contact.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals(e.phone, contact.valuesOf(ContactValueKind.PHONE).single().value)
        assertEquals(e.fixture.date, contact.valuesOf(ContactValueKind.BIRTHDAY).single().value)
        assertEquals(e.fixture.street, contact.valuesOf(ContactValueKind.POSTAL_ADDRESS).single().components["street"])
        val emails = contact.valuesOf(ContactValueKind.EMAIL)
        assertEquals(listOf(EMAIL, EMAIL), emails.map { it.value })
        assertEquals(listOf("email-$slot-0", "email-$slot-1"), emails.map { it.metadata[PROTON_EMAIL_ID_KEY] })
        assertEquals(listOf("g1,g2", "g2,g3"), emails.map { it.metadata[PROTON_GROUP_IDS_KEY] })
        assertEquals(2, emails.map { it.id }.distinct().size)
        val groups = listOf(
            ContactGroup(ACCOUNT, "g1", "One", memberships = listOf(GroupMembership(contact.id, emails[0].id))),
            ContactGroup(ACCOUNT, "g2", "Two", memberships = emails.map { GroupMembership(contact.id, it.id) }),
            ContactGroup(ACCOUNT, "g3", "Three", memberships = listOf(GroupMembership(contact.id, emails[1].id))),
        )
        assertEquals(setOf("g1", "g2"), policy.projectMemberships(contact, groups).canonicalGroupIds)
        val switched = contact.copy(values = contact.values.map {
            // The editor's preference action updates vCard PREF metadata. isPrimary alone is
            // a derived convenience and does not replace the canonical ranking contract.
            if (it.kind == ContactValueKind.EMAIL) it.copy(metadata = it.metadata +
                (CanonicalPrimaryValuePolicy.VCARD_PREF_METADATA to if (it.id == emails[1].id) "1" else "2")) else it
        })
        assertEquals(setOf("g2", "g3"), policy.projectMemberships(switched, groups).canonicalGroupIds)
        val result = policy.applyObservedMemberships(contact, groups, setOf("g3"))
        assertEquals(setOf("g3"), policy.projectMemberships(contact, result.updatedGroups).canonicalGroupIds)
        assertEquals(setOf("g2", "g3"), policy.projectMemberships(switched, result.updatedGroups).canonicalGroupIds)
        val encoded = codec.encode(contact)
        assertTrue(encoded.encryptedPrivate.contains(e.fixture.opaque))
        assertTrue(encoded.encryptedPrivate.contains(e.fixture.date))
        assertTrue(encoded.encryptedPrivate.contains(escape(e.note)))
        assertTrue(encoded.encryptedPrivate.contains(e.phone))
        assertEquals(2, encoded.signed.lineSequence().count { it.contains("EMAIL") && it.endsWith(EMAIL) })
        assertFalse(encoded.signed.contains(escape(e.note)))
        assertFalse(encoded.clear.contains(escape(e.note)))
    }

    private fun fixture(slot: Int, variant: Int, reversed: Boolean): CanonicalContact {
        val f = CompatibilityGenerator.fixture(variant)
        val public = card("FN:${escape(f.display)}\r\nITEM1.EMAIL;TYPE=home;PREF=1;PREF=1:$EMAIL\r\nITEM2.EMAIL;TYPE=work:$EMAIL")
        val private = card("N:${escape(f.family)};${escape(f.given)};;;\r\nTEL;TYPE=cell:${f.phone}\r\nNOTE:${escape(f.note)}\r\n" +
            "BDAY:${f.date}\r\nADR;TYPE=work:;;${escape(f.street)};Paris;;75000;France\r\n${f.opaque}")
        val cards = listOf(ProtonPlainContactCard(ContactCardType.Signed, validateSingleCompleteHydratedVCard(public)),
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, validateSingleCompleteHydratedVCard(private)))
        return codec.decode(ACCOUNT, remote(slot), if (reversed) cards.reversed() else cards)
    }

    private fun remote(slot: Int) = Contact(UserId("synthetic-user"), ContactId("synthetic-$slot"), "Index name", List(2) { index ->
        ContactEmail(userId = UserId("synthetic-user"), id = ContactEmailId("email-$slot-$index"),
            name = "Index name", email = EMAIL, defaults = 1, order = index, contactId = ContactId("synthetic-$slot"),
            canonicalEmail = null, labelIds = if (index == 0) listOf("g1", "g2") else listOf("g2", "g3"),
            isProton = false, lastUsedTime = 0)
    })

    private fun card(lines: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:shared-synthetic-uid\r\n$lines\r\nEND:VCARD\r\n"
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    private companion object {
        const val ACCOUNT = "synthetic-account"
        const val EMAIL = "repeated@example.test"
    }
}
