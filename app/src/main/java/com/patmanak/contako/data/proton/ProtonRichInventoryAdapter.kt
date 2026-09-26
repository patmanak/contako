package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Frozen, replaceable transport for the D-032 rich `/contacts/v4` inventory.
 *
 * The route is represented by maintained public go-proton-api source, but is not treated as a
 * documented third-party contract. The production transport remains isolated and replaceable, and
 * its presence in the runtime grants no live-test authorization.
 */
internal fun interface ProtonRichInventoryWireTransport {
    suspend fun getPage(
        account: AccountScope,
        pageIndex: Int,
        pageSize: Int,
    ): String
}

/** Parses and validates the complete metadata needed to avoid an ordinary N+1 hydration pass. */
internal class ProtonRichInventoryAdapter(
    private val expectedAccount: AccountScope,
    private val transport: ProtonRichInventoryWireTransport,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val unitStatus: ProtonRichInventoryUnitStatus =
        ProtonRichInventoryUnitStatus.LIVE_VALIDATION_REQUIRED,
) : ProtonContactInventoryGateway {
    init {
        require(pageSize in 1..ContactInventoryPage.MAX_INVENTORY_PAGE_SIZE)
    }

    override suspend fun page(
        account: AccountScope,
        cursor: InventoryCursor?,
    ): GatewayOutcome<ContactInventoryPage> = inventoryGatewayCall {
        if (account != expectedAccount) throw ProtonAuthenticationRequired()
        val pageIndex = cursor?.decodePageIndex() ?: 0
        val raw = transport.getPage(account, pageIndex, pageSize)
        try {
            require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES)
            val response = parseResponse(raw)
            require(response.total in 0..ContactInventoryPage.MAX_INVENTORY_TOTAL)
            require(response.contacts.size <= pageSize)

            val consumedBefore = Math.multiplyExact(pageIndex, pageSize)
            require(consumedBefore <= response.total)
            require(response.contacts.size <= response.total - consumedBefore)
            val consumed = consumedBefore + response.contacts.size
            if (consumed < response.total) require(response.contacts.isNotEmpty())
            val next = if (consumed < response.total) InventoryCursor("rich-page-${pageIndex + 1}") else null

            ContactInventoryPage(
                contacts = response.contacts,
                requestedCursor = cursor,
                nextCursor = next,
                totalCount = response.total,
                snapshotAuthority = if (
                    unitStatus == ProtonRichInventoryUnitStatus.LIVE_CONTRACT_ATTESTED
                ) {
                    ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION
                } else {
                    ContactInventorySnapshotAuthority.UNATTESTED
                },
            )
        } catch (malformed: ProtonMalformedContactResponse) {
            throw malformed
        } catch (_: IllegalArgumentException) {
            throw ProtonMalformedContactResponse()
        } catch (_: ArithmeticException) {
            throw ProtonMalformedContactResponse()
        }
    }

    private fun InventoryCursor.decodePageIndex(): Int {
        val match = CURSOR.matchEntire(value) ?: throw ProtonInvalidInventoryCursor()
        val page = match.groupValues[1].toIntOrNull() ?: throw ProtonInvalidInventoryCursor()
        if (page <= 0 || page > MAX_PAGE_INDEX) throw ProtonInvalidInventoryCursor()
        return page
    }

    private fun parseResponse(raw: String): ParsedInventoryResponse {
        val root = try {
            WIRE_JSON.parseToJsonElement(raw).jsonObject
        } catch (_: IllegalArgumentException) {
            throw ProtonMalformedContactResponse()
        }
        requireWireCode(root)
        val total = root.requiredInt("Total")
        val contacts = root.requiredArray("Contacts").map(::parseContact)
        if (contacts.map(ContactInventoryMetadata::id).distinct().size != contacts.size) {
            throw ProtonMalformedContactResponse()
        }
        return ParsedInventoryResponse(total, contacts)
    }

    private fun parseContact(element: JsonElement): ContactInventoryMetadata {
        val item = element.objectOrMalformed()
        val id = RemoteContactId(item.requiredString("ID"))
        // Source-model integers are copied without conversion. Their seconds/bytes interpretation
        // remains explicitly gated by ProtonRichInventoryUnitStatus until live contract evidence.
        val rawSize = item.requiredLong("Size")
        val rawModifyTime = item.requiredLong("ModifyTime")
        val uid = item.requiredString("UID")
        require(rawSize in 0..ContactInventoryMetadata.MAX_CONTACT_SIZE_BYTES)
        require(rawModifyTime in 0..ContactInventoryMetadata.MAX_EPOCH_SECONDS)

        val emails = item.requiredArray("ContactEmails").map(::parseEmail)
        if (emails.map(WireEmail::id).distinct().size != emails.size) throw ProtonMalformedContactResponse()
        val contactGroups = item.optionalStringArray("LabelIDs").map(::RemoteGroupId)
        val membershipGroups = emails.flatMap(WireEmail::groupIds)
        val allGroups = (contactGroups + membershipGroups).distinct()
        val version = remoteVersion(uid, rawModifyTime, rawSize)
        val contractAttested = unitStatus == ProtonRichInventoryUnitStatus.LIVE_CONTRACT_ATTESTED

        return ContactInventoryMetadata(
            id = id,
            displayName = item.optionalString("Name"),
            version = version,
            sizeBytes = rawSize,
            modifiedAtEpochSeconds = rawModifyTime,
            emailIds = emails.map(WireEmail::id),
            groupIds = allGroups,
            versionProvenance = if (contractAttested) {
                ContactInventoryVersionProvenance.REMOTE_SERVER
            } else {
                ContactInventoryVersionProvenance.REMOTE_SERVER_UNATTESTED
            },
            coverage = if (contractAttested) {
                ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION
            } else {
                ContactInventoryCoverage.REMOTE_REVISION_UNATTESTED
            },
            emailGroupMemberships = emails.map { email ->
                RemoteEmailGroupMembership(email.id, email.groupIds)
            },
        )
    }

    private fun parseEmail(element: JsonElement): WireEmail {
        val item = element.objectOrMalformed()
        return WireEmail(
            id = RemoteEmailId(item.requiredString("ID")),
            groupIds = item.optionalStringArray("LabelIDs").map(::RemoteGroupId),
        )
    }

    private fun remoteVersion(uid: String, rawModifyTime: Long, rawSize: Long): RemoteVersion {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$uid\u0000$rawModifyTime\u0000$rawSize".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return RemoteVersion("server-$digest")
    }

    private data class ParsedInventoryResponse(
        val total: Int,
        val contacts: List<ContactInventoryMetadata>,
    )

    private data class WireEmail(
        val id: RemoteEmailId,
        val groupIds: List<RemoteGroupId>,
    )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 300
        const val MAX_RESPONSE_BYTES = 16 * 1_024 * 1_024
        const val MAX_PAGE_INDEX = 100_000
        val CURSOR = Regex("^rich-page-([1-9][0-9]{0,5})$")
        val WIRE_JSON = Json { isLenient = false; ignoreUnknownKeys = true }
    }
}

