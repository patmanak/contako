package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.AvailableContactGroups
import com.patmanak.contako.data.gateway.ContactGroupCapabilities
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.ContactMutation
import com.patmanak.contako.data.gateway.ContactMutationReceipt
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonContactMutationGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.gateway.RemoteContactGroup
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.VerifiedContactCard
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroupDefaults
import com.patmanak.contako.domain.policy.ContactValidation
import ezvcard.property.Categories
import me.proton.core.contact.domain.entity.ContactCardType
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCard
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactWithCards
import me.proton.core.contact.domain.repository.ContactRemoteDataSource
import me.proton.core.crypto.common.pgp.exception.CryptoException
import me.proton.core.domain.entity.UserId
import me.proton.core.label.domain.entity.Label
import me.proton.core.label.domain.entity.LabelId
import me.proton.core.label.domain.entity.LabelType
import me.proton.core.label.domain.entity.NewLabel
import me.proton.core.label.domain.entity.UpdateLabel
import me.proton.core.label.domain.repository.LabelRemoteDataSource
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult

/** Account identity supplied by the already established Gate C session graph. */
internal fun interface ProtonReadyUserProvider {
    suspend fun currentUserId(): UserId?
}

/** Narrow public Proton Core contact surface; deterministic fakes implement the same boundary. */
internal interface ProtonContactRemotePort {
    suspend fun inventory(userId: UserId): List<Contact>
    suspend fun hydrate(userId: UserId, contactId: ContactId): ContactWithCards
    suspend fun create(userId: UserId, cards: List<ContactCard>): Contact
    suspend fun update(userId: UserId, contactId: ContactId, cards: List<ContactCard>): Contact
    suspend fun delete(userId: UserId, contactId: ContactId)
}

internal enum class ProtonContactCreateStage {
    PROTECT,
    RAW_HTTP,
    RAW_PARSE,
    HYDRATE_VERIFY,
}

/** Closed product identity only; no contact, response, or throwable value crosses this boundary. */
internal fun interface ProtonContactCreateStageObserver {
    fun onStage(stage: ProtonContactCreateStage)
}

internal class ProtonContactCreateStageMonitor : ProtonContactCreateStageObserver {
    @Volatile
    private var observer: ProtonContactCreateStageObserver = ProtonContactCreateStageObserver { }

    override fun onStage(stage: ProtonContactCreateStage) = observer.onStage(stage)

    fun observe(observer: ProtonContactCreateStageObserver) {
        this.observer = observer
    }
}

internal class ProtonCoreContactRemotePort(
    private val remote: ContactRemoteDataSource,
    private val rawCreate: ProtonRawContactCreateTransport,
    private val createStageObserver: ProtonContactCreateStageObserver = ProtonContactCreateStageObserver { },
) : ProtonContactRemotePort {
    override suspend fun inventory(userId: UserId): List<Contact> = remote.getAllContacts(userId)

    override suspend fun hydrate(userId: UserId, contactId: ContactId): ContactWithCards =
        hydrateMapped(remote, userId, contactId)

    override suspend fun create(userId: UserId, cards: List<ContactCard>): Contact {
        createStageObserver.onStage(ProtonContactCreateStage.RAW_HTTP)
        val raw = rawCreate.create(userId, cards)
        createStageObserver.onStage(ProtonContactCreateStage.RAW_PARSE)
        val createdId = ContactId(parseRawCreateContactId(raw))
        createStageObserver.onStage(ProtonContactCreateStage.HYDRATE_VERIFY)
        val hydrated = hydrateMapped(remote, userId, createdId)
        if (hydrated.contact.id != createdId) throw ProtonHydrationMalformedResponse(
            GatewayContactHydrationCategory.UID_ID_CONSISTENCY,
        )
        return hydrated.contact
    }

    override suspend fun update(userId: UserId, contactId: ContactId, cards: List<ContactCard>): Contact =
        remote.updateContact(userId, contactId, cards)

    override suspend fun delete(userId: UserId, contactId: ContactId) {
        remote.deleteContacts(userId, listOf(contactId))
    }
}

/**
 * Public-Core-only contact adapter. Cursor values are local snapshot continuations; no private
 * endpoint or remote cursor contract is inferred. The public index has no remote revision, so a
 * clearly tagged local fingerprint detects changes visible in that index.
 */
