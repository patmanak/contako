package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.provider.AndroidProviderProjectionResult
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import com.patmanak.contako.data.sync.AndroidPostWriteProjectionProof
import com.patmanak.contako.data.sync.AndroidProjectionRepairCategory
import com.patmanak.contako.data.sync.classifyAndroidProjectionMismatch
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.*
import org.junit.Test

class AndroidPostWriteEquivalenceTest {
    private val mapper = CanonicalAndroidContactMapper()
    private val context = AndroidInteroperabilityContext(AccountScope("synthetic-account"), "synthetic-android", 4, 2)
    private val desired = AndroidContactSnapshot("contact", listOf(
        AndroidContactRow(AndroidValueIdentity("name"), AndroidRowKind.STRUCTURED_NAME, value = "Élie Straße",
            components = mapOf(AndroidComponent.DISPLAY_NAME to "Élie Straße", AndroidComponent.GIVEN_NAME to "Élie",
                AndroidComponent.FAMILY_NAME to "Straße")),
        AndroidContactRow(AndroidValueIdentity("phone-a"), AndroidRowKind.PHONE, value = "+12025550123", isPrimary = true,
            semanticType = AndroidSemanticType.MOBILE),
        AndroidContactRow(AndroidValueIdentity("phone-b"), AndroidRowKind.PHONE, value = "+12025550124", order = 1),
        AndroidContactRow(AndroidValueIdentity("email-a"), AndroidRowKind.EMAIL, value = "a@example.test", isPrimary = true),
        AndroidContactRow(AndroidValueIdentity("email-b"), AndroidRowKind.EMAIL, value = "b@example.test", order = 1),
        AndroidContactRow(AndroidValueIdentity("note"), AndroidRowKind.NOTE, value = "Synthetic preserved note"),
        AndroidContactRow(AndroidValueIdentity("date"), AndroidRowKind.BIRTHDAY, value = "1980-12-31"),
        AndroidContactRow(AndroidValueIdentity("org"), AndroidRowKind.ORGANIZATION, value = "Atelier Élan",
            components = mapOf(AndroidComponent.COMPANY to "Atelier Élan", AndroidComponent.DEPARTMENT to "Recherche",
                AndroidComponent.TITLE to "Ingénieur")),
    ))

    @Test fun approvedPostWriteNormalizationsAreEquivalentWithoutChangingTheStrictFingerprint() {
        val observations = listOf(
            change("note") { it.copy(value = " Synthetic   preserved\tnote ") },
            desired.copy(rows = desired.rows.reversed().map { it.copy(order = it.order + 3) }),
            change("phone-a") { it.copy(isPrimary = false, isSuperPrimary = true) },
            change("phone-a") { it.copy(value = "+1 (202) 555-0123") },
            change("phone-a") { it.copy(semanticType = AndroidSemanticType.CUSTOM, customLabel = "Cell") },
            change("name") { it.copy(components = it.components + mapOf(AndroidComponent.GIVEN_NAME to "Straße",
                AndroidComponent.FAMILY_NAME to "Élie")) },
            change("org") { it.copy(value = "Atelier Élan, Recherche", components = it.components + mapOf(
                AndroidComponent.COMPANY to "Atelier Élan, Recherche", AndroidComponent.DEPARTMENT to "")) },
        )
        observations.forEach { actual ->
            assertNotEquals(mapper.fingerprint(desired), mapper.fingerprint(actual))
            assertEquals(mapper.fingerprint(desired), mapper.fingerprint(mapper.postWriteComparisonSnapshot(actual, desired)))
            assertNotNull(proof(actual))
            // The strict observation and its native values survive: comparison is not adoption.
            assertNotEquals(desired, actual)
        }
    }

    @Test fun realLossAndUnapprovedSemanticChangesRemainDifferent() {
        val observations = listOf(
            desired.copy(rows = desired.rows.filterNot { it.identity.canonicalValueId == "note" }),
            change("note") { it.copy(value = "Synthetic preserved") },
            change("phone-a") { it.copy(value = "+12025550129") },
            change("phone-a") { it.copy(value = "12025550123") },
            change("phone-a") { it.copy(semanticType = AndroidSemanticType.CUSTOM, customLabel = "My mobile") },
            change("email-a") { it.copy(isPrimary = false) },
            change("email-a") { it.copy(isSuperPrimary = true) },
            change("date") { it.copy(value = "--12-31") },
            change("date") { it.copy(value = "1980-12-30") },
            change("name") { it.copy(components = it.components + (AndroidComponent.GIVEN_NAME to "Elie")) },
            change("name") { it.copy(components = it.components + (AndroidComponent.GIVEN_NAME to "Élie Élie")) },
            change("org") { it.copy(value = "Atelier", components = it.components + (AndroidComponent.COMPANY to "Atelier")) },
        )
        observations.forEach { actual ->
            assertNotEquals(mapper.fingerprint(desired), mapper.fingerprint(mapper.postWriteComparisonSnapshot(actual, desired)))
            assertNull(proof(actual))
        }
        val national = AndroidContactSnapshot("contact", listOf(AndroidContactRow(AndroidValueIdentity("p"), AndroidRowKind.PHONE,
            value = "0612345678")))
        val international = national.copy(rows = national.rows.map { it.copy(value = "+33612345678") })
        assertNotEquals(mapper.fingerprint(national), mapper.fingerprint(mapper.postWriteComparisonSnapshot(international, national)))
    }

