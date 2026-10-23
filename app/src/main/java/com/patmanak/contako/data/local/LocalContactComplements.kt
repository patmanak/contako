package com.patmanak.contako.data.local

import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import java.util.Locale

/**
 * Retains only explicitly local-only data when adopting a verified Proton version.
 * Remote values, removals, raw preservation data and revision remain authoritative.
 * A whole-contact Proton conflict choice uses the same rule as normal hydration.
 */
internal fun retainLocalContactComplements(local: CanonicalContact, remote: CanonicalContact): CanonicalContact {
    val localDates = local.valuesOf(ContactValueKind.CUSTOM_DATE)
    // Remote decoding does not generate CUSTOM_DATE. Refuse a value-ID collision rather
    // than replacing either peer's value; the surrounding transaction remains atomic.
    require(localDates.none { date -> remote.values.any { it.id == date.id } })
    val values = remote.values.map { incoming ->
        if (!incoming.label.isNullOrBlank() || !incoming.metadata["vcardTypeTokens"].isNullOrBlank()) {
            return@map incoming
        }
        // Equality is exact and occurrence-unique: do not attach a former local label to
        // a changed, removed or ambiguous repeated remote value.
        val candidates = local.values.filter { it.sameComplementTarget(incoming) }
        val old = candidates.singleOrNull()?.takeIf { it.hasLocalOnlyLabel() } ?: return@map incoming
        if (remote.values.count { it.sameComplementTarget(incoming) } != 1) return@map incoming
        incoming.copy(label = old.label,
            metadata = incoming.metadata + old.metadata.filterKeys { it in LOCAL_TYPE_METADATA } +
                (LOCAL_LABEL_METADATA to "true"))
    }
    return remote.copy(values = values + localDates)
}

private fun ContactValue.sameComplementTarget(other: ContactValue): Boolean =
    kind == other.kind && value == other.value && components == other.components

private fun ContactValue.hasLocalOnlyLabel(): Boolean {
    val locallyEstablished = metadata[LOCAL_LABEL_METADATA] == "true" ||
        !metadata["androidSemanticType"].isNullOrBlank() ||
        (preservationKey == null && metadata["vcardTypeTokens"].isNullOrBlank())
    if (!locallyEstablished) return false
    return hasUnsupportedGeneratedLabel()
}

/** Called only at the durable local-edit boundary, never while adopting remote data. */
internal fun markEditedLocalContactLabels(previous: CanonicalContact?, edited: CanonicalContact): CanonicalContact {
    val before = previous?.values.orEmpty().associateBy(ContactValue::id)
    return edited.copy(values = edited.values.map { value ->
        val old = before[value.id]
        if (value.hasUnsupportedGeneratedLabel() && (old == null || old.label != value.label)) {
            value.copy(metadata = value.metadata + (LOCAL_LABEL_METADATA to "true"))
        } else if (!value.hasUnsupportedGeneratedLabel() && LOCAL_LABEL_METADATA in value.metadata) {
            value.copy(metadata = value.metadata - LOCAL_LABEL_METADATA)
        } else value
    })
}

private fun ContactValue.hasUnsupportedGeneratedLabel(): Boolean {
    val type = label?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT) ?: return false
    return when (kind) {
        ContactValueKind.URL, ContactValueKind.RELATIONSHIP -> true
        ContactValueKind.POSTAL_ADDRESS -> type !in setOf("HOME", "WORK", "OTHER")
        else -> false
    }
}

private val LOCAL_TYPE_METADATA = setOf("androidSemanticType", "androidCustomLabel")
private const val LOCAL_LABEL_METADATA = "contakoLocalOnlyLabel"
