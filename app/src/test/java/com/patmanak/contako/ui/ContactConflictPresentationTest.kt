package com.patmanak.contako.ui

import com.patmanak.contako.R
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactConflictPresentationTest {
    @Test fun comparisonAcceptsEveryCanonicalKindIncludingReadOnlyProtonFields() {
        ContactValueKind.entries.forEach { kind -> assertTrue(kind.name, conflictValueLabel(kind) != 0) }
        assertEquals(R.string.field_structured_name, conflictValueLabel(ContactValueKind.STRUCTURED_NAME))
        assertEquals(R.string.contact_preserved_section, conflictValueLabel(ContactValueKind.UNKNOWN_VCARD_PROPERTY))
    }
}
