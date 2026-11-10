package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactWithCards
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.ResponseCodes

/** Targeted reads only. A failed or missing resource is never an authoritative deletion proof. */
internal interface ProtonContactEventResourceGateway {
    suspend fun contact(account: AccountScope, id: RemoteContactId): GatewayOutcome<ContactInventoryMetadata>
    suspend fun emailOwner(account: AccountScope, id: RemoteEmailId): GatewayOutcome<RemoteContactId>
    suspend fun groups(account: AccountScope, ids: Set<RemoteGroupId>): GatewayOutcome<AvailableContactGroups>
}

/** Raw reads stay behind the authenticated, byte-bounded Proton Core transport. */
internal interface ProtonContactEventResourceTransport {
    suspend fun email(account: AccountScope, id: RemoteEmailId): String
    suspend fun groups(account: AccountScope, ids: Set<RemoteGroupId>): String
}

/**
 * Resolves event identities without an address-book crawl. Complete-card cryptographic verification
 * remains the separate verified-card gateway's responsibility; this reader returns public metadata.
 */
internal class ProtonContactEventResourceReader(
    private val expectedAccount: AccountScope,
    private val userProvider: ProtonReadyUserProvider,
    private val contactLoader: suspend (UserId, ContactId) -> ContactWithCards,
    private val transport: ProtonContactEventResourceTransport,
) : ProtonContactEventResourceGateway {
    override suspend fun contact(account: AccountScope, id: RemoteContactId): GatewayOutcome<ContactInventoryMetadata> =
        call {
            val user = requireUser(account)
            val contact = contactLoader(user, ContactId(id.value)).contact
            require(contact.userId == user && contact.id.id == id.value)
            require(contact.contactEmails.all { it.userId == user && it.contactId == contact.id })
            toPublicContactInventoryMetadata(contact)
        }

    override suspend fun emailOwner(account: AccountScope, id: RemoteEmailId): GatewayOutcome<RemoteContactId> =
        call {
            requireUser(account)
            val email = parseRoot(transport.email(account, id), MAX_EMAIL_RESPONSE_BYTES)["ContactEmail"]
                as? JsonObject ?: throw ProtonMalformedContactResponse()
            require(email.identifier("ID") == id.value)
            RemoteContactId(email.identifier("ContactID"))
        }

    override suspend fun groups(account: AccountScope, ids: Set<RemoteGroupId>): GatewayOutcome<AvailableContactGroups> =
        call {
            requireUser(account)
            require(ids.size <= MAX_GROUPS)
            val groups = ids.sortedBy { it.value }.chunked(GROUP_READ_BATCH_SIZE).flatMap { batch ->
                val requested = batch.toSet()
                val labels = parseRoot(transport.groups(account, requested), MAX_GROUP_RESPONSE_BYTES)["Labels"]
                    as? JsonObject ?: throw ProtonMalformedContactResponse()
                require(labels.size == requested.size)
                val parsed = labels.map { (key, element) ->
                    val label = element as? JsonObject ?: throw ProtonMalformedContactResponse()
                    val id = RemoteGroupId(label.identifier("ID"))
                    require(key == id.value && id in requested)
                    require(label.integer("Type") == CONTACT_GROUP_TYPE)
                    val name = label.string("Name")
                    val color = when (val value = label["Color"]) {
                        null, JsonNull -> null
                        is JsonPrimitive -> {
                            require(value.isString)
                            value.content
                        }
                        else -> throw ProtonMalformedContactResponse()
                    }
                    RemoteContactGroup(id, name, color)
                }
                require(parsed.map { it.id }.toSet() == requested)
                parsed
            }
            AvailableContactGroups(groups)
        }

    private suspend fun requireUser(account: AccountScope): UserId {
        if (account != expectedAccount) throw ProtonAuthenticationRequired()
        return userProvider.currentUserId() ?: throw ProtonAuthenticationRequired()
    }

    private suspend inline fun <T> call(crossinline block: suspend () -> T): GatewayOutcome<T> = try {
        GatewayOutcome.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
    } catch (error: Exception) {
        val failure = error.toContactGatewayFailureOutcome()
        val http = (error as? ApiException)?.error as? ApiResult.Error.Http
        // This is only an unresolved targeted resource. Canonical deletion still needs the
        // separate structured-presence gateway and reconciliation guards.
        if (http?.httpCode in setOf(400, 404, 422) && http?.proton?.code == ResponseCodes.NOT_EXISTS)
            failure.copy(category = GatewayFailureCategory.NOT_FOUND)
        else failure
    }

    private fun parseRoot(raw: String, maxBytes: Int): JsonObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes)
        val root = JSON.parseToJsonElement(raw) as? JsonObject ?: throw ProtonMalformedContactResponse()
        require(root.integer("Code") == 1_000)
        return root
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
        require(value.isString)
        return value.content
    }

    private fun JsonObject.identifier(key: String): String = string(key).also {
        require(it.isNotBlank() && it.length <= 4_096 && it.none(Char::isISOControl))
    }

    private fun JsonObject.integer(key: String): Int {
        val value = this[key] as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
        require(!value.isString)
        return value.intOrNull ?: throw ProtonMalformedContactResponse()
    }

    companion object {
        const val GROUP_READ_BATCH_SIZE = 100
        const val MAX_GROUPS = 10_000
        const val MAX_EMAIL_RESPONSE_BYTES = 1_024 * 1_024
        const val MAX_GROUP_RESPONSE_BYTES = 4 * 1_024 * 1_024
        private const val CONTACT_GROUP_TYPE = 2
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
