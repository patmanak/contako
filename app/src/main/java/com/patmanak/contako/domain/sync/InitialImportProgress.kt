package com.patmanak.contako.domain.sync

/** Last live prerequisite observation, not proof that Android contact values are synchronized. */
enum class AndroidAvailability { UNKNOWN, PERMISSION_REQUIRED, CONTEXT_NOT_ESTABLISHED, UNAVAILABLE, READY }

/** Granting permission alone cannot establish a usable Android account/provider context. */
fun AndroidAvailability.withContactsPermission(granted: Boolean): AndroidAvailability = when {
    !granted -> AndroidAvailability.PERMISSION_REQUIRED
    this == AndroidAvailability.PERMISSION_REQUIRED -> AndroidAvailability.UNKNOWN
    else -> this
}

/**
 * Current, account-scoped local observations of the first import. These are read-model counts,
 * never incremental event counters. Activity is observed separately from the running engine.
 *
 * An established inventory describes the last committed inventory, including an empty one. It
 * does not prove that no newer remote change exists. No pending Android obligation is weaker
 * than a field-by-field proof that the current provider contents match every canonical value.
 * This private UI model MUST NOT be included in exported diagnostics.
 */
data class InitialImportSnapshot(
    val inventoryEstablished: Boolean,
    val localContactCount: Int,
    val inventoryContactCount: Int?,
    val deferredContactCount: Int,
    val androidPendingContactCount: Int,
) {
    init {
        require(localContactCount >= 0 && deferredContactCount >= 0 && androidPendingContactCount >= 0)
        require(inventoryContactCount == null || inventoryContactCount >= 0)
        require(inventoryEstablished == (inventoryContactCount != null))
    }

    /** Only acquisition of the committed remote inventory; independent of Android and uploads. */
    val remoteComplete: Boolean get() = inventoryEstablished && deferredContactCount == 0

    override fun toString() = "InitialImportSnapshot(REDACTED, inventoryEstablished=$inventoryEstablished)"
}
