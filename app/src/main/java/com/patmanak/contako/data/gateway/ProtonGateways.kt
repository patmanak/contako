package com.patmanak.contako.data.gateway

import com.patmanak.contako.domain.model.CanonicalContact
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Account-scoped Proton integration boundaries.
 *
 * These contracts deliberately contain no Proton DTO, HTTP route, credential store, token, raw
 * exception, or server-provided message. Wire implementations remain behind replaceable data
 * boundaries and their runtime composition grants no live-operation authorization.
 */
abstract class RedactedOpaqueValue internal constructor(
    internal val value: String,
    private val label: String,
) {
    init {
        require(value.isNotBlank())
        require(value.length <= MAX_OPAQUE_VALUE_LENGTH)
    }

    final override fun equals(other: Any?): Boolean =
        other != null && other::class == this::class && (other as RedactedOpaqueValue).value == value

    final override fun hashCode(): Int = 31 * this::class.hashCode() + value.hashCode()
    final override fun toString(): String = "$label(REDACTED)"

    private companion object {
        const val MAX_OPAQUE_VALUE_LENGTH = 4_096
    }
}

class AccountScope(value: String) : RedactedOpaqueValue(value, "AccountScope")
class RemoteContactId(value: String) : RedactedOpaqueValue(value, "RemoteContactId")
class RemoteGroupId(value: String) : RedactedOpaqueValue(value, "RemoteGroupId")
class RemoteEmailId(value: String) : RedactedOpaqueValue(value, "RemoteEmailId")
class RemoteVersion(value: String) : RedactedOpaqueValue(value, "RemoteVersion")
class InventoryCursor(value: String) : RedactedOpaqueValue(value, "InventoryCursor")

/** Closed, stable categories suitable for durable retry policy and sanitized UI copy. */
enum class GatewayFailureCategory {
    AUTHENTICATION_REQUIRED,
    ACCOUNT_ALREADY_CONNECTED,
    PERMISSION_OR_PLAN_DENIED,
    RATE_LIMITED,
    LOCAL_REQUEST_BUDGET_EXHAUSTED,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    VALIDATION_REJECTED,
    CONFLICT,
    NOT_FOUND,
    CRYPTOGRAPHIC_VERIFICATION_FAILED,
    MALFORMED_RESPONSE,
    UNSUPPORTED_AUTHENTICATION,
    CLIENT_IDENTITY_REJECTED,
    REMOTE_SERVICE_FAILURE,
    HUMAN_VERIFICATION_REQUIRED,
    UNKNOWN,
}

/** Closed structural identity for malformed responses; never carries server or contact data. */
enum class GatewayMalformedResponseCategory {
    RAW_CREATE_RESPONSE_SIZE,
    RAW_CREATE_ROOT_OBJECT,
    RAW_CREATE_RESPONSES_TYPE,
    RAW_CREATE_RESPONSES_COUNT,
    RAW_CREATE_RESPONSE_ITEM,
    RAW_CREATE_INDEX,
    RAW_CREATE_NESTED_RESPONSE,
    RAW_CREATE_NESTED_CODE,
    RAW_CREATE_CONTACT_OBJECT,
    RAW_CREATE_CONTACT_ID,
}

/** Closed hydration-stage identity; it never carries response, card, or contact data. */
enum class GatewayContactHydrationCategory {
    HTTP_RESPONSE_MAPPING,
    CARD_BOUNDS,
    DECRYPT_VERIFY,
    WIRE_CARD_VALIDATION,
    AUTHENTICITY_REQUIREMENT,
    DECRYPT_OPERATION,
    SIGNATURE_VERIFICATION,
    PLAINTEXT_VCARD_NORMALIZATION,
    PLAINTEXT_VCARD_STRUCTURE,
    PLAINTEXT_VCARD_VERSION,
    PLAINTEXT_VCARD_PARSER,
    PLAINTEXT_VCARD_SERIALIZATION,
    PLAINTEXT_BOUNDS,
    VCARD_PARSE,
    VCARD_PARSE_BOUNDS,
    VCARD_PARSE_CHARACTERS,
    VCARD_PARSE_ENVELOPE,
    VCARD_PARSE_DUPLICATE_LABEL,
    VCARD_PARSE_DUPLICATE_PARAMETER,
    VCARD_PARSE_PARAMETER_SYNTAX,
    VCARD_PARSE_DUPLICATE_CARD,
    VCARD_PARSE_PUBLIC_KEY,
    UID_ID_CONSISTENCY,
}

