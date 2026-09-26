package com.patmanak.contako.data.android.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProviderBoundaryModelTest {
    @Test
    fun blankSourceIdentityIsNeverRepresentableAsAnOwnedRemoteIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidOwnedRawContact(
                rawContactId = 1,
                sourceIdentity = " ",
                dirty = true,
                deleted = false,
                version = 1,
            )
        }
    }

    @Test
    fun providerControlledMimeTypeIsRedactedFromDiagnostics() {
        val hostileMime = "vnd.android.cursor.item/private-canary"
        val row = AndroidOwnedDataRow(
            dataRowId = 1,
            rawContactId = 2,
            mimeType = hostileMime,
            canonicalValueId = null,
            canonicalOrder = null,
            linkedValueIdsEncoding = null,
            isPrimary = false,
            isSuperPrimary = false,
            stringSlots = List(14) { null },
            binarySlot = null,
        )

        assertTrue(row.toString().contains("REDACTED"))
        assertFalse(row.toString().contains(hostileMime))
        assertFalse(row.toString().contains("private-canary"))
    }
}
