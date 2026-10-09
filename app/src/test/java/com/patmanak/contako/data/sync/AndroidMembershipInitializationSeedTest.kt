package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.AndroidGroupMembershipSnapshotBinaryCodec
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMembershipInitializationSeedTest {
    private val context = AndroidInteroperabilityContext(AccountScope("fixture"), "fixture-android", 1, 2)
    private val contact = CanonicalContact("fixture", "contact", remoteContactId = "remote", values = listOf(
        ContactValue("email", ContactValueKind.EMAIL, "sample@example.invalid", order = 0),
    ))

    @Test fun `interrupted initial marker reconstructs exactly the original encoded empty snapshot`() {
        for (canonical in listOf(contact, contact.copy(values = emptyList()))) {
            val original = AndroidMembershipInitializationSeed(context, canonical, 10)
            val recovered = AndroidMembershipInitializationSeed(context, canonical, 10)
            assertTrue(recovered.matchesInterruptedInitialization(original.ledger))
            assertArrayEquals(original.baseline.encodedSnapshot, recovered.baseline.encodedSnapshot)
            assertEquals(original.baseline.fingerprint, recovered.baseline.fingerprint)
            val snapshot = AndroidGroupMembershipSnapshotBinaryCodec.decode(recovered.baseline.encodedSnapshot)
            assertEquals(emptyList<String>(), snapshot.canonicalGroupIds)
            assertEquals(snapshot.semanticFingerprint().sha256Hex, original.ledger.androidBaselineFingerprint)
        }
    }

    @Test fun `completed pending detached and unproved states cannot recover as an initial marker`() {
        val seed = AndroidMembershipInitializationSeed(context, contact, 10)
        val entry = seed.ledger
        val altered = listOf(
            entry.copy(revision = 1),
            entry.copy(projectionState = "DETACHED"),
            entry.copy(projectionState = "REPAIR_REQUIRED"),
            entry.copy(ingestionState = "CANONICAL_DELTA_COMMITTED"),
            entry.copy(canonicalProjectionFingerprint = "a".repeat(64)),
            entry.copy(androidBaselineFingerprint = null),
            entry.copy(androidBaselineFingerprint = "a".repeat(64)),
            entry.copy(projectionState = "WRITE_PENDING", pendingProjectionFingerprint = "a".repeat(64)),
        )
        altered.forEach { assertFalse(seed.matchesInterruptedInitialization(it)) }
    }

    @Test fun `changed account epoch raw identity or preferred email prevents recovery`() {
        val seed = AndroidMembershipInitializationSeed(context, contact, 10)
        val entry = seed.ledger
        listOf(
            entry.copy(accountId = "other"), entry.copy(canonicalContactId = "other"),
            entry.copy(providerEpoch = 3), entry.copy(rawContactLocator = 11),
            entry.copy(preferredEmailValueId = "other"),
        ).forEach { assertFalse(seed.matchesInterruptedInitialization(it)) }
        val differentCanonical = contact.copy(values = contact.values.map { it.copy(id = "new-email") })
        assertFalse(AndroidMembershipInitializationSeed(context, differentCanonical, 10)
            .matchesInterruptedInitialization(entry))
    }
}
