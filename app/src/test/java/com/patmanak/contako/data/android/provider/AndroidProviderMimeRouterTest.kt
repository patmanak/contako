package com.patmanak.contako.data.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProviderMimeRouterTest {
    private val router = AndroidProviderMimeRouter()

    @Test
    fun retainsExactRcsRowsWithoutAdmittingUnknownOrLookalikeMimeTypes() {
        val name = row(1, STRUCTURED_NAME)
        val rcs = row(2, "vnd.android.cursor.item/rcs_data").copy(
            stringSlots = List(14) { "opaque-fixture-$it" }, binarySlot = byteArrayOf(1, 2, 3),
        )
        val unknown = row(3, PRIVATE_MIME)
        val lookalike = row(4, "vnd.android.cursor.item/rcs_data_extra")
        val secondRcs = row(5, rcs.mimeType)
        val routed = router.route(RAW_CONTACT_ID, listOf(name, rcs, unknown, lookalike, secondRcs))
        assertEquals(listOf(name), routed.contactRows.rows)
        assertEquals(listOf(rcs, secondRcs), routed.providerLocalRows.rows)
        assertSame(rcs, routed.providerLocalRows.rows.first())
        assertEquals(listOf(unknown, lookalike), routed.unsupportedOwnedRows.rows)
        assertTrue(routed.groupMembershipRows.rows.isEmpty())
    }

    @Test
    fun providerLocalRowsStillParticipateInScopeIdentityAndSizeGuards() {
        val mime = "vnd.android.cursor.item/rcs_data"
        assertFailure(AndroidProviderMimeRouterFailure.MIXED_RAW_CONTACTS, mime) {
            router.route(RAW_CONTACT_ID, listOf(row(1, mime, RAW_CONTACT_ID + 1)))
        }
        assertFailure(AndroidProviderMimeRouterFailure.DUPLICATE_PROVIDER_ROW, mime) {
            router.route(RAW_CONTACT_ID, listOf(row(1, mime), row(1, EMAIL)))
        }
        assertFailure(AndroidProviderMimeRouterFailure.BOUND_EXCEEDED, mime) {
            router.route(RAW_CONTACT_ID, (1L..129L).map { row(it, mime) })
        }
    }

    @Test
    fun partitionsEveryRowExplicitlyWhilePreservingEncounterOrderAndIdentity() {
        val firstContact = row(5, STRUCTURED_NAME)
        val firstGroup = row(3, GROUP_MEMBERSHIP)
        val unsupported = row(4, PRIVATE_MIME)
        val secondContact = row(2, EMAIL)
        val secondGroup = row(1, GROUP_MEMBERSHIP)

        val routed = router.route(
            expectedRawContactId = RAW_CONTACT_ID,
            rows = listOf(firstContact, firstGroup, unsupported, secondContact, secondGroup),
        )

        assertEquals(listOf(firstContact, secondContact), routed.contactRows.rows)
        assertEquals(listOf(firstGroup, secondGroup), routed.groupMembershipRows.rows)
        assertEquals(listOf(unsupported), routed.unsupportedOwnedRows.rows)
        assertSame(firstContact, routed.contactRows.rows[0])
        assertSame(firstGroup, routed.groupMembershipRows.rows[0])
        assertSame(unsupported, routed.unsupportedOwnedRows.rows[0])
    }

    @Test
    fun routesEveryContactCodecMimeWithoutAdmittingGroupsOrUnknownRows() {
        val contactRows = CONTACT_MIMES.mapIndexed { index, mime -> row(index.toLong() + 1, mime) }
        val group = row(100, GROUP_MEMBERSHIP)
        val unsupported = row(101, PRIVATE_MIME)

        val routed = router.route(RAW_CONTACT_ID, contactRows + group + unsupported)

        assertEquals(contactRows, routed.contactRows.rows)
        assertEquals(listOf(group), routed.groupMembershipRows.rows)
        assertEquals(listOf(unsupported), routed.unsupportedOwnedRows.rows)
    }

    @Test
    fun rejectsMixedRawContactsBeforeReturningAnyPartition() {
        assertFailure(AndroidProviderMimeRouterFailure.MIXED_RAW_CONTACTS, PRIVATE_MIME) {
            router.route(
                RAW_CONTACT_ID,
                listOf(row(1, STRUCTURED_NAME), row(2, PRIVATE_MIME, rawContactId = RAW_CONTACT_ID + 1)),
            )
        }
    }

    @Test
    fun rejectsDuplicateProviderRowsAcrossDifferentMimeFamilies() {
        assertFailure(AndroidProviderMimeRouterFailure.DUPLICATE_PROVIDER_ROW, PRIVATE_MIME) {
            router.route(
                RAW_CONTACT_ID,
                listOf(row(7, STRUCTURED_NAME), row(7, PRIVATE_MIME)),
            )
        }
    }

    @Test
    fun rejectsOversizedBatch() {
        val oversized = (1L..129L).map { id -> row(id, STRUCTURED_NAME) }

        assertFailure(AndroidProviderMimeRouterFailure.BOUND_EXCEEDED, PRIVATE_MIME) {
            router.route(RAW_CONTACT_ID, oversized)
        }
    }

    @Test
    fun acceptsEmptyAndExactMaximumBatches() {
        val empty = router.route(RAW_CONTACT_ID, emptyList())
        assertEquals(emptyList<AndroidOwnedDataRow>(), empty.contactRows.rows)
        assertEquals(emptyList<AndroidOwnedDataRow>(), empty.groupMembershipRows.rows)
        assertEquals(emptyList<AndroidOwnedDataRow>(), empty.unsupportedOwnedRows.rows)

        val maximum = (1L..128L).map { id -> row(id, STRUCTURED_NAME) }
        assertEquals(maximum, router.route(RAW_CONTACT_ID, maximum).contactRows.rows)
    }

    @Test
    fun rejectsInvalidExpectedRawContactWithoutLeakingIdentifiers() {
        assertFailure(AndroidProviderMimeRouterFailure.ACCOUNT_SCOPE_MISMATCH, PRIVATE_MIME) {
            router.route(0, listOf(row(1, PRIVATE_MIME)))
        }
    }

    private fun assertFailure(
        expected: AndroidProviderMimeRouterFailure,
        secret: String,
        block: () -> Unit,
    ) {
        val failure = assertThrows(AndroidProviderMimeRouterException::class.java) { block() }
        assertEquals(expected, failure.category)
        assertFalse(failure.message.orEmpty().contains(secret))
        assertFalse(failure.message.orEmpty().contains(RAW_CONTACT_ID.toString()))
    }

    private fun row(
        id: Long,
        mimeType: String,
        rawContactId: Long = RAW_CONTACT_ID,
    ) = AndroidOwnedDataRow(
        dataRowId = id,
        rawContactId = rawContactId,
        mimeType = mimeType,
        canonicalValueId = null,
        canonicalOrder = null,
        linkedValueIdsEncoding = null,
        isPrimary = false,
        isSuperPrimary = false,
        stringSlots = List(14) { null },
        binarySlot = null,
    )

    private companion object {
        const val RAW_CONTACT_ID = 42L
        const val STRUCTURED_NAME = "vnd.android.cursor.item/name"
        const val EMAIL = "vnd.android.cursor.item/email_v2"
        const val PHONE = "vnd.android.cursor.item/phone_v2"
        const val POSTAL = "vnd.android.cursor.item/postal-address_v2"
        const val ORGANIZATION = "vnd.android.cursor.item/organization"
        const val PHOTO = "vnd.android.cursor.item/photo"
        const val NICKNAME = "vnd.android.cursor.item/nickname"
        const val NOTE = "vnd.android.cursor.item/note"
        const val WEBSITE = "vnd.android.cursor.item/website"
        const val EVENT = "vnd.android.cursor.item/contact_event"
        const val RELATIONSHIP = "vnd.android.cursor.item/relation"
        const val GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership"
        const val PRIVATE_MIME = "vnd.android.cursor.item/private-canary"

        val CONTACT_MIMES = listOf(
            STRUCTURED_NAME,
            EMAIL,
            PHONE,
            POSTAL,
            ORGANIZATION,
            PHOTO,
            NICKNAME,
            NOTE,
            WEBSITE,
            EVENT,
            RELATIONSHIP,
        )
    }
}