internal class ProtonPublicContactGateway(
    private val expectedAccount: AccountScope,
    private val userProvider: ProtonReadyUserProvider,
    private val remote: ProtonContactRemotePort,
    private val cardCrypto: ProtonContactCardCrypto,
    private val vCardCodec: ProtonContactVCardCodec = ProtonContactVCardCodec(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val createStageObserver: ProtonContactCreateStageObserver = ProtonContactCreateStageObserver { },
    private val updateFailureObserver: ProtonContactUpdateFailureObserver = ProtonContactUpdateFailureObserver { _, _, _, _ -> },
) : ProtonContactInventoryGateway, ProtonVerifiedContactCardGateway, ProtonContactMutationGateway {
    private val inventoryMutex = Mutex()
    private var generation = 0L
    private var snapshot: InventorySnapshot? = null

    init {
        require(pageSize in 1..ContactInventoryPage.MAX_INVENTORY_PAGE_SIZE)
    }

    override suspend fun page(
        account: AccountScope,
        cursor: InventoryCursor?,
    ): GatewayOutcome<ContactInventoryPage> = gatewayCall {
        val userId = requireUser(account)
        inventoryMutex.withLock {
            val active = if (cursor == null) {
                val contacts = remote.inventory(userId)
                require(contacts.size <= ContactInventoryPage.MAX_INVENTORY_TOTAL)
                if (contacts.map { it.id.id }.distinct().size != contacts.size) {
                    throw ProtonMalformedContactResponse()
                }
                generation++
                InventorySnapshot(generation, contacts.map(::toInventoryMetadata)).also { snapshot = it }
            } else {
                snapshot?.takeIf { it.nextCursor == cursor } ?: throw ProtonInvalidInventoryCursor()
            }

            val requestedOffset = if (cursor == null) 0 else active.nextOffset
            val pageContacts = active.contacts.drop(requestedOffset).take(pageSize)
            val followingOffset = requestedOffset + pageContacts.size
            val next = if (followingOffset < active.contacts.size) {
                InventoryCursor("local-${active.generation}-$followingOffset")
            } else {
                null
            }
            active.nextOffset = followingOffset
            active.nextCursor = next
            if (next == null) snapshot = null
            ContactInventoryPage(
                contacts = pageContacts,
                requestedCursor = cursor,
                nextCursor = next,
                totalCount = active.contacts.size,
                // Collection-level authority, distinct from per-contact revision metadata. The
                // maintained public route returns the account's complete contact list in one
                // successful call, and pages are served from that single immutable snapshot, so
                // membership of the collection is attested even though entries carry only a local
                // fingerprint. D-032 needs exactly this to authorize deletion by absence; without
                // it no ordinary pass could ever plan. D-096 records the decision.
                snapshotAuthority = ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
            )
        }
    }

    override suspend fun fetch(
        account: AccountScope,
        contactId: RemoteContactId,
    ): GatewayOutcome<VerifiedContactCard> = gatewayCall {
        val userId = requireUser(account)
        val remoteContact = remote.hydrate(userId, ContactId(contactId.value))
        if (remoteContact.contact.id.id != contactId.value) throw ProtonHydrationMalformedResponse(
            GatewayContactHydrationCategory.UID_ID_CONSISTENCY,
        )
        validateHydrationBounds(remoteContact)
        val plainCards = try {
            cardCrypto.decryptAndVerify(userId, remoteContact.contactCards)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val hydrationCategory = (error as? ProtonContactHydrationFailure)?.category
                ?: GatewayContactHydrationCategory.DECRYPT_VERIFY
            when (error.toContactGatewayFailure()) {
                GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED ->
                    throw ProtonHydrationVerificationFailure(hydrationCategory)
                GatewayFailureCategory.MALFORMED_RESPONSE,
                GatewayFailureCategory.VALIDATION_REJECTED,
                -> throw ProtonHydrationMalformedResponse(hydrationCategory)
                else -> throw error
            }
        }
        if (plainCards.isEmpty()) throw ProtonHydrationMalformedResponse(
            GatewayContactHydrationCategory.CARD_BOUNDS,
        )
        val canonical = try {
            vCardCodec.decode(
                accountId = account.value,
                remote = remoteContact.contact,
                plainCards = plainCards,
            )
        } catch (error: ProtonInconsistentContactIdentity) {
            throw ProtonHydrationMalformedResponse(GatewayContactHydrationCategory.UID_ID_CONSISTENCY)
        } catch (error: ProtonVCardParseFailure) {
            throw ProtonHydrationMalformedResponse(error.category)
        } catch (error: ProtonHydrationMalformedResponse) {
            throw error
        } catch (_: ProtonMalformedContactResponse) {
            throw ProtonHydrationMalformedResponse(GatewayContactHydrationCategory.VCARD_PARSE)
        } catch (_: IllegalArgumentException) {
            throw ProtonHydrationMalformedResponse(GatewayContactHydrationCategory.VCARD_PARSE)
        }
        VerifiedContactCard(
            id = contactId,
            version = verifiedContentFingerprint(remoteContact.contact, plainCards),
            contact = canonical,
            compatibleVersions = setOf(fingerprint(remoteContact), contentFingerprint(remoteContact)),
        )
    }

    override suspend fun apply(
        account: AccountScope,
        mutation: ContactMutation,
    ): GatewayOutcome<ContactMutationReceipt> = gatewayCall {
        val userId = requireUser(account)
        when (mutation) {
            is ContactMutation.Create -> {
                requireUploadable(account, mutation.contact, requiresName = true)
                createStageObserver.onStage(ProtonContactCreateStage.PROTECT)
                val cards = cardCrypto.protect(userId, vCardCodec.encode(mutation.contact))
                val plain = cardCrypto.decryptAndVerify(userId, cards)
                val created = remote.create(userId, cards)
                confirmedWriteReceipt(userId, mutation.contact, created.id, plain)
            }
            is ContactMutation.Update -> {
                updateStep(ProtonContactUpdateStage.VALIDATE) {
                    if (mutation.expectedVersion != null) throw ProtonUnsupportedVersionPrecondition()
                    require(mutation.contact.remoteContactId == mutation.id.value)
                    requireUploadable(account, mutation.contact, requiresName = false)
                }
                val prepared = updateStep(ProtonContactUpdateStage.ENCODE) { vCardCodec.encode(mutation.contact) }
                val cards = updateStep(ProtonContactUpdateStage.PROTECT) { cardCrypto.protect(userId, prepared) }
                val plain = updateStep(ProtonContactUpdateStage.VERIFY_PREPARED) { cardCrypto.decryptAndVerify(userId, cards) }
                val updated = updateStep(ProtonContactUpdateStage.REMOTE_UPDATE) {
                    remote.update(userId, ContactId(mutation.id.value), cards)
                }
                updateStep(ProtonContactUpdateStage.READBACK) {
                    if (updated.id.id != mutation.id.value) throw ProtonMalformedContactResponse()
                    confirmedWriteReceipt(userId, mutation.contact, updated.id, plain)
                }
            }
            is ContactMutation.Delete -> {
                if (mutation.expectedVersion != null) throw ProtonUnsupportedVersionPrecondition()
                remote.delete(userId, ContactId(mutation.id.value))
                ContactMutationReceipt(mutation.id, null)
            }
        }
    }

    private suspend fun <T> updateStep(stage: ProtonContactUpdateStage, block: suspend () -> T): T =
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val encoding = failure as? ProtonContactEncodingException
            runCatching { updateFailureObserver.onFailure(stage, failure.toContactGatewayFailure(),
                encoding?.stage, encoding?.field) }
            throw failure
        }

    private suspend fun requireUser(account: AccountScope): UserId {
        if (account != expectedAccount) throw ProtonAuthenticationRequired()
        return userProvider.currentUserId() ?: throw ProtonAuthenticationRequired()
    }

    /**
     * A mutation response is not a complete read baseline. Establish it from one maintained
     * readback of this written contact, after verifying that all submitted plaintext survived.
     * No internal write replay and no additional full reads during ordinary no-change sync.
     */
    private suspend fun confirmedWriteReceipt(
        userId: UserId,
        desired: CanonicalContact,
        id: ContactId,
        expectedPlain: List<ProtonPlainContactCard>,
    ): ContactMutationReceipt {
        val stored = remote.hydrate(userId, id)
        if (stored.contact.id != id) throw ProtonMalformedContactResponse()
        val actualPlain = cardCrypto.decryptAndVerify(userId, stored.contactCards)
        if (comparableContactCards(expectedPlain) != comparableContactCards(actualPlain)) {
            throw IOException("CONTACT_WRITE_READBACK_PENDING")
        }
        return ContactMutationReceipt(RemoteContactId(id.id), verifiedContentFingerprint(stored.contact, actualPlain),
            emailIdentityReceipt(desired, stored.contact))
    }

    /** A contact write can replace email identities even when its email text is unchanged. */
    private fun emailIdentityReceipt(contact: com.patmanak.contako.domain.model.CanonicalContact, remote: Contact): Map<String, String> =
        contact.valuesOf(com.patmanak.contako.domain.model.ContactValueKind.EMAIL)
            .distinctBy { it.value.trim().lowercase(java.util.Locale.ROOT) }
            .mapNotNull { value ->
                remote.contactEmails.singleOrNull {
                    it.email.trim().equals(value.value.trim(), ignoreCase = true)
                }?.let { value.id to it.id.id }
            }.toMap()

    private fun requireUploadable(
        account: AccountScope,
        contact: CanonicalContact,
        requiresName: Boolean,
    ) {
        require(contact.accountId == account.value)
        require(!contact.isDeleted)
        val blockingReasons = if (requiresName) {
            contact.actionRequiredReasons
        } else {
            contact.actionRequiredReasons - "MISSING_NAME"
        }
        require(blockingReasons.isEmpty())
        if (requiresName) require(ContactValidation.canCreate(contact))
    }

    private fun toInventoryMetadata(contact: Contact): ContactInventoryMetadata {
        require(contact.id.id.isNotBlank())
        require(contact.contactEmails.size <= ContactInventoryMetadata.MAX_REFERENCES_PER_CONTACT)
        val emailIds = contact.contactEmails.map { RemoteEmailId(it.id.id) }
        val groupIds = contact.contactEmails.flatMap { it.labelIds }.distinct().map(::RemoteGroupId)
        val memberships = contact.contactEmails.map { email ->
            RemoteEmailGroupMembership(
                emailId = RemoteEmailId(email.id.id),
                groupIds = email.labelIds.distinct().map(::RemoteGroupId),
            )
        }
        return ContactInventoryMetadata(
            id = RemoteContactId(contact.id.id),
            displayName = contact.name,
            version = fingerprint(contact),
            sizeBytes = null,
            modifiedAtEpochSeconds = null,
            emailIds = emailIds,
            groupIds = groupIds,
            versionProvenance = ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT,
            coverage = ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY,
            emailGroupMemberships = memberships,
        )
    }

    private fun validateHydrationBounds(contact: ContactWithCards) {
        if (contact.contactCards.isEmpty() || contact.contactCards.size > MAX_CARDS_PER_CONTACT ||
            contact.contactEmails.size > ContactInventoryMetadata.MAX_REFERENCES_PER_CONTACT
        ) throw ProtonHydrationMalformedResponse(GatewayContactHydrationCategory.CARD_BOUNDS)
        val totalBytes = contact.contactCards.sumOf { card ->
            when (card) {
                is ContactCard.ClearText -> card.data.toByteArray(Charsets.UTF_8).size.toLong()
                is ContactCard.Signed -> card.data.toByteArray(Charsets.UTF_8).size.toLong() +
                    card.signature.toByteArray(Charsets.UTF_8).size
                is ContactCard.Encrypted -> card.data.toByteArray(Charsets.UTF_8).size.toLong() +
                    (card.signature?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            }
        }
        if (totalBytes > MAX_HYDRATED_CARD_BYTES) throw ProtonHydrationMalformedResponse(
            GatewayContactHydrationCategory.CARD_BOUNDS,
        )
    }

    private data class InventorySnapshot(
        val generation: Long,
        val contacts: List<ContactInventoryMetadata>,
        var nextOffset: Int = 0,
        var nextCursor: InventoryCursor? = null,
    )

    private companion object {
        const val DEFAULT_PAGE_SIZE = 300
        const val MAX_CARDS_PER_CONTACT = 16
        const val MAX_HYDRATED_CARD_BYTES = 10L * 1_024 * 1_024
    }
}

internal class ProtonPublicContactGroupGateway(
    private val expectedAccount: AccountScope,
    private val userProvider: ProtonReadyUserProvider,
    private val remote: LabelRemoteDataSource,
) : ProtonContactGroupGateway {
    override fun capabilities(): ContactGroupCapabilities = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE

    override suspend fun list(account: AccountScope): GatewayOutcome<AvailableContactGroups> = gatewayCall {
        val labels = remote.getLabels(requireUser(account), LabelType.ContactGroup)
        require(labels.size <= MAX_GROUPS)
        require(labels.all { it.type == LabelType.ContactGroup })
        AvailableContactGroups(labels.map { label -> label.toRemoteGroup() })
    }

    override suspend fun create(
        account: AccountScope,
        mutation: ContactGroupMutation.Create,
    ): GatewayOutcome<RemoteContactGroup> = gatewayCall {
        validateGroup(mutation.name, mutation.color)
        remote.createLabel(
            requireUser(account),
            NewLabel(
                parentId = null,
                name = mutation.name.trim(),
                type = LabelType.ContactGroup,
                color = mutation.color ?: DEFAULT_GROUP_COLOR,
                isNotified = false,
                isExpanded = false,
                isSticky = false,
            ),
        ).toRemoteGroup()
    }

    override suspend fun update(
        account: AccountScope,
        mutation: ContactGroupMutation.Update,
    ): GatewayOutcome<RemoteContactGroup> = gatewayCall {
        validateGroup(mutation.name, mutation.color)
        val updated = remote.updateLabel(
            requireUser(account),
            UpdateLabel(
                labelId = LabelId(mutation.id.value),
                parentId = null,
                name = mutation.name.trim(),
                color = mutation.color ?: DEFAULT_GROUP_COLOR,
                isNotified = null,
                isExpanded = null,
                isSticky = null,
            ),
        )
        if (updated.labelId.id != mutation.id.value) throw ProtonMalformedContactResponse()
        updated.toRemoteGroup()
    }

    override suspend fun delete(
        account: AccountScope,
        mutation: ContactGroupMutation.Delete,
    ): GatewayOutcome<Unit> = gatewayCall {
        remote.deleteLabel(requireUser(account), LabelId(mutation.id.value))
    }

    private suspend fun requireUser(account: AccountScope): UserId {
        if (account != expectedAccount) throw ProtonAuthenticationRequired()
        return userProvider.currentUserId() ?: throw ProtonAuthenticationRequired()
    }

    private fun validateGroup(name: String, color: String?) {
        require(name.isNotBlank() && name.trim().length <= MAX_GROUP_NAME_LENGTH)
        require(color == null || GROUP_COLOR.matches(color))
    }

    private fun Label.toRemoteGroup(): RemoteContactGroup {
        require(type == LabelType.ContactGroup)
        return RemoteContactGroup(RemoteGroupId(labelId.id), name, color)
    }

    private companion object {
        const val MAX_GROUPS = 10_000
        const val MAX_GROUP_NAME_LENGTH = 100
        const val DEFAULT_GROUP_COLOR = ContactGroupDefaults.CREATE_COLOR
        val GROUP_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

private fun fingerprint(contact: Contact): RemoteVersion {
    val normalized = buildString {
        append(contact.id.id).append('\u0000').append(contact.name).append('\u0000')
        contact.contactEmails.sortedBy { it.id.id }.forEach { email ->
            append(email.id.id).append('\u0000')
            append(email.email).append('\u0000')
            append(email.name).append('\u0000')
            append(email.order).append('\u0000')
            email.labelIds.sorted().forEach { append(it).append('\u0000') }
        }
    }
    return RemoteVersion(sha256(normalized))
}

/** Legacy full-card alias only. The old write receipt's index hash is deliberately not an alias. */
private fun fingerprint(contact: ContactWithCards): RemoteVersion {
    val normalized = buildString {
        append(fingerprint(contact.contact).value).append('\u0000')
        contact.contactCards.forEach { card ->
            when (card) {
                is ContactCard.ClearText -> append('0').append(card.data)
                is ContactCard.Signed -> append('2').append(card.data).append(card.signature)
                is ContactCard.Encrypted -> append('1').append(card.data).append(card.signature.orEmpty())
            }
            append('\u0000')
        }
    }
    return RemoteVersion(sha256(normalized))
}

/**
 * Same complete content baseline for acknowledged writes and verified reads. Label assignments
 * are independent D-007 group intent, not a concurrent contact edit. Keep index identity/name/
 * email/order as well as every card and signature. Length framing prevents ambiguous joins;
 * sorting cards tolerates transport ordering without dropping any content or multiplicity.
 * This is local change evidence, never a server revision or conditional-write precondition.
 */
internal fun contentFingerprint(contact: ContactWithCards): RemoteVersion {
    fun frame(vararg values: String): String = values.joinToString("") { "${it.length}:$it" }
    val index = contact.contact
    val normalized = buildString {
        append(frame(index.id.id, index.name))
        append(frame(index.contactEmails.size.toString()))
        index.contactEmails.sortedBy { it.id.id }.forEach {
            append(frame(it.id.id, it.email, it.name, it.order.toString()))
        }
        val cards = contact.contactCards.map { card ->
            when (card) {
                is ContactCard.ClearText -> frame("0", card.data)
                is ContactCard.Signed -> frame("2", card.data, card.signature)
                is ContactCard.Encrypted -> frame("1", card.data, card.signature.orEmpty())
            }
        }.sorted()
        append(frame(cards.size.toString()))
        cards.forEach { append(frame(it)) }
    }
    return RemoteVersion("content-v1:${sha256(normalized)}")
}

/**
 * Compare validated plaintext, not volatile encrypted packets/signatures. The caller MUST have
 * passed all declared-type, signature, decryption and bounded vCard checks first. Original card
 * types and every normalized vCard byte (including unknown fields) remain in the digest.
 * The write-side local round trip uses maintained crypto and adds no network request.
 */
internal fun verifiedContentFingerprint(contact: Contact, plain: List<ProtonPlainContactCard>): RemoteVersion {
    fun frame(vararg values: String): String = values.joinToString("") { "${it.length}:$it" }
    val normalized = buildString {
        append(frame(contact.id.id, contact.name, contact.contactEmails.size.toString()))
        contact.contactEmails.sortedBy { it.id.id }.forEach {
            append(frame(it.id.id, it.email, it.name, it.order.toString()))
        }
        val cards = comparableContactCards(plain).map { frame(it.type.value.toString(), it.vCard) }.sorted()
        append(frame(cards.size.toString()))
        cards.forEach { append(frame(it)) }
    }
    return RemoteVersion("content-v3:${sha256(normalized)}")
}

/** Only Proton's clear per-email CATEGORIES are group intent; keep all other card properties. */
internal fun comparableContactCards(plain: List<ProtonPlainContactCard>): List<ProtonPlainContactCard> = plain
    .mapNotNull { card ->
        if (card.type != ContactCardType.ClearText) return@mapNotNull card
        val parsed = parseSingleCompleteVCard(card.vCard)
        parsed.removeProperties(Categories::class.java)
        val remainder = parsed.write()
        if (hasContactCardPayloadProperties(remainder)) card.copy(vCard = remainder) else null
    }
    .sortedWith(compareBy({ it.type.value }, { it.vCard }))

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private suspend inline fun <T> gatewayCall(crossinline block: suspend () -> T): GatewayOutcome<T> =
    try {
        GatewayOutcome.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        error.toContactGatewayFailureOutcome()
    }

internal fun Throwable.toContactGatewayFailureOutcome(): GatewayOutcome.Failure {
    val category = toContactGatewayFailure()
    val retryAfterMillis = if (category == GatewayFailureCategory.RATE_LIMITED) {
        ((this as? ApiException)?.error as? ApiResult.Error.Http)
            ?.retryAfter
            ?.inWholeMilliseconds
            ?.coerceAtLeast(0)
    } else {
        null
    }
    val malformedResponseCategory = (this as? ProtonRawCreateResponseException)?.category
    val protonResponseCode = ((this as? ProtonRawCreateResponseException)?.protonResponseCode
        ?: ((this as? ApiException)?.error as? ApiResult.Error.Http)?.proton?.code)
        ?.takeIf { it in SAFE_PROTON_RESPONSE_CODES }
    val contactHydrationCategory = (this as? ProtonContactHydrationFailure)?.category
    return GatewayOutcome.Failure(
        category,
        retryAfterMillis,
        malformedResponseCategory,
        contactHydrationCategory,
        protonResponseCode,
    )
}

private val SAFE_PROTON_RESPONSE_CODES = 1_000..9_999

internal fun Throwable.toContactGatewayFailure(): GatewayFailureCategory = when {
    containsCause<GateCLocalRequestBudgetExceeded>() -> GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED
    containsCause<ProtonResponseSizeExceeded>() -> GatewayFailureCategory.MALFORMED_RESPONSE
    else -> when (this) {
        is ProtonAuthenticationRequired -> GatewayFailureCategory.AUTHENTICATION_REQUIRED
        is ProtonSessionIdentityMismatch -> GatewayFailureCategory.AUTHENTICATION_REQUIRED
        is ProtonInvalidInventoryCursor, is IllegalArgumentException -> GatewayFailureCategory.VALIDATION_REJECTED
        is ProtonUnsupportedVersionPrecondition -> GatewayFailureCategory.CONFLICT
        is ProtonMalformedContactResponse, is ProtonInconsistentInventorySnapshot ->
            GatewayFailureCategory.MALFORMED_RESPONSE
        is ProtonContactVerificationFailure -> GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED
        is CryptoException -> GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED
        is ApiException -> when (val apiError = error) {
            is ApiResult.Error.Timeout -> GatewayFailureCategory.TIMEOUT
            is ApiResult.Error.Parse -> GatewayFailureCategory.MALFORMED_RESPONSE
            is ApiResult.Error.NoInternet, is ApiResult.Error.Connection -> GatewayFailureCategory.NETWORK_UNAVAILABLE
            is ApiResult.Error.Http -> when (apiError.httpCode) {
                400, 422 -> GatewayFailureCategory.VALIDATION_REJECTED
                401 -> GatewayFailureCategory.AUTHENTICATION_REQUIRED
                403 -> GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
                404 -> GatewayFailureCategory.NOT_FOUND
                409, 412 -> GatewayFailureCategory.CONFLICT
                429 -> GatewayFailureCategory.RATE_LIMITED
                in 500..599 -> GatewayFailureCategory.REMOTE_SERVICE_FAILURE
                else -> GatewayFailureCategory.UNKNOWN
            }
        }
        is IOException -> GatewayFailureCategory.NETWORK_UNAVAILABLE
        is SecurityException -> GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED
        else -> GatewayFailureCategory.UNKNOWN
    }
}

private inline fun <reified T : Throwable> Throwable.containsCause(): Boolean {
    var current: Throwable? = this
    repeat(8) {
        val inspected = current ?: return false
        if (inspected is T) return true
        current = inspected.cause?.takeUnless { it === inspected }
    }
    return false
}

internal class ProtonAuthenticationRequired : IllegalStateException()
internal class ProtonInvalidInventoryCursor : IllegalArgumentException()
internal class ProtonUnsupportedVersionPrecondition : IllegalStateException()
internal open class ProtonMalformedContactResponse : IllegalStateException()
internal open class ProtonContactVerificationFailure : SecurityException()

internal interface ProtonContactHydrationFailure {
    val category: GatewayContactHydrationCategory
}

internal class ProtonHydrationMalformedResponse(
    override val category: GatewayContactHydrationCategory,
) : ProtonMalformedContactResponse(), ProtonContactHydrationFailure

internal class ProtonHydrationVerificationFailure(
    override val category: GatewayContactHydrationCategory = GatewayContactHydrationCategory.DECRYPT_VERIFY,
) : ProtonContactVerificationFailure(), ProtonContactHydrationFailure

private suspend fun hydrateMapped(
    remote: ContactRemoteDataSource,
    userId: UserId,
    contactId: ContactId,
): ContactWithCards = try {
    remote.getContactWithCards(userId, contactId)
} catch (error: Exception) {
    if (error is CancellationException) throw error
    if (error.toContactGatewayFailure() in setOf(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            GatewayFailureCategory.VALIDATION_REJECTED,
        )
    ) throw ProtonHydrationMalformedResponse(GatewayContactHydrationCategory.HTTP_RESPONSE_MAPPING)
    throw error
}
