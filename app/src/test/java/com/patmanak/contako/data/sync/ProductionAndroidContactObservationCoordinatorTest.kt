package com.patmanak.contako.data.sync

import android.provider.ContactsContract
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouter
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitRepairReason
import com.patmanak.contako.data.local.RoomAndroidUnifiedCommitReplanReason
import com.patmanak.contako.data.local.RoomAndroidMembershipLedgerStaleReason
import com.patmanak.contako.data.local.AndroidCanonicalContactRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionAndroidContactObservationCoordinatorTest {
    @Test
    fun `ingestion replan diagnostic remains a closed value-free contract`() {
        assertEquals(11, AndroidIngestReplanReason.entries.size)
        assertEquals(
            AndroidIngestReplanReason.CONTACT_PROVIDER_ACK_STALE,
            AndroidIngestReplanReason.valueOf("CONTACT_PROVIDER_ACK_STALE"),
        )
    }

    @Test
    fun `canonical rejection diagnostic remains a closed value-free contract`() {
        assertEquals(11, AndroidCanonicalContactRejectionReason.entries.size)
        assertEquals(
            AndroidCanonicalContactRejectionReason.DUPLICATE_VALUE_ORDER,
            AndroidCanonicalContactRejectionReason.valueOf("DUPLICATE_VALUE_ORDER"),
        )
    }

    @Test
    fun `existing contact commit diagnostic remains a closed two-reason contract`() {
        assertEquals(
            setOf(
                RoomAndroidUnifiedCommitRepairReason.CONTACT_CANONICAL_REJECTED,
                RoomAndroidUnifiedCommitRepairReason.MEMBERSHIP_REPAIR_REQUIRED,
            ),
            RoomAndroidUnifiedCommitRepairReason.entries.toSet(),
        )
    }

    @Test
    fun `existing contact commit replan diagnostic remains a closed value-free contract`() {
        assertEquals(11, RoomAndroidUnifiedCommitReplanReason.entries.size)
        assertEquals(
            RoomAndroidUnifiedCommitReplanReason.MEMBERSHIP_STALE_CANONICAL_CONTEXT,
            RoomAndroidUnifiedCommitReplanReason.valueOf("MEMBERSHIP_STALE_CANONICAL_CONTEXT"),
        )
    }

    @Test
    fun `membership ledger stale diagnostic remains a closed value-free contract`() {
        assertEquals(6, RoomAndroidMembershipLedgerStaleReason.entries.size)
        assertEquals(
            RoomAndroidMembershipLedgerStaleReason.RAW_CONTACT_LOCATOR,
            RoomAndroidMembershipLedgerStaleReason.valueOf("RAW_CONTACT_LOCATOR"),
        )
    }

    @Test
    fun `contact lifecycle routing requires exact claim and source shape`() {
        assertEquals(AndroidContactObservationPath.CREATED, raw(null, null, deleted = false).observationPath())
        assertEquals(AndroidContactObservationPath.DELETED, raw("canonical", "remote", deleted = true).observationPath())
        assertEquals(AndroidContactObservationPath.EXISTING, raw("canonical", "remote", deleted = false).observationPath())
        assertEquals(AndroidContactObservationPath.INVALID, raw("canonical", null, deleted = false).observationPath())
        assertEquals(AndroidContactObservationPath.INVALID, raw(null, "remote", deleted = false).observationPath())
        assertEquals(AndroidContactObservationPath.INVALID, raw(null, null, deleted = true).observationPath())
    }

    @Test
    fun `created lifecycle accepts routed memberships but rejects unsupported or binary rows`() {
        assertEquals(false, createdLifecycleRouteRequiresRepair(false, false))
        assertEquals(true, createdLifecycleRouteRequiresRepair(false, true))
        assertEquals(true, createdLifecycleRouteRequiresRepair(true, false))
    }

    @Test
    fun `existing contact diagnostic classifies unsupported rows without exporting MIME values`() {
        val custom = route(row("vnd.android.cursor.item/contact_user_defined_field"))
        val vendor = route(row("application/vnd.example.private"))
        val multiple = route(
            row("vnd.android.cursor.item/im", id = 1),
            row("vnd.android.cursor.item/sip_address", id = 2),
        )

        assertEquals(
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_CUSTOM_FIELD,
            existingContactUnsupportedRowsRepairReason(custom),
        )
        assertEquals(
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_VENDOR_MIME,
            existingContactUnsupportedRowsRepairReason(vendor),
        )
        assertEquals(
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_MULTIPLE_FAMILIES,
            existingContactUnsupportedRowsRepairReason(multiple),
        )
    }

    @Test
    fun `provider local RCS does not block managed rows but unrelated unknown rows still do`() {
        val rcs = row("vnd.android.cursor.item/rcs_data", id = 1)
        assertEquals(null, existingContactUnsupportedRowsRepairReason(route(rcs)))
        assertEquals(
            AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_ANDROID_MIME,
            existingContactUnsupportedRowsRepairReason(route(
                rcs, row("vnd.android.cursor.item/unknown-fixture", id = 2),
            )),
        )
    }

    @Test
    fun `existing contact reuses retained photo only for a clean standard binary row`() {
        val route = route(
            row(
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
                binary = byteArrayOf(1),
            ),
        )
        val baseline = AndroidContactSnapshot(
            canonicalContactId = "contact",
            rows = listOf(
                AndroidContactRow(
                    identity = AndroidValueIdentity("photo"),
                    kind = AndroidRowKind.PHOTO,
                    binaryReference = "data:image/jpeg;base64,AQ==",
                ),
            ),
        )

        assertEquals(
            "data:image/jpeg;base64,AQ==",
            existingContactRetainedPhotoReference(route, baseline, rawContactDirty = false),
        )
        assertEquals(null, existingContactRetainedPhotoReference(route, baseline, rawContactDirty = true))
        assertEquals(null, existingContactRetainedPhotoReference(route, baseline.copy(rows = emptyList()), false))
    }

    @Test
    fun `dirty standard photo remains eligible for durable capture rather than baseline reuse`() {
        val route = route(
            row(
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
                binary = byteArrayOf(1),
            ),
        )
        val baseline = AndroidContactSnapshot(
            canonicalContactId = "contact",
            rows = listOf(
                AndroidContactRow(
                    identity = AndroidValueIdentity("photo"),
                    kind = AndroidRowKind.PHOTO,
                    binaryReference = "data:image/jpeg;base64,AQ==",
                ),
            ),
        )

        assertEquals(null, existingContactRetainedPhotoReference(route, baseline, rawContactDirty = true))
    }

    private fun raw(claim: String?, source: String?, deleted: Boolean) = AndroidOwnedRawContact(
        rawContactId = 7,
        canonicalContactIdClaim = claim,
        sourceIdentity = source,
        dirty = true,
        deleted = deleted,
        version = 11,
    )

    private fun route(vararg rows: AndroidOwnedDataRow) = AndroidProviderMimeRouter().route(7, rows.toList())

    private fun row(
        mime: String,
        id: Long = 1,
        binary: ByteArray? = null,
    ) = AndroidOwnedDataRow(
        dataRowId = id,
        rawContactId = 7,
        mimeType = mime,
        canonicalValueId = null,
        canonicalOrder = null,
        linkedValueIdsEncoding = null,
        isPrimary = false,
        isSuperPrimary = false,
        stringSlots = List(14) { null },
        binarySlot = binary,
    )
}
