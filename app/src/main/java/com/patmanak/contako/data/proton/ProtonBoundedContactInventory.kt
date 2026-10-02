package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.ContactInventoryPage
import me.proton.core.contact.data.api.ContactApi
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactEmail
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider

/** Uses maintained Core page models, with bounds enforced while acquiring the directory. */
internal class ProtonBoundedContactInventory(
    private val contactsPage: suspend (UserId, Int, Int) -> me.proton.core.contact.data.api.response.GetContactsResponse,
    private val emailsPage: suspend (UserId, Int, Int) -> me.proton.core.contact.data.api.response.GetContactEmailsResponse,
) {
    constructor(provider: ApiProvider) : this(
        { user, page, size -> provider.get<ContactApi>(user).invoke { getContacts(page, size) }.valueOrThrow },
        { user, page, size -> provider.get<ContactApi>(user).invoke { getContactEmails(page, size) }.valueOrThrow },
    )
    suspend fun read(user: UserId): List<Contact> {
        val contacts = linkedMapOf<ContactId, Contact>()
        val emails = hashMapOf<ContactId, MutableList<ContactEmail>>()
        val emailIds = hashSetOf<String>()
        var characters = 0L
        fun retain(strings: List<String>) {
            // Account for object/reference overhead too, including empty strings.
            characters += strings.sumOf { it.length.toLong() + 32 }
            if (characters > MAX_CHARACTERS) throw ProtonMalformedContactResponse()
        }
        var page = 0
        var contactsTotal: Int? = null
        do {
            val response = contactsPage(user, page, PAGE_SIZE)
            if (contactsTotal != null && contactsTotal != response.total) throw ProtonMalformedContactResponse()
            contactsTotal = response.total
            if (response.total !in 0..ContactInventoryPage.MAX_INVENTORY_TOTAL ||
                response.contacts.size > PAGE_SIZE || page > ContactInventoryPage.MAX_INVENTORY_TOTAL / PAGE_SIZE)
                throw ProtonMalformedContactResponse()
            if (response.contacts.size != minOf(PAGE_SIZE, response.total - contacts.size)) throw ProtonMalformedContactResponse()
            response.contacts.forEach {
                if (it.id.isBlank()) throw ProtonMalformedContactResponse()
                if (contacts.size >= ContactInventoryPage.MAX_INVENTORY_TOTAL) throw ProtonMalformedContactResponse()
                retain(listOf(it.id, it.name))
                val id = ContactId(it.id)
                if (contacts.put(id, Contact(user, id, it.name, emptyList())) != null) throw ProtonMalformedContactResponse()
            }
            page++
        } while (page <= response.total / PAGE_SIZE)
        page = 0
        var emailsTotal: Int? = null
        do {
            val response = emailsPage(user, page, PAGE_SIZE)
            if (emailsTotal != null && emailsTotal != response.total) throw ProtonMalformedContactResponse()
            emailsTotal = response.total
            if (response.total !in 0..MAX_EMAILS || response.contactEmails.size > PAGE_SIZE || page > MAX_EMAILS / PAGE_SIZE)
                throw ProtonMalformedContactResponse()
            if (response.contactEmails.size != minOf(PAGE_SIZE, response.total - emailIds.size)) throw ProtonMalformedContactResponse()
            response.contactEmails.forEach {
                if (it.id.isBlank() || ContactId(it.contactId) !in contacts || it.labelIds.size > 1000 ||
                    it.labelIds.any(String::isBlank) || it.labelIds.distinct().size != it.labelIds.size) throw ProtonMalformedContactResponse()
                if (emailIds.size >= MAX_EMAILS) throw ProtonMalformedContactResponse()
                retain(listOf(it.id, it.contactId, it.name, it.email, it.canonicalEmail.orEmpty()) + it.labelIds)
                if (!emailIds.add(it.id)) throw ProtonMalformedContactResponse()
                emails.getOrPut(ContactId(it.contactId)) { mutableListOf() }.add(it.toContactEmail(user))
            }
            page++
        } while (page <= response.total / PAGE_SIZE)
        return contacts.map { (id, contact) -> contact.copy(contactEmails = emails[id].orEmpty()) }
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val MAX_EMAILS = 300_000
        const val MAX_CHARACTERS = 16L * 1024 * 1024
    }
}
