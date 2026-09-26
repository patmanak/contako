package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind

/** One deterministic primary-selection rule shared by Proton, Contako, and Android projections. */
internal object CanonicalPrimaryValuePolicy {
    fun select(contact: CanonicalContact, kind: ContactValueKind): ContactValue? =
        select(contact.valuesOf(kind))

    fun select(values: List<ContactValue>): ContactValue? = ranked(values)

    fun preferredEmail(contact: CanonicalContact): ContactValue? =
        ranked(contact.valuesOf(ContactValueKind.EMAIL).filter { it.value.isNotBlank() })

    private fun ranked(values: List<ContactValue>): ContactValue? = values.minWithOrNull(
        compareBy<ContactValue> { value ->
            value.metadata[VCARD_PREF_METADATA]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: Int.MAX_VALUE
        }.thenBy(ContactValue::order)
            .thenBy(ContactValue::id),
    )

    internal const val VCARD_PREF_METADATA = "vcardPref"
}
