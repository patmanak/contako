package com.patmanak.contako.data.local

import com.patmanak.contako.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ContactConflictSnapshotCodecTest {
    @Test fun fullSnapshotPreservesUnicodeUnknownFieldsAndLargePhotos() {
        val contact = CanonicalContact("synthetic", "contact", "Éloïse", "范", "Alias", values = listOf(
            ContactValue("photo", ContactValueKind.PHOTO, "data:image/jpeg;base64," + "A".repeat(3 * 1024 * 1024), order = 0),
            ContactValue("note", ContactValueKind.NOTE, "Été\n東京", "custom", 1, true,
                mapOf("a" to "b"), mapOf("private" to "retained"), "binary", "property:1")),
            revision = 15, updatedAtEpochMillis = 10, remoteContactId = "remote", remoteVCardUid = "uid",
            remoteVersion = "v1", preservationEnvelope = PreservationEnvelope(mapOf("X-CUSTOM" to "è"), "baseline"),
            actionRequiredReasons = setOf("MISSING_NAME"), pendingMutationRevision = 15, conflictState = "conflict", isDeleted = true)
        assertEquals(contact, ContactConflictSnapshotCodec.decode(ContactConflictSnapshotCodec.encode(contact)))
    }

    @Test fun malformedVersionAndTruncationFailClosed() {
        val bytes = ContactConflictSnapshotCodec.encode(CanonicalContact("fixture", "contact"))
        assertTrue(runCatching { ContactConflictSnapshotCodec.decode(bytes.copyOf(bytes.size - 1)) }.isFailure)
        bytes[3] = 2
        assertTrue(runCatching { ContactConflictSnapshotCodec.decode(bytes) }.isFailure)
    }
}
