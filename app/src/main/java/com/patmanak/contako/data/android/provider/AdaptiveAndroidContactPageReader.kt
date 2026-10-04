package com.patmanak.contako.data.android.provider

/**
 * Retry a byte-bounded page with fewer contacts, without changing the provider cursor or
 * weakening the reader's before/after version proof. An individually oversized contact remains
 * an explicit failure; ownership, permission, malformed data and stale observations are never
 * interpreted as a reason to accept less evidence.
 */
internal fun readAdaptiveAndroidContactPage(
    initialLimit: Int = 100,
    read: (Int) -> AndroidStableRawContactPageResult,
): AndroidStableRawContactPageResult {
    require(initialLimit in 1..100)
    var limit = initialLimit
    while (true) {
        try {
            return read(limit)
        } catch (error: AndroidProviderBoundaryException) {
            if (error.category != AndroidProviderFailureCategory.BOUND_EXCEEDED || limit == 1) {
                throw error
            }
            limit = (limit / 2).coerceAtLeast(1)
        }
    }
}
