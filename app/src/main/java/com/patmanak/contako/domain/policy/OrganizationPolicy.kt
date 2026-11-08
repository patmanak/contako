package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind

/** ORG's scalar is a display summary; its structured units are edited independently. */
object OrganizationPolicy {
    const val COMPANY = "company"
    const val DEPARTMENT = "department"
    val editableComponents = listOf(COMPANY, DEPARTMENT)

    fun company(value: ContactValue): String = value.components["component_0"]
        ?: value.components[COMPANY]
        ?: if (value.components.keys.any { unitIndex(it) != null } || DEPARTMENT in value.components) "" else value.value

    fun department(value: ContactValue): String = value.components["component_1"]
        ?: value.components[DEPARTMENT].orEmpty()

    fun updateComponent(value: ContactValue, component: String, text: String): ContactValue {
        require(value.kind == ContactValueKind.ORGANIZATION)
        require(component in editableComponents)
        val company = if (component == COMPANY) text else company(value)
        val department = if (component == DEPARTMENT) text else department(value)
        val components = value.components + mapOf(COMPANY to company, "component_0" to company,
            DEPARTMENT to department, "component_1" to department)
        val summary = components.entries.mapNotNull { (key, unit) -> unitIndex(key)?.let { it to unit } }
            .sortedBy { it.first }.map { it.second }.filter(String::isNotBlank).joinToString(" / ")
        return value.copy(value = summary, components = components)
    }

    private fun unitIndex(key: String): Int? = key.takeIf { it.startsWith("component_") }
        ?.removePrefix("component_")?.toIntOrNull()?.takeIf { it >= 0 }
}
