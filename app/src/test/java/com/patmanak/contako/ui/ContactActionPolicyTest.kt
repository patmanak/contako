package com.patmanak.contako.ui

import androidx.compose.ui.graphics.Color
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactActionPolicyTest {
    @Test
    fun `website action accepts only absolute host-scoped HTTPS`() {
        assertEquals("https://example.test/path", safeHttpsContactUrl("https://example.test/path"))
        assertEquals("https://example.test/path", safeHttpsContactUrl("example.test/path"))
        assertEquals("HTTPS://EXAMPLE.TEST/path", safeHttpsContactUrl("HTTPS://EXAMPLE.TEST/path"))

        listOf(
            "http://example.test",
            "intent://example.test/#Intent;scheme=https;end",
            "javascript:alert(1)",
            "mailto:person@example.test",
            "tel:+33102030405",
            "file:///tmp/contact",
            "content://contacts/1",
            "https://user@example.test",
            "//example.test/path",
            "https://exa mple.test",
            "https://",
        ).forEach { assertNull(it, safeHttpsContactUrl(it)) }
    }

    @Test
    fun `quick actions retain every target and use canonical vCard preference first`() {
        val contact = CanonicalContact(
            accountId = "local",
            id = "contact",
            values = listOf(
                ContactValue("phone-a", ContactValueKind.PHONE, "first", order = 0),
                ContactValue(
                    "phone-b",
                    ContactValueKind.PHONE,
                    "preferred",
                    order = 1,
                    metadata = mapOf("vcardPref" to "1"),
                ),
                ContactValue("phone-c", ContactValueKind.PHONE, "third", order = 2),
                ContactValue("email", ContactValueKind.EMAIL, "mail", order = 0),
            ),
        )

        assertEquals(
            listOf("preferred", "first", "third"),
            contactQuickActionTargets(contact, ContactQuickAction.CALL).map(ContactValue::value),
        )
        assertEquals(
            contactQuickActionTargets(contact, ContactQuickAction.CALL),
            contactQuickActionTargets(contact, ContactQuickAction.MESSAGE),
        )
        assertEquals(listOf("mail"), contactQuickActionTargets(contact, ContactQuickAction.EMAIL).map(ContactValue::value))
        assertTrue(contactQuickActionTargets(contact, ContactQuickAction.MAP).isEmpty())
    }

    @Test
    fun `group chip colors fall back safely and select the stronger text contrast`() {
        val fallback = Color(0xFFEAE5FF)
        assertEquals(Color(0xFF8080FF), parseContactGroupColor("#8080FF", fallback))
        assertEquals(fallback, parseContactGroupColor("not-a-color", fallback))
        assertEquals(Color(0xFF191927), contrastingContactGroupContentColor(Color(0xFF8080FF)))
        assertEquals(Color.White, contrastingContactGroupContentColor(Color(0xFF191927)))
    }

    @Test
    fun `avatar initials use the first and last visible words`() {
        assertEquals("AM", contactAvatarInitials("Alice Marie Martin"))
        assertEquals("É", contactAvatarInitials("  Élodie  "))
        assertEquals("?", contactAvatarInitials("   "))
    }
}
