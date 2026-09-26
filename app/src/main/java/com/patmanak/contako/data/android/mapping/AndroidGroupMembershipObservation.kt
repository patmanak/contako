package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupMembershipRow
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRow
import com.patmanak.contako.data.android.provider.GroupMembershipRows
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class AndroidGroupMembershipDecodeFailure {
    BOUND_EXCEEDED,
    DUPLICATE_PROVIDER_ROW,
    DUPLICATE_MEMBERSHIP,
    MIXED_RAW_CONTACTS,
    UNKNOWN_GROUP,
    DELETED_GROUP,
    UNTRUSTED_GROUP_IDENTITY,
    MALFORMED_MEMBERSHIP_ROW,
}

internal class AndroidGroupMembershipDecodeException(
    val category: AndroidGroupMembershipDecodeFailure,
) : IllegalArgumentException("Android group membership decode failed: ${category.name}")

/** Domain-specific digest for a complete contact-plus-memberships observation. */
internal class AndroidCompositeObservationFingerprint(val sha256Hex: String) {
    init {
        require(Regex("[0-9a-f]{64}").matches(sha256Hex))
    }

    override fun equals(other: Any?): Boolean =
        other is AndroidCompositeObservationFingerprint && other.sha256Hex == sha256Hex

    override fun hashCode(): Int = sha256Hex.hashCode()
    override fun toString(): String = "AndroidCompositeObservationFingerprint(REDACTED)"
}

/** Durable group identity expected from the current account/provider epoch ledger. */
internal data class AndroidTrustedGroupBinding(
    val account: AccountScope,
    val androidAccountName: AndroidProviderAccountName,
    val providerEpoch: Long,
    val canonicalGroupId: String,
    val groupRowId: Long,
    val expectedProviderVersion: Long,
    val sourceIdentity: String?,
) {
    init {
        require(providerEpoch >= 0)
        require(canonicalGroupId.isNotBlank())
        require(canonicalGroupId.utf8Size() <= MAX_ID_BYTES)
        require(groupRowId > 0)
        require(expectedProviderVersion >= 0)
        require(sourceIdentity == null || sourceIdentity.isNotBlank())
        require(sourceIdentity == null || sourceIdentity.utf8Size() <= MAX_ID_BYTES)
    }

    override fun toString(): String =
        "AndroidTrustedGroupBinding(REDACTED, providerEpoch=$providerEpoch)"

    private companion object {
        const val MAX_ID_BYTES = 4_096
    }
}

/** Exhaustive account/epoch catalog assembled only from a valid complete page chain. */
internal class AndroidCompleteGroupCatalog private constructor(
    val account: AccountScope,
    val androidAccountName: AndroidProviderAccountName,
    val providerEpoch: Long,
    val rows: List<AndroidOwnedGroupRow>,
) {
    init {
        require(providerEpoch >= 0)
        require(rows.size <= MAX_GROUPS)
        require(rows.map(AndroidOwnedGroupRow::groupRowId).distinct().size == rows.size)
    }

    override fun toString(): String =
        "AndroidCompleteGroupCatalog(REDACTED, providerEpoch=$providerEpoch, rowCount=${rows.size})"

    companion object {
        fun fromExhaustivePages(
            account: AccountScope,
            providerEpoch: Long,
            pages: List<AndroidOwnedGroupRowPage>,
        ): AndroidCompleteGroupCatalog {
            require(providerEpoch >= 0)
            require(pages.isNotEmpty() && pages.size <= MAX_PAGES)
            val androidAccountName = pages.first().accountName
            require(pages.all { it.accountName == androidAccountName })
            var expectedAfter = 0L
            val rows = ArrayList<AndroidOwnedGroupRow>()
            pages.forEachIndexed { index, page ->
                require(page.requestedAfterGroupRowId == expectedAfter)
                require(index == pages.lastIndex || page.nextAfterGroupRowId != null)
                rows += page.groups
                require(rows.size <= MAX_GROUPS)
                expectedAfter = page.nextAfterGroupRowId ?: 0L
            }
            require(pages.last().nextAfterGroupRowId == null)
            require(rows.map(AndroidOwnedGroupRow::groupRowId).distinct().size == rows.size)
            return AndroidCompleteGroupCatalog(account, androidAccountName, providerEpoch, rows)
        }

        private const val MAX_GROUPS = 512
        private const val MAX_PAGES = MAX_GROUPS + 1
    }
}

