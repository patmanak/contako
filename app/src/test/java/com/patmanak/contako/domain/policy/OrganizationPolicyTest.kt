package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.*
import org.junit.Test

class OrganizationPolicyTest {
    @Test fun editingCompanyPreservesDepartmentAdditionalUnitsAndOpaqueState() {
        val original = ContactValue("org", ContactValueKind.ORGANIZATION, "Before / Sales / Europe / Team", order = 2,
            components = mapOf("company" to "Before", "component_0" to "Before",
                "department" to "Sales", "component_1" to "Sales", "component_2" to "Europe",
                "component_3" to "Team", "opaque" to "unchanged"),
            metadata = mapOf("opaque" to "retained"), preservationKey = "source-key", label = "Work")
        val edited = OrganizationPolicy.updateComponent(original, OrganizationPolicy.COMPANY, "Café / Studio")
        assertEquals("Café / Studio", OrganizationPolicy.company(edited))
        assertEquals("Sales", OrganizationPolicy.department(edited))
        assertEquals("Café / Studio / Sales / Europe / Team", edited.value)
        assertEquals(original.copy(value = edited.value, components = original.components +
            mapOf("company" to "Café / Studio", "component_0" to "Café / Studio")), edited)
    }

    @Test fun editingAndClearingDepartmentDoNotShiftOrDropFurtherUnits() {
        val original = ContactValue("org", ContactValueKind.ORGANIZATION, "Company / Sales / Division", order = 0,
            components = mapOf("component_0" to "Company", "component_1" to "Sales", "component_2" to "Division"))
        val changed = OrganizationPolicy.updateComponent(original, OrganizationPolicy.DEPARTMENT, "Research / Design")
        assertEquals("Research / Design", changed.components["component_1"])
        assertEquals("Research / Design", changed.components["department"])
        val cleared = OrganizationPolicy.updateComponent(changed, OrganizationPolicy.DEPARTMENT, "")
        assertEquals("", cleared.components["component_1"])
        assertEquals("", cleared.components["department"])
        assertEquals("Division", cleared.components["component_2"])
        assertEquals("Company / Division", cleared.value)
    }

    @Test fun anUnstructuredCompanyIsLiteralAndAStructuredSummaryIsNeverCopiedIntoCompany() {
        val scalar = ContactValue("org", ContactValueKind.ORGANIZATION, "Company / Studio", order = 0,
            components = mapOf("opaque" to "kept"))
        assertEquals("Company / Studio", OrganizationPolicy.company(scalar))
        val edited = OrganizationPolicy.updateComponent(scalar, OrganizationPolicy.DEPARTMENT, "Sales")
        assertEquals("Company / Studio", edited.components["component_0"])
        assertEquals("Company / Studio / Sales", edited.value)
        val unitOnly = scalar.copy(value = "Division summary", components = mapOf("component_2" to "Division"))
        assertEquals("", OrganizationPolicy.company(unitOnly))
        assertEquals("Division", OrganizationPolicy.updateComponent(unitOnly, OrganizationPolicy.DEPARTMENT, "")
            .components["component_2"])
    }
}
