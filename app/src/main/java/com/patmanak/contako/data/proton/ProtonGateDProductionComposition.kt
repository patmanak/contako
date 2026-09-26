package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayMalformedResponseCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonContactMutationGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.proton.core.contact.data.api.request.CreateContactsRequest
import me.proton.core.contact.data.api.resource.ContactCardsResource
import me.proton.core.contact.data.api.resource.toContactCardResource
import me.proton.core.contact.domain.entity.ContactCard
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Runtime surfaces for Gate D. No Room/checkpoint implementation is owned by this composition.
 * Network operations remain dormant until a caller explicitly invokes one of these gateways.
 */
internal data class ProtonGateDComposition(
    val inventory: ProtonContactInventoryGateway,
    val verifiedCards: ProtonVerifiedContactCardGateway,
    val contactMutations: ProtonContactMutationGateway,
    val contactCreateStages: ProtonContactCreateStageMonitor,
    val emailGroupAssignmentStages: ProtonEmailGroupAssignmentStageMonitor,
    val groups: ProtonContactGroupGateway,
    val emailLabels: ProtonContactEmailLabelGateway,
    val membershipReader: ProtonEmailGroupMembershipReader,
    val vCardCodec: ProtonContactVCardCodec,
    val richInventoryUnitStatus: ProtonRichInventoryUnitStatus =
        ProtonRichInventoryUnitStatus.LIVE_VALIDATION_REQUIRED,
)

/** Source models expose integer fields but do not document their units as a third-party contract. */
internal enum class ProtonRichInventoryUnitStatus {
    LIVE_VALIDATION_REQUIRED,
    LIVE_CONTRACT_ATTESTED,
}

/** Public Proton Core routes plus isolated, replaceable legacy-evidence routes. */
internal interface ProtonGateDWireApi : BaseRetrofitApi {
    /** Maintained Proton Core Android ContactApi create route. */
    @POST("contacts/v4/contacts")
    @Streaming
    suspend fun createContacts(@Body body: RequestBody): ResponseBody

    /** Maintained go-proton-api route; not a documented third-party contract. */
    @GET("contacts/v4")
    @Streaming
    suspend fun getRichContacts(
        @Query("Page") page: Int,
        @Query("PageSize") pageSize: Int,
    ): ResponseBody

    /** Maintained Proton Core Android 36.6.2 ContactApi route. */
    @GET("contacts/v4/contacts/emails")
    @Streaming
    suspend fun getContactEmails(
        @Query("Page") page: Int,
        @Query("PageSize") pageSize: Int,
    ): ResponseBody

    /** Legacy prototype evidence only; public Proton Core exposes no equivalent mutation surface. */
    @PUT("contacts/v4/contacts/emails/label")
    @Streaming
    suspend fun labelContactEmails(@Body body: RequestBody): ResponseBody

    /** Legacy prototype evidence only; public Proton Core exposes no equivalent mutation surface. */
    @PUT("contacts/v4/contacts/emails/unlabel")
    @Streaming
    suspend fun unlabelContactEmails(@Body body: RequestBody): ResponseBody
}

internal fun interface ProtonContactEmailPageWireTransport {
    suspend fun getPage(account: AccountScope, pageIndex: Int, pageSize: Int): String
}

internal fun interface ProtonRawContactCreateTransport {
    suspend fun create(userId: UserId, cards: List<ContactCard>): String
}

/**
 * Authenticated raw transport on the sole Proton Core ApiProvider/session graph. Response bodies
 * are streamed through hard byte caps before UTF-8 decoding; payloads are never logged.
 */
