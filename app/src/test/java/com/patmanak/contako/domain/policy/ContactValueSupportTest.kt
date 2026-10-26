package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactValueSupportTest {
    @Test fun customDatesAndPhoneticsArePermanentLocalComplements() {
        assertTrue(ContactValueSupport.isLocalOnly(value(ContactValueKind.CUSTOM_DATE)))
        assertTrue(ContactValueSupport.isLocalOnly(value(ContactValueKind.PHONETIC_NAME)))
        assertFalse(ContactValueSupport.hasLocalOnlyLabel(value(ContactValueKind.CUSTOM_DATE, "Custom")))
    }

    @Test fun onlyEstablishedUnsupportedLabelsAreLocalOnly() {
        for (kind in listOf(ContactValueKind.URL, ContactValueKind.RELATIONSHIP, ContactValueKind.POSTAL_ADDRESS)) {
            val local = value(kind, "Cabin")
            assertTrue(ContactValueSupport.hasLocalOnlyLabel(local))
            assertTrue(ContactValueSupport.isLocalOnly(local))
            val imported = local.copy(preservationKey = "imported-value")
            assertFalse(ContactValueSupport.hasLocalOnlyLabel(imported))
            assertTrue(ContactValueSupport.hasLocalOnlyLabel(imported.copy(metadata = mapOf("contakoLocalOnlyLabel" to "true"))))
            assertTrue(ContactValueSupport.hasLocalOnlyLabel(imported.copy(metadata = mapOf("androidSemanticType" to "CUSTOM"))))
            assertFalse(ContactValueSupport.hasLocalOnlyLabel(local.copy(metadata = mapOf("vcardTypeTokens" to "x-remote"))))
            assertFalse(ContactValueSupport.hasLocalOnlyLabel(imported.copy(metadata = mapOf("contakoLocalOnlyLabel" to "TRUE"))))
        }
    }

    @Test fun postalStandardLabelsRemainRepresentableAndUrlOrRelationshipLabelsDoNot() {
        for (label in listOf("HOME", " work ", "oThEr")) {
            assertFalse(ContactValueSupport.hasLocalOnlyLabel(value(ContactValueKind.POSTAL_ADDRESS, label)))
            assertTrue(ContactValueSupport.hasLocalOnlyLabel(value(ContactValueKind.URL, label)))
            assertTrue(ContactValueSupport.hasLocalOnlyLabel(value(ContactValueKind.RELATIONSHIP, label)))
        }
        for (kind in ContactValueKind.entries) {
            assertFalse(ContactValueSupport.hasLocalOnlyLabel(value(kind, " \t")))
        }
        assertFalse(ContactValueSupport.isLocalOnly(value(ContactValueKind.EMAIL, "Cabin")))
        assertFalse(ContactValueSupport.isLocalOnly(value(ContactValueKind.PHONE, "Cabin")))
    }

    @Test fun pendingErrorsAndOpaqueMetadataNeverInventLocalRepresentation() {
        for (kind in listOf(ContactValueKind.NOTE, ContactValueKind.EMAIL, ContactValueKind.UNKNOWN_VCARD_PROPERTY)) {
            assertFalse(ContactValueSupport.isLocalOnly(value(kind).copy(metadata = mapOf(
                "syncDisposition" to "LOCAL_ONLY", "pending" to "true", "error" to "NETWORK_ERROR"))))
        }
    }

    private fun value(kind: ContactValueKind, label: String? = null) =
        ContactValue("synthetic-value", kind, "synthetic content", label, 0)
}
