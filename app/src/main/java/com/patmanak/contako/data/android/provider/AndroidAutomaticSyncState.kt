package com.patmanak.contako.data.android.provider

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.sync.AndroidAutomaticSyncState
import com.patmanak.contako.data.sync.AndroidSyncWorkScheduler
import com.patmanak.contako.domain.sync.SyncTrigger

internal class FrameworkAndroidAutomaticSyncState : AndroidAutomaticSyncState {
    override fun ensurePeriodic(accountName: String, intervalSeconds: Long) {
        ContentResolver.addPeriodicSync(account(accountName), AUTHORITY, Bundle.EMPTY, intervalSeconds)
    }

    override fun request(accountName: String, triggers: Set<SyncTrigger>) {
        ContentResolver.requestSync(
            account(accountName),
            AUTHORITY,
            Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
                putString(
                    AndroidSyncWorkScheduler.EXTRA_TRIGGERS,
                    triggers.joinToString(",", transform = SyncTrigger::name),
                )
            },
        )
    }

    override fun isEnabled(accountName: String): Boolean =
        ContentResolver.getMasterSyncAutomatically() &&
            ContentResolver.getSyncAutomatically(account(accountName), AUTHORITY)

    private fun account(name: String) = Account(name, ContakoAndroidAccountContract.ACCOUNT_TYPE)

    private companion object {
        const val AUTHORITY = ContakoAndroidAccountContract.CONTACTS_AUTHORITY
    }
}
