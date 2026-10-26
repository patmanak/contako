package com.patmanak.contako.ui

import com.patmanak.contako.R
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactConflictPresentationTest {
    @Test fun comparisonComponentsUseUserFacingResourcesRatherThanPrivateKeys() {
        assertEquals(R.string.field_address_postal_code, conflictComponentLabel("postal_code", ContactValueKind.POSTAL_ADDRESS))
        assertEquals(R.string.conflict_additional_names, conflictComponentLabel("additional", ContactValueKind.STRUCTURED_NAME))
        assertEquals(R.string.field_last_name, conflictComponentLabel("family", ContactValueKind.PHONETIC_NAME))
        org.junit.Assert.assertNull(conflictComponentLabel("component_0", ContactValueKind.ORGANIZATION))
    }

    @Test fun comparisonAcceptsEveryCanonicalKindIncludingReadOnlyProtonFields() {
        ContactValueKind.entries.forEach { kind -> assertTrue(kind.name, conflictValueLabel(kind) != 0) }
        assertEquals(R.string.field_structured_name, conflictValueLabel(ContactValueKind.STRUCTURED_NAME))
        assertEquals(R.string.contact_preserved_section, conflictValueLabel(ContactValueKind.UNKNOWN_VCARD_PROPERTY))
    }
}