internal data class AndroidResolvedGroupMemberships(
    val canonicalGroupIds: Set<String>,
) {
    init {
        require(canonicalGroupIds.none(String::isBlank))
        require(canonicalGroupIds.all { it.utf8Size() <= MAX_ID_BYTES })
        require(canonicalGroupIds.size <= MAX_GROUPS)
    }

    override fun toString(): String =
        "AndroidResolvedGroupMemberships(REDACTED, groupCount=${canonicalGroupIds.size})"

    private companion object {
        const val MAX_GROUPS = 512
        const val MAX_ID_BYTES = 4_096
    }
}

/** Resolves raw provider locators only through complete, ledger-backed group identities. */
internal class AndroidGroupMembershipObservationDecoder {
    fun decode(
        catalog: AndroidCompleteGroupCatalog,
        expectedRawContactId: Long,
        memberships: GroupMembershipRows,
        trustedBindings: List<AndroidTrustedGroupBinding>,
    ): AndroidResolvedGroupMemberships {
        if (memberships.rows.size > MAX_MEMBERSHIPS) {
            fail(AndroidGroupMembershipDecodeFailure.BOUND_EXCEEDED)
        }
        val decodedRows = memberships.rows.map { row ->
            val encodedGroupLocator = row.stringSlots.firstOrNull()
                ?: fail(AndroidGroupMembershipDecodeFailure.MALFORMED_MEMBERSHIP_ROW)
            val groupRowLocator = encodedGroupLocator.toLongOrNull()
                ?.takeIf { it > 0 && it.toString() == encodedGroupLocator }
                ?: fail(AndroidGroupMembershipDecodeFailure.MALFORMED_MEMBERSHIP_ROW)
            AndroidOwnedGroupMembershipRow(
                dataRowId = row.dataRowId,
                rawContactId = row.rawContactId,
                groupRowId = groupRowLocator,
            )
        }
        return decodeRows(catalog, expectedRawContactId, decodedRows, trustedBindings)
    }

    private fun decodeRows(
        catalog: AndroidCompleteGroupCatalog,
        expectedRawContactId: Long,
        memberships: List<AndroidOwnedGroupMembershipRow>,
        trustedBindings: List<AndroidTrustedGroupBinding>,
    ): AndroidResolvedGroupMemberships {
        if (expectedRawContactId <= 0 || memberships.any { it.rawContactId != expectedRawContactId }) {
            fail(AndroidGroupMembershipDecodeFailure.MIXED_RAW_CONTACTS)
        }
        if (catalog.rows.size > MAX_GROUPS || trustedBindings.size > MAX_GROUPS ||
            memberships.size > MAX_MEMBERSHIPS
        ) {
            fail(AndroidGroupMembershipDecodeFailure.BOUND_EXCEEDED)
        }
        if (trustedBindings.any {
                it.account != catalog.account ||
                    it.androidAccountName != catalog.androidAccountName ||
                    it.providerEpoch != catalog.providerEpoch
            }
        ) {
            fail(AndroidGroupMembershipDecodeFailure.UNTRUSTED_GROUP_IDENTITY)
        }
        if (catalog.rows.map(AndroidOwnedGroupRow::groupRowId).distinct().size != catalog.rows.size ||
            trustedBindings.map(AndroidTrustedGroupBinding::groupRowId).distinct().size != trustedBindings.size ||
            trustedBindings.map(AndroidTrustedGroupBinding::canonicalGroupId).distinct().size != trustedBindings.size ||
            memberships.map(AndroidOwnedGroupMembershipRow::dataRowId).distinct().size != memberships.size
        ) {
            fail(AndroidGroupMembershipDecodeFailure.DUPLICATE_PROVIDER_ROW)
        }
        if (memberships.map(AndroidOwnedGroupMembershipRow::groupRowId).distinct().size != memberships.size) {
            fail(AndroidGroupMembershipDecodeFailure.DUPLICATE_MEMBERSHIP)
        }
        val catalogByLocator = catalog.rows.associateBy(AndroidOwnedGroupRow::groupRowId)
        val bindingByLocator = trustedBindings.associateBy(AndroidTrustedGroupBinding::groupRowId)
        val resolved = memberships.map { membership ->
            val group = catalogByLocator[membership.groupRowId]
                ?: fail(AndroidGroupMembershipDecodeFailure.UNKNOWN_GROUP)
            if (group.deleted) fail(AndroidGroupMembershipDecodeFailure.DELETED_GROUP)
            val binding = bindingByLocator[membership.groupRowId]
                ?: fail(AndroidGroupMembershipDecodeFailure.UNTRUSTED_GROUP_IDENTITY)
            if (group.canonicalGroupIdClaim != binding.canonicalGroupId ||
                group.version != binding.expectedProviderVersion ||
                group.sourceIdentity != binding.sourceIdentity
            ) {
                fail(AndroidGroupMembershipDecodeFailure.UNTRUSTED_GROUP_IDENTITY)
            }
            binding.canonicalGroupId
        }.toSortedSet()
        return AndroidResolvedGroupMemberships(resolved)
    }

