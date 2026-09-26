package com.patmanak.contako.android.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** Android cancels a sync by interrupting its thread, including while it awaits the shared runner. */
internal fun runSyncAdapterBlocking(block: suspend CoroutineScope.() -> Unit) {
    try {
        runBlocking(block = block)
    } catch (_: InterruptedException) {
        // runBlocking cancels only this waiter. Account-scoped work and durable intent remain
        // owned by the shared runner. Preserve Android's cancellation signal without turning
        // it into a process crash or an authentication/I/O failure that schedules a retry.
        Thread.currentThread().interrupt()
    }
}
