package com.patmanak.contako.data.proton

import com.patmanak.contako.domain.model.ContactValue
import me.proton.core.contact.domain.entity.ContactEmail
import java.util.Locale

/**
 * Proton retains repeated email identities ordered by ContactEmail.order. Match occurrence
 * order only with equal multiplicity and unique service orders/identities. A single service
 * identity may also represent several preserved local rows of that address.
 * See WebClients/packages/shared/lib/contacts/getContactEmailsMap.ts.
 */
internal fun protonEmailOccurrences(
    values: List<ContactValue>,
    remoteEmails: List<ContactEmail>,
): Map<String, ContactEmail> {
    fun key(value: String) = value.trim().lowercase(Locale.ROOT)
    val remoteByAddress = remoteEmails.groupBy { key(it.email) }
    return buildMap {
        values.groupBy { key(it.value) }.forEach { (address, local) ->
            val remote = remoteByAddress[address].orEmpty()
            if (remote.size == 1) {
                local.forEach { put(it.id, remote.single()) }
            } else if (remote.size == local.size && remote.isNotEmpty() &&
                remote.map { it.order }.distinct().size == remote.size &&
                remote.map { it.id }.distinct().size == remote.size &&
                local.map { it.order }.distinct().size == local.size) {
                local.sortedBy { it.order }.zip(remote.sortedBy { it.order }).forEach { (value, email) ->
                    put(value.id, email)
                }
            }
        }
    }
}
