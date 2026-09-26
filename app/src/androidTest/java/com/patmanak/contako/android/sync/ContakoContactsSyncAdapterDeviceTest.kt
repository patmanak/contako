package com.patmanak.contako.android.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.provider.ContactsContract
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.Manifest
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContakoContactsSyncAdapterDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun grantContacts() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
            grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        }
    }

    @Test fun exactFrameworkScopeDelegatesToSharedRunnerAndMapsRetry() {
        grantContacts()
        var passes = 0
        val runner = AccountSyncRunner(CoroutineScope(Dispatchers.Default)) {
            passes++
            SyncPassOutcome.RETRY_WAITING
        }
        val adapter = ContakoContactsSyncAdapter(context) { runner }
        val result = SyncResult()
        val provider = requireNotNull(context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY))

        provider.use { adapter.onPerformSync(
            Account("synthetic", ContakoAndroidAccountContract.ACCOUNT_TYPE),
            Bundle(),
            ContakoAndroidAccountContract.CONTACTS_AUTHORITY,
            it,
            result,
        ) }

        assertEquals(1, passes)
        assertEquals(1L, result.stats.numIoExceptions)
    }

    @Test fun foreignAuthorityOrAccountNeverReachesRunner() {
        grantContacts()
        var resolutions = 0
        val adapter = ContakoContactsSyncAdapter(context) {
            resolutions++
            null
        }
        val result = SyncResult()
        val provider = requireNotNull(context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY))
        provider.use {
            adapter.onPerformSync(Account("synthetic", "foreign"), Bundle(), "foreign", it, result)
        }

        assertEquals(0, resolutions)
        assertEquals(1L, result.stats.numAuthExceptions)
    }
}
