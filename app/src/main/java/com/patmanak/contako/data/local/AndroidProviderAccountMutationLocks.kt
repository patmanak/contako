package com.patmanak.contako.data.local

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

/** Live-process account exclusion plus bounded independent contact projection slots. */
internal object AndroidProviderAccountMutationLocks {
    private class AccountGate {
        val exclusive = Mutex()
        val contactProjectionSlots = Semaphore(CONTACT_PROJECTION_PERMITS)
    }

    private val gates = ConcurrentHashMap<String, AccountGate>()

    suspend fun <T> withAccountLock(accountId: String, block: suspend () -> T): T {
        require(accountId.isNotBlank())
        val gate = gates.computeIfAbsent(accountId) { AccountGate() }
        return gate.exclusive.withLock {
            var acquired = 0
            try {
                repeat(CONTACT_PROJECTION_PERMITS) {
                    gate.contactProjectionSlots.acquire()
                    acquired++
                }
                block()
            } finally {
                repeat(acquired) { gate.contactProjectionSlots.release() }
            }
        }
    }

    suspend fun <T> withContactProjectionSlot(accountId: String, block: suspend () -> T): T {
        require(accountId.isNotBlank())
        return gates.computeIfAbsent(accountId) { AccountGate() }
            .contactProjectionSlots
            .withPermit { block() }
    }

    private const val CONTACT_PROJECTION_PERMITS = 16
}
