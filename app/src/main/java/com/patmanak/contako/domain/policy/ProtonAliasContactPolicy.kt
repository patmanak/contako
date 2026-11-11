package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.Locale

/** Conservative display heuristic, never an ownership proof or a synchronization rule. */
object ProtonAliasContactPolicy {
    fun isAliasOnly(contact: CanonicalContact): Boolean {
        if (contact.firstName.isNotBlank() || contact.lastName.isNotBlank()) return false
        val emails = contact.values.filter { it.kind == ContactValueKind.EMAIL && it.value.isNotBlank() }
        if (emails.isEmpty() || emails.any { !isKnownAliasAddress(it.value) }) return false
        // Proton can use an email as FN; that repeats the address rather than enriching the card.
        if (contact.displayName.isNotBlank() && emails.none {
                it.value.trim().equals(contact.displayName.trim(), ignoreCase = true)
            }) return false
        return contact.values.none { value ->
            // N:;;;; is a common imported empty name, not four characters of useful data.
            val scalarContent = value.value.isNotBlank() &&
                !(value.kind == ContactValueKind.STRUCTURED_NAME && value.components.isNotEmpty())
            value.kind != ContactValueKind.EMAIL && (scalarContent ||
                value.components.values.any(String::isNotBlank) || !value.binaryReference.isNullOrBlank())
        }
    }

    private fun isKnownAliasAddress(value: String): Boolean {
        val address = value.trim()
        if (!ContactValidation.isValidEmail(address) || address.any(Char::isWhitespace)) return false
        val domain = address.substringAfter('@').lowercase(Locale.ROOT)
        // Exact matches only: custom domains and lookalike suffixes stay visible.
        return domain in KNOWN_DOMAINS
    }

    // Public service domains verified against primary sources; see USER_GUIDE.md#filter-the-contact-directory.
    // This deliberately does not claim to enumerate account-specific or future domains.
    private val KNOWN_DOMAINS = setOf(
        "pass2mail.com", "passfwd.com", "passinbox.com", "passmail.com", "passmail.net",
        "8alias.com", "8shield.net", "aleeas.com", "dralias.com", "silomails.com",
        "simplelogin.co", "simplelogin.com", "simplelogin.fr", "simplelogin.io", "slmail.me", "slmails.com",
    )
}
