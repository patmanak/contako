package com.patmanak.contako.data.android.mapping

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGroupMembershipSnapshotBinaryCodecTest {
    @Test
    fun `round trip sorts canonical groups and deterministically preserves locator mappings`() {
        val snapshot = available(
            mappings = listOf(mapping("group-b", 12, 102), mapping("group-a", 11, 101)),
        )

        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(snapshot)
        val decoded = AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded)

        assertEquals(snapshot, decoded)
        assertEquals(listOf("group-a", "group-b"), decoded.canonicalGroupIds)
        assertArrayEquals(encoded, AndroidGroupMembershipSnapshotBinaryCodec.encode(decoded))
        assertFalse(decoded.toString().contains("group-a"))
        assertFalse(decoded.toString().contains("private-contact"))
    }

    @Test
    fun `semantic fingerprint excludes all locators but includes semantic scope and memberships`() {
        val first = available(listOf(mapping("group-a", 11, 101), mapping("group-b", 12, 102)))
        val replacedLocators = available(listOf(mapping("group-a", 91, 901), mapping("group-b", 92, 902)))

        assertEquals(first.semanticFingerprint(), replacedLocators.semanticFingerprint())
        assertNotEquals(
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(
                AndroidGroupMembershipSnapshotBinaryCodec.encode(first),
            ),
            AndroidGroupMembershipSnapshotBinaryCodec.integrityFingerprint(
                AndroidGroupMembershipSnapshotBinaryCodec.encode(replacedLocators),
            ),
        )
        assertNotEquals(first.semanticFingerprint(), available(listOf(mapping("group-a", 11, 101))).semanticFingerprint())
        assertNotEquals(first.semanticFingerprint(), available(first.locatorMappings, accountId = "other").semanticFingerprint())
        assertNotEquals(first.semanticFingerprint(), available(first.locatorMappings, contactId = "other").semanticFingerprint())
        assertNotEquals(first.semanticFingerprint(), available(first.locatorMappings, emailId = "other").semanticFingerprint())
        assertNotEquals(first.semanticFingerprint(), noEmail(first.locatorMappings).semanticFingerprint())
    }

    @Test
    fun `decoded preferred email must still match current canonical policy before mutation`() {
        val snapshot = available(emptyList(), emailId = "email-preferred")
        snapshot.requireCurrentCanonicalContext(contactWithPreferredEmail("email-preferred"))

        assertContextFailure(AndroidGroupSnapshotContextFailure.ACCOUNT_SCOPE_MISMATCH) {
            snapshot.requireCurrentCanonicalContext(contactWithPreferredEmail("email-preferred", accountId = "other"))
        }
        assertContextFailure(AndroidGroupSnapshotContextFailure.CONTACT_IDENTITY_MISMATCH) {
            snapshot.requireCurrentCanonicalContext(contactWithPreferredEmail("email-preferred", contactId = "other"))
        }
        assertContextFailure(AndroidGroupSnapshotContextFailure.PREFERRED_EMAIL_CONTEXT_MISMATCH) {
            snapshot.requireCurrentCanonicalContext(contactWithPreferredEmail("email-changed"))
        }
        assertContextFailure(AndroidGroupSnapshotContextFailure.PREFERRED_EMAIL_CONTEXT_MISMATCH) {
            snapshot.requireCurrentCanonicalContext(CanonicalContact("private-account", "private-contact"))
        }

        noEmail(emptyList()).requireCurrentCanonicalContext(CanonicalContact("private-account", "private-contact"))
    }

    @Test
    fun `no-email snapshot preserves impossible observed memberships without inventing an email`() {
        val snapshot = noEmail(listOf(mapping("group-a", 11, 101)))

        val decoded = AndroidGroupMembershipSnapshotBinaryCodec.decode(
            AndroidGroupMembershipSnapshotBinaryCodec.encode(snapshot),
        )

        assertEquals(AndroidGroupMembershipAvailability.NO_EMAIL, decoded.membershipAvailability)
        assertEquals(null, decoded.preferredEmailValueId)
        assertEquals(listOf("group-a"), decoded.canonicalGroupIds)
    }

    @Test
    fun `every strict prefix truncation is rejected with redacted category diagnostics`() {
        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(
            available(listOf(mapping("private-group", 11, 101))),
        )

        for (size in 0 until encoded.size) {
            assertFailure(AndroidGroupSnapshotCodecFailure.TRUNCATED) {
                AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.copyOf(size))
            }
        }
    }

    @Test
    fun `magic version enum trailing bytes utf8 order and duplicates are rejected`() {
        val encoded = AndroidGroupMembershipSnapshotBinaryCodec.encode(
            available(listOf(mapping("group-a", 11, 101), mapping("group-b", 12, 102))),
        )
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.copyOf().also { it[0] = 0 })
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.UNSUPPORTED_VERSION) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.copyOf().also { writeInt(it, 4, 2) })
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.UNKNOWN_ENUM) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.replaceAsciiOnce("AVAILABLE", "ZZZZZZZZZ"))
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded + byteArrayOf(0))
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.copyOf().also { bytes ->
                bytes[indexOf(bytes, "private-contact")] = 0xc3.toByte()
            })
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.DUPLICATE_ENTRY) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.replaceAsciiOnce("group-b", "group-a"))
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.MALFORMED_FORMAT) {
            val reordered = encoded.copyOf()
            replaceAsciiInPlace(reordered, "group-a", "group-z")
            AndroidGroupMembershipSnapshotBinaryCodec.decode(reordered)
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.DUPLICATE_ENTRY) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.replaceLongOnce(12, 11))
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.DUPLICATE_ENTRY) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encoded.replaceLongOnce(102, 101))
        }
    }

    @Test
    fun `count identity total and model duplicate bounds are enforced`() {
        val empty = available(emptyList())
        val encodedEmpty = AndroidGroupMembershipSnapshotBinaryCodec.encode(empty)
        assertFailure(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(encodedEmpty.copyOf().also {
                writeInt(it, it.size - Int.SIZE_BYTES, AndroidGroupMembershipSnapshot.MAX_GROUPS + 1)
            })
        }
        assertFailure(AndroidGroupSnapshotCodecFailure.BOUND_EXCEEDED) {
            AndroidGroupMembershipSnapshotBinaryCodec.decode(ByteArray(3 * 1_024 * 1_024 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            available(listOf(mapping("group-a", 11, 101), mapping("group-b", 11, 102)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            available(listOf(mapping("group-a", 11, 101), mapping("group-b", 12, 101)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapping("x".repeat(4_097), 11, 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapping("\uD800", 11, 101)
        }
    }

    private fun available(
        mappings: Collection<AndroidGroupMembershipLocatorMapping>,
        accountId: String = "private-account",
        contactId: String = "private-contact",
        emailId: String = "private-email",
    ) = AndroidGroupMembershipSnapshot.create(
        accountId = accountId,
        canonicalContactId = contactId,
        preferredEmailValueId = emailId,
        membershipAvailability = AndroidGroupMembershipAvailability.AVAILABLE,
        locatorMappings = mappings,
    )

    private fun noEmail(mappings: Collection<AndroidGroupMembershipLocatorMapping>) =
        AndroidGroupMembershipSnapshot.create(
            accountId = "private-account",
            canonicalContactId = "private-contact",
            preferredEmailValueId = null,
            membershipAvailability = AndroidGroupMembershipAvailability.NO_EMAIL,
            locatorMappings = mappings,
        )

    private fun mapping(canonicalGroupId: String, group: Long, data: Long) =
        AndroidGroupMembershipLocatorMapping(canonicalGroupId, group, data)

    private fun contactWithPreferredEmail(
        emailId: String,
        accountId: String = "private-account",
        contactId: String = "private-contact",
    ) = CanonicalContact(
        accountId = accountId,
        id = contactId,
        values = listOf(
            ContactValue(
                id = emailId,
                kind = ContactValueKind.EMAIL,
                value = "redacted@example.invalid",
                order = 0,
                metadata = mapOf("PREF" to "1"),
            ),
        ),
    )

    private fun assertContextFailure(expected: AndroidGroupSnapshotContextFailure, action: () -> Unit) {
        val error = assertThrows(AndroidGroupSnapshotContextException::class.java) { action() }
        assertEquals(expected, error.category)
        assertFalse(error.message.orEmpty().contains("private"))
        assertFalse(error.message.orEmpty().contains("email"))
    }

    private fun assertFailure(expected: AndroidGroupSnapshotCodecFailure, action: () -> Unit) {
        val error = assertThrows(AndroidGroupSnapshotCodecException::class.java) { action() }
        assertEquals(expected, error.category)
        assertTrue(error.message.orEmpty().contains(expected.name))
        assertFalse(error.message.orEmpty().contains("private"))
        assertFalse(error.message.orEmpty().contains("group-a"))
    }

    private fun ByteArray.replaceAsciiOnce(before: String, after: String): ByteArray = copyOf().also {
        replaceAsciiInPlace(it, before, after)
    }

    private fun replaceAsciiInPlace(target: ByteArray, before: String, after: String) {
        require(before.length == after.length)
        val offset = indexOf(target, before)
        after.toByteArray(StandardCharsets.US_ASCII).copyInto(target, offset)
    }

    private fun ByteArray.replaceLongOnce(before: Long, after: Long): ByteArray {
        val source = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(before).array()
        val replacement = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(after).array()
        val offset = (0..size - source.size).first { candidate ->
            source.indices.all { index -> this[candidate + index] == source[index] }
        }
        return copyOf().also { replacement.copyInto(it, offset) }
    }

    private fun indexOf(bytes: ByteArray, text: String): Int {
        val target = text.toByteArray(StandardCharsets.UTF_8)
        return (0..bytes.size - target.size).first { offset ->
            target.indices.all { index -> bytes[offset + index] == target[index] }
        }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
