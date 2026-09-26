package com.patmanak.contako.data.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.domain.sync.SyncTrigger
import com.patmanak.contako.data.android.provider.FrameworkAndroidAutomaticSyncState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSyncWorkSchedulerDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val account = Account("contako-scheduling-test", ContakoAndroidAccountContract.ACCOUNT_TYPE)

    @After
    fun cleanup() {
        ContentResolver.removePeriodicSync(account, ContakoAndroidAccountContract.CONTACTS_AUTHORITY, android.os.Bundle.EMPTY)
        AccountManager.get(context).removeAccountExplicitly(account)
    }

    @Test
    fun `07-BATTERY system scheduler retains one hourly periodic request`() {
        assertTrue(AccountManager.get(context).addAccountExplicitly(account, null, null))
        val scheduler = AndroidSyncWorkScheduler(FrameworkAndroidAutomaticSyncState())
        repeat(100) { scheduler.ensurePeriodic(account.name, 3_600) }

        val matching = ContentResolver.getPeriodicSyncs(account, ContakoAndroidAccountContract.CONTACTS_AUTHORITY)
            .filter { it.period == 3_600L }
        assertEquals(1, matching.size)
    }

    @Test
    fun `07-NET one shot request is accepted for a syncable automatic account`() {
        assertTrue(AccountManager.get(context).addAccountExplicitly(account, null, null))
        ContentResolver.setIsSyncable(account, ContakoAndroidAccountContract.CONTACTS_AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContakoAndroidAccountContract.CONTACTS_AUTHORITY, true)
        assertTrue(ContentResolver.getSyncAutomatically(account, ContakoAndroidAccountContract.CONTACTS_AUTHORITY))

        // The test account intentionally has no production Room binding, so the SyncAdapter may
        // finish before a pending/active probe can observe it. The framework acceptance boundary is
        // that requestSync returns normally for the exact account and authority; trigger encoding
        // and coalescing remain covered deterministically by SyncSchedulingPolicyTest.
        AndroidSyncWorkScheduler(FrameworkAndroidAutomaticSyncState())
            .request(account.name, setOf(SyncTrigger.CONNECTIVITY_RETURNED))
    }
}
