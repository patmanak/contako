package com.patmanak.contako.ui

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import java.net.URI
import java.net.URISyntaxException

internal enum class ContactQuickAction {
    CALL,
    MESSAGE,
    EMAIL,
    MAP,
}

/** Returns every local target in preferred-first display order; the UI chooses when several exist. */
internal fun contactQuickActionTargets(
    contact: CanonicalContact,
    action: ContactQuickAction,
): List<ContactValue> {
    val kind = when (action) {
        ContactQuickAction.CALL,
        ContactQuickAction.MESSAGE,
        -> ContactValueKind.PHONE
        ContactQuickAction.EMAIL -> ContactValueKind.EMAIL
        ContactQuickAction.MAP -> ContactValueKind.POSTAL_ADDRESS
    }
    val values = contact.valuesOf(kind)
    val preferredId = CanonicalPrimaryValuePolicy.select(values)?.id
    return values.sortedWith(
        compareBy<ContactValue> { it.id != preferredId }
            .thenBy(ContactValue::order)
            .thenBy(ContactValue::id),
    )
}

/** Produces a compact, payload-local avatar fallback without persisting derived data. */
internal fun contactAvatarInitials(displayName: String): String {
    val words = displayName.trim().splitToSequence(' ').filter(String::isNotBlank).toList()
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** Converts visible contact text into the only generic web action Contako permits. */
internal fun safeHttpsContactUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_CONTACT_URI_CHARS ||
        trimmed.any(Char::isISOControl) || trimmed.startsWith("//")
    ) return null
    val candidate = if (SCHEME_PREFIX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val uri = try {
        URI(candidate)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) ||
        uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in -1..65_535
    ) return null
    return uri.toASCIIString()
}

private const val MAX_CONTACT_URI_CHARS = 8 * 1_024
private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
