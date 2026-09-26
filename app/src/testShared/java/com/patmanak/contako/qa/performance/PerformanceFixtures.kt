package com.patmanak.contako.qa.performance

import java.security.MessageDigest
import kotlin.random.Random

internal enum class PerformanceFixtureProfile(
    val fixtureId: String,
    val contactCount: Int,
    val groupCount: Int,
) {
    EMPTY("FX-P-000", 0, 0),
    SMALL("FX-P-100", 100, 7),
    NOMINAL("FX-P-300", 300, 20),
    STRESS("FX-P-1000", 1_000, 60),
    ROBUSTNESS("FX-P-5000", 5_000, 200),
    PHOTO("FX-P-PHOTO", 300, 20),
    VCARD("FX-P-VCARD", 300, 20),
}

internal data class PerformanceFixture(
    val profile: PerformanceFixtureProfile,
    val seed: Long,
    val contacts: List<PerformanceContact>,
    val groups: List<PerformanceGroup>,
) {
    val canonicalDigest: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        contacts.forEach { contact ->
            digest.update(contact.canonicalBytes())
        }
        groups.forEach { group ->
            digest.update("${group.id}|${group.memberIndexes.joinToString(",")};".toByteArray())
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal data class PerformanceContact(
    val id: String,
    val displayName: String,
    val email: String,
    val phone: String,
    val photoBytes: ByteArray,
    val opaqueVCardProperty: String,
) {
    private fun text() = "$id|$displayName|$email|$phone|$opaqueVCardProperty|"

    fun canonicalBytes(): ByteArray = text().toByteArray() + photoBytes

    override fun equals(other: Any?): Boolean = other is PerformanceContact &&
        text() == other.text() && photoBytes.contentEquals(other.photoBytes)

    override fun hashCode(): Int = 31 * text().hashCode() + photoBytes.contentHashCode()
}

internal data class PerformanceGroup(val id: String, val memberIndexes: List<Int>)

internal object PerformanceFixtureGenerator {
    const val DEFAULT_SEED = 0x07C07A6BL
    const val MAX_PHOTO_BYTES = 256 * 1_024
    const val MAX_VCARD_PROPERTY_CHARS = 16 * 1_024

    fun generate(
        profile: PerformanceFixtureProfile,
        seed: Long = DEFAULT_SEED,
    ): PerformanceFixture {
        val random = Random(seed xor profile.fixtureId.hashCode().toLong())
        val contacts = List(profile.contactCount) { index ->
            val suffix = random.nextInt().toUInt().toString(16).padStart(8, '0')
            val photoSize = if (profile == PerformanceFixtureProfile.PHOTO) {
                32 * 1_024 + (index % 8) * 32 * 1_024
            } else {
                0
            }
            val opaqueSize = if (profile == PerformanceFixtureProfile.VCARD) {
                2 * 1_024 + (index % 8) * 2 * 1_024
            } else {
                24
            }
            PerformanceContact(
                id = "p-${index.toString().padStart(5, '0')}-$suffix",
                displayName = "Synthetic ${index.toString().padStart(5, '0')}",
                email = "fixture-$index-$suffix@example.invalid",
                phone = "+1555${index.toString().padStart(7, '0')}",
                photoBytes = ByteArray(photoSize) { random.nextInt(0, 256).toByte() },
                opaqueVCardProperty = buildString(opaqueSize.coerceAtMost(MAX_VCARD_PROPERTY_CHARS)) {
                    repeat(opaqueSize.coerceAtMost(MAX_VCARD_PROPERTY_CHARS)) {
                        append(('A'.code + random.nextInt(26)).toChar())
                    }
                },
            )
        }
        val groups = List(profile.groupCount) { groupIndex ->
            val members = if (contacts.isEmpty()) emptyList() else List(10) {
                (groupIndex * 10 + it) % contacts.size
            }
            PerformanceGroup("g-${groupIndex.toString().padStart(3, '0')}", members)
        }
        return PerformanceFixture(profile, seed, contacts, groups)
    }
}