/**
 * Conservative replay rule for a failure returned by a remote mutation.
 *
 * A true result means that the request may have reached Proton even though Contako did not receive
 * an authoritative acknowledgement. The durable synchronization layer MUST reconcile remote state
 * before it invokes the mutation again. Read-only callers MUST ignore this mutation-specific rule.
 */
fun GatewayFailureCategory.requiresMutationReconciliationBeforeReplay(): Boolean = when (this) {
    GatewayFailureCategory.NETWORK_UNAVAILABLE,
    GatewayFailureCategory.TIMEOUT,
    GatewayFailureCategory.CANCELLED,
    GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
    GatewayFailureCategory.UNKNOWN,
    -> true
    else -> false
}

sealed interface GatewayOutcome<out T> {
    data class Success<T>(val value: T) : GatewayOutcome<T> {
        override fun toString(): String = "GatewayOutcome.Success(REDACTED)"
    }

    data class Failure(
        val category: GatewayFailureCategory,
        val retryAfterMillis: Long? = null,
        val malformedResponseCategory: GatewayMalformedResponseCategory? = null,
        val contactHydrationCategory: GatewayContactHydrationCategory? = null,
        val protonResponseCode: Int? = null,
    ) : GatewayOutcome<Nothing> {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0)
            require(malformedResponseCategory == null || category == GatewayFailureCategory.MALFORMED_RESPONSE)
            require(
                protonResponseCode == null ||
                    protonResponseCode in SAFE_PROTON_RESPONSE_CODE_RANGE,
            )
            require(
                contactHydrationCategory == null ||
                    category == GatewayFailureCategory.MALFORMED_RESPONSE ||
                    category == GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED,
            )
        }

        override fun toString(): String = buildString {
            append("GatewayOutcome.Failure(category=$category, retryAfterMillis=$retryAfterMillis")
            malformedResponseCategory?.let { append(", malformedResponseCategory=$it") }
            contactHydrationCategory?.let { append(", contactHydrationCategory=$it") }
            protonResponseCode?.let { append(", protonResponseCode=$it") }
            append(')')
        }

        private companion object {
            val SAFE_PROTON_RESPONSE_CODE_RANGE = 1_000..9_999
        }
    }
}

/**
 * Mutable, operation-scoped secret input. Ownership transfer immediately clears the caller's
 * buffer. Consumption is one-shot and clears the owned buffer in a finally block.
 */
