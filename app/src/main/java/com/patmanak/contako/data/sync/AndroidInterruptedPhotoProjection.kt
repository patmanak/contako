package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper
import com.patmanak.contako.data.android.provider.AndroidOwnedRawContact
import com.patmanak.contako.data.local.AndroidPhotoProviderWriteJournalEntity
import com.patmanak.contako.data.local.AndroidProjectionLedgerEntity

/** A pending fingerprint alone is insufficient: the photo journal is prepared AFTER
 * the atomic contact/membership provider batch. Recovery still requires exact current
 * photo bytes, normalized fields and membership readback before adopting that observation.
 */
internal fun isInterruptedPhotoProjectionRecoverable(
    context: AndroidInteroperabilityContext,
    ledger: AndroidProjectionLedgerEntity,
    canonicalRevision: Long,
    raw: AndroidOwnedRawContact,
    desired: AndroidContactSnapshot,
    journal: AndroidPhotoProviderWriteJournalEntity,
    mapper: CanonicalAndroidContactMapper,
): Boolean {
    val photo = desired.rows.singleOrNull { it.kind == AndroidRowKind.PHOTO } ?: return false
    return raw.dirty && !raw.deleted && ledger.androidBaselineFingerprint == null &&
        ledger.tombstoneState == "NONE" && ledger.adoptionState == "ADOPTED" &&
        ledger.pendingProjectionFingerprint == mapper.fingerprint(desired).sha256Hex &&
        ledger.accountId == context.account.value && ledger.providerEpoch == context.providerEpoch &&
        ledger.canonicalContactId == desired.canonicalContactId &&
        raw.canonicalContactIdClaim == ledger.canonicalContactId && raw.sourceIdentity == ledger.sourceIdentity &&
        ledger.rawContactLocator == raw.rawContactId && journal.accountId == ledger.accountId &&
        journal.canonicalContactId == ledger.canonicalContactId && journal.providerEpoch == ledger.providerEpoch &&
        journal.androidAccountName == context.androidAccountName && journal.rawContactLocator == raw.rawContactId &&
        journal.expectedSourceIdentity == raw.sourceIdentity && journal.expectedCanonicalRevision == canonicalRevision &&
        journal.expectedLedgerRevision == ledger.revision && raw.version >= journal.expectedRawContactVersion &&
        journal.canonicalValueId == photo.identity.canonicalValueId && journal.binaryReference == photo.binaryReference &&
        journal.state in setOf("PREPARED", "COMMITTED")
}

/** Closed comparison detail only; never carry the compared snapshots or field values. */
internal data class AndroidInitialBaselineMismatch(
    val category: AndroidProjectionRepairCategory,
    val kind: AndroidRowKind?,
    val component: AndroidComponent?,
    val difference: AndroidComponentDifference?,
)

/** Keeps actual generated name parts as the baseline, but only after full comparison. */
internal fun verifiedInitialProjectionBaseline(desired: AndroidContactSnapshot,
    observed: AndroidContactSnapshot, mapper: CanonicalAndroidContactMapper,
    onMismatch: (AndroidInitialBaselineMismatch) -> Unit = {},
): AndroidContactSnapshot? {
    val matches = mapper.fingerprint(mapper.normalizeGeneratedName(observed, desired)) == mapper.fingerprint(desired)
    if (!matches) runCatching {
        var kind: AndroidRowKind? = null
        var component: AndroidComponent? = null
        var difference: AndroidComponentDifference? = null
        val category = classifyAndroidProjectionMismatch(desired, observed, mapper) { k, c, d ->
            kind = k
            component = c
            difference = d
        }
        onMismatch(AndroidInitialBaselineMismatch(category, kind, component, difference))
    }
    return observed.takeIf { matches }
}
