package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPoliciesTest {
    @Test
    fun createValidation_acceptsAnyNameButNotEmailOnly() {
        assertTrue(ContactValidation.canCreate(contact(firstName = "Ada")))
        assertTrue(ContactValidation.canCreate(contact(lastName = "Lovelace")))
        assertTrue(ContactValidation.canCreate(contact(displayName = "Ada L.")))
        assertFalse(
            ContactValidation.canCreate(
                contact(values = listOf(value(ContactValueKind.EMAIL, "ada@example.test"))),
            ),
        )
    }

    @Test
    fun actionRequiredReason_distinguishesMissingNameAndInvalidEmail() {
        assertEquals("MISSING_NAME", ContactValidation.actionRequiredReason(contact(), true))
        assertEquals(
            "INVALID_EMAIL",
            ContactValidation.actionRequiredReason(
                contact(
                    firstName = "Ada",
                    values = listOf(value(ContactValueKind.EMAIL, "not-an-email")),
                ),
                true,
            ),
        )
        assertNull(
            ContactValidation.actionRequiredReason(
                contact(values = listOf(value(ContactValueKind.EMAIL, "ada@example.test"))),
                false,
            ),
        )
    }

    @Test
    fun actionRequiredReasons_reportEveryCorrection() {
        val reasons = ContactValidation.actionRequiredReasons(
            contact(values = listOf(value(ContactValueKind.EMAIL, "invalid"))),
            requiresCreateValidation = true,
        )

        assertEquals(setOf("MISSING_NAME", "INVALID_EMAIL"), reasons)
    }

    @Test
    fun search_isCaseAndDiacriticInsensitiveAcrossRepeatableValues() {
        val contact = contact(
            firstName = "Élodie",
            values = listOf(
                value(ContactValueKind.EMAIL, "elodie@example.test"),
                value(ContactValueKind.PHONE, "+33 6 00 00 00 00"),
            ),
        )

        assertTrue(ContactSearch.matches(contact, "ELODIE"))
        assertTrue(ContactSearch.matches(contact, "example.test"))
        assertTrue(ContactSearch.matches(contact, "+33 6"))
        assertFalse(ContactSearch.matches(contact, "absent"))
    }

    private fun contact(
        firstName: String = "",
        lastName: String = "",
        displayName: String = "",
        values: List<ContactValue> = emptyList(),
    ) = CanonicalContact(
        accountId = "account",
        id = "contact",
        firstName = firstName,
        lastName = lastName,
        displayName = displayName,
        values = values,
    )

    private fun value(kind: ContactValueKind, raw: String) = ContactValue(
        id = "$kind-$raw",
        kind = kind,
        value = raw,
        order = 0,
    )
}
