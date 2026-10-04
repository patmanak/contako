package com.patmanak.contako.data.android.provider

/** Shared across loaders/verifiers: item concurrency must not multiply live bitmap allocations. */
internal object AndroidPhotoWorkBudget {
    private val monitor = Any()

    fun <T> withDecodedPhoto(block: () -> T): T = synchronized(monitor) { block() }
}
