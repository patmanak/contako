package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind

/** One structured ADR model shared by UI, Android mapping, persistence, and Proton rendering. */
object PostalAddressPolicy {
    const val PO_BOX = "po_box"
    const val EXTENDED = "extended"
    const val STREET = "street"
    const val LOCALITY = "locality"
    const val REGION = "region"
    const val POSTAL_CODE = "postal_code"
    const val COUNTRY = "country"

    val componentKeys = listOf(PO_BOX, EXTENDED, STREET, LOCALITY, REGION, POSTAL_CODE, COUNTRY)

    fun emptyComponents(): Map<String, String> = componentKeys.associateWith { "" }

    /** The visible address is derived; structured components remain the canonical source. */
    fun formattedValue(components: Map<String, String>): String = componentKeys
        .map { components[it].orEmpty() }
        .filter(String::isNotBlank)
        .joinToString(", ")

    /**
     * Repairs legacy Contako rows that stored only the former free-form field.
     *
     * The old scalar is retained verbatim as the street component. Imported structured components
     * stay authoritative, including fields Android cannot represent exactly.
     */
    fun normalize(value: ContactValue): ContactValue {
        if (value.kind != ContactValueKind.POSTAL_ADDRESS) return value
        val structured = componentKeys.any { value.components[it].orEmpty().isNotBlank() }
        val components = when {
            structured -> value.components
            value.value.isNotBlank() -> value.components + (STREET to value.value)
            else -> value.components + emptyComponents()
        }
        return value.copy(value = formattedValue(components), components = components)
    }

    fun updateComponent(value: ContactValue, component: String, text: String): ContactValue {
        require(value.kind == ContactValueKind.POSTAL_ADDRESS)
        require(component in componentKeys)
        val components = normalize(value).components + (component to text)
        return value.copy(value = formattedValue(components), components = components)
    }
}
