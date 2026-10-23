package com.patmanak.contako.android.sync

import com.patmanak.contako.domain.sync.SyncCompletionCause
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncRunState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCompletionClassificationTest {
    @Test fun onlyAttestedAuthenticationAndStorageUseTheirFrameworkErrorCategories() {
        val expected = mapOf(
            SyncCompletionCause.AUTHENTICATION to AndroidSyncCompletionClassification.AUTHENTICATION,
            SyncCompletionCause.STORAGE to AndroidSyncCompletionClassification.STORAGE,
            SyncCompletionCause.TRANSIENT_IO to AndroidSyncCompletionClassification.RETRYABLE_IO,
            SyncCompletionCause.VALIDATION to AndroidSyncCompletionClassification.ACTION_REQUIRED,
            SyncCompletionCause.ANDROID_PERMISSION to AndroidSyncCompletionClassification.ACTION_REQUIRED,
            SyncCompletionCause.CONFLICT to AndroidSyncCompletionClassification.ACTION_REQUIRED,
            SyncCompletionCause.PROJECTION to AndroidSyncCompletionClassification.ACTION_REQUIRED,
            SyncCompletionCause.UNKNOWN to AndroidSyncCompletionClassification.ACTION_REQUIRED,
        )
        SyncCompletionCause.entries.forEach { cause ->
            assertEquals(cause.name, expected.getValue(cause), classifyAndroidSyncCompletion(
                SyncRunState.Completed(SyncPassOutcome.ACTION_REQUIRED, 1, cause)))
        }
    }

    @Test fun genericFailureIsNotReportedAsAnInventedDatabaseError() {
        assertEquals(AndroidSyncCompletionClassification.RETRYABLE_IO,
            classifyAndroidSyncCompletion(SyncRunState.Completed(SyncPassOutcome.FAILED, 1)))
        assertEquals(AndroidSyncCompletionClassification.ACTION_REQUIRED,
            classifyAndroidSyncCompletion(SyncRunState.Completed(SyncPassOutcome.ACTION_REQUIRED, 1)))
        assertEquals(AndroidSyncCompletionClassification.RETRYABLE_IO,
            classifyAndroidSyncCompletion(SyncRunState.Completed(SyncPassOutcome.RETRY_WAITING, 1)))
    }

    @Test fun successCancellationAndMissingPassNeverRetainAnEarlierFailure() {
        for (cause in SyncCompletionCause.entries) {
            assertEquals(AndroidSyncCompletionClassification.SUCCESS,
                classifyAndroidSyncCompletion(SyncRunState.Completed(SyncPassOutcome.SUCCESS, 2, cause)))
            assertEquals(AndroidSyncCompletionClassification.NONE,
                classifyAndroidSyncCompletion(SyncRunState.Completed(SyncPassOutcome.CANCELLED, 2, cause)))
        }
        assertEquals(AndroidSyncCompletionClassification.NONE, classifyAndroidSyncCompletion(null))
    }
}
