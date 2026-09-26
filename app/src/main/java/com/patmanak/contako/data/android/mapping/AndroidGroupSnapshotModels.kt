package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.AndroidProjectionFingerprint
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.policy.CanonicalPrimaryValuePolicy
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Digest of the complete encoded baseline, including replaceable provider locators. */
internal class AndroidSnapshotIntegrityFingerprint(val sha256Hex: String) {
    init {
        require(SHA_256_HEX.matches(sha256Hex))
    }

    override fun equals(other: Any?): Boolean =
        other is AndroidSnapshotIntegrityFingerprint && other.sha256Hex == sha256Hex

    override fun hashCode(): Int = sha256Hex.hashCode()
    override fun toString(): String = "AndroidSnapshotIntegrityFingerprint(REDACTED)"

    private companion object {
        val SHA_256_HEX = Regex("[0-9a-f]{64}")
    }
}

internal enum class AndroidGroupSnapshotContextFailure {
    ACCOUNT_SCOPE_MISMATCH,
    CONTACT_IDENTITY_MISMATCH,
    PREFERRED_EMAIL_CONTEXT_MISMATCH,
}

/** Category-only diagnostics; canonical values and identities remain redacted. */
internal class AndroidGroupSnapshotContextException(
    val category: AndroidGroupSnapshotContextFailure,
) : IllegalArgumentException("Android group snapshot context failure: ${category.name}")

/** Provider-neutral semantic baseline for one canonical group. */
internal data class AndroidGroupSnapshot(
    val accountId: String,
    val canonicalGroupId: String,
    val title: String,
    val isVisible: Boolean,
) {
    init {
        requireBoundedSnapshotIdentity(accountId)
        requireBoundedSnapshotIdentity(canonicalGroupId)
        requireBoundedSnapshotText(title, MAX_TITLE_BYTES)
    }

    fun semanticFingerprint(): AndroidProjectionFingerprint = semanticDigest(
        domain = "contako-android-group-snapshot-v1",
        values = listOf(accountId, canonicalGroupId, title, if (isVisible) "1" else "0"),
    )

    override fun toString(): String = "AndroidGroupSnapshot(REDACTED, isVisible=$isVisible)"

    private companion object {
        const val MAX_TITLE_BYTES = 16 * 1_024
    }
}

/** Replaceable provider locators for one resolved canonical group membership. */
internal data class AndroidGroupMembershipLocatorMapping(
    val canonicalGroupId: String,
    val groupRowLocator: Long,
    val dataRowLocator: Long,
) {
    init {
        requireBoundedSnapshotIdentity(canonicalGroupId)
        require(groupRowLocator > 0)
        require(dataRowLocator > 0)
    }

    override fun toString(): String = "AndroidGroupMembershipLocatorMapping(REDACTED)"
}

/**
 * Provider-neutral membership baseline.
 *
 * [locatorMappings] is retained for provider reconciliation, but every replaceable locator is
 * deliberately excluded from [semanticFingerprint]. The factory sorts and defensively copies the
 * mappings so encoding and fingerprinting never depend on provider/query encounter order.
 */
