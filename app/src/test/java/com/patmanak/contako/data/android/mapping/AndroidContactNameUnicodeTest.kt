package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.*
import org.junit.Test

class AndroidContactNameUnicodeTest {
    private val mapper = CanonicalAndroidContactMapper()
    private val names = listOf("Straße", "Groß", "ẞ", "Zoë", "Élève", "François", "Ångström",
        "Zoe\u0308", "\"Alias\"", "O’Connor", "名前", "😀", "Gro\uFFFD", "Jean\u00A0Luc", "Zo\u200Be")

    @Test fun providerSplitOfEmailAliasNeverBecomesCanonicalNameDuringUnrelatedEdit() {
        val alias = "person@example.test"
        val canonical = contact(alias).copy(firstName = "", lastName = "", values = listOf(
            ContactValue("synthetic-note", ContactValueKind.NOTE, "Original note", order = 0),
        ))
        val desired = mapper.project(canonical)
        val baseline = located(desired).let { snapshot -> snapshot.copy(rows = snapshot.rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(components = row.components + mapOf(
                AndroidComponent.GIVEN_NAME to "person@example", AndroidComponent.FAMILY_NAME to "test")) else row
        }) }
        assertTrue(mapper.planProjection(baseline, canonical).operations.isEmpty())
        assertEquals(canonical, mapper.applyControlledDelta(canonical, baseline, baseline).contact)
        val observed = baseline.copy(rows = baseline.rows.map { row ->
            if (row.kind == AndroidRowKind.NOTE) row.copy(value = "Edited note") else row
        })
        val adopted = mapper.applyControlledDelta(canonical, baseline, observed).contact
        assertEquals(alias, adopted.displayName)
        assertEquals("", adopted.firstName)
        assertEquals("", adopted.lastName)
        assertFalse(adopted.values.any { it.kind == ContactValueKind.STRUCTURED_NAME })
        assertEquals("Edited note", adopted.values.single { it.kind == ContactValueKind.NOTE }.value)
        // A genuine native rename must still be visible against the actual provider baseline.
        val renamed = baseline.copy(rows = baseline.rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(value = "Edited test",
                components = row.components + mapOf(AndroidComponent.DISPLAY_NAME to "Edited test",
                    AndroidComponent.GIVEN_NAME to "Edited")) else row
        })
        val changed = mapper.applyControlledDelta(canonical, baseline, renamed).contact
        assertEquals("Edited", changed.firstName)
        assertEquals("test", changed.lastName)
        assertEquals(alias, changed.displayName)
    }

    @Test fun unicodeNamesSurviveProjectionAndDurableSnapshotWithoutFalseEdits() {
        names.forEach { name ->
            val contact = contact(name)
            val desired = mapper.project(contact)
            val actual = located(desired)
            val reread = AndroidContactSnapshotBinaryCodec.decode(AndroidContactSnapshotBinaryCodec.encode(actual))
            assertEquals(name, reread.rows.single().value)
            assertEquals(name, reread.rows.single().components[AndroidComponent.GIVEN_NAME])
            assertEquals(actual, reread)
            assertTrue(mapper.planProjection(reread, contact).operations.isEmpty())
            assertEquals(contact, mapper.applyControlledDelta(contact, actual, reread).contact)
        }
    }

    @Test fun displayOnlyUnicodeNameSplitByProviderConvergesWithoutInventingCanonicalNames() {
        names.forEach { name ->
            val contact = contact(name).copy(displayName = "$name Example", firstName = "", lastName = "")
            val desired = mapper.project(contact)
            val actual = located(desired).let { snapshot -> snapshot.copy(rows = snapshot.rows.map { row ->
                row.copy(components = row.components + mapOf(AndroidComponent.GIVEN_NAME to name,
                    AndroidComponent.FAMILY_NAME to "Example"))
            }) }
            assertTrue(mapper.planProjection(actual, contact).operations.isEmpty())
            assertEquals(contact, mapper.applyControlledDelta(contact, actual, actual).contact)
        }
    }

    @Test fun genuinelyDifferentUnicodeNamesAreNotSilentlyAcceptedAsEqual() {
        listOf("Straße" to "Strasse", "Zoë" to "Zoe", "Zoë" to "Zoe\u0308",
            "Gro\uFFFD" to "Groß", "Zo\u200Be" to "Zoe").forEach { (before, after) ->
            val contact = contact(before)
            val desired = mapper.project(contact)
            val actual = located(desired).let { snapshot -> snapshot.copy(rows = snapshot.rows.map { row ->
                row.copy(value = after, components = row.components + mapOf(
                    AndroidComponent.DISPLAY_NAME to after, AndroidComponent.GIVEN_NAME to after))
            }) }
            assertFalse(mapper.planProjection(actual, contact).operations.isEmpty())
            assertNotEquals(mapper.fingerprint(desired), mapper.fingerprint(actual))
        }
    }

    private fun contact(name: String) = CanonicalContact("synthetic-account", "synthetic-contact",
        displayName = name, firstName = name, lastName = "", values = emptyList())

    private fun located(snapshot: AndroidContactSnapshot) = snapshot.copy(rows = snapshot.rows.mapIndexed { index, row ->
        row.copy(identity = row.identity.copy(providerRowId = index.toLong() + 1))
    })
}