    @Test fun readbackVersionMustMatchTheActualWriteReceiptAndProofRemainsOwnerBound() {
        val actual = change("phone-a") { it.copy(value = "+1 202-555-0123") }
        assertNull(proof(actual, writeVersion = null))
        assertNull(proof(actual, writeVersion = 8, observedVersion = 9))
        val valid = requireNotNull(proof(actual))
        val expectedHash = mapper.fingerprint(desired).sha256Hex
        val observedHash = mapper.fingerprint(actual).sha256Hex
        assertTrue(valid.matches(context, "contact", 7, 10, "remote", expectedHash, observedHash))
        assertFalse(valid.matches(context.copy(providerEpoch = 3), "contact", 7, 10, "remote", expectedHash, observedHash))
        assertFalse(valid.matches(context.copy(androidAccountName = "other"), "contact", 7, 10, "remote", expectedHash, observedHash))
        assertFalse(valid.matches(context, "contact", 8, 10, "remote", expectedHash, observedHash))
        assertFalse(valid.matches(context, "contact", 7, 11, "remote", expectedHash, observedHash))
        assertFalse(valid.matches(context, "contact", 7, 10, "other", expectedHash, observedHash))
        assertFalse(valid.matches(context, "contact", 7, 10, "remote", expectedHash, mapper.fingerprint(desired).sha256Hex))
    }

    @Test fun toleratedProviderFormatDoesNotBecomeAProtonEditAndLaterNativeEditStillArrives() {
        val canonical = CanonicalContact(context.account.value, "contact", displayName = "Élie Straße", values = listOf(
            ContactValue("phone", ContactValueKind.PHONE, "+12025550123", order = 0),
            ContactValue("note", ContactValueKind.NOTE, "Synthetic retained note", order = 0),
        ))
        val projected = mapper.project(canonical)
        val observed = projected.copy(rows = projected.rows.mapIndexed { index, row ->
            (if (row.kind == AndroidRowKind.PHONE) row.copy(value = "+1 (202) 555-0123", isPrimary = false) else row)
                .copy(identity = row.identity.copy(providerRowId = index + 1L), order = row.order + 5)
        })
        assertEquals(mapper.fingerprint(projected), mapper.fingerprint(mapper.postWriteComparisonSnapshot(observed, projected)))
        // Completion stores this observed snapshot. No-op ingestion compares native to native.
        assertEquals(canonical, mapper.applyControlledDelta(canonical, observed, observed).contact)
        val nativeEdit = observed.copy(rows = observed.rows.map { row ->
            if (row.kind == AndroidRowKind.PHONE) row.copy(value = "+1 (202) 555-0129") else row
        })
        val changed = mapper.applyControlledDelta(canonical, observed, nativeEdit)
        assertEquals(setOf("phone"), changed.changedValueIds)
        assertEquals("+1 (202) 555-0129", changed.contact.valuesOf(ContactValueKind.PHONE).single().value)
        assertEquals(canonical.valuesOf(ContactValueKind.NOTE), changed.contact.valuesOf(ContactValueKind.NOTE))
    }

    @Test fun diagnosticIgnoresApprovedDifferencesOnlyWhenTheCallerHasThePostWriteProof() {
        val formatted = change("phone-a") { it.copy(value = "+1 202-555-0123") }
        val missingYear = formatted.copy(rows = formatted.rows.map { if (it.kind == AndroidRowKind.BIRTHDAY) it.copy(value = "--12-31") else it })
        assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_OTHER,
            classifyAndroidProjectionMismatch(desired, missingYear, mapper))
        assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_DATE,
            classifyAndroidProjectionMismatch(desired, missingYear, mapper, allowPostWriteEquivalences = true))
    }

    private fun change(id: String, mutation: (AndroidContactRow) -> AndroidContactRow) =
        desired.copy(rows = desired.rows.map { if (it.identity.canonicalValueId == id) mutation(it) else it })

    private fun proof(actual: AndroidContactSnapshot, writeVersion: Long? = 8, observedVersion: Long = 8) =
        AndroidPostWriteProjectionProof.create(context, "contact", 7, 10, "remote",
            AndroidProviderProjectionResult.Applied(1, rawContactVersion = writeVersion), observedVersion, null,
            desired, actual, mapper)
}