internal class ProtonCoreGateDWireClient(
    private val expectedAccount: AccountScope,
    private val userProvider: ProtonReadyUserProvider,
    private val apiProvider: ApiProvider,
) : ProtonRichInventoryWireTransport,
    ProtonEmailGroupAssignmentWireTransport,
    ProtonRawContactCreateTransport {

    override suspend fun create(
        userId: UserId,
        cards: List<ContactCard>,
    ): String {
        if (userProvider.currentUserId() != userId) throw ProtonAuthenticationRequired()
        val request = serializeRawCreateRequest(cards)
        require(request.toByteArray(Charsets.UTF_8).size <= MAX_CREATE_REQUEST_BYTES)
        val body = request.toRequestBody(JSON_MEDIA_TYPE)
        val api = apiProvider.get<ProtonGateDWireApi>(userId)
        val response = api.invoke { createContacts(body) }.valueOrThrow
        return response.use { it.readBoundedUtf8(MAX_CREATE_RESPONSE_BYTES) }
    }

    override suspend fun getPage(account: AccountScope, pageIndex: Int, pageSize: Int): String =
        invokeBoundedPage(account, pageIndex, pageSize) {
            getRichContacts(pageIndex, pageSize)
        }

    override suspend fun put(
        account: AccountScope,
        operation: EmailGroupAssignmentWireOperation,
        utf8JsonBody: String,
    ): String {
        require(utf8JsonBody.toByteArray(Charsets.UTF_8).size <= MAX_MUTATION_REQUEST_BYTES)
        val body = utf8JsonBody.toRequestBody(JSON_MEDIA_TYPE)
        return invokeBounded(account, MAX_MUTATION_RESPONSE_BYTES) {
            when (operation) {
                EmailGroupAssignmentWireOperation.ASSIGN -> labelContactEmails(body)
                EmailGroupAssignmentWireOperation.REMOVE -> unlabelContactEmails(body)
            }
        }
    }

    suspend fun getContactEmailPage(
        account: AccountScope,
        pageIndex: Int,
        pageSize: Int,
    ): String = invokeBoundedPage(account, pageIndex, pageSize) {
        getContactEmails(pageIndex, pageSize)
    }

    private suspend fun invokeBoundedPage(
        account: AccountScope,
        pageIndex: Int,
        pageSize: Int,
        call: suspend ProtonGateDWireApi.() -> ResponseBody,
    ): String {
        require(pageIndex in 0..MAX_PAGE_INDEX)
        require(pageSize in 1..ContactInventoryPage.MAX_INVENTORY_PAGE_SIZE)
        return invokeBounded(account, MAX_CONTACT_RESPONSE_BYTES, call)
    }

    private suspend fun invokeBounded(
        account: AccountScope,
        maxResponseBytes: Int,
        call: suspend ProtonGateDWireApi.() -> ResponseBody,
    ): String {
        if (account != expectedAccount) throw ProtonAuthenticationRequired()
        val userId = userProvider.currentUserId() ?: throw ProtonAuthenticationRequired()
        val api = apiProvider.get<ProtonGateDWireApi>(userId)
        val response = api.invoke { call() }.valueOrThrow
        return response.use { it.readBoundedUtf8(maxResponseBytes) }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_CONTACT_RESPONSE_BYTES = GateDRawResponseLimits.CONTACT_BYTES
        const val MAX_MUTATION_REQUEST_BYTES = 2 * 1_024 * 1_024
        const val MAX_MUTATION_RESPONSE_BYTES = GateDRawResponseLimits.MUTATION_BYTES
        const val MAX_CREATE_REQUEST_BYTES = 12 * 1_024 * 1_024
        const val MAX_CREATE_RESPONSE_BYTES = GateDRawResponseLimits.CREATE_BYTES
    }
}

internal fun serializeRawCreateRequest(
    cards: List<ContactCard>,
): String {
    require(cards.isNotEmpty() && cards.size <= 16)
    val cardBytes = cards.sumOf { card ->
        when (card) {
            is ContactCard.ClearText -> card.data.toByteArray(Charsets.UTF_8).size.toLong()
            is ContactCard.Signed -> card.data.toByteArray(Charsets.UTF_8).size.toLong() +
                card.signature.toByteArray(Charsets.UTF_8).size
            is ContactCard.Encrypted -> card.data.toByteArray(Charsets.UTF_8).size.toLong() +
                (card.signature?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        }
    }
    require(cardBytes <= MAX_RAW_CREATE_CARD_BYTES)
    return WIRE_JSON.encodeToString(
        CreateContactsRequest.create(
            contacts = listOf(ContactCardsResource(cards.map(ContactCard::toContactCardResource))),
            overwrite = false,
            labels = 0,
        ),
    )
}

internal fun parseRawCreateContactId(raw: String): String {
    if (raw.toByteArray(Charsets.UTF_8).size > MAX_RAW_CREATE_JSON_BYTES) {
        throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_RESPONSE_SIZE)
    }
    val root = try {
        WIRE_JSON.parseToJsonElement(raw) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_ROOT_OBJECT)
    val responses = root["Responses"] as? JsonArray
        ?: throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_RESPONSES_TYPE)
    if (responses.size != 1) {
        throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_RESPONSES_COUNT)
    }
    val item = responses.single() as? JsonObject
        ?: throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_RESPONSE_ITEM)
    if (item.closedRawCreateInt("Index", GatewayMalformedResponseCategory.RAW_CREATE_INDEX) != 0) {
        throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_INDEX)
    }
    val response = item["Response"] as? JsonObject
        ?: throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_NESTED_RESPONSE)
    val nestedCode = response.closedRawCreateInt("Code", GatewayMalformedResponseCategory.RAW_CREATE_NESTED_CODE)
    if (nestedCode != 1_000) {
        throw ProtonRawCreateResponseException(
            GatewayMalformedResponseCategory.RAW_CREATE_NESTED_CODE,
            nestedCode.takeIf { it in SAFE_PROTON_RESPONSE_CODE_RANGE },
        )
    }
    val contact = response["Contact"] as? JsonObject
        ?: throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_CONTACT_OBJECT)
    val id = contact.closedRawCreateString("ID", GatewayMalformedResponseCategory.RAW_CREATE_CONTACT_ID)
    if (id.toByteArray(Charsets.UTF_8).size > MAX_RAW_CONTACT_ID_BYTES) {
        throw ProtonRawCreateResponseException(GatewayMalformedResponseCategory.RAW_CREATE_CONTACT_ID)
    }
    return id
}