    private fun fail(category: AndroidGroupMembershipDecodeFailure): Nothing =
        throw AndroidGroupMembershipDecodeException(category)

    private companion object {
        const val MAX_GROUPS = 512
        const val MAX_MEMBERSHIPS = 128
    }
}

/** Provider-neutral observation whose fingerprint excludes every replaceable Android locator. */
internal class AndroidContactObservation private constructor(
    val contact: AndroidContactSnapshot,
    val preferredEmailValueId: String?,
    val membershipAvailability: AndroidGroupMembershipAvailability,
    val canonicalGroupIds: Set<String>,
) {
    init {
        require(
            (membershipAvailability == AndroidGroupMembershipAvailability.NO_EMAIL) ==
                (preferredEmailValueId == null),
        )
        require(preferredEmailValueId == null || preferredEmailValueId.isNotBlank())
        require(preferredEmailValueId == null || preferredEmailValueId.utf8Size() <= MAX_ID_BYTES)
        require(canonicalGroupIds.none(String::isBlank))
        require(canonicalGroupIds.all { it.utf8Size() <= MAX_ID_BYTES })
        require(canonicalGroupIds.size <= MAX_GROUPS)
    }

    fun fingerprint(mapper: CanonicalAndroidContactMapper): AndroidCompositeObservationFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.putSizedUtf8("contako-android-contact-observation-v1")
        digest.putSizedUtf8(mapper.fingerprint(contact).sha256Hex)
        digest.putSizedUtf8(membershipAvailability.name)
        digest.putSizedUtf8(preferredEmailValueId.orEmpty())
        canonicalGroupIds.sorted().forEach { groupId -> digest.putSizedUtf8(groupId) }
        return AndroidCompositeObservationFingerprint(digest.digest().joinToString("") { "%02x".format(it) })
    }

    override fun toString(): String =
        "AndroidContactObservation(REDACTED, availability=$membershipAvailability, " +
            "groupCount=${canonicalGroupIds.size})"

    private fun MessageDigest.putSizedUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    companion object {
        fun fromCanonicalContext(
            contact: AndroidContactSnapshot,
            canonical: CanonicalContact,
            observedCanonicalGroupIds: Set<String>,
        ): AndroidContactObservation {
            require(contact.canonicalContactId == canonical.id)
            val preferredEmail = CanonicalPrimaryValuePolicy.preferredEmail(canonical)
            return AndroidContactObservation(
                contact = contact,
                preferredEmailValueId = preferredEmail?.id,
                membershipAvailability = if (preferredEmail == null) {
                    AndroidGroupMembershipAvailability.NO_EMAIL
                } else {
                    AndroidGroupMembershipAvailability.AVAILABLE
                },
                canonicalGroupIds = observedCanonicalGroupIds.toSet(),
            )
        }

        const val MAX_GROUPS = 512
        const val MAX_ID_BYTES = 4_096
    }
}

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
