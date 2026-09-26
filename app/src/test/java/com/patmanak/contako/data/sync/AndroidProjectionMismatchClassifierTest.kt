package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.*
import com.patmanak.contako.domain.model.CanonicalContact
import org.junit.Assert.*
import org.junit.Test

class AndroidProjectionMismatchClassifierTest {
    private val mapper = CanonicalAndroidContactMapper()
    private val phone = AndroidContactRow(AndroidValueIdentity("phone"), AndroidRowKind.PHONE, value = "123")

    @Test fun acceptedGeneratedNameDoesNotHideTheActualPhoneDifference() {
        val desired = mapper.project(CanonicalContact("account", "contact", displayName = "Ada Lovelace"))
            .let { it.copy(rows = it.rows + phone) }
        val actual = desired.copy(rows = desired.rows.map { row -> when (row.kind) {
            AndroidRowKind.STRUCTURED_NAME -> row.copy(components = row.components + mapOf(
                AndroidComponent.GIVEN_NAME to "Ada", AndroidComponent.FAMILY_NAME to "Lovelace"))
            AndroidRowKind.PHONE -> row.copy(value = "456")
            else -> row
        } })
        assertNotEquals(mapper.fingerprint(desired), mapper.fingerprint(mapper.normalizeGeneratedName(actual, desired)))
        assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_OTHER,
            classifyAndroidProjectionMismatch(desired, actual, mapper) { _, _, _ -> fail("Accepted name must not be reported") })
    }

    @Test fun emptyComponentsAndImplicitPrimaryFlagsDoNotHideLaterDifferences() {
        val email = AndroidContactRow(AndroidValueIdentity("email"), AndroidRowKind.EMAIL, value = "a@example.test", isPrimary = true)
        val date = AndroidContactRow(AndroidValueIdentity("date"), AndroidRowKind.BIRTHDAY, value = "--12-10", isPrimary = true)
        val desired = AndroidContactSnapshot("contact", listOf(email, date, phone))
        val actual = desired.copy(rows = listOf(
            email.copy(isPrimary = false, components = mapOf(AndroidComponent.DISPLAY_NAME to "")),
            date.copy(isPrimary = false, isSuperPrimary = true), phone.copy(value = "456"),
        ))
        assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_OTHER,
            classifyAndroidProjectionMismatch(desired, actual, mapper))
    }

    @Test fun comparisonViewPreservesFingerprintAndLeavesSourceSnapshotsUntouched() {
        val rows = AndroidRowKind.entries.mapIndexed { index, kind -> AndroidContactRow(
            AndroidValueIdentity("value-$index", index.toLong() + 1), kind, value = "content",
            isPrimary = true, isSuperPrimary = true, binaryReference = "",
            components = mapOf(AndroidComponent.GIVEN_NAME to "", AndroidComponent.COUNTRY to "content"),
        ) }
        val source = AndroidContactSnapshot("contact", rows)
        val comparison = mapper.projectionComparisonSnapshot(source)
        assertEquals(mapper.fingerprint(source), mapper.fingerprint(comparison))
        assertTrue(source.rows.all { it.identity.providerRowId != null && it.isPrimary && it.isSuperPrimary })
        assertTrue(source.rows.all { it.components.containsKey(AndroidComponent.GIVEN_NAME) })
        assertTrue(comparison.rows.all { it.identity.providerRowId == null })
    }

    @Test fun componentDiagnosticReportsOnlyFieldAndDifferenceEnums() {
        val cases = listOf(
            "" to ("value" to AndroidComponentDifference.EXPECTED_EMPTY),
            "value" to ("" to AndroidComponentDifference.OBSERVED_EMPTY),
            "value" to (" value " to AndroidComponentDifference.WHITESPACE_ONLY),
            "before" to ("after" to AndroidComponentDifference.DIFFERENT),
        )
        cases.forEach { (before, afterAndKind) ->
            val desired = AndroidContactSnapshot("contact", listOf(AndroidContactRow(
                AndroidValueIdentity("address"), AndroidRowKind.POSTAL_ADDRESS,
                components = mapOf(AndroidComponent.STREET to before),
            )))
            val actual = desired.copy(rows = desired.rows.map { it.copy(
                components = mapOf(AndroidComponent.STREET to afterAndKind.first),
            ) })
            val observed = mutableListOf<Triple<AndroidRowKind, AndroidComponent, AndroidComponentDifference>>()
            assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_COMPONENTS,
                classifyAndroidProjectionMismatch(desired, actual, mapper) { kind, component, difference ->
                    observed += Triple(kind, component, difference)
                })
            assertEquals(listOf(Triple(AndroidRowKind.POSTAL_ADDRESS, AndroidComponent.STREET, afterAndKind.second)), observed)
        }
    }

    @Test fun explicitNamesAreNotNormalizedAwayAndObserverFailureCannotChangeResult() {
        val desired = mapper.project(CanonicalContact("account", "contact", displayName = "Ada", firstName = "Ada"))
        val actual = desired.copy(rows = desired.rows.map { it.copy(
            components = it.components + (AndroidComponent.GIVEN_NAME to "Grace"),
        ) })
        assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_COMPONENTS,
            classifyAndroidProjectionMismatch(desired, actual, mapper) { _, _, _ -> error("observer unavailable") })
        assertNotEquals(mapper.fingerprint(desired), mapper.fingerprint(mapper.normalizeGeneratedName(actual, desired)))
    }
}
