package com.patmanak.contako.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A UI intent belongs to one account lifetime, even when the local partition is reused. */
data class ContactMutationAccess(val generation: Long = 0, val blocked: Boolean = false)

interface ContactMutationAdmission {
    val access: StateFlow<ContactMutationAccess>
    fun accepts(generation: Long): Boolean = access.value.let { !it.blocked && it.generation == generation }

    /** Runs under the same exclusion boundary as account cleanup; stale intents do not run. */
    suspend fun run(generation: Long, action: suspend () -> Unit): Boolean
}

/** Preview/test default. Production MUST use the shared account lifecycle admission. */
object UnrestrictedContactMutationAdmission : ContactMutationAdmission {
    override val access: StateFlow<ContactMutationAccess> = MutableStateFlow(ContactMutationAccess())
    override suspend fun run(generation: Long, action: suspend () -> Unit): Boolean {
        action()
        return true
    }
}
