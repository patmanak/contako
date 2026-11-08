package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValueKind

/** An explicit name always wins. Fallback does not create structured given/family-name fields. */
object ContactDisplayNamePolicy {
    fun resolve(contact: CanonicalContact): String = contact.displayName.trim().ifEmpty {
        val structured = contact.valuesOf(ContactValueKind.STRUCTURED_NAME).firstOrNull()
        val name = listOf(structured?.components?.get("prefix").orEmpty(),
            structured?.components?.get("given") ?: contact.firstName,
            structured?.components?.get("additional").orEmpty(),
            structured?.components?.get("family") ?: contact.lastName,
            structured?.components?.get("suffix").orEmpty()).map(String::trim).filter(String::isNotEmpty).joinToString(" ")
        val phonetic = contact.valuesOf(ContactValueKind.PHONETIC_NAME).firstOrNull()?.let { value ->
            listOf(value.components["given"] ?: value.value, value.components["additional"].orEmpty(),
                value.components["family"].orEmpty()).map(String::trim).filter(String::isNotEmpty).joinToString(" ")
        }.orEmpty()
        name.ifEmpty { phonetic }.ifEmpty {
            sequenceOf(ContactValueKind.NICKNAME, ContactValueKind.ORGANIZATION, ContactValueKind.PHONE, ContactValueKind.EMAIL)
                .map { kind -> contact.valuesOf(kind).firstOrNull { it.value.isNotBlank() }?.value?.trim().orEmpty() }
                .firstOrNull(String::isNotEmpty).orEmpty()
        }
    }
}
