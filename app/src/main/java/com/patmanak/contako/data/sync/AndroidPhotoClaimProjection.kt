package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidProjectionPlan
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidRowOperation
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow

/**
 * Materialize missing provider claims after decoding through the durable identity resolver.
 * Native editors may replace a photo row without SYNC1/SYNC2. Semantic equality does not
 * establish those physical claims, which the journaled photo writer still requires.
 * The normal provider writer must authorize the exact durable binding and raw version;
 * this function supplies a row update, never authority to adopt a provider identity.
 */
internal fun materializeMissingPhotoClaims(
    plan: AndroidProjectionPlan,
    decoded: AndroidContactSnapshot,
    rawContactId: Long,
    providerRows: List<AndroidOwnedDataRow>,
): AndroidProjectionPlan {
    if (decoded.canonicalContactId != plan.desired.canonicalContactId) return plan
    val desired = plan.desired.rows.singleOrNull { it.kind == AndroidRowKind.PHOTO } ?: return plan
    val current = decoded.rows.singleOrNull { it.kind == AndroidRowKind.PHOTO } ?: return plan
    val physical = providerRows.singleOrNull { it.isStandardPhoto } ?: return plan
    if (physical.rawContactId != rawContactId || current.identity.providerRowId != physical.dataRowId ||
        current.identity.canonicalValueId != desired.identity.canonicalValueId ||
        physical.canonicalValueId != null && physical.canonicalValueId != current.identity.canonicalValueId ||
        physical.canonicalOrderMalformed ||
        physical.canonicalValueId != null && physical.canonicalOrder != null
    ) return plan
    // Existing updates already set all claims. A delete/reinsert must keep its original
    // identity lifecycle; never append a second operation for that row or photo.
    if (plan.operations.any { operation ->
            when (operation) {
                is AndroidRowOperation.Insert -> operation.desired.kind == AndroidRowKind.PHOTO
                is AndroidRowOperation.Update -> operation.desired.kind == AndroidRowKind.PHOTO ||
                    operation.currentIdentity.providerRowId == physical.dataRowId
                is AndroidRowOperation.Delete -> operation.currentIdentity.providerRowId == physical.dataRowId
            }
        }
    ) return plan
    return plan.copy(operations = plan.operations + AndroidRowOperation.Update(
        current.identity,
        desired.withoutProviderLocator().copy(isSuperPrimary = current.isSuperPrimary),
    ))
}
