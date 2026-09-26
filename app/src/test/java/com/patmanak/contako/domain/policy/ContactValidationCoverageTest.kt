package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.repository.SaveValidationIssue
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactValidationCoverageTest {
    @Test
    fun actionRequiredStateRetainsEverySimultaneousCorrectionReason() {
        val incomplete = CanonicalContact(
            accountId = "synthetic-account",
            id = "synthetic-draft",
            values = listOf(
                ContactValue(
                    id = "synthetic-email",
                    kind = ContactValueKind.EMAIL,
                    value = "invalid-email",
                    order = 0,
                ),
            ),
        )

        assertEquals(
            setOf("MISSING_NAME", "INVALID_EMAIL"),
            ContactValidation.actionRequiredReasons(incomplete, requiresCreateValidation = true),
        )
        assertEquals(
            setOf("INVALID_EMAIL"),
            ContactValidation.actionRequiredReasons(incomplete, requiresCreateValidation = false),
        )
    }

    @Test
    fun canonicalIdentityChecksRejectAmbiguousValueIdsAndFamilyOrder() {
        val duplicateId = contact(
            ContactValue("duplicate", ContactValueKind.EMAIL, "one@example.test", order = 0),
            ContactValue("duplicate", ContactValueKind.PHONE, "+33123456789", order = 0),
        )
        val duplicateFamilyOrder = contact(
            ContactValue("email-1", ContactValueKind.EMAIL, "one@example.test", order = 0),
            ContactValue("email-2", ContactValueKind.EMAIL, "two@example.test", order = 0),
        )
        val negativeOrder = contact(
            ContactValue("email", ContactValueKind.EMAIL, "one@example.test", order = -1),
        )

        assertEquals(
            setOf(SaveValidationIssue.DUPLICATE_VALUE_ID),
            ContactValidation.canonicalValueIdentityIssues(duplicateId),
        )
        assertEquals(
            setOf(SaveValidationIssue.DUPLICATE_VALUE_ORDER),
            ContactValidation.canonicalValueIdentityIssues(duplicateFamilyOrder),
        )
        assertEquals(
            setOf(SaveValidationIssue.NEGATIVE_VALUE_ORDER),
            ContactValidation.canonicalValueIdentityIssues(negativeOrder),
        )
    }

    private fun contact(vararg values: ContactValue) = CanonicalContact(
        accountId = "synthetic-account",
        id = "synthetic-contact",
        firstName = "Synthetic",
        values = values.toList(),
    )
}
