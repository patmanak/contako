package com.patmanak.contako.data.android.provider

import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCleanProjectionPresenceTest {
    private val ledger = AndroidProjectionLedgerEntity(
        "account", "contact", 0, 0, 42, "source", null, null, null, null,
        "CLEAN", "NONE", "NONE", "ADOPTED",
    )
    private val raw = AndroidOwnedRawContact(42, "contact", "source", false, false, 7)

    @Test fun exactOwnedMetadataAllowsSkippingWithoutDataOrPhotoProof() {
        assertEquals(setOf("contact"), cleanProjectionIdsWithOwnedMetadata(listOf(ledger), listOf(raw)))
    }

    @Test fun missingDirtyDeletedReboundOrAmbiguousRowsCannotHideAProjectionObligation() {
        val observations = listOf(
            emptyList(), listOf(raw.copy(dirty = true)), listOf(raw.copy(deleted = true)),
            listOf(raw.copy(rawContactId = 43)), listOf(raw.copy(sourceIdentity = "foreign")),
            listOf(raw.copy(canonicalContactIdClaim = "another")),
            listOf(raw.copy(canonicalContactIdClaim = null)), listOf(raw, raw),
        )
        observations.forEach { metadata ->
            assertEquals(emptySet<String>(), cleanProjectionIdsWithOwnedMetadata(listOf(ledger), metadata))
        }
        assertEquals(emptySet<String>(), cleanProjectionIdsWithOwnedMetadata(listOf(ledger.copy(sourceIdentity = null)), listOf(raw)))
    }
}