private fun JsonObject.closedRawCreateInt(
    name: String,
    category: GatewayMalformedResponseCategory,
): Int {
    val value = this[name] as? JsonPrimitive ?: throw ProtonRawCreateResponseException(category)
    if (value.isString) throw ProtonRawCreateResponseException(category)
    return value.intOrNull ?: throw ProtonRawCreateResponseException(category)
}

private fun JsonObject.closedRawCreateString(
    name: String,
    category: GatewayMalformedResponseCategory,
): String {
    val value = this[name] as? JsonPrimitive ?: throw ProtonRawCreateResponseException(category)
    if (!value.isString || value.content.isBlank()) throw ProtonRawCreateResponseException(category)
    return value.content
}

internal class ProtonRawCreateResponseException(
    val category: GatewayMalformedResponseCategory,
    val protonResponseCode: Int? = null,
) : ProtonMalformedContactResponse()

internal fun ResponseBody.readBoundedUtf8(maxBytes: Int): String {
    require(maxBytes > 0)
    val declared = contentLength()
    if (declared > maxBytes) throw ProtonMalformedContactResponse()
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1_024))
    val buffer = ByteArray(8 * 1_024)
    var total = 0
    try {
        byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total = Math.addExact(total, read)
                if (total > maxBytes) throw ProtonMalformedContactResponse()
                output.write(buffer, 0, read)
            }
        }
    } finally {
        buffer.fill(0)
    }
    val bytes = output.toByteArray()
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw ProtonMalformedContactResponse()
    } finally {
        bytes.fill(0)
    }
}

/**
 * Since the rich route exposes no snapshot token, a page set is published only after two complete,
 * byte-bounded canonical reads agree. Continuations then serve the verified in-memory second pass.
 */
internal class SnapshotVerifyingRichInventoryWireTransport(
    private val maxCompleteSnapshotBytes: Long = MAX_COMPLETE_SNAPSHOT_BYTES,
    private val upstream: ProtonRichInventoryWireTransport,
) : ProtonRichInventoryWireTransport {
    private val mutex = Mutex()
    private var active: VerifiedWirePageSet? = null

    override suspend fun getPage(
        account: AccountScope,
        pageIndex: Int,
        pageSize: Int,
    ): String = mutex.withLock {
        require(pageSize in 1..ContactInventoryPage.MAX_INVENTORY_PAGE_SIZE)
        require(maxCompleteSnapshotBytes > 0)
        if (pageIndex == 0) {
            active = null
            val first = readCompleteSnapshot(
                account,
                pageSize,
                "Contacts",
                retainPages = false,
                maxCompleteSnapshotBytes = maxCompleteSnapshotBytes,
                fetch = upstream::getPage,
            )
            val second = readCompleteSnapshot(
                account,
                pageSize,
                "Contacts",
                retainPages = true,
                maxCompleteSnapshotBytes = maxCompleteSnapshotBytes,
                fetch = upstream::getPage,
            )
            if (first.signature != second.signature) throw ProtonInconsistentInventorySnapshot()
            active = VerifiedWirePageSet(account, pageSize, second.pages, nextPage = 0)
        }
        val snapshot = active ?: throw ProtonInvalidInventoryCursor()
        if (
            account != snapshot.account ||
            pageSize != snapshot.pageSize ||
            pageIndex != snapshot.nextPage ||
            pageIndex !in snapshot.pages.indices
        ) {
            throw ProtonInvalidInventoryCursor()
        }
        snapshot.pages[pageIndex].also {
            snapshot.nextPage++
            if (snapshot.nextPage == snapshot.pages.size) active = null
        }
    }

}