class OperationSecret private constructor(private val value: CharArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    internal fun <T> consume(block: (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    internal suspend fun <T> consumeSuspend(block: suspend (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) value.fill('\u0000')
    }

    override fun toString(): String = "OperationSecret(REDACTED)"

    companion object {
        fun takeAndClear(source: CharArray): OperationSecret {
            val owned = try {
                require(source.isNotEmpty())
                require(source.size <= MAX_SECRET_LENGTH)
                source.copyOf()
            } finally {
                source.fill('\u0000')
            }
            return OperationSecret(owned)
        }

        private const val MAX_SECRET_LENGTH = 16_384
    }
}

enum class SecondFactorMethod { CODE, SECURITY_KEY }

sealed interface AuthenticationState {
    data object Ready : AuthenticationState
    class SecondFactorRequired private constructor(
        val methods: Set<SecondFactorMethod>,
    ) : AuthenticationState {
        override fun toString(): String = "SecondFactorRequired(methods=$methods)"

        companion object {
            fun fromOfferedMethods(methods: Set<SecondFactorMethod>): AuthenticationState {
                require(methods.isNotEmpty())
                return if (SecondFactorMethod.CODE in methods) {
                    SecondFactorRequired(methods.toSet())
                } else {
                    SecurityKeyOnlyUnsupported
                }
            }
        }
    }
    data object MailboxPasswordRequired : AuthenticationState
    data object KeyUnlockRequired : AuthenticationState
    data object SecurityKeyOnlyUnsupported : AuthenticationState
}

/** SRP login and code-capable second factor; implementations MUST consume secrets in-call only. */
interface ProtonAuthenticationGateway {
    suspend fun signIn(
        account: AccountScope,
        username: OperationSecret,
        password: OperationSecret,
    ): GatewayOutcome<AuthenticationState>

    suspend fun submitSecondFactor(
        account: AccountScope,
        code: OperationSecret,
    ): GatewayOutcome<AuthenticationState>

    suspend fun cancel(account: AccountScope)
}

enum class SessionState {
    READY,
    AUTHENTICATION_REQUIRED,
    INTERACTIVE_MAILBOX_PASSWORD_REQUIRED,
    INTERACTIVE_KEY_UNLOCK_REQUIRED,
    REVOKED,
}

/** Session material stays inside Proton Core's encrypted account state and never crosses this API. */
interface ProtonSessionGateway {
    suspend fun restore(account: AccountScope): GatewayOutcome<SessionState>
    suspend fun refresh(account: AccountScope): GatewayOutcome<SessionState>
    suspend fun unlockKeys(account: AccountScope, password: OperationSecret): GatewayOutcome<SessionState>
    suspend fun lockKeys(account: AccountScope): GatewayOutcome<Unit>
    suspend fun revokeAndClear(account: AccountScope): GatewayOutcome<Unit>
}

fun interface ProtonLocalSessionCleanupGateway {
    suspend fun clearLocal(account: AccountScope): GatewayOutcome<Unit>
}

class ContactInventoryMetadata(
    val id: RemoteContactId,
    val displayName: String?,
    val version: RemoteVersion,
    val sizeBytes: Long?,
    val modifiedAtEpochSeconds: Long?,
    emailIds: List<RemoteEmailId>,
    groupIds: List<RemoteGroupId>,
    val versionProvenance: ContactInventoryVersionProvenance,
    val coverage: ContactInventoryCoverage,
    emailGroupMemberships: List<RemoteEmailGroupMembership> = emptyList(),
) {
    val emailIds: List<RemoteEmailId> = emailIds.toList()
    val groupIds: List<RemoteGroupId> = groupIds.toList()
    val emailGroupMemberships: List<RemoteEmailGroupMembership> = emailGroupMemberships.toList()

    init {
        require(displayName == null || displayName.length <= MAX_DISPLAY_NAME_LENGTH)
        require(sizeBytes == null || sizeBytes in 0..MAX_CONTACT_SIZE_BYTES)
        require(modifiedAtEpochSeconds == null || modifiedAtEpochSeconds in 0..MAX_EPOCH_SECONDS)
        require(this.emailIds.size <= MAX_REFERENCES_PER_CONTACT)
        require(this.groupIds.size <= MAX_REFERENCES_PER_CONTACT)
        require(this.emailIds.distinct().size == this.emailIds.size)
        require(this.groupIds.distinct().size == this.groupIds.size)
        require(this.emailGroupMemberships.map(RemoteEmailGroupMembership::emailId).distinct().size == this.emailGroupMemberships.size)
        require(this.emailGroupMemberships.all { it.emailId in this.emailIds })
        require(this.emailGroupMemberships.flatMap(RemoteEmailGroupMembership::groupIds).all { it in this.groupIds })
    }

    override fun toString(): String =
        "ContactInventoryMetadata(REDACTED, emailCount=${emailIds.size}, groupCount=${groupIds.size})"

    companion object {
        const val MAX_CONTACT_SIZE_BYTES: Long = 10L * 1_024 * 1_024
        const val MAX_EPOCH_SECONDS: Long = 253_402_300_799
        const val MAX_REFERENCES_PER_CONTACT: Int = 10_000
        const val MAX_DISPLAY_NAME_LENGTH: Int = 16_384
    }
}

enum class ContactInventoryVersionProvenance {
    REMOTE_SERVER,
    REMOTE_SERVER_UNATTESTED,
    LOCAL_INDEX_FINGERPRINT,
}

enum class ContactInventoryCoverage {
    AUTHORITATIVE_REMOTE_REVISION,
    REMOTE_REVISION_UNATTESTED,
    PUBLIC_DIRECTORY_FIELDS_ONLY,
}

class RemoteEmailGroupMembership(
    val emailId: RemoteEmailId,
    groupIds: List<RemoteGroupId>,
) {
    val groupIds: List<RemoteGroupId> = groupIds.toList()

    init {
        require(this.groupIds.size <= ContactInventoryMetadata.MAX_REFERENCES_PER_CONTACT)
        require(this.groupIds.distinct().size == this.groupIds.size)
    }

    override fun toString(): String = "RemoteEmailGroupMembership(REDACTED, groupCount=${groupIds.size})"
}

class ContactInventoryPage(
    contacts: List<ContactInventoryMetadata>,
    val requestedCursor: InventoryCursor?,
    val nextCursor: InventoryCursor?,
    val totalCount: Int,
    val snapshotAuthority: ContactInventorySnapshotAuthority =
        ContactInventorySnapshotAuthority.UNATTESTED,
) {
    val contacts: List<ContactInventoryMetadata> = contacts.toList()
    val isComplete: Boolean get() = nextCursor == null

    init {
        require(totalCount in 0..MAX_INVENTORY_TOTAL)
        require(this.contacts.size <= MAX_INVENTORY_PAGE_SIZE)
        require(this.contacts.size <= totalCount)
        require(this.contacts.map(ContactInventoryMetadata::id).distinct().size == this.contacts.size)
    }

    override fun toString(): String =
        "ContactInventoryPage(contactCount=${contacts.size}, totalCount=$totalCount, isComplete=$isComplete)"

    companion object {
        const val MAX_INVENTORY_PAGE_SIZE = 1_000
        const val MAX_INVENTORY_TOTAL = 100_000
    }
}

/** Collection-level proof. It is explicit so an empty inventory cannot pass by vacuous truth. */
enum class ContactInventorySnapshotAuthority {
    UNATTESTED,
    AUTHORITATIVE_REMOTE_REVISION,
}

/** A complete inventory exists only after every page passes continuity and global uniqueness. */
class ValidatedCompleteInventory private constructor(
    val contacts: List<ContactInventoryMetadata>,
    val snapshotAuthority: ContactInventorySnapshotAuthority,
) {
    val totalCount: Int get() = contacts.size

    override fun toString(): String = "ValidatedCompleteInventory(contactCount=$totalCount)"

    companion object {
        fun fromPages(pages: List<ContactInventoryPage>): ValidatedCompleteInventory {
            require(pages.isNotEmpty())
            val expectedTotal = pages.first().totalCount
            require(pages.all { it.totalCount == expectedTotal })
            val snapshotAuthority = pages.first().snapshotAuthority
            require(pages.all { it.snapshotAuthority == snapshotAuthority })

            var expectedCursor: InventoryCursor? = null
            var terminalSeen = false
            val seenCursors = mutableSetOf<InventoryCursor>()
            val contacts = mutableListOf<ContactInventoryMetadata>()

            pages.forEach { page ->
                require(!terminalSeen)
                require(page.requestedCursor == expectedCursor)
                contacts += page.contacts
                terminalSeen = page.isComplete
                expectedCursor = page.nextCursor
                expectedCursor?.let { require(seenCursors.add(it)) }
            }

            require(terminalSeen)
            require(expectedCursor == null)
            require(contacts.size == expectedTotal)
            require(contacts.map(ContactInventoryMetadata::id).distinct().size == contacts.size)
            return ValidatedCompleteInventory(contacts.toList(), snapshotAuthority)
        }
    }
}

/** Complete lightweight inventory. It MUST NOT hydrate full cards as a side effect. */
fun interface ProtonContactInventoryGateway {
    suspend fun page(
        account: AccountScope,
        cursor: InventoryCursor?,
    ): GatewayOutcome<ContactInventoryPage>
}

/** A full card may cross this boundary only after maintained crypto has verified and decrypted it. */
data class VerifiedContactCard(
    val id: RemoteContactId,
    val version: RemoteVersion?,
    val contact: CanonicalContact,
    /** Verified full-card aliases for migration; an index-only hash MUST NOT be included. */
    val compatibleVersions: Set<RemoteVersion> = emptySet(),
) {
    fun matchesBaseline(baseline: String?): Boolean = baseline != null &&
        (version?.value == baseline || compatibleVersions.any { it.value == baseline })

    override fun toString(): String = "VerifiedContactCard(REDACTED)"
}

fun interface ProtonVerifiedContactCardGateway {
    suspend fun fetch(
        account: AccountScope,
        contactId: RemoteContactId,
    ): GatewayOutcome<VerifiedContactCard>
}

sealed interface ContactMutation {
    data class Create(val contact: CanonicalContact) : ContactMutation {
        override fun toString(): String = "ContactMutation.Create(REDACTED)"
    }

    data class Update(
        val id: RemoteContactId,
        val expectedVersion: RemoteVersion?,
        val contact: CanonicalContact,
    ) : ContactMutation {
        override fun toString(): String = "ContactMutation.Update(REDACTED)"
    }

    data class Delete(
        val id: RemoteContactId,
        val expectedVersion: RemoteVersion?,
    ) : ContactMutation {
        override fun toString(): String = "ContactMutation.Delete(REDACTED)"
    }
}

data class ContactMutationReceipt(
    val id: RemoteContactId,
    val version: RemoteVersion?,
    /** Canonical email value ID to current service email ID, from this write's response. */
    val emailIdsByValueId: Map<String, String>? = null,
) {
    override fun toString(): String = "ContactMutationReceipt(REDACTED)"
}

/**
 * Full-card writes only; inventory reconciliation remains a distinct mandatory step.
 *
 * Implementations MUST issue at most one remote write per invocation and MUST NOT internally replay
 * an uncertain failure. The caller applies [requiresMutationReconciliationBeforeReplay] before any
 * later invocation for the same durable mutation.
 */
interface ProtonContactMutationGateway {
    suspend fun apply(
        account: AccountScope,
        mutation: ContactMutation,
    ): GatewayOutcome<ContactMutationReceipt>
}

class RemoteContactGroup(
    val id: RemoteGroupId,
    val name: String,
    val color: String?,
) {
    init {
        require(name.isNotBlank() && name.length <= 512)
        require(color == null || color.length <= 64)
    }

    override fun toString(): String = "RemoteContactGroup(REDACTED)"
}

sealed interface ContactGroupMutation {
    data class Create(val name: String, val color: String?) : ContactGroupMutation {
        init {
            require(name.isNotBlank() && name.length <= 512)
            require(color == null || color.length <= 64)
        }

        override fun toString(): String = "ContactGroupMutation.Create(REDACTED)"
    }

    data class Update(val id: RemoteGroupId, val name: String, val color: String?) : ContactGroupMutation {
        init {
            require(name.isNotBlank() && name.length <= 512)
            require(color == null || color.length <= 64)
        }

        override fun toString(): String = "ContactGroupMutation.Update(REDACTED)"
    }

    data class Delete(val id: RemoteGroupId) : ContactGroupMutation {
        override fun toString(): String = "ContactGroupMutation.Delete(REDACTED)"
    }
}

class AvailableContactGroups(
    groups: List<RemoteContactGroup>,
) {
    val groups: List<RemoteContactGroup> = groups.toList()

    init {
        require(this.groups.map(RemoteContactGroup::id).distinct().size == this.groups.size)
    }

    override fun toString(): String =
        "AvailableContactGroups(groupCount=${groups.size})"
}

/**
 * Contact-group boundary. Each mutation invocation follows the same single-attempt and mandatory
 * reconciliation-before-replay rule as [ProtonContactMutationGateway].
 */
interface ProtonContactGroupGateway {
    fun capabilities(): ContactGroupCapabilities

    suspend fun list(account: AccountScope): GatewayOutcome<AvailableContactGroups>
    suspend fun create(
        account: AccountScope,
        mutation: ContactGroupMutation.Create,
    ): GatewayOutcome<RemoteContactGroup>

    suspend fun update(
        account: AccountScope,
        mutation: ContactGroupMutation.Update,
    ): GatewayOutcome<RemoteContactGroup>

    suspend fun delete(
        account: AccountScope,
        mutation: ContactGroupMutation.Delete,
    ): GatewayOutcome<Unit>
}

enum class ContactGroupCapability {
    LIST,
    CREATE,
    UPDATE,
    DELETE,
    ASSIGN_EMAILS,
}

enum class ContactGroupCapabilityAvailability { AVAILABLE, DENIED, UNKNOWN }

enum class ContactGroupPublicCoreSurface { EXPOSED, NOT_EXPOSED }

class ContactGroupCapabilities(
    availability: Map<ContactGroupCapability, ContactGroupCapabilityAvailability>,
    publicCoreSurface: Map<ContactGroupCapability, ContactGroupPublicCoreSurface>,
) {
    private val availability: Map<ContactGroupCapability, ContactGroupCapabilityAvailability> = availability.toMap()
    private val publicCoreSurface: Map<ContactGroupCapability, ContactGroupPublicCoreSurface> = publicCoreSurface.toMap()

    fun availability(capability: ContactGroupCapability): ContactGroupCapabilityAvailability =
        availability[capability] ?: ContactGroupCapabilityAvailability.UNKNOWN

    fun publicCoreSurface(capability: ContactGroupCapability): ContactGroupPublicCoreSurface =
        publicCoreSurface[capability] ?: ContactGroupPublicCoreSurface.NOT_EXPOSED

    override fun toString(): String = "ContactGroupCapabilities(REDACTED)"

    companion object {
        val PROTON_CORE_36_6_2_SURFACE: ContactGroupCapabilities = ContactGroupCapabilities(
            mapOf(
                ContactGroupCapability.LIST to ContactGroupCapabilityAvailability.UNKNOWN,
                ContactGroupCapability.CREATE to ContactGroupCapabilityAvailability.UNKNOWN,
                ContactGroupCapability.UPDATE to ContactGroupCapabilityAvailability.UNKNOWN,
                ContactGroupCapability.DELETE to ContactGroupCapabilityAvailability.UNKNOWN,
                ContactGroupCapability.ASSIGN_EMAILS to ContactGroupCapabilityAvailability.UNKNOWN,
            ),
            publicCoreSurface = mapOf(
                ContactGroupCapability.LIST to ContactGroupPublicCoreSurface.EXPOSED,
                ContactGroupCapability.CREATE to ContactGroupPublicCoreSurface.EXPOSED,
                ContactGroupCapability.UPDATE to ContactGroupPublicCoreSurface.EXPOSED,
                ContactGroupCapability.DELETE to ContactGroupPublicCoreSurface.EXPOSED,
                ContactGroupCapability.ASSIGN_EMAILS to ContactGroupPublicCoreSurface.NOT_EXPOSED,
            ),
        )
    }
}

sealed interface EmailLabelMutation {
    /**
     * One serial bulk request is capped at the nominal-high 300-contact profile. Larger changes
     * MUST be split into separately budgeted, reconcilable batches by the future sync engine.
     */
    companion object {
        const val MAX_EMAIL_IDS_PER_MUTATION: Int = 300
    }

    class Assign(
        val groupId: RemoteGroupId,
        emailIds: List<RemoteEmailId>,
    ) : EmailLabelMutation {
        val emailIds: List<RemoteEmailId> = emailIds.toList()

        init {
            require(this.emailIds.isNotEmpty())
            require(this.emailIds.size <= MAX_EMAIL_IDS_PER_MUTATION)
            require(this.emailIds.distinct().size == this.emailIds.size)
        }

        override fun toString(): String = "EmailLabelMutation.Assign(REDACTED, emailCount=${emailIds.size})"
    }

    class Remove(
        val groupId: RemoteGroupId,
        emailIds: List<RemoteEmailId>,
    ) : EmailLabelMutation {
        val emailIds: List<RemoteEmailId> = emailIds.toList()

        init {
            require(this.emailIds.isNotEmpty())
            require(this.emailIds.size <= MAX_EMAIL_IDS_PER_MUTATION)
            require(this.emailIds.distinct().size == this.emailIds.size)
        }

        override fun toString(): String = "EmailLabelMutation.Remove(REDACTED, emailCount=${emailIds.size})"
    }
}

/** Isolates the one capability not exposed by Proton Core's maintained label API. */
interface ProtonContactEmailLabelGateway {
    suspend fun apply(
        account: AccountScope,
        mutation: EmailLabelMutation,
    ): GatewayOutcome<Unit>
}
