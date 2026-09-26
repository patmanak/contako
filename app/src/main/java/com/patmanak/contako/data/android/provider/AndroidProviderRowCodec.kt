package com.patmanak.contako.data.android.provider

import android.provider.ContactsContract
import com.patmanak.contako.data.android.mapping.AndroidComponent
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidLinkedValueRole
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidSemanticType
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal enum class AndroidProviderRowCodecFailure {
    ACCOUNT_SCOPE_MISMATCH,
    MIXED_RAW_CONTACTS,
    DUPLICATE_PROVIDER_ROW,
    DUPLICATE_CANONICAL_IDENTITY,
    DUPLICATE_SINGLETON,
    GROUP_MEMBERSHIP_REQUIRES_SEPARATE_CODEC,
    UNSUPPORTED_ROW_KIND,
    MALFORMED_ROW,
    PHOTO_BINARY_MISSING,
    UNEXPECTED_BINARY,
    MALFORMED_TYPE,
    MALFORMED_DATE,
    MALFORMED_ORDER,
    MALFORMED_LINKED_IDENTITIES,
    IDENTITY_BINDING_DIVERGENCE,
    VALUE_ID_ALLOCATION_FAILED,
    PHOTO_CAPTURE_FAILED,
    BOUND_EXCEEDED,
}

/** Category-only failure: provider payloads and identifiers are never included in diagnostics. */
internal class AndroidProviderRowCodecException(
    val category: AndroidProviderRowCodecFailure,
) : IllegalArgumentException("Android provider row decode failed: ${category.name}")

internal data class AndroidDurableValueBinding(
    val canonicalValueId: String,
    val linkedCanonicalValueIds: Map<AndroidLinkedValueRole, String>,
) {
    override fun toString(): String = "AndroidDurableValueBinding(REDACTED)"
}

internal sealed interface AndroidProviderIdentityResolution {
    data class Bound(
        val bindingsByProviderRowId: Map<Long, AndroidDurableValueBinding>,
    ) : AndroidProviderIdentityResolution
    data object Divergence : AndroidProviderIdentityResolution
}

internal data class AndroidProviderIdentityClaim(
    val accountName: AndroidProviderAccountName,
    val canonicalContactId: String,
    val rawContactId: Long,
    val providerRowId: Long,
    val kind: AndroidRowKind,
    val claimedCanonicalValueId: String?,
    val claimedLinkedCanonicalValueIds: Map<AndroidLinkedValueRole, String>,
) {
    override fun toString(): String = "AndroidProviderIdentityClaim(REDACTED, kind=$kind)"
}

internal fun interface AndroidProviderIdentityResolver {
    /**
     * Validates an existing durable row binding or atomically persists a newly allocated identity.
     * DATA_SYNC claims are untrusted input. The provider row ID is a locator, never identity.
     * The whole claim batch MUST be validated and persisted atomically. New bindings MUST be
     * protected by durable uniqueness constraints; partial persistence on rejection is forbidden.
     */
    fun resolve(claims: List<AndroidProviderIdentityClaim>): AndroidProviderIdentityResolution
}

internal fun interface AndroidDurablePhotoCapture {
    /** Copies provider bytes to durable app-private storage and returns an immutable reference. */
    fun capture(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        providerRowId: Long,
        bytes: ByteArray,
    ): String
}