internal class VerifiedWirePageSet(
    val account: AccountScope,
    val pageSize: Int,
    pages: List<String>,
    var nextPage: Int,
) {
    val pages: List<String> = pages.toList()

    override fun toString(): String =
        "VerifiedWirePageSet(pageCount=${pages.size}, nextPage=$nextPage, REDACTED)"
}

/** Complete, double-read email membership snapshot using Proton Core's maintained email index. */
internal class ProtonCoreAuthoritativeEmailGroupMembershipReader(
    private val expectedAccount: AccountScope,
    private val transport: ProtonContactEmailPageWireTransport,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxCompleteSnapshotBytes: Long = MAX_COMPLETE_SNAPSHOT_BYTES,
) : ProtonEmailGroupMembershipReader {
    init {
        require(pageSize in 1..ContactInventoryPage.MAX_INVENTORY_PAGE_SIZE)
        require(maxCompleteSnapshotBytes > 0)
    }

    override suspend fun members(
        account: AccountScope,
        groupId: RemoteGroupId,
    ): GatewayOutcome<AuthoritativeEmailGroupMembership> = membershipGatewayCall {
        if (account != expectedAccount) throw ProtonAuthenticationRequired()
        val first = readCompleteSnapshot(
            account,
            pageSize,
            "ContactEmails",
            retainPages = false,
            maxCompleteSnapshotBytes = maxCompleteSnapshotBytes,
            fetch = transport::getPage,
        )
        val second = readCompleteSnapshot(
            account,
            pageSize,
            "ContactEmails",
            retainPages = true,
            maxCompleteSnapshotBytes = maxCompleteSnapshotBytes,
            fetch = transport::getPage,
        )
        if (first.signature != second.signature) throw ProtonInconsistentInventorySnapshot()
        val members = second.pages.flatMap { raw ->
            val root = parseWireRoot(raw)
            root.requiredWireArray("ContactEmails").map { element ->
                val email = element as? JsonObject ?: throw ProtonMalformedContactResponse()
                val id = email.requiredWireString("ID")
                val labelIds = email.optionalWireStringArray("LabelIDs")
                id to labelIds
            }
        }
        if (members.map(Pair<String, List<String>>::first).distinct().size != members.size) {
            throw ProtonMalformedContactResponse()
        }
        AuthoritativeEmailGroupMembership(
            account = account,
            groupId = groupId,
            emailIds = members.asSequence()
                .filter { (_, labels) -> groupId.value in labels }
                .map { (id, _) -> RemoteEmailId(id) }
                .toList(),
        )
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 1_000
    }
}

internal class CompleteWireSnapshot(
    pages: List<String>,
    val signature: String,
) {
    val pages: List<String> = pages.toList()

    override fun toString(): String = "CompleteWireSnapshot(pageCount=${pages.size}, REDACTED)"
}

private suspend fun readCompleteSnapshot(
    account: AccountScope,
    pageSize: Int,
    collectionName: String,
    retainPages: Boolean,
    maxCompleteSnapshotBytes: Long,
    fetch: suspend (AccountScope, Int, Int) -> String,
): CompleteWireSnapshot {
    val pages = mutableListOf<String>()
    val signature = MessageDigest.getInstance("SHA-256")
    val seenIds = mutableSetOf<String>()
    var expectedTotal: Int? = null
    var consumed = 0
    var pageIndex = 0
    var cumulativeBytes = 0L
    do {
        if (pageIndex > MAX_PAGE_INDEX) throw ProtonMalformedContactResponse()
        val raw = fetch(account, pageIndex, pageSize)
        val rawBytes = raw.toByteArray(Charsets.UTF_8)
        try {
            if (rawBytes.size > MAX_PAGE_RESPONSE_BYTES) throw ProtonMalformedContactResponse()
            cumulativeBytes = Math.addExact(cumulativeBytes, rawBytes.size.toLong())
            if (cumulativeBytes > maxCompleteSnapshotBytes) throw ProtonMalformedContactResponse()
        } finally {
            rawBytes.fill(0)
        }
        val root = parseWireRoot(raw)
        val total = root.requiredWireInt("Total")
        if (total !in 0..MAX_WIRE_TOTAL) throw ProtonMalformedContactResponse()
        if (expectedTotal == null) expectedTotal = total else if (expectedTotal != total) {
            throw ProtonInconsistentInventorySnapshot()
        }
        val items = root.requiredWireArray(collectionName)
        if (items.size > pageSize || items.size > total - consumed) throw ProtonMalformedContactResponse()
        if (consumed < total && items.isEmpty()) throw ProtonMalformedContactResponse()
        items.forEach { element ->
            val item = element as? JsonObject ?: throw ProtonMalformedContactResponse()
            if (!seenIds.add(item.requiredWireString("ID"))) throw ProtonMalformedContactResponse()
        }
        consumed += items.size
        if (retainPages) pages += raw
        val canonicalBytes = canonicalInventoryPage(root, collectionName).toByteArray(Charsets.UTF_8)
        try {
            updateLengthFramed(signature, canonicalBytes)
        } finally {
            canonicalBytes.fill(0)
        }
        pageIndex++
    } while (consumed < requireNotNull(expectedTotal))
    if (consumed != expectedTotal) throw ProtonMalformedContactResponse()
    val digest = signature.digest()
    return try {
        CompleteWireSnapshot(
            pages = pages.toList(),
            signature = Base64.getEncoder().encodeToString(digest),
        )
    } finally {
        digest.fill(0)
    }
}

