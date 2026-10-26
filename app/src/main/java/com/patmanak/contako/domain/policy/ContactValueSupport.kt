package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.Locale

/** Permanent local complements, independent of pending work or peer availability. */
object ContactValueSupport {
    /** The value itself has no generated Proton representation; labels are classified separately. */
    fun isLocalOnlyValue(kind: ContactValueKind): Boolean =
        kind == ContactValueKind.CUSTOM_DATE || kind == ContactValueKind.PHONETIC_NAME

    fun isLocalOnly(value: ContactValue): Boolean =
        isLocalOnlyValue(value.kind) || hasLocalOnlyLabel(value)

    fun hasLocalOnlyLabel(value: ContactValue): Boolean {
        val locallyEstablished = value.metadata["contakoLocalOnlyLabel"] == "true" ||
            !value.metadata["androidSemanticType"].isNullOrBlank() ||
            (value.preservationKey == null && value.metadata["vcardTypeTokens"].isNullOrBlank())
        return locallyEstablished && hasUnsupportedGeneratedLabel(value)
    }

    /** Also used when marking an explicit local label edit, before provenance is recorded. */
    internal fun hasUnsupportedGeneratedLabel(value: ContactValue): Boolean {
        val type = value.label?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT) ?: return false
        return when (value.kind) {
            ContactValueKind.URL, ContactValueKind.RELATIONSHIP -> true
            ContactValueKind.POSTAL_ADDRESS -> type !in setOf("HOME", "WORK", "OTHER")
            else -> false
        }
    }
}
