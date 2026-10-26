package com.patmanak.contako.data.local

import com.patmanak.contako.domain.sync.*
import org.junit.Assert.*
import org.junit.Test

class ContactSyncEvidenceRulesTest {
    private val evidence = ContactSyncEvidenceEntity("synthetic-owner", "synthetic-contact", "PROTON",
        7, 1234, ContactSyncEvidenceKind.VERIFIED_READ.name, "synthetic-remote", "opaque-version")

    @Test fun missingEvidenceCannotBePromotedByAnOtherwiseCleanAccountOrCaller() {
        assertEquals(ContactPeerSyncState.UNKNOWN, contactPeerDetail(null, current = true).state)
        assertNull(contactPeerDetail(null, current = false).evidence)
    }

    @Test fun currentAndHistoricalProofsKeepTheirActualRevisionNatureAndLocalConfirmationDate() {
        val current = contactPeerDetail(evidence, current = true)
        assertEquals(ContactPeerSyncState.CONFIRMED, current.state)
        assertEquals(ContactSyncEvidence(7, 1234, ContactSyncEvidenceKind.VERIFIED_READ), current.evidence)
        val historical = contactPeerDetail(evidence, current = false)
        assertEquals(ContactPeerSyncState.HISTORICAL, historical.state)
        assertEquals(current.evidence, historical.evidence)
    }

    @Test fun pendingIntentAndFailuresRemainDistinctFromTheRetainedOlderConfirmation() {
        assertEquals(ContactPeerSyncState.PENDING, contactPeerDetail(evidence, current = false, pending = true).state)
        assertEquals(evidence.canonicalRevision, contactPeerDetail(evidence, current = false, pending = true).evidence?.localRevision)
        assertEquals(ContactPeerSyncState.ACTION_REQUIRED,
            contactPeerDetail(evidence, current = true, pending = true, actionRequired = true).state)
        assertEquals(ContactPeerSyncState.CONFLICT,
            contactPeerDetail(evidence, current = true, pending = true, actionRequired = true, conflict = true).state)
    }

    @Test fun unavailablePeerDoesNotEraseItsHistoricalEvidenceOrInventAnError() {
        val detail = contactPeerDetail(evidence.copy(peer = "ANDROID", kind = ContactSyncEvidenceKind.ANDROID_READBACK.name),
            current = false, unavailable = true)
        assertEquals(ContactPeerSyncState.UNAVAILABLE, detail.state)
        assertEquals(1234L, detail.evidence?.confirmedAtEpochMillis)
    }

    @Test fun proofAndPresentationStringRepresentationsExcludePrivateIdentitiesVersionsAndDates() {
        val rendered = evidence.toString() + contactPeerDetail(evidence, true)
        listOf("synthetic-owner", "synthetic-contact", "synthetic-remote", "opaque-version", "1234").forEach {
            assertFalse(rendered.contains(it))
        }
    }
}