/** Strict decoder for one already account-scoped raw contact. */
internal class AndroidProviderRowCodec(
    private val identityResolver: AndroidProviderIdentityResolver,
    private val photoCapture: AndroidDurablePhotoCapture,
    /**
     * Projection verification fallback for providers that retain `display_photo` but clear the
     * inline Data/Photo thumbnail. Observation/ingestion composition MUST keep the default so an
     * Android-originated photo can never be accepted without captured bytes.
     */
    private val missingPhotoReference: () -> String? = { null },
) {
    fun decode(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        expectedRawContactId: Long,
        rows: List<AndroidOwnedDataRow>,
    ): AndroidContactSnapshot {
        if (canonicalContactId.isBlank() || canonicalContactId.length > MAX_ID_LENGTH || expectedRawContactId <= 0) {
            fail(AndroidProviderRowCodecFailure.ACCOUNT_SCOPE_MISMATCH)
        }
        if (rows.size > MAX_ROWS_PER_CONTACT) fail(AndroidProviderRowCodecFailure.BOUND_EXCEEDED)
        if (rows.any { it.rawContactId != expectedRawContactId }) {
            fail(AndroidProviderRowCodecFailure.MIXED_RAW_CONTACTS)
        }
        if (rows.map(AndroidOwnedDataRow::dataRowId).distinct().size != rows.size) {
            fail(AndroidProviderRowCodecFailure.DUPLICATE_PROVIDER_ROW)
        }

        // Complete every payload/cardinality/known-identity check before the first durable effect.
        val preflight = rows.sortedBy(AndroidOwnedDataRow::dataRowId).mapIndexed { fallbackOrder, row ->
            preflight(row, fallbackOrder)
        }
        validatePreflightCardinalities(preflight)
        validateKnownIdentities(preflight)
        val bindings = resolveIdentities(accountName, canonicalContactId, expectedRawContactId, preflight)
        validateResolvedIdentities(bindings)
        val decoded = preflight.zip(bindings).map { (validated, binding) ->
            decodeRow(accountName, canonicalContactId, validated, binding)
        }
        validateIdentities(decoded)
        return AndroidContactSnapshot(canonicalContactId, decoded)
    }

    private fun preflight(row: AndroidOwnedDataRow, fallbackOrder: Int): PreflightRow {
        row.stringSlots.forEach { value ->
            if (value != null && value.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES) {
                fail(AndroidProviderRowCodecFailure.BOUND_EXCEEDED)
            }
        }
        val kind = row.kind()
        if (row.canonicalOrderMalformed) fail(AndroidProviderRowCodecFailure.MALFORMED_ORDER)
        val order = when {
            row.canonicalOrder == null -> fallbackOrder
            row.canonicalOrder < 0 -> fail(AndroidProviderRowCodecFailure.MALFORMED_ORDER)
            row.canonicalOrder > MAX_ORDER -> fail(AndroidProviderRowCodecFailure.BOUND_EXCEEDED)
            else -> row.canonicalOrder
        }
        val linked = decodeLinkedIdentities(row.linkedValueIdsEncoding, kind)
        val retainedPhotoReference = when {
            kind == AndroidRowKind.PHOTO -> {
                val bytes = row.binarySlot
                if (bytes == null) {
                    missingPhotoReference()?.takeIf { reference ->
                        reference.isNotBlank() &&
                            reference.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_BYTES
                    } ?: fail(AndroidProviderRowCodecFailure.PHOTO_BINARY_MISSING)
                } else {
                    if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) {
                        fail(AndroidProviderRowCodecFailure.BOUND_EXCEEDED)
                    }
                    null
                }
            }
            row.binarySlot != null -> fail(AndroidProviderRowCodecFailure.UNEXPECTED_BINARY)
            else -> null
        }
        validatePayload(row, kind)
        return PreflightRow(row, kind, order, linked, retainedPhotoReference)
    }

    private fun validatePayload(row: AndroidOwnedDataRow, kind: AndroidRowKind) {
        when (kind) {
            AndroidRowKind.STRUCTURED_NAME,
            AndroidRowKind.PHOTO,
            AndroidRowKind.NICKNAME,
            AndroidRowKind.NOTE,
            -> Unit
            AndroidRowKind.EMAIL -> emailType(row.typeCode(), row.slotOrNull(DATA3))
            AndroidRowKind.PHONE -> phoneType(row.typeCode(), row.slotOrNull(DATA3))
            AndroidRowKind.POSTAL_ADDRESS -> postalType(row.typeCode(), row.slotOrNull(DATA3))
            AndroidRowKind.ORGANIZATION -> organizationType(row.typeCode(), row.slotOrNull(DATA3))
            AndroidRowKind.WEBSITE -> websiteType(row.typeCode(), row.slotOrNull(DATA3))
            AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY,
            AndroidRowKind.CUSTOM_DATE,
            -> row.slot(DATA1).validatedDate()
            AndroidRowKind.RELATIONSHIP -> relationshipType(row.typeCode(), row.slotOrNull(DATA3))
        }
    }

    private fun decodeRow(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        preflight: PreflightRow,
        binding: AndroidDurableValueBinding,
    ): AndroidContactRow {
        val row = preflight.row
        val kind = preflight.kind
        val common = CommonRow(
            identity = AndroidValueIdentity(binding.canonicalValueId, row.dataRowId),
            kind = kind,
            order = preflight.order,
            primary = row.isPrimary,
            superPrimary = row.isSuperPrimary,
            linked = binding.linkedCanonicalValueIds,
        )
        return when (kind) {
            AndroidRowKind.STRUCTURED_NAME -> common.row(
                value = row.slot(DATA1),
                components = mapOf(
                    AndroidComponent.DISPLAY_NAME to row.slot(DATA1),
                    AndroidComponent.GIVEN_NAME to row.slot(DATA2),
                    AndroidComponent.FAMILY_NAME to row.slot(DATA3),
                    AndroidComponent.PREFIX to row.slot(DATA4),
                    AndroidComponent.MIDDLE_NAME to row.slot(DATA5),
                    AndroidComponent.SUFFIX to row.slot(DATA6),
                    AndroidComponent.PHONETIC_GIVEN_NAME to row.slot(DATA7),
                    AndroidComponent.PHONETIC_MIDDLE_NAME to row.slot(DATA8),
                    AndroidComponent.PHONETIC_FAMILY_NAME to row.slot(DATA9),
                ),
            )
            AndroidRowKind.EMAIL -> common.typedRow(row, emailType(row.typeCode(), row.slotOrNull(DATA3)))
            AndroidRowKind.PHONE -> common.typedRow(row, phoneType(row.typeCode(), row.slotOrNull(DATA3)))
            AndroidRowKind.POSTAL_ADDRESS -> common.typedRow(
                row,
                postalType(row.typeCode(), row.slotOrNull(DATA3)),
                components = mapOf(
                    AndroidComponent.FORMATTED_ADDRESS to row.slot(DATA1),
                    AndroidComponent.STREET to row.slot(DATA4),
                    AndroidComponent.PO_BOX to row.slot(DATA5),
                    AndroidComponent.EXTENDED_ADDRESS to row.slot(DATA6),
                    AndroidComponent.LOCALITY to row.slot(DATA7),
                    AndroidComponent.REGION to row.slot(DATA8),
                    AndroidComponent.POSTCODE to row.slot(DATA9),
                    AndroidComponent.COUNTRY to row.slot(DATA10),
                ),
            )
            AndroidRowKind.ORGANIZATION -> common.typedRow(
                row,
                organizationType(row.typeCode(), row.slotOrNull(DATA3)),
                components = mapOf(
                    AndroidComponent.COMPANY to row.slot(DATA1),
                    AndroidComponent.TITLE to row.slot(DATA4),
                    AndroidComponent.DEPARTMENT to row.slot(DATA5),
                    AndroidComponent.ROLE to row.slot(DATA6),
                ),
            )
            AndroidRowKind.PHOTO -> {
                val reference = row.binarySlot?.let { bytes ->
                    try {
                        photoCapture.capture(
                            accountName,
                            canonicalContactId,
                            row.dataRowId,
                            bytes.copyOf(),
                        )
                    } catch (_: RuntimeException) {
                        fail(AndroidProviderRowCodecFailure.PHOTO_CAPTURE_FAILED)
                    }
                } ?: requireNotNull(preflight.retainedPhotoReference)
                if (reference.isBlank() || reference.length > MAX_REFERENCE_BYTES ||
                    reference.toByteArray(StandardCharsets.UTF_8).size > MAX_REFERENCE_BYTES
                ) {
                    fail(AndroidProviderRowCodecFailure.PHOTO_CAPTURE_FAILED)
                }
                common.row(binaryReference = reference)
            }
            AndroidRowKind.NICKNAME,
            AndroidRowKind.NOTE,
            -> common.row(value = row.slot(DATA1))
            AndroidRowKind.WEBSITE -> common.typedRow(row, websiteType(row.typeCode(), row.slotOrNull(DATA3)))
            AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY,
            -> common.row(value = row.slot(DATA1).validatedDate())
            AndroidRowKind.CUSTOM_DATE -> {
                val type = if (row.typeCode() == ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM) {
                    customOrOther(row.slotOrNull(DATA3))
                } else {
                    DecodedType(AndroidSemanticType.OTHER)
                }
                common.row(
                    value = row.slot(DATA1).validatedDate(),
                    semanticType = type.semantic,
                    customLabel = type.customLabel,
                )
            }
            AndroidRowKind.RELATIONSHIP -> common.typedRow(
                row,
                relationshipType(row.typeCode(), row.slotOrNull(DATA3)),
            )
        }
    }

    private fun resolveIdentities(
        accountName: AndroidProviderAccountName,
        canonicalContactId: String,
        rawContactId: Long,
        preflight: List<PreflightRow>,
    ): List<AndroidDurableValueBinding> {
        val claims = preflight.map { validated ->
            AndroidProviderIdentityClaim(
                accountName = accountName,
                canonicalContactId = canonicalContactId,
                rawContactId = rawContactId,
                providerRowId = validated.row.dataRowId,
                kind = validated.kind,
                claimedCanonicalValueId = validated.row.canonicalValueId,
                claimedLinkedCanonicalValueIds = validated.linked,
            )
        }
        val resolution = try {
            identityResolver.resolve(claims)
        } catch (_: RuntimeException) {
            fail(AndroidProviderRowCodecFailure.VALUE_ID_ALLOCATION_FAILED)
        }
        val resolved = when (resolution) {
            is AndroidProviderIdentityResolution.Bound -> resolution.bindingsByProviderRowId
            AndroidProviderIdentityResolution.Divergence ->
                fail(AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE)
        }
        if (resolved.keys != claims.map { it.providerRowId }.toSet()) {
            fail(AndroidProviderRowCodecFailure.VALUE_ID_ALLOCATION_FAILED)
        }
        return claims.map { claim ->
            val binding = requireNotNull(resolved[claim.providerRowId])
            val claimedId = claim.claimedCanonicalValueId?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LENGTH }
            if (binding.canonicalValueId.isBlank() || binding.canonicalValueId.length > MAX_ID_LENGTH ||
                binding.canonicalValueId == claim.providerRowId.toString()
            ) {
                fail(AndroidProviderRowCodecFailure.VALUE_ID_ALLOCATION_FAILED)
            }
            if (claimedId != null && binding.canonicalValueId != claimedId) {
                fail(AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE)
            }
            if (binding.linkedCanonicalValueIds != claim.claimedLinkedCanonicalValueIds) {
                fail(AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE)
            }
            binding
        }
    }

    private fun validatePreflightCardinalities(rows: List<PreflightRow>) {
        SINGLETON_KINDS.forEach { kind ->
            val count = rows.count { it.kind == kind }
            if (count > 1) fail(AndroidProviderRowCodecFailure.DUPLICATE_SINGLETON)
        }
    }

    private fun validateKnownIdentities(rows: List<PreflightRow>) {
        val knownPrimary = rows.mapNotNull { preflight ->
            preflight.row.canonicalValueId?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LENGTH }
        }
        if (knownPrimary.distinct().size != knownPrimary.size) {
            fail(AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY)
        }
        val linked = rows.flatMap { it.linked.values }
        if (linked.distinct().size != linked.size || linked.any { it in knownPrimary }) {
            fail(AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY)
        }
    }

    private fun validateIdentities(rows: List<AndroidContactRow>) {
        if (rows.map { it.identity.canonicalValueId }.distinct().size != rows.size) {
            fail(AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY)
        }
        val allPrimary = rows.map { it.identity.canonicalValueId }.toSet()
        val allLinked = rows.flatMap { it.linkedCanonicalValueIds.values }
        if (allLinked.distinct().size != allLinked.size || allLinked.any { it in allPrimary }) {
            fail(AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY)
        }
    }

    private fun validateResolvedIdentities(bindings: List<AndroidDurableValueBinding>) {
        val primary = bindings.map { it.canonicalValueId }
        if (primary.distinct().size != primary.size) {
            fail(AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY)
        }
        val linked = bindings.flatMap { it.linkedCanonicalValueIds.values }
        if (linked.distinct().size != linked.size || linked.any { it in primary }) {
            fail(AndroidProviderRowCodecFailure.DUPLICATE_CANONICAL_IDENTITY)
        }
    }

    private fun decodeLinkedIdentities(
        encoded: String?,
        kind: AndroidRowKind,
    ): Map<AndroidLinkedValueRole, String> {
        if (encoded.isNullOrBlank()) return emptyMap()
        if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_LINKED_ENCODING_BYTES) {
            fail(AndroidProviderRowCodecFailure.BOUND_EXCEEDED)
        }
        val allowed = when (kind) {
            AndroidRowKind.STRUCTURED_NAME -> setOf(AndroidLinkedValueRole.PHONETIC_NAME)
            AndroidRowKind.ORGANIZATION -> setOf(AndroidLinkedValueRole.TITLE, AndroidLinkedValueRole.ROLE)
            else -> emptySet()
        }
        val result = linkedMapOf<AndroidLinkedValueRole, String>()
        encoded.split(',').forEach { entry ->
            val parts = entry.split(':', limit = 3)
            if (parts.size !in 2..3) fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
            val role = AndroidLinkedValueRole.entries.firstOrNull { it.name == parts[0] }
                ?: fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
            if (role !in allowed || role in result) fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
            val payload = if (parts.size == 2) {
                // Legacy writer format: ROLE:<base64url>.
                parts[1]
            } else {
                val encodedLength = parts[1].toIntOrNull()
                    ?: fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
                parts[2].also { candidate ->
                    if (encodedLength != candidate.length) {
                        fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
                    }
                }
            }
            if (payload.isEmpty() || !BASE64_URL.matches(payload)) {
                fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
            }
            val bytes = try {
                Base64.getUrlDecoder().decode(payload)
            } catch (_: IllegalArgumentException) {
                fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
            }
            val id = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: Exception) {
                fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
            }
            if (id.isBlank() || id.length > MAX_ID_LENGTH) {
                fail(AndroidProviderRowCodecFailure.MALFORMED_LINKED_IDENTITIES)
            }
            result[role] = id
        }
        return result
    }

    private fun AndroidOwnedDataRow.kind(): AndroidRowKind = when (mimeType) {
        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> AndroidRowKind.STRUCTURED_NAME
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> AndroidRowKind.EMAIL
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> AndroidRowKind.PHONE
        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> AndroidRowKind.POSTAL_ADDRESS
        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> AndroidRowKind.ORGANIZATION
        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> AndroidRowKind.PHOTO
        ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> AndroidRowKind.NICKNAME
        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> AndroidRowKind.NOTE
        ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> AndroidRowKind.WEBSITE
        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> when (typeCode()) {
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> AndroidRowKind.BIRTHDAY
            ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> AndroidRowKind.ANNIVERSARY
            ContactsContract.CommonDataKinds.Event.TYPE_OTHER,
            ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM,
            -> AndroidRowKind.CUSTOM_DATE
            else -> fail(AndroidProviderRowCodecFailure.MALFORMED_TYPE)
        }
        ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> AndroidRowKind.RELATIONSHIP
        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE ->
            fail(AndroidProviderRowCodecFailure.GROUP_MEMBERSHIP_REQUIRES_SEPARATE_CODEC)
        else -> fail(AndroidProviderRowCodecFailure.UNSUPPORTED_ROW_KIND)
    }

    private fun AndroidOwnedDataRow.typeCode(): Int? {
        val raw = slotOrNull(DATA2)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return raw.toIntOrNull() ?: fail(AndroidProviderRowCodecFailure.MALFORMED_TYPE)
    }

    private fun AndroidOwnedDataRow.slot(index: Int): String = slotOrNull(index).orEmpty()

    private fun AndroidOwnedDataRow.slotOrNull(index: Int): String? = stringSlots[index]

    private fun CommonRow.typedRow(
        source: AndroidOwnedDataRow,
        type: DecodedType,
        components: Map<AndroidComponent, String> = emptyMap(),
    ): AndroidContactRow = row(
        value = source.slot(DATA1),
        semanticType = type.semantic,
        customLabel = type.customLabel,
        components = components,
    )

    private fun CommonRow.row(
        value: String = "",
        semanticType: AndroidSemanticType = AndroidSemanticType.UNSPECIFIED,
        customLabel: String? = null,
        components: Map<AndroidComponent, String> = emptyMap(),
        binaryReference: String? = null,
    ) = AndroidContactRow(
        identity = identity,
        kind = kind,
        value = value,
        semanticType = semanticType,
        customLabel = customLabel,
        order = order,
        isPrimary = primary,
        isSuperPrimary = superPrimary,
        components = components,
        linkedCanonicalValueIds = linked,
        binaryReference = binaryReference,
    )

    private fun emailType(type: Int?, label: String?): DecodedType = when (type) {
        null -> DecodedType(AndroidSemanticType.UNSPECIFIED)
        ContactsContract.CommonDataKinds.Email.TYPE_HOME -> DecodedType(AndroidSemanticType.HOME)
        ContactsContract.CommonDataKinds.Email.TYPE_WORK -> DecodedType(AndroidSemanticType.WORK)
        ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> DecodedType(AndroidSemanticType.MOBILE)
        ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM -> customOrOther(label)
        else -> DecodedType(AndroidSemanticType.OTHER)
    }

    private fun phoneType(type: Int?, label: String?): DecodedType = when (type) {
        null -> DecodedType(AndroidSemanticType.UNSPECIFIED)
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> DecodedType(AndroidSemanticType.HOME)
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> DecodedType(AndroidSemanticType.MOBILE)
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> DecodedType(AndroidSemanticType.WORK)
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> DecodedType(AndroidSemanticType.FAX_WORK)
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> DecodedType(AndroidSemanticType.FAX_HOME)
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> DecodedType(AndroidSemanticType.PAGER)
        ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK -> DecodedType(AndroidSemanticType.CALLBACK)
        ContactsContract.CommonDataKinds.Phone.TYPE_CAR -> DecodedType(AndroidSemanticType.CAR)
        ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN -> DecodedType(AndroidSemanticType.COMPANY_MAIN)
        ContactsContract.CommonDataKinds.Phone.TYPE_ISDN -> DecodedType(AndroidSemanticType.ISDN)
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> DecodedType(AndroidSemanticType.MAIN)
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX -> DecodedType(AndroidSemanticType.OTHER_FAX)
        ContactsContract.CommonDataKinds.Phone.TYPE_RADIO -> DecodedType(AndroidSemanticType.RADIO)
        ContactsContract.CommonDataKinds.Phone.TYPE_TELEX -> DecodedType(AndroidSemanticType.TELEX)
        ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD -> DecodedType(AndroidSemanticType.TTY_TDD)
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> DecodedType(AndroidSemanticType.WORK_MOBILE)
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER -> DecodedType(AndroidSemanticType.WORK_PAGER)
        ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT -> DecodedType(AndroidSemanticType.ASSISTANT)
        ContactsContract.CommonDataKinds.Phone.TYPE_MMS -> DecodedType(AndroidSemanticType.MMS)
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> customOrOther(label)
        else -> DecodedType(AndroidSemanticType.OTHER)
    }

    private fun postalType(type: Int?, label: String?): DecodedType = when (type) {
        null -> DecodedType(AndroidSemanticType.UNSPECIFIED)
        ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> DecodedType(AndroidSemanticType.HOME)
        ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> DecodedType(AndroidSemanticType.WORK)
        ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM -> customOrOther(label)
        else -> DecodedType(AndroidSemanticType.OTHER)
    }

    private fun organizationType(type: Int?, label: String?): DecodedType = when (type) {
        null -> DecodedType(AndroidSemanticType.UNSPECIFIED)
        ContactsContract.CommonDataKinds.Organization.TYPE_WORK -> DecodedType(AndroidSemanticType.WORK)
        ContactsContract.CommonDataKinds.Organization.TYPE_CUSTOM -> customOrOther(label)
        else -> DecodedType(AndroidSemanticType.OTHER)
    }

    private fun websiteType(type: Int?, label: String?): DecodedType = when (type) {
        null -> DecodedType(AndroidSemanticType.UNSPECIFIED)
        ContactsContract.CommonDataKinds.Website.TYPE_HOME -> DecodedType(AndroidSemanticType.HOME)
        ContactsContract.CommonDataKinds.Website.TYPE_WORK -> DecodedType(AndroidSemanticType.WORK)
        ContactsContract.CommonDataKinds.Website.TYPE_BLOG -> DecodedType(AndroidSemanticType.BLOG)
        ContactsContract.CommonDataKinds.Website.TYPE_PROFILE -> DecodedType(AndroidSemanticType.PROFILE)
        ContactsContract.CommonDataKinds.Website.TYPE_FTP -> DecodedType(AndroidSemanticType.FTP)
        ContactsContract.CommonDataKinds.Website.TYPE_CUSTOM -> customOrOther(label)
        else -> DecodedType(AndroidSemanticType.OTHER)
    }

    private fun relationshipType(type: Int?, label: String?): DecodedType = when (type) {
        null -> DecodedType(AndroidSemanticType.UNSPECIFIED)
        ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT -> DecodedType(AndroidSemanticType.ASSISTANT)
        ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER -> DecodedType(AndroidSemanticType.BROTHER)
        ContactsContract.CommonDataKinds.Relation.TYPE_CHILD -> DecodedType(AndroidSemanticType.CHILD)
        ContactsContract.CommonDataKinds.Relation.TYPE_DOMESTIC_PARTNER -> DecodedType(AndroidSemanticType.DOMESTIC_PARTNER)
        ContactsContract.CommonDataKinds.Relation.TYPE_FATHER -> DecodedType(AndroidSemanticType.FATHER)
        ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND -> DecodedType(AndroidSemanticType.FRIEND)
        ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER -> DecodedType(AndroidSemanticType.MANAGER)
        ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER -> DecodedType(AndroidSemanticType.MOTHER)
        ContactsContract.CommonDataKinds.Relation.TYPE_PARENT -> DecodedType(AndroidSemanticType.PARENT)
        ContactsContract.CommonDataKinds.Relation.TYPE_PARTNER -> DecodedType(AndroidSemanticType.PARTNER)
        ContactsContract.CommonDataKinds.Relation.TYPE_REFERRED_BY -> DecodedType(AndroidSemanticType.REFERRED_BY)
        ContactsContract.CommonDataKinds.Relation.TYPE_RELATIVE -> DecodedType(AndroidSemanticType.RELATIVE)
        ContactsContract.CommonDataKinds.Relation.TYPE_SISTER -> DecodedType(AndroidSemanticType.SISTER)
        ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE -> DecodedType(AndroidSemanticType.SPOUSE)
        ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM -> customOrOther(label)
        else -> DecodedType(AndroidSemanticType.OTHER)
    }

    private fun customOrOther(label: String?): DecodedType = label?.takeIf { it.isNotBlank() }
        ?.let { DecodedType(AndroidSemanticType.CUSTOM, it) }
        ?: DecodedType(AndroidSemanticType.OTHER)

    private fun String.validatedDate(): String {
        if (!isReadableAndroidEventDate(this)) fail(AndroidProviderRowCodecFailure.MALFORMED_DATE)
        return this
    }

    private data class CommonRow(
        val identity: AndroidValueIdentity,
        val kind: AndroidRowKind,
        val order: Int,
        val primary: Boolean,
        val superPrimary: Boolean,
        val linked: Map<AndroidLinkedValueRole, String>,
    )

    private data class PreflightRow(
        val row: AndroidOwnedDataRow,
        val kind: AndroidRowKind,
        val order: Int,
        val linked: Map<AndroidLinkedValueRole, String>,
        val retainedPhotoReference: String?,
    )

    private data class DecodedType(
        val semantic: AndroidSemanticType,
        val customLabel: String? = null,
    )

    private companion object {
        const val DATA1 = 0
        const val DATA2 = 1
        const val DATA3 = 2
        const val DATA4 = 3
        const val DATA5 = 4
        const val DATA6 = 5
        const val DATA7 = 6
        const val DATA8 = 7
        const val DATA9 = 8
        const val DATA10 = 9
        const val MAX_ROWS_PER_CONTACT = 128
        const val MAX_ID_LENGTH = 4_096
        const val MAX_ORDER = 1_000_000
        const val MAX_TEXT_BYTES = 16 * 1_024
        const val MAX_LINKED_ENCODING_BYTES = 16 * 1_024
        // A canonical inline data URI remains app-private and may represent up to the loader's
        // 10 MiB decoded ceiling. It never crosses Binder as text.
        const val MAX_REFERENCE_BYTES = 15 * 1_024 * 1_024
        const val MAX_PHOTO_BYTES = 10 * 1_024 * 1_024
        val BASE64_URL = Regex("[A-Za-z0-9_-]+")
        val SINGLETON_KINDS = setOf(
            AndroidRowKind.STRUCTURED_NAME,
            AndroidRowKind.ORGANIZATION,
            AndroidRowKind.PHOTO,
            AndroidRowKind.NICKNAME,
            AndroidRowKind.NOTE,
            AndroidRowKind.BIRTHDAY,
            AndroidRowKind.ANNIVERSARY,
        )

        fun fail(category: AndroidProviderRowCodecFailure): Nothing =
            throw AndroidProviderRowCodecException(category)
    }
}
