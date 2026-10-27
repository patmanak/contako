package com.patmanak.contako.ui

import com.patmanak.contako.data.android.mapping.ContactFieldCompatibility
import com.patmanak.contako.data.android.mapping.FieldCompatibilityDescription
import com.patmanak.contako.data.android.mapping.ProtonFieldSupport
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind

internal enum class ContactFieldDestination { SHARED, PROTON, ANDROID, LOCAL, READ_ONLY, UNKNOWN }

/** Private presentation data; format support does not establish actual peer delivery. */
internal data class ContactInformationField(val value: ContactValue, val facts: FieldCompatibilityDescription) {
    val destination: ContactFieldDestination get() = when {
        value.kind == ContactValueKind.UNKNOWN_VCARD_PROPERTY -> ContactFieldDestination.READ_ONLY
        facts.proton == ProtonFieldSupport.GENERATED -> when (facts.represented) {
            true -> ContactFieldDestination.SHARED
            false -> ContactFieldDestination.PROTON
            null -> ContactFieldDestination.UNKNOWN
        }
        facts.proton == ProtonFieldSupport.LOCAL_ONLY -> when (facts.represented) {
            true -> ContactFieldDestination.ANDROID
            false -> ContactFieldDestination.LOCAL
            null -> ContactFieldDestination.UNKNOWN
        }
        else -> ContactFieldDestination.UNKNOWN
    }
    override fun toString() = "ContactInformationField(REDACTED)"
}

internal fun contactInformationFields(contact: CanonicalContact, original: CanonicalContact? = null,
    editing: Boolean = false): List<ContactInformationField> = contact.values
    .filter { it.kind != ContactValueKind.STRUCTURED_NAME }
    .sortedBy(ContactValue::order)
    .map { value -> ContactInformationField(value, if (editing)
        ContactFieldCompatibility.describeDraft(contact, original, value.id)
        else ContactFieldCompatibility.describe(contact, value.id)) }

internal fun ContactEditorState.informationContact(): CanonicalContact =
    (original ?: CanonicalContact("information-draft", "information-draft")).copy(
        firstName = firstName, lastName = lastName, displayName = displayName,
        values = values + images + original?.values.orEmpty().filter {
            it.kind in setOf(ContactValueKind.STRUCTURED_NAME, ContactValueKind.UNKNOWN_VCARD_PROPERTY)
        }.filter { preserved -> (values + images).none { it.id == preserved.id } },
    )
