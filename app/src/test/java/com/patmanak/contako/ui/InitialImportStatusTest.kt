package com.patmanak.contako.ui

import com.patmanak.contako.domain.sync.AndroidAvailability
import com.patmanak.contako.domain.sync.InitialImportSnapshot
import com.patmanak.contako.domain.sync.SyncActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class InitialImportStatusTest {
    private val complete = InitialImportSnapshot(true, 3, 3, 0, 0, 3)

    @Test fun activeOrScheduledWorkIsNotReportedAsFinished() {
        listOf(SyncActivity.RUNNING, SyncActivity.SCHEDULED).forEach { activity ->
            assertEquals(InitialImportStatus.IN_PROGRESS,
                initialImportStatus(complete, activity, AndroidAvailability.READY, true))
        }
    }

    @Test fun completionRequiresBothImportAndAcknowledgedSystemCopies() {
        assertEquals(InitialImportStatus.COMPLETED,
            initialImportStatus(complete, SyncActivity.IDLE, AndroidAvailability.READY, false))
        listOf(complete.copy(androidSynchronizedContactCount = null),
            complete.copy(androidSynchronizedContactCount = 2),
            complete.copy(androidPendingContactCount = 1),
            complete.copy(inventoryEstablished = false, inventoryContactCount = null)).forEach { pending ->
            assertEquals(InitialImportStatus.IN_PROGRESS,
                initialImportStatus(pending, SyncActivity.IDLE, AndroidAvailability.READY, false))
        }
        assertEquals(InitialImportStatus.COMPLETED, initialImportStatus(
            InitialImportSnapshot(true, 0, 0, 0, 0, 0), SyncActivity.IDLE, AndroidAvailability.READY, false))
    }

    @Test fun unavailableOrUnknownSystemIsNotReportedAsSynchronized() {
        listOf(AndroidAvailability.UNKNOWN, AndroidAvailability.CONTEXT_NOT_ESTABLISHED).forEach { availability ->
            assertEquals(InitialImportStatus.IN_PROGRESS,
                initialImportStatus(complete, SyncActivity.IDLE, availability, false))
        }
        listOf(AndroidAvailability.PERMISSION_REQUIRED, AndroidAvailability.UNAVAILABLE).forEach { availability ->
            assertEquals(InitialImportStatus.COMPLETED_WITH_ERRORS,
                initialImportStatus(complete, SyncActivity.IDLE, availability, false))
        }
    }

    @Test fun stoppedFailuresRemainVisibleEvenWithNoPendingUploads() {
        assertEquals(InitialImportStatus.COMPLETED_WITH_ERRORS,
            initialImportStatus(complete, SyncActivity.IDLE, AndroidAvailability.READY, true))
        assertEquals(InitialImportStatus.COMPLETED_WITH_ERRORS,
            initialImportStatus(complete.copy(deferredContactCount = 1), SyncActivity.IDLE,
                AndroidAvailability.READY, false))
    }
}
