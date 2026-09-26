package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.PreservationEnvelope

// Local-only snapshots: these keys are never Proton cards or serialized vCard properties.
private const val SOURCE_PREFIX = "pending-value-source/"
private const val CARD_PREFIX = "proton-card-"

/** Keep the source of positional value references immutable while absorbing verified remote data. */
internal fun mergePendingProtonPreservation(
    local: PreservationEnvelope?,
    remote: PreservationEnvelope?,
): PreservationEnvelope? {
    if (local == null) return remote
    if (remote == null) return local
    val sources = local.valueSourceCards().mapKeys { (key, _) -> SOURCE_PREFIX + key }
    return PreservationEnvelope(
        rawProperties = local.rawProperties +
            remote.rawProperties.filterKeys { !it.startsWith(SOURCE_PREFIX) } + sources,
        remoteBaseline = remote.remoteBaseline ?: local.remoteBaseline,
    )
}

/** A second retry must not repin references to an already replaced card. */
internal fun PreservationEnvelope.valueSourceCards(): Map<String, String> {
    val pinned = rawProperties.filterKeys { it.startsWith(SOURCE_PREFIX + CARD_PREFIX) }
        .mapKeys { (key, _) -> key.removePrefix(SOURCE_PREFIX) }
    return if (pinned.isEmpty()) rawProperties.filterKeys { it.startsWith(CARD_PREFIX) } else pinned
}

/** Multiset union retains intentional repeats but does not multiply fields on every retry. */
internal fun mergePreservedPropertyOccurrences(current: List<String>, source: List<String>): List<String> {
    val remaining = current.groupingBy { it }.eachCount().toMutableMap()
    return current + source.filter { line ->
        val count = remaining[line] ?: 0
        if (count > 0) {
            remaining[line] = count - 1
            false
        } else true
    }
}