private fun requireWireCode(root: JsonObject) {
    if (root.requiredInt("Code") != 1_000) throw ProtonMalformedContactResponse()
}

private fun JsonElement.objectOrMalformed(): JsonObject = this as? JsonObject
    ?: throw ProtonMalformedContactResponse()

private fun JsonObject.requiredArray(name: String): JsonArray = this[name] as? JsonArray
    ?: throw ProtonMalformedContactResponse()

private fun JsonObject.requiredString(name: String): String {
    val primitive = this[name] as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
    if (!primitive.isString) throw ProtonMalformedContactResponse()
    return primitive.content.takeIf(String::isNotBlank) ?: throw ProtonMalformedContactResponse()
}

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
    if (!primitive.isString) throw ProtonMalformedContactResponse()
    return primitive.content
}

private fun JsonObject.requiredLong(name: String): Long {
    val primitive = this[name] as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
    if (primitive.isString) throw ProtonMalformedContactResponse()
    return primitive.longOrNull ?: throw ProtonMalformedContactResponse()
}

private fun JsonObject.requiredInt(name: String): Int {
    val primitive = this[name] as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
    if (primitive.isString) throw ProtonMalformedContactResponse()
    return primitive.intOrNull ?: throw ProtonMalformedContactResponse()
}

private fun JsonObject.optionalStringArray(name: String): List<String> {
    val value = this[name] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    val array = value as? JsonArray ?: throw ProtonMalformedContactResponse()
    val strings = array.map { item ->
        val primitive = item as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
        if (!primitive.isString || primitive.content.isBlank()) throw ProtonMalformedContactResponse()
        primitive.content
    }
    if (strings.distinct().size != strings.size) throw ProtonMalformedContactResponse()
    return strings
}

private suspend inline fun <T> inventoryGatewayCall(crossinline block: suspend () -> T): GatewayOutcome<T> =
    try {
        GatewayOutcome.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        GatewayOutcome.Failure(
            when (error) {
                is ProtonMalformedContactResponse, is ProtonInconsistentInventorySnapshot ->
                    com.patmanak.contako.data.gateway.GatewayFailureCategory.MALFORMED_RESPONSE
                else -> error.toContactGatewayFailure()
            },
        )
    }
