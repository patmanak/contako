package com.patmanak.contako.data.android.provider

import com.patmanak.contako.data.local.AndroidPhotoProjectionReceiptEntity
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import java.security.MessageDigest

/** Exact observed representation, never a perceptual similarity or a source-byte guess. */
internal fun AndroidPhotoProjectionReceiptEntity.matchesPhoto(
    context: AndroidInteroperabilityContext,
    contactId: String,
    observation: AndroidStableRawContactObservation,
): Boolean {
    val raw = observation.rawContact
    val row = observation.dataRows.singleOrNull { it.isStandardPhoto } ?: return false
    val bytes = row.binarySlot ?: return false
    return accountId == context.account.value && canonicalContactId == contactId &&
        androidAccountName == context.androidAccountName && providerEpoch == context.providerEpoch &&
        !raw.deleted && raw.canonicalContactIdClaim == contactId && raw.rawContactId == rawContactLocator &&
        raw.version >= rawContactVersion && row.dataRowId == dataRowLocator &&
        row.canonicalValueId == canonicalValueId && bytes.size.toLong() == readbackSize &&
        androidPhotoSha256(bytes) == readbackSha256
}

internal fun androidPhotoSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun syncAdapterUri(base: android.net.Uri, account: AndroidProviderAccountName): android.net.Uri =
    base.buildUpon().appendQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, account.value)
        .appendQueryParameter(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE,
            com.patmanak.contako.android.account.ContakoAndroidAccountContract.ACCOUNT_TYPE).build()
