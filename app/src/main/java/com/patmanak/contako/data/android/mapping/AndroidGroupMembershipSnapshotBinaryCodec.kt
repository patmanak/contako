package com.patmanak.contako.data.android.mapping

/** Versioned deterministic binary codec for [AndroidGroupMembershipSnapshot]. */
internal object AndroidGroupMembershipSnapshotBinaryCodec {
    fun encode(snapshot: AndroidGroupMembershipSnapshot): ByteArray {
        if (snapshot.locatorMappings.size > AndroidGroupMembershipSnapshot.MAX_GROUPS) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED)
        }
        val writer = AndroidGroupSnapshotBinaryWriter(MAX_TOTAL_BYTES)
        writer.writeInt(MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeString(snapshot.accountId, MAX_ID_BYTES)
        writer.writeString(snapshot.canonicalContactId, MAX_ID_BYTES)
        writer.writeString(snapshot.membershipAvailability.name, MAX_ENUM_BYTES)
        writer.writeNullableString(snapshot.preferredEmailValueId, MAX_ID_BYTES)
        writer.writeInt(snapshot.locatorMappings.size)
        snapshot.locatorMappings.forEach { mapping ->
            writer.writeString(mapping.canonicalGroupId, MAX_ID_BYTES)
            writer.writeLong(mapping.groupRowLocator)
            writer.writeLong(mapping.dataRowLocator)
        }
        return writer.toByteArray()
    }

    fun decode(encoded: ByteArray): AndroidGroupMembershipSnapshot {
        if (encoded.size > MAX_TOTAL_BYTES) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED)
        val reader = AndroidGroupSnapshotBinaryReader(encoded)
        if (reader.readInt() != MAGIC) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        if (reader.readInt() != FORMAT_VERSION) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.UNSUPPORTED_VERSION)
        }
        val accountId = reader.readString(MAX_ID_BYTES)
        val canonicalContactId = reader.readString(MAX_ID_BYTES)
        val availability = reader.readEnum(AndroidGroupMembershipAvailability.entries, MAX_ENUM_BYTES)
        val preferredEmailValueId = reader.readNullableString(MAX_ID_BYTES)
        val count = reader.readCount(AndroidGroupMembershipSnapshot.MAX_GROUPS)
        val mappings = ArrayList<AndroidGroupMembershipLocatorMapping>(count)
        val groupLocators = mutableSetOf<Long>()
        val dataLocators = mutableSetOf<Long>()
        var previousCanonicalGroupId: String? = null
        repeat(count) {
            val canonicalGroupId = reader.readString(MAX_ID_BYTES)
            when {
                canonicalGroupId == previousCanonicalGroupId ->
                    groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.DUPLICATE_ENTRY)
                previousCanonicalGroupId != null && canonicalGroupId < requireNotNull(previousCanonicalGroupId) ->
                    groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
            }
            previousCanonicalGroupId = canonicalGroupId
            val groupRowLocator = reader.readLong()
            val dataRowLocator = reader.readLong()
            if (!groupLocators.add(groupRowLocator) || !dataLocators.add(dataRowLocator)) {
                groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.DUPLICATE_ENTRY)
            }
            mappings += try {
                AndroidGroupMembershipLocatorMapping(canonicalGroupId, groupRowLocator, dataRowLocator)
            } catch (_: IllegalArgumentException) {
                groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
            }
        }
        if (!reader.isExhausted()) groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        return try {
            AndroidGroupMembershipSnapshot.create(
                accountId = accountId,
                canonicalContactId = canonicalContactId,
                preferredEmailValueId = preferredEmailValueId,
                membershipAvailability = availability,
                locatorMappings = mappings,
            )
        } catch (_: IllegalArgumentException) {
            groupSnapshotCodecFail(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT)
        }
    }

    fun integrityFingerprint(encoded: ByteArray): AndroidSnapshotIntegrityFingerprint =
        encodedIntegrityFingerprint("contako-android-membership-baseline-v1", encoded, MAX_TOTAL_BYTES)

    private const val MAGIC = 0x434D534E // CMSN
    private const val FORMAT_VERSION = 1
    private const val MAX_ENUM_BYTES = 64
    private const val MAX_ID_BYTES = 4_096
    private const val MAX_TOTAL_BYTES = 3 * 1_024 * 1_024
}
