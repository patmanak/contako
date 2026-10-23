package com.patmanak.contako.android.sync

import com.patmanak.contako.domain.sync.SyncCompletionCause
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncRunState

internal enum class AndroidSyncCompletionClassification { SUCCESS, AUTHENTICATION, STORAGE, RETRYABLE_IO, ACTION_REQUIRED, NONE }

/** Does not infer an auth/database failure from the severity of the overall outcome. */
internal fun classifyAndroidSyncCompletion(completion: SyncRunState.Completed?): AndroidSyncCompletionClassification {
    if (completion == null || completion.outcome == SyncPassOutcome.CANCELLED) return AndroidSyncCompletionClassification.NONE
    if (completion.outcome == SyncPassOutcome.SUCCESS) return AndroidSyncCompletionClassification.SUCCESS
    return when (completion.cause) {
        SyncCompletionCause.AUTHENTICATION -> AndroidSyncCompletionClassification.AUTHENTICATION
        SyncCompletionCause.STORAGE -> AndroidSyncCompletionClassification.STORAGE
        SyncCompletionCause.TRANSIENT_IO -> AndroidSyncCompletionClassification.RETRYABLE_IO
        SyncCompletionCause.VALIDATION, SyncCompletionCause.ANDROID_PERMISSION,
        SyncCompletionCause.CONFLICT, SyncCompletionCause.PROJECTION -> AndroidSyncCompletionClassification.ACTION_REQUIRED
        SyncCompletionCause.UNKNOWN, null -> if (completion.outcome == SyncPassOutcome.ACTION_REQUIRED)
            AndroidSyncCompletionClassification.ACTION_REQUIRED else AndroidSyncCompletionClassification.RETRYABLE_IO
    }
}
