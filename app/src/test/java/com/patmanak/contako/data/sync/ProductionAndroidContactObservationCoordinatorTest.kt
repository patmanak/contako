package com.patmanak.contako.data.sync

import android.provider.ContactsContract
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouter
import com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure
import com.patmanak.contako.data.android.provider.AndroidProviderPhotoReferenceEncoder
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
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
    fun `created lifecycle ignores opaque binaries but rejects binaries in recognized text fields`() {
        val photo = row("vnd.android.cursor.item/photo", binary = byteArrayOf(1))
        val opaque = row("application/vnd.fixture.unknown", id = 2, binary = byteArrayOf(2))
        assertEquals(false, createdLifecycleRouteRequiresRepair(route(photo, opaque)))
        assertEquals(true, createdLifecycleRouteRequiresRepair(route(
            row("vnd.android.cursor.item/email_v2", binary = byteArrayOf(1)), opaque,
        )))
    }

    @Test
    fun `native photo capture retains bytes as a durable canonical reference`() {
        val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        assertEquals(
            AndroidProviderPhotoReferenceEncoder.encode(photo),
            createdContactPhotoCapture().capture(AndroidProviderAccountName("fixture"), "contact", 7, photo),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `native photo capture rejects unknown binary content`() {
        createdContactPhotoCapture().capture(AndroidProviderAccountName("fixture"), "contact", 7, byteArrayOf(1))
    }

    @Test
    fun `local payload failures are isolated while identity catalog and allocation failures stop ingestion`() {
        assertEquals(true, isContactLocalPlanFailure(AndroidExistingContactPlanRepairReason.UNSUPPORTED_PROVIDER_IM))
        assertEquals(true, isContactLocalPlanFailure(AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_READBACK_MISMATCH))
        listOf(
            AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_NO_PHOTO_RECOVERY,
            AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_JOURNAL_MISSING,
            AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_PHOTO_RECOVERY_STATE_MISMATCH,
            AndroidExistingContactPlanRepairReason.CONTACT_BASELINE_MEMBERSHIPS_DIFFER,
        ).forEach { assertEquals(true, isContactLocalPlanFailure(it)) }
        assertEquals(true, isContactLocalPlanFailure(
            AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE, AndroidProviderRowCodecFailure.MALFORMED_DATE,
        ))
        listOf(
            AndroidExistingContactPlanRepairReason.ACCOUNT_MISSING,
            AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_SOURCE_IDENTITY_MISMATCH,
            AndroidExistingContactPlanRepairReason.CONTACT_LEDGER_PROVIDER_EPOCH_MISMATCH,
            AndroidExistingContactPlanRepairReason.MEMBERSHIP_CATALOG_MISMATCH,
            AndroidExistingContactPlanRepairReason.GROUP_LEDGER_BINDING_INCOMPLETE,
            AndroidExistingContactPlanRepairReason.INVALID_ARGUMENT,
        ).forEach { assertEquals(false, isContactLocalPlanFailure(it)) }
        listOf(
            AndroidProviderRowCodecFailure.ACCOUNT_SCOPE_MISMATCH,
            AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE,
            AndroidProviderRowCodecFailure.VALUE_ID_ALLOCATION_FAILED,
            AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES,
        ).forEach { assertEquals(false, isContactLocalCodecFailure(it)) }
        assertEquals(false, isContactLocalPlanFailure(AndroidExistingContactPlanRepairReason.ROW_CODEC_FAILURE))
    }

    @Test
    fun `unmapped field families never enter the supported snapshot or identity resolver`() {
        val name = row("vnd.android.cursor.item/name").copy(stringSlots = listOf("Fixture") + List(13) { null })
        val mimes = listOf("vnd.android.cursor.item/contact_user_defined_field", "vnd.android.cursor.item/im",
            "vnd.android.cursor.item/sip_address", "vnd.android.cursor.item/identity", "application/vnd.example.private",
            "vnd.android.cursor.item/unknown-fixture", "vnd.sec.cursor.item/profile_card_extra")
        val opaque = mimes.mapIndexed { index, mime -> row(mime, index.toLong() + 2, byteArrayOf(1)).copy(
            canonicalValueId = "spoofed-same-claim", canonicalOrderMalformed = true,
            linkedValueIdsEncoding = "not-a-managed-encoding",
        ) }
        val routed = route(name, *opaque.toTypedArray())
        assertEquals(opaque, routed.unsupportedOwnedRows.rows)
        assertEquals(false, createdLifecycleRouteRequiresRepair(routed))
        var resolved = 0
        val snapshot = com.patmanak.contako.data.android.provider.AndroidProviderRowCodec(
            com.patmanak.contako.data.android.provider.AndroidProviderIdentityResolver { claims ->
                resolved += claims.size
                com.patmanak.contako.data.android.provider.AndroidProviderIdentityResolution.Bound(
                    claims.associate { it.providerRowId to com.patmanak.contako.data.android.provider.AndroidDurableValueBinding("name", emptyMap()) },
                )
            },
            createdContactPhotoCapture(),
        ).decode(AndroidProviderAccountName("fixture"), "contact", 7, routed.contactRows.rows)
        assertEquals(1, resolved)
        assertEquals("Fixture", snapshot.rows.single().components[com.patmanak.contako.data.android.mapping.AndroidComponent.DISPLAY_NAME])
    }

    @Test
    fun `profile card does not block a standard photo or become a binary contact field`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        val photo = row(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, id = 1, binary = bytes)
        val profile = row("vnd.sec.cursor.item/profile_card", id = 2, binary = byteArrayOf(3, 2, 1))
        val routed = route(photo, profile)

        assertEquals(listOf(photo), routed.contactRows.rows)
        assertEquals(listOf(profile), routed.providerLocalRows.rows)
        assertEquals(false, createdLifecycleRouteRequiresRepair(routed))
        assertEquals(AndroidProviderPhotoReferenceEncoder.encode(bytes),
            createdContactPhotoCapture().capture(AndroidProviderAccountName("fixture"), "contact", 7,
                requireNotNull(routed.contactRows.rows.single().binarySlot)))
        assertEquals(false, createdLifecycleRouteRequiresRepair(route(photo, profile,
            row("vnd.sec.cursor.item/profile_card_extra", id = 3, binary = byteArrayOf(9)))))
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
