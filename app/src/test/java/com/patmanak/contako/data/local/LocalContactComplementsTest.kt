package com.patmanak.contako.data.local

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContactComplementsTest {
    private val date = ContactValue("custom-date", ContactValueKind.CUSTOM_DATE, "--10-23", "Événement", 0,
        metadata = mapOf("syncDisposition" to "LOCAL_ONLY", "androidSemanticType" to "CUSTOM"))
    private val url = ContactValue("local-url", ContactValueKind.URL, "https://example.test/profile", "Profile", 0,
        metadata = mapOf("androidSemanticType" to "PROFILE"))
    private fun contact(vararg values: ContactValue) = CanonicalContact("account", "contact", values = values.toList())

    @Test fun customDatesAndUnsupportedLabelsSurviveRemoteAdoptionWithoutInventingRemoteValues() {
        val note = ContactValue("remote-note", ContactValueKind.NOTE, "New Proton note", order = 0)
        val remoteUrl = url.copy(id = "remote-url", label = null, metadata = emptyMap())
        val remote = contact(remoteUrl, note).copy(remoteVersion = "new-version",
            preservationEnvelope = PreservationEnvelope(mapOf("opaque" to "remote original")))
        val merged = retainLocalContactComplements(contact(date, url), remote)
        assertEquals(listOf(remoteUrl.copy(label = url.label,
            metadata = url.metadata + ("contakoLocalOnlyLabel" to "true")), note, date), merged.values)
        assertEquals(remote.remoteVersion, merged.remoteVersion)
        assertEquals(remote.preservationEnvelope, merged.preservationEnvelope)
        assertEquals(merged, retainLocalContactComplements(merged, remote))
    }

    @Test fun removedOrChangedRemoteValuesAndExplicitRemoteTypesAreAuthoritative() {
        val local = contact(date, url)
        assertEquals(listOf(date), retainLocalContactComplements(local, contact()).values)
        val changed = url.copy(id = "changed", value = "https://example.test/new", label = null, metadata = emptyMap())
        assertEquals(changed, retainLocalContactComplements(local, contact(changed)).values.first())
        val typed = changed.copy(value = url.value, label = "Remote label")
        assertEquals(typed, retainLocalContactComplements(local, contact(typed)).values.first())
        val tokens = changed.copy(value = url.value, metadata = mapOf("vcardTypeTokens" to "x-remote"))
        assertEquals(tokens, retainLocalContactComplements(local, contact(tokens)).values.first())
    }

    @Test fun duplicateOccurrencesDoNotGuessWhichLocalLabelBelongsToWhichRemoteRow() {
        val remote = url.copy(id = "remote-url", label = null, metadata = emptyMap())
        val duplicateLocal = contact(url, url.copy(id = "another-local", label = "Blog", order = 1))
        assertEquals(remote, retainLocalContactComplements(duplicateLocal, contact(remote)).values.single())
        val duplicateRemote = contact(remote, remote.copy(id = "another-remote", order = 1))
        assertTrue(retainLocalContactComplements(contact(url), duplicateRemote).values.all { it.label == null })
    }

    @Test fun remoteRepresentableLabelsAreNotRestoredAfterTheirRemoteRemoval() {
        val address = ContactValue("address", ContactValueKind.POSTAL_ADDRESS, "Rue des Fleurs", "HOME", 0)
        val remote = address.copy(id = "remote-address", label = null)
        assertEquals(remote, retainLocalContactComplements(contact(address), contact(remote)).values.single())
        val custom = address.copy(label = "Cabane", metadata = mapOf("androidSemanticType" to "CUSTOM", "androidCustomLabel" to "Cabane"))
        assertEquals("Cabane", retainLocalContactComplements(contact(custom), contact(remote)).values.single().label)
    }

    @Test fun importedRemoteUrlTypesAndOpaqueLabelsCanBeRemovedRemotely() {
        val typed = url.copy(metadata = mapOf("vcardTypeTokens" to "HOME"), preservationKey = "imported-url")
        val remote = url.copy(label = null, metadata = emptyMap(), preservationKey = "new-imported-url")
        assertEquals(remote, retainLocalContactComplements(contact(typed), contact(remote)).values.single())
        val opaque = typed.copy(metadata = emptyMap())
        val local = contact(opaque).copy(preservationEnvelope = PreservationEnvelope(mapOf("card" to "ITEM1.X-ABLABEL:Profile")))
        assertEquals(remote, retainLocalContactComplements(local, contact(remote)).values.single())
    }

    @Test fun explicitLocalLabelEditIsRetainedButUnrelatedEditDoesNotClaimImportedLabels() {
        val imported = url.copy(label = "Remote label", preservationKey = "imported-url", metadata = emptyMap())
        val previous = contact(imported)
        val unrelated = markEditedLocalContactLabels(previous, previous.copy(displayName = "Changed"))
        assertFalse("contakoLocalOnlyLabel" in unrelated.values.single().metadata)
        val edited = markEditedLocalContactLabels(previous, contact(imported.copy(label = "Local label")))
        assertEquals("true", edited.values.single().metadata["contakoLocalOnlyLabel"])
        val remote = contact(imported.copy(label = null, id = "new-imported-url"))
        assertEquals("Local label", retainLocalContactComplements(edited, remote).values.single().label)
        val cleared = markEditedLocalContactLabels(edited, edited.copy(values = edited.values.map { it.copy(label = null) }))
        assertFalse("contakoLocalOnlyLabel" in cleared.values.single().metadata)
    }

    @Test fun contactsAndValuesRemainImmutable() {
        val remote = contact(url.copy(label = null, metadata = emptyMap()))
        val local = contact(date, url)
        retainLocalContactComplements(local, remote)
        assertEquals(listOf(date, url), local.values)
        assertFalse(remote.values.any { it.kind == ContactValueKind.CUSTOM_DATE })
        assertEquals(null, remote.values.single().label)
    }
}