internal class AndroidGroupMembershipSnapshot private constructor(
    val accountId: String,
    val canonicalContactId: String,
    val preferredEmailValueId: String?,
    val membershipAvailability: AndroidGroupMembershipAvailability,
    val locatorMappings: List<AndroidGroupMembershipLocatorMapping>,
) {
    val canonicalGroupIds: List<String> = locatorMappings.map(AndroidGroupMembershipLocatorMapping::canonicalGroupId)

    init {
        requireBoundedSnapshotIdentity(accountId)
        requireBoundedSnapshotIdentity(canonicalContactId)
        require(
            (membershipAvailability == AndroidGroupMembershipAvailability.NO_EMAIL) ==
                (preferredEmailValueId == null),
        )
        preferredEmailValueId?.let(::requireBoundedSnapshotIdentity)
        require(locatorMappings.size <= MAX_GROUPS)
        require(canonicalGroupIds.zipWithNext().all { (left, right) -> left < right })
        require(locatorMappings.map(AndroidGroupMembershipLocatorMapping::groupRowLocator).distinct().size ==
            locatorMappings.size)
        require(locatorMappings.map(AndroidGroupMembershipLocatorMapping::dataRowLocator).distinct().size ==
            locatorMappings.size)
    }

    fun semanticFingerprint(): AndroidProjectionFingerprint = semanticDigest(
        domain = "contako-android-group-membership-snapshot-v1",
        values = buildList {
            add(accountId)
            add(canonicalContactId)
            add(membershipAvailability.name)
            add(preferredEmailValueId.orEmpty())
            addAll(canonicalGroupIds)
        },
    )

    /**
     * Revalidates the canonical context immediately before applying a decoded baseline.
     * A stored preferred-email identity MUST NOT authorize a mutation after canonical data changed.
     */
    fun requireCurrentCanonicalContext(contact: CanonicalContact) {
        if (contact.accountId != accountId) contextFail(AndroidGroupSnapshotContextFailure.ACCOUNT_SCOPE_MISMATCH)
        if (contact.id != canonicalContactId) {
            contextFail(AndroidGroupSnapshotContextFailure.CONTACT_IDENTITY_MISMATCH)
        }
        val currentPreferredEmailId = CanonicalPrimaryValuePolicy.preferredEmail(contact)?.id
        val currentAvailability = if (currentPreferredEmailId == null) {
            AndroidGroupMembershipAvailability.NO_EMAIL
        } else {
            AndroidGroupMembershipAvailability.AVAILABLE
        }
        if (currentPreferredEmailId != preferredEmailValueId || currentAvailability != membershipAvailability) {
            contextFail(AndroidGroupSnapshotContextFailure.PREFERRED_EMAIL_CONTEXT_MISMATCH)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AndroidGroupMembershipSnapshot &&
            accountId == other.accountId &&
            canonicalContactId == other.canonicalContactId &&
            preferredEmailValueId == other.preferredEmailValueId &&
            membershipAvailability == other.membershipAvailability &&
            locatorMappings == other.locatorMappings

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + canonicalContactId.hashCode()
        result = 31 * result + (preferredEmailValueId?.hashCode() ?: 0)
        result = 31 * result + membershipAvailability.hashCode()
        result = 31 * result + locatorMappings.hashCode()
        return result
    }

    override fun toString(): String =
        "AndroidGroupMembershipSnapshot(REDACTED, availability=$membershipAvailability, " +
            "groupCount=${locatorMappings.size})"

    companion object {
        fun create(
            accountId: String,
            canonicalContactId: String,
            preferredEmailValueId: String?,
            membershipAvailability: AndroidGroupMembershipAvailability,
            locatorMappings: Collection<AndroidGroupMembershipLocatorMapping>,
        ): AndroidGroupMembershipSnapshot = AndroidGroupMembershipSnapshot(
            accountId = accountId,
            canonicalContactId = canonicalContactId,
            preferredEmailValueId = preferredEmailValueId,
            membershipAvailability = membershipAvailability,
            locatorMappings = locatorMappings.sortedBy(AndroidGroupMembershipLocatorMapping::canonicalGroupId),
        )

        const val MAX_GROUPS = 128
    }
}

private fun contextFail(category: AndroidGroupSnapshotContextFailure): Nothing =
    throw AndroidGroupSnapshotContextException(category)

private const val MAX_ID_BYTES = 4_096

private fun requireBoundedSnapshotIdentity(value: String) {
    require(value.isNotBlank())
    requireBoundedSnapshotText(value, MAX_ID_BYTES)
}

private fun requireBoundedSnapshotText(value: String, maximumBytes: Int) {
    val bytes = try {
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
            .remaining()
    } catch (_: Exception) {
        throw IllegalArgumentException("Snapshot text is not valid Unicode")
    }
    require(bytes <= maximumBytes)
}

private fun semanticDigest(domain: String, values: List<String>): AndroidProjectionFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.putSizedUtf8(domain)
    values.forEach(digest::putSizedUtf8)
    return AndroidProjectionFingerprint(
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
    )
}

private fun MessageDigest.putSizedUtf8(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}
