package com.patmanak.contako.data.android.provider

import android.provider.ContactsContract

internal enum class AndroidProviderMimeRouterFailure {
    ACCOUNT_SCOPE_MISMATCH,
    MIXED_RAW_CONTACTS,
    DUPLICATE_PROVIDER_ROW,
    BOUND_EXCEEDED,
}

/** Category-only failure: provider payloads and identifiers are never included in diagnostics. */
internal class AndroidProviderMimeRouterException(
    val category: AndroidProviderMimeRouterFailure,
) : IllegalArgumentException("Android provider MIME routing failed: ${category.name}")

internal data class ContactRows(
    val rows: List<AndroidOwnedDataRow>,
)

internal data class GroupMembershipRows(
    val rows: List<AndroidOwnedDataRow>,
) {
    init {
        require(rows.all { it.mimeType == ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE })
    }
}

internal data class UnsupportedOwnedRows(
    val rows: List<AndroidOwnedDataRow>,
)

/** Opaque provider-local rows: never decoded into canonical values or targeted by projection. */
internal data class ProviderLocalRows(
    val rows: List<AndroidOwnedDataRow>,
)

/**
 * Complete partition of one already account-scoped raw contact's Data rows.
 *
 * Each partition retains provider encounter order and the original row objects. Group membership
 * and unsupported/provider-local rows remain explicit outputs and cannot become contact rows.
 */
internal data class AndroidProviderMimeRoute(
    val contactRows: ContactRows,
    val groupMembershipRows: GroupMembershipRows,
    val unsupportedOwnedRows: UnsupportedOwnedRows,
    val providerLocalRows: ProviderLocalRows,
) {
    init {
        val totalRows = contactRows.rows.size + groupMembershipRows.rows.size +
            unsupportedOwnedRows.rows.size + providerLocalRows.rows.size
        require(totalRows <= MAX_ROWS_PER_CONTACT)
    }

    private companion object {
        const val MAX_ROWS_PER_CONTACT = 128
    }
}

/** Validates and partitions one complete provider row batch before any family-specific codec. */
internal class AndroidProviderMimeRouter {
    fun route(
        expectedRawContactId: Long,
        rows: List<AndroidOwnedDataRow>,
    ): AndroidProviderMimeRoute {
        if (expectedRawContactId <= 0) fail(AndroidProviderMimeRouterFailure.ACCOUNT_SCOPE_MISMATCH)
        if (rows.size > MAX_ROWS_PER_CONTACT) fail(AndroidProviderMimeRouterFailure.BOUND_EXCEEDED)
        if (rows.any { it.rawContactId != expectedRawContactId }) {
            fail(AndroidProviderMimeRouterFailure.MIXED_RAW_CONTACTS)
        }
        if (rows.map(AndroidOwnedDataRow::dataRowId).distinct().size != rows.size) {
            fail(AndroidProviderMimeRouterFailure.DUPLICATE_PROVIDER_ROW)
        }

        val contactRows = ArrayList<AndroidOwnedDataRow>(rows.size)
        val groupMembershipRows = ArrayList<AndroidOwnedDataRow>()
        val unsupportedOwnedRows = ArrayList<AndroidOwnedDataRow>()
        val providerLocalRows = ArrayList<AndroidOwnedDataRow>()
        rows.forEach { row ->
            when (row.mimeType) {
                in CONTACT_MIME_TYPES -> contactRows += row
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> groupMembershipRows += row
                // Observed on Samsung. Preserve this exact opaque MIME in the provider; do not
                // generalize to other vendor fields or remove it from version/scope checks.
                "vnd.android.cursor.item/rcs_data" -> providerLocalRows += row
                else -> unsupportedOwnedRows += row
            }
        }
        return AndroidProviderMimeRoute(
            contactRows = ContactRows(contactRows.toList()),
            groupMembershipRows = GroupMembershipRows(groupMembershipRows.toList()),
            unsupportedOwnedRows = UnsupportedOwnedRows(unsupportedOwnedRows.toList()),
            providerLocalRows = ProviderLocalRows(providerLocalRows.toList()),
        )
    }

    private fun fail(category: AndroidProviderMimeRouterFailure): Nothing =
        throw AndroidProviderMimeRouterException(category)

    private companion object {
        const val MAX_ROWS_PER_CONTACT = 128

        val CONTACT_MIME_TYPES = setOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
        )
    }
}
