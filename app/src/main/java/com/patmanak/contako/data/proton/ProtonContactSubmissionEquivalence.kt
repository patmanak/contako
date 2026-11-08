package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.CanonicalContact
import me.proton.core.contact.domain.entity.ContactCardType

/** Exact deterministic wire equivalence, including repeatable and opaque properties.
 * Only verified cards may be supplied on the remote side. Group assignments are independent.
 * A failed or unsupported encoding is missing proof, never permission to discard a conflict.
 */
internal fun equivalentContactSubmission(local: CanonicalContact, remote: CanonicalContact): Boolean {
    if (local.accountId != remote.accountId || local.isDeleted || remote.isDeleted) return false
    val uid = local.remoteVCardUid ?: local.id
    if (remote.remoteVCardUid != uid) return false
    fun wire(contact: CanonicalContact): List<ProtonPlainContactCard> {
        val encoded = ProtonContactVCardCodec().encode(contact.copy(id = local.id, remoteVCardUid = uid))
        return comparableContactCards(listOf(
            ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
            ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
            ProtonPlainContactCard(ContactCardType.ClearText, encoded.clear),
        ))
    }
    return try { wire(local) == wire(remote) } catch (_: IllegalArgumentException) { false }
}