private fun updateLengthFramed(digest: MessageDigest, value: ByteArray) {
    digest.update((value.size ushr 24).toByte())
    digest.update((value.size ushr 16).toByte())
    digest.update((value.size ushr 8).toByte())
    digest.update(value.size.toByte())
    digest.update(value)
}

private fun parseWireRoot(raw: String): JsonObject {
    val root = try {
        WIRE_JSON.parseToJsonElement(raw) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw ProtonMalformedContactResponse()
    if (root.requiredWireInt("Code") != 1_000) throw ProtonMalformedContactResponse()
    return root
}

private fun JsonObject.requiredWireArray(name: String): JsonArray =
    this[name] as? JsonArray ?: throw ProtonMalformedContactResponse()

private fun JsonObject.requiredWireString(name: String): String {
    val value = this[name] as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
    if (!value.isString || value.content.isBlank()) throw ProtonMalformedContactResponse()
    return value.content
}

private fun JsonObject.requiredWireInt(name: String): Int {
    val value = this[name] as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
    if (value.isString) throw ProtonMalformedContactResponse()
    return value.intOrNull ?: throw ProtonMalformedContactResponse()
}

private fun JsonObject.optionalWireStringArray(name: String): List<String> {
    val value = this[name] ?: return emptyList()
    val array = value as? JsonArray ?: throw ProtonMalformedContactResponse()
    val strings = array.map { element ->
        val primitive = element as? JsonPrimitive ?: throw ProtonMalformedContactResponse()
        if (!primitive.isString || primitive.content.isBlank()) throw ProtonMalformedContactResponse()
        primitive.content
    }
    if (strings.distinct().size != strings.size) throw ProtonMalformedContactResponse()
    return strings
}

private fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            JsonPrimitive(key).toString() + ":" + canonicalJson(value)
        }
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
    else -> element.toString()
}

/** Ignores volatile response metadata and wire ordering while retaining all inventory fields. */
private fun canonicalInventoryPage(root: JsonObject, collectionName: String): String {
    val items = root.requiredWireArray(collectionName)
        .map { it as? JsonObject ?: throw ProtonMalformedContactResponse() }
        .sortedBy { it.requiredWireString("ID") }
    return canonicalJson(
        JsonObject(
            mapOf(
                "Total" to JsonPrimitive(root.requiredWireInt("Total")),
                collectionName to JsonArray(items),
            ),
        ),
    )
}

private suspend inline fun <T> membershipGatewayCall(
    crossinline block: suspend () -> T,
): GatewayOutcome<T> = try {
    GatewayOutcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    GatewayOutcome.Failure(
        category = when (error) {
            is ProtonMalformedContactResponse, is ProtonInconsistentInventorySnapshot ->
                GatewayFailureCategory.MALFORMED_RESPONSE
            else -> error.toContactGatewayFailure()
        },
        retryAfterMillis = error.toContactGatewayFailureOutcome().retryAfterMillis,
    )
}

internal class ProtonInconsistentInventorySnapshot : IllegalStateException()

private const val MAX_PAGE_RESPONSE_BYTES = 16 * 1_024 * 1_024
private const val MAX_COMPLETE_SNAPSHOT_BYTES = 64L * 1_024 * 1_024
private const val MAX_WIRE_TOTAL = 100_000
private const val MAX_PAGE_INDEX = 100_000
private const val MAX_RAW_CREATE_JSON_BYTES = 64 * 1_024
private const val MAX_RAW_CONTACT_ID_BYTES = 1_024
private const val MAX_RAW_CREATE_CARD_BYTES = 10L * 1_024 * 1_024
private val SAFE_PROTON_RESPONSE_CODE_RANGE = 1_000..9_999
private val WIRE_JSON = Json { isLenient = false; ignoreUnknownKeys = true }
