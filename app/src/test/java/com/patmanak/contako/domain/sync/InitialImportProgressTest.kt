package com.patmanak.contako.domain.sync

import org.junit.Assert.*
import org.junit.Test

class InitialImportProgressTest {
    @Test fun noCheckpointIsUnknownEvenWhenNoLocalMutationOrAndroidCopyExists() {
        val initial = InitialImportSnapshot(false, 0, null, 0, 0)
        assertFalse(initial.remoteComplete)
        assertNull(initial.inventoryContactCount)
        assertEquals(0, SyncDashboardSnapshot(SyncDashboardState.PENDING, initialImport = initial).pendingMutationCount)
    }

    @Test fun establishedEmptyInventoryFinishesAcquisitionWithoutInventingContacts() {
        val empty = InitialImportSnapshot(true, 0, 0, 0, 0)
        assertTrue(empty.remoteComplete)
        assertEquals(0, empty.localContactCount)
    }

    @Test fun localAvailabilityAndAndroidBacklogAreIndependentOfRemoteCompletion() {
        val partial = InitialImportSnapshot(true, 25, 300, 275, 25)
        assertFalse(partial.remoteComplete)
        assertEquals(25, partial.localContactCount)
        val acquired = partial.copy(localContactCount = 300, deferredContactCount = 0, androidPendingContactCount = 300)
        assertTrue(acquired.remoteComplete)
        assertEquals(300, acquired.androidPendingContactCount)
        // Local creations, deletions and last-known inventory are different scopes, not a
        // fraction that can be clamped or interpreted as a fabricated global percentage.
        assertTrue(acquired.copy(localContactCount = 301).remoteComplete)
    }

    @Test fun committedCountsCanBeReconstructedWithoutRetryOrActivityEvents() {
        val before = InitialImportSnapshot(false, 25, null, 1, 25)
        val recreated = InitialImportSnapshot(false, 25, null, 1, 25)
        assertEquals(before, recreated)
        assertFalse(recreated.remoteComplete)
        assertEquals(24, recreated.copy(localContactCount = 24).localContactCount)
    }

    @Test fun impossibleOrNegativeObservationInputsAreRejected() {
        listOf<() -> InitialImportSnapshot>(
            { InitialImportSnapshot(false, -1, null, 0, 0) },
            { InitialImportSnapshot(false, 0, null, -1, 0) },
            { InitialImportSnapshot(false, 0, null, 0, -1) },
            { InitialImportSnapshot(true, 0, -1, 0, 0) },
            { InitialImportSnapshot(true, 0, null, 0, 0) },
            { InitialImportSnapshot(false, 0, 0, 0, 0) },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }

    @Test fun modelLoggingDoesNotExposeExactPrivateCounts() {
        val snapshot = InitialImportSnapshot(true, 123, 456, 7, 8)
        assertEquals("InitialImportSnapshot(REDACTED, inventoryEstablished=true)", snapshot.toString())
    }
}
