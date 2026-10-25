package com.patmanak.contako.data.sync

/**
 * Presentation attribution for the immediately returned operation only. True means every
 * failure behind a partial result was retained as an exact current contact issue. It never
 * grants projection, acknowledgement, retry or ownership authority. A caller without this
 * explicit proof MUST keep account-wide failure attribution conservative.
 */
internal interface AndroidScopedFailureProofSource {
    val lastFailureIsFullyScoped: Boolean
}
