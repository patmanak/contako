package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.*
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.AndroidPhotoProviderWriteJournalEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity
import com.patmanak.contako.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AndroidInterruptedPhotoProjectionTest {
    private val mapper = CanonicalAndroidContactMapper()
    private val context = AndroidInteroperabilityContext(AccountScope("account"), "android-account", 1, 2)
    private val canonical = CanonicalContact(accountId = "account", id = "contact", displayName = "Ada Lovelace",
        values = listOf(ContactValue("photo", ContactValueKind.PHOTO, "image", order = 0),
            ContactValue("phone", ContactValueKind.PHONE, "123", order = 0)))
    private val desired = mapper.project(canonical)
    private val fingerprint = mapper.fingerprint(desired).sha256Hex
    private val ledger = AndroidProjectionLedgerEntity("account", "contact", 4, 2, 10, "remote",
        fingerprint, null, fingerprint, null, "WRITE_PENDING", "NONE", "NONE", "ADOPTED")
    private val raw = AndroidOwnedRawContact(10, "contact", "remote", true, false, 8)
    private val journal = AndroidPhotoProviderWriteJournalEntity("account", "contact", "android-account", 2,
        10, 7, "remote", 3, 4, "photo", "image", 1, "a".repeat(64), "PREPARED", null)

    @Test fun `only the exact interrupted command permits recovery consideration`() {
        assertTrue(isInterruptedPhotoProjectionRecoverable(context, ledger, 3, raw, desired, journal, mapper))
        listOf(journal.copy(accountId = "other"), journal.copy(providerEpoch = 3),
            journal.copy(rawContactLocator = 11), journal.copy(expectedSourceIdentity = "other"),
            journal.copy(expectedCanonicalRevision = 4), journal.copy(expectedLedgerRevision = 5),
            journal.copy(binaryReference = "different-image"), journal.copy(canonicalValueId = "other"),
            journal.copy(state = "REPAIR_REQUIRED")).forEach {
            assertFalse(isInterruptedPhotoProjectionRecoverable(context, ledger, 3, raw, desired, it, mapper))
        }
        listOf(ledger.copy(androidBaselineFingerprint = fingerprint), ledger.copy(pendingProjectionFingerprint = null),
            ledger.copy(tombstoneState = "CANONICAL_COMMITTED"), ledger.copy(adoptionState = "AWAITING_REMOTE_ID"))
            .forEach { assertFalse(isInterruptedPhotoProjectionRecoverable(context, it, 3, raw, desired, journal, mapper)) }
        assertFalse(isInterruptedPhotoProjectionRecoverable(context, ledger, 3, raw.copy(dirty = false), desired, journal, mapper))
        assertFalse(isInterruptedPhotoProjectionRecoverable(context, ledger, 3, raw.copy(deleted = true), desired, journal, mapper))
    }

    @Test fun `actual generated name is retained but native edits and deletions prevent automatic recovery`() {
        val observed = desired.copy(rows = desired.rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(components = row.components + mapOf(
                AndroidComponent.GIVEN_NAME to "Ada", AndroidComponent.FAMILY_NAME to "Lovelace")) else row
        })
        assertSame(observed, verifiedInitialProjectionBaseline(desired, observed, mapper))
        assertFalse(mapper.applyControlledDelta(canonical, observed, observed).hasChanges)
        val edited = observed.copy(rows = observed.rows.map { row ->
            if (row.kind == AndroidRowKind.PHONE) row.copy(value = "456") else row
        })
        assertNull(verifiedInitialProjectionBaseline(desired, edited, mapper))
        assertNull(verifiedInitialProjectionBaseline(desired, observed.copy(rows = observed.rows.filterNot {
            it.kind == AndroidRowKind.PHONE }), mapper))
        assertNull(verifiedInitialProjectionBaseline(desired, observed.copy(rows = observed.rows.map { row ->
            if (row.kind == AndroidRowKind.PHOTO) row.copy(binaryReference = "another-photo") else row
        }), mapper))
    }

    @Test fun `baseline diagnostics cannot authorize mismatches or expose compared values`() {
        val edited = desired.copy(rows = desired.rows.map {
            if (it.kind == AndroidRowKind.PHONE) it.copy(value = "PRIVATE_DIAGNOSTIC_CANARY") else it
        })
        var detail: AndroidInitialBaselineMismatch? = null
        assertNull(verifiedInitialProjectionBaseline(desired, edited, mapper) { detail = it })
        assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_OTHER, detail?.category)
        assertFalse(detail.toString().contains("PRIVATE_DIAGNOSTIC_CANARY"))
        assertNull(verifiedInitialProjectionBaseline(desired, edited, mapper) { error("observer failure") })
        assertSame(desired, verifiedInitialProjectionBaseline(desired, desired, mapper) {
            fail("An equal snapshot must not emit mismatch details")
        })
    }

    @Test fun `matching name parts do not authorize replacing an explicit alias during initial photo recovery`() {
        val contact = canonical.copy(firstName = "Mira", lastName = "Aster", displayName = "Aster Alias")
        val expected = mapper.project(contact)
        val observed = expected.copy(rows = expected.rows.map { row ->
            if (row.kind == AndroidRowKind.STRUCTURED_NAME) row.copy(
                value = "Mira Aster",
                components = row.components + (AndroidComponent.DISPLAY_NAME to "Mira Aster"),
            ) else row
        })
        var mismatch: AndroidInitialBaselineMismatch? = null

        assertSame(expected, verifiedInitialProjectionBaseline(expected, expected, mapper))
        assertNull(verifiedInitialProjectionBaseline(expected, observed, mapper) { mismatch = it })
        assertEquals(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_NAME, mismatch?.category)
    }
}
