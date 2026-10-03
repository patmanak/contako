package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Opaque, account-scoped event position. Never a contact revision or a diagnostic value. */
internal fun validateContactEventCursor(value: String) {
    require(value.isNotBlank() && value.length <= 1024 && value.none { it.isISOControl() })
}

internal class ContactEventsDelta(
    val nextCursor: String,
    val changedContacts: Set<RemoteContactId>,
    val refreshAll: Boolean,
    val changedEmails: Set<RemoteEmailId> = emptySet(),
) {
    init { validateContactEventCursor(nextCursor) }
    override fun toString(): String = "ContactEventsDelta(REDACTED)"
}

internal fun interface ProtonContactEventsGateway {
    suspend fun read(account: AccountScope, cursor: String?): GatewayOutcome<ContactEventsDelta>
}

internal interface ProtonContactEventsTransport {
    suspend fun latest(account: AccountScope): String
    suspend fun events(account: AccountScope, cursor: String): String
}

/**
 * Contacts-only v6 feed used by Proton Web; no mail/account payload acquisition.
 * Wire reference: ProtonMail/WebClients, packages/shared/lib/api/events.ts.
 * Empty live responses omit collections, despite the TypeScript nullable declaration.
 */
internal class ProtonContactEventReader(private val transport: ProtonContactEventsTransport) : ProtonContactEventsGateway {
    override suspend fun read(account: AccountScope, cursor: String?): GatewayOutcome<ContactEventsDelta> = try {
        GatewayOutcome.Success(readDelta(account, cursor))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE)
    } catch (error: Exception) {
        error.toContactGatewayFailureOutcome()
    }

    private suspend fun readDelta(account: AccountScope, cursor: String?): ContactEventsDelta {
        if (cursor == null) {
            // Capture BEFORE inventory/full-card acquisition: later edits remain on the next pass.
            return ContactEventsDelta(parse(transport.latest(account)).cursor(), emptySet(), true)
        }
        validateContactEventCursor(cursor)
        var position: String = cursor
        val visited = mutableSetOf(position)
        val actions = mutableMapOf<RemoteContactId, Int>()
        val changedEmails = mutableSetOf<RemoteEmailId>()
        var refresh = false
        repeat(MAX_PAGES) {
            val page = parse(transport.events(account, position))
            val next = page.cursor()
            val more = page.flag("More")
            val pageRefresh = page.flag("Refresh")
            refresh = refresh || pageRefresh
            for (item in page.items("Contacts")) {
                val id = RemoteContactId(item.identifier())
                val action = item.action()
                actions[id] = action
                require(actions.size <= 100_000)
            }
            // Resolve these through the current/previous directory, never through email text.
            page.items("ContactEmails").forEach {
                it.action()
                changedEmails += RemoteEmailId(it.identifier())
                require(changedEmails.size <= 100_000)
            }
            if (!more) return ContactEventsDelta(next, actions.filterValues { it != 0 }.keys, refresh, changedEmails)
            require(next != position && visited.add(next))
            position = next
        }
        throw IllegalArgumentException()
    }

    private fun parse(raw: String): JsonObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSON.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException()
        require((root["Code"] as? JsonPrimitive)?.intOrNull == 1000)
        return root
    }

    private fun JsonObject.cursor(): String = identifier("EventID").also(::validateContactEventCursor)
    private fun JsonObject.identifier(key: String = "ID"): String {
        val value = this[key] as? JsonPrimitive ?: throw IllegalArgumentException()
        require(value.isString)
        return value.content.also(::validateContactEventCursor)
    }
    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw IllegalArgumentException()
    private fun JsonObject.action(): Int =
        ((this["Action"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException())
            .also { require(it in 0..2) }
    private fun JsonObject.items(key: String): List<JsonObject> = when (val value = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> {
            require(value.size <= 10_000)
            value.map { it as? JsonObject ?: throw IllegalArgumentException() }
        }
        else -> throw IllegalArgumentException()
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_PAGES = 100
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
