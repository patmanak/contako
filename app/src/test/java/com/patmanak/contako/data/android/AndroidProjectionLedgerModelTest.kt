package com.patmanak.contako.data.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProjectionLedgerModelTest {
    @Test
    fun `room identity and bounded slice never depend on provider row ids or APIs`() {
        val entities = projectFile(
            "src/main/java/com/patmanak/contako/data/local/LocalEntities.kt",
        ).readText()
        val ledger = projectFile(
            "src/main/java/com/patmanak/contako/data/android/RoomAndroidProjectionLedger.kt",
        ).readText()
        val dao = projectFile(
            "src/main/java/com/patmanak/contako/data/local/LocalDaos.kt",
        ).readText()

        assertTrue(entities.contains("primaryKeys = [\"account_id\", \"canonical_contact_id\"]"))
        assertTrue(entities.contains("raw_contact_locator"))
        assertFalse(entities.contains("primaryKeys = [\"raw_contact_locator\"]"))
        assertTrue(dao.contains("AND revision = :expectedRevision"))
        listOf("ContactsContract", "ContentResolver", "ContentProviderOperation").forEach { forbidden ->
            assertFalse("Provider API leaked into Room ledger slice: $forbidden", ledger.contains(forbidden))
        }
    }

    @Test
    fun `fingerprints are fixed sha256 values and diagnostics are redacted`() {
        val fingerprint = AndroidProjectionFingerprint("a".repeat(64))

        assertTrue(fingerprint.toString().contains("REDACTED"))
        assertFalse(fingerprint.toString().contains(fingerprint.sha256Hex))
        assertTrue(runCatching { AndroidProjectionFingerprint("A".repeat(64)) }.isFailure)
        assertTrue(runCatching { AndroidProjectionFingerprint("a".repeat(63)) }.isFailure)
    }

    @Test
    fun `raw contact handles are epoch bound locators rather than durable identity`() {
        val first = AndroidRawContactLocator(providerEpoch = 1, localRowHandle = 10)
        val afterProviderReset = AndroidRawContactLocator(providerEpoch = 2, localRowHandle = 10)

        assertNotEquals(first, afterProviderReset)
        assertTrue(first.toString().contains("REDACTED"))
        assertFalse(first.toString().contains("10"))
        assertTrue(runCatching { AndroidRawContactLocator(0, 0) }.isFailure)
    }

    @Test
    fun `snapshot keeps canonical identity when replaceable locator changes`() {
        val first = snapshot(AndroidRawContactLocator(0, 11))
        val replacement = snapshot(AndroidRawContactLocator(0, 99))

        assertEquals(first.canonicalContactId, replacement.canonicalContactId)
        assertNotEquals(first.rawContactLocator, replacement.rawContactLocator)
        assertTrue(first.toString().contains("REDACTED"))
        assertFalse(first.toString().contains(first.canonicalContactId))
    }

    private fun snapshot(locator: AndroidRawContactLocator) = AndroidProjectionLedgerSnapshot(
        canonicalContactId = "stable-canonical-contact",
        revision = 1,
        providerEpoch = 0,
        rawContactLocator = locator,
        hasSourceIdentity = true,
        canonicalProjectionFingerprint = AndroidProjectionFingerprint("a".repeat(64)),
        androidBaselineFingerprint = AndroidProjectionFingerprint("a".repeat(64)),
        pendingProjectionFingerprint = null,
        projectionState = AndroidProjectionWriteState.CLEAN,
        ingestionState = AndroidIngestionState.BASELINED,
        tombstoneState = AndroidTombstoneState.NONE,
        adoptionState = AndroidAdoptionState.ADOPTED,
    )

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath), File("..", relativePath))
        return candidates.firstOrNull(File::exists) ?: error("Missing project file: $relativePath")
    }
}
