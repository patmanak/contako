package com.patmanak.contako.ui

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.*
import org.junit.Test

class ContactInformationTest {
    @Test fun informationSeparatesActualOccurrencesWithoutDroppingSecondaryOrPreservedFields() {
        val contact = contact(listOf(
            value("phone", ContactValueKind.PHONE, "+33600000001"),
            value("note-1", ContactValueKind.NOTE, "Primary note"),
            value("note-2", ContactValueKind.NOTE, "Secondary note").copy(order = 1),
            value("custom-date", ContactValueKind.CUSTOM_DATE, "2000-01-02"),
            value("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-SYNTHETIC:retained"),
        ))
        val rows = contactInformationFields(contact).associateBy { it.value.id }
        assertEquals(ContactFieldDestination.SHARED, rows.getValue("phone").destination)
        assertEquals(ContactFieldDestination.SHARED, rows.getValue("note-1").destination)
        assertEquals(ContactFieldDestination.PROTON, rows.getValue("note-2").destination)
        assertEquals(ContactFieldDestination.ANDROID, rows.getValue("custom-date").destination)
        assertEquals(ContactFieldDestination.READ_ONLY, rows.getValue("opaque").destination)
        assertEquals(contact.values.toSet(), rows.values.map { it.value }.toSet())
        assertEquals("ContactInformationField(REDACTED)", rows.getValue("opaque").toString())
    }

    @Test fun labelEditingChangesOnlyTheInformationLabelFactAndDoesNotDemoteTheValue() {
        val url = value("url", ContactValueKind.URL, "https://example.test").copy(label = "Home",
            preservationKey = "source-url")
        val original = contact(listOf(url))
        val draft = original.copy(values = listOf(url.copy(label = "Profile")))
        val changed = contactInformationFields(draft, original, editing = true).single()
        assertEquals(ContactFieldDestination.SHARED, changed.destination)
        assertTrue(changed.facts.localLabelOnly)
        assertFalse(contactInformationFields(original, original, editing = true).single().facts.localLabelOnly)
        assertEquals("Home", original.values.single().label)
    }

    @Test fun draftInformationRetainsOpaquePropertiesAndMediaWithoutDuplicatingEditableOccurrences() {
        val preserved = value("opaque", ContactValueKind.UNKNOWN_VCARD_PROPERTY, "X-SYNTHETIC:retained")
        val name = value("name", ContactValueKind.STRUCTURED_NAME, "Example;Synthetic")
        val original = contact(listOf(preserved, name))
        val photo = value("photo", ContactValueKind.PHOTO, "https://example.test/photo.png")
        val editor = ContactEditorState(original = original, firstName = "Draft", displayName = "Draft name",
            values = listOf(preserved), images = listOf(photo))
        val snapshot = editor.informationContact()
        assertEquals(3, snapshot.values.size)
        assertEquals(listOf(preserved, photo, name), snapshot.values)
        assertEquals("Draft name", snapshot.displayName)
        assertEquals(setOf("opaque", "photo"), contactInformationFields(snapshot).map { it.value.id }.toSet())
        assertEquals(original, editor.original)
    }

    @Test fun ambiguousOccurrenceIdentityIsUnknownRatherThanInventingAndroidSupport() {
        val repeated = value("same", ContactValueKind.PHONE, "+33600000001")
        val rows = contactInformationFields(contact(listOf(repeated, repeated.copy(value = "+33600000002"))))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.destination == ContactFieldDestination.UNKNOWN })
    }

    private fun value(id: String, kind: ContactValueKind, text: String) = ContactValue(id, kind, text, order = 0)
    private fun contact(values: List<ContactValue>) = CanonicalContact("information-owner", "information-contact",
        displayName = "Synthetic example", values = values)
}
