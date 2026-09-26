package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.CanonicalAndroidContactMapper

/** Diagnostic comparison MUST use the same equivalences as post-write fingerprint verification.
 * Only enums may cross the observer boundary; snapshots MUST NOT be logged or retained.
 */
internal fun classifyAndroidProjectionMismatch(
    desiredSnapshot: AndroidContactSnapshot,
    actualSnapshot: AndroidContactSnapshot,
    mapper: CanonicalAndroidContactMapper,
    onComponentMismatch: (AndroidRowKind, AndroidComponent, AndroidComponentDifference) -> Unit = { _, _, _ -> },
): AndroidProjectionRepairCategory {
    val desired = mapper.projectionComparisonSnapshot(desiredSnapshot)
    val actual = mapper.projectionComparisonSnapshot(mapper.normalizeGeneratedName(actualSnapshot, desiredSnapshot))
    if (desired.canonicalContactId != actual.canonicalContactId) {
        return AndroidProjectionRepairCategory.POST_WRITE_CONTACT_ROW_SET
    }
    val desiredRows = desired.rows.associateBy { it.identity.canonicalValueId to it.kind }
    val actualRows = actual.rows.associateBy { it.identity.canonicalValueId to it.kind }
    if (desiredRows.keys != actualRows.keys || desiredRows.size != desired.rows.size ||
        actualRows.size != actual.rows.size
    ) return AndroidProjectionRepairCategory.POST_WRITE_CONTACT_ROW_SET
    desiredRows.forEach { (key, expected) ->
        val observed = actualRows.getValue(key)
        if (expected.isPrimary != observed.isPrimary) return when (expected.kind) {
            AndroidRowKind.STRUCTURED_NAME ->
                AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_NAME_PRIMARY
            AndroidRowKind.PHOTO -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_PHOTO_PRIMARY
            AndroidRowKind.EMAIL -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_EMAIL_PRIMARY
            AndroidRowKind.PHONE -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_PHONE_PRIMARY
            AndroidRowKind.POSTAL_ADDRESS ->
                AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_POSTAL_PRIMARY
            AndroidRowKind.WEBSITE -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_WEBSITE_PRIMARY
            AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY,
            AndroidRowKind.CUSTOM_DATE,
            -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_DATE_PRIMARY
            AndroidRowKind.RELATIONSHIP ->
                AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_RELATIONSHIP_PRIMARY
            AndroidRowKind.ORGANIZATION ->
                AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_ORGANIZATION_PRIMARY
            else -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_OTHER_PRIMARY
        }
        if (expected.isSuperPrimary != observed.isSuperPrimary) return when (expected.kind) {
            AndroidRowKind.STRUCTURED_NAME ->
                AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_NAME_SUPER_PRIMARY
            AndroidRowKind.PHOTO ->
                AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_PHOTO_SUPER_PRIMARY
            else -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_OTHER_SUPER_PRIMARY
        }
        if (expected.value != observed.value) return when (expected.kind) {
            AndroidRowKind.STRUCTURED_NAME -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_NAME
            AndroidRowKind.PHOTO -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_PHOTO
            AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY,
            AndroidRowKind.CUSTOM_DATE,
            -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_DATE
            else -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VALUE_OTHER
        }
        if (expected.semanticType != observed.semanticType || expected.customLabel != observed.customLabel) {
            return when (expected.kind) {
                AndroidRowKind.EMAIL -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_EMAIL
                AndroidRowKind.PHONE -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_PHONE
                AndroidRowKind.POSTAL_ADDRESS -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_POSTAL
                AndroidRowKind.ORGANIZATION ->
                    AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_ORGANIZATION
                AndroidRowKind.WEBSITE -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_WEBSITE
                AndroidRowKind.BIRTHDAY,
                AndroidRowKind.ANNIVERSARY,
                AndroidRowKind.CUSTOM_DATE,
                -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_DATE
                AndroidRowKind.RELATIONSHIP ->
                    AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_RELATIONSHIP
                else -> AndroidProjectionRepairCategory.POST_WRITE_CONTACT_TYPE_OTHER
            }
        }
        if (expected.order != observed.order) return AndroidProjectionRepairCategory.POST_WRITE_CONTACT_ORDER
        if (expected.components != observed.components) {
            val component = AndroidComponent.entries.first {
                expected.components[it].orEmpty() != observed.components[it].orEmpty()
            }
            val before = expected.components[component].orEmpty()
            val after = observed.components[component].orEmpty()
            val difference = when {
                before.isEmpty() -> AndroidComponentDifference.EXPECTED_EMPTY
                after.isEmpty() -> AndroidComponentDifference.OBSERVED_EMPTY
                before.trim().replace(Regex("\\s+"), " ") == after.trim().replace(Regex("\\s+"), " ") ->
                    AndroidComponentDifference.WHITESPACE_ONLY
                else -> AndroidComponentDifference.DIFFERENT
            }
            runCatching { onComponentMismatch(expected.kind, component, difference) }
            return AndroidProjectionRepairCategory.POST_WRITE_CONTACT_COMPONENTS
        }
        if (expected.linkedCanonicalValueIds != observed.linkedCanonicalValueIds) {
            return AndroidProjectionRepairCategory.POST_WRITE_CONTACT_LINKS
        }
        if (expected.binaryReference != observed.binaryReference) {
            return AndroidProjectionRepairCategory.POST_WRITE_CONTACT_PHOTO_REFERENCE
        }
    }
    return AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VERIFICATION
}
