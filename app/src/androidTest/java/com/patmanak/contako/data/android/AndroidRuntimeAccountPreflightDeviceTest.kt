package com.patmanak.contako.data.android

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.provider.AndroidContactsRuntimeProbe
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidInteroperabilityPreflightResult
import com.patmanak.contako.data.sync.AndroidInteroperabilityRepairReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeAccountPreflightDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var accountManager: AccountManager
    private lateinit var androidAccount: Account

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
            grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CONTACTS)
        }
        accountManager = AccountManager.get(context)
        androidAccount = Account(
            "contako-preflight-${System.nanoTime().toString(36)}",
            ContakoAndroidAccountContract.ACCOUNT_TYPE,
        )
        check(accountManager.addAccountExplicitly(androidAccount, null, null))
    }

    @After
    fun tearDown() {
        if (::androidAccount.isInitialized) accountManager.removeAccountExplicitly(androidAccount)
        if (::database.isInitialized && database.isOpen) database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun boundAccountIsReadWithoutChangingRevisionOrEpoch() = runBlocking {
        val ledger = RoomAndroidProjectionLedger(database)
        val initial = ledger.ensureAccount(ACCOUNT)
        val bound = ledger.bindAndroidAccountName(ACCOUNT, initial.revision, androidAccount.name)
            as AndroidAccountBindingResult.Bound
        context.contentResolver.acquireContentProviderClient(
            ContakoAndroidAccountContract.CONTACTS_AUTHORITY,
        )!!.use {
            ContactsContract.SyncState.set(
                it,
                androidAccount,
                AndroidContactsRuntimeProbe.encodeProviderEpoch(bound.account.providerEpoch),
            )
        }
        val preflight = AndroidRuntimeAccountPreflight(
            RoomAndroidDurableAccountContextReader(database),
            AndroidContactsRuntimeProbe(context),
        )

        val result = preflight.check(ACCOUNT) { false } as AndroidInteroperabilityPreflightResult.Ready
        val after = requireNotNull(ledger.loadAccount(ACCOUNT))

        assertEquals(bound.account, after)
        assertEquals(bound.account.revision, result.context.accountRevision)
        assertEquals(bound.account.providerEpoch, result.context.providerEpoch)
    }

    @Test
    fun missingDurableContextCreatesNoRoomState() = runBlocking {
        val result = AndroidRuntimeAccountPreflight(
            RoomAndroidDurableAccountContextReader(database),
            AndroidContactsRuntimeProbe(context),
        ).check(ACCOUNT) { false } as AndroidInteroperabilityPreflightResult.RepairRequired

        assertEquals(AndroidInteroperabilityRepairReason.DURABLE_ACCOUNT_CONTEXT_MISSING, result.reason)
        assertNull(database.androidProjectionLedgerDao().getAccount(ACCOUNT.value))
    }

    @Test
    fun duplicateDurableAndroidNameFailsClosedWithoutChangingEitherAccount() = runBlocking {
        val ledger = RoomAndroidProjectionLedger(database)
        val first = ledger.ensureAccount(ACCOUNT)
        ledger.bindAndroidAccountName(ACCOUNT, first.revision, androidAccount.name)
        val second = ledger.ensureAccount(FOREIGN_ACCOUNT)
        ledger.bindAndroidAccountName(FOREIGN_ACCOUNT, second.revision, androidAccount.name)
        val beforePrimary = requireNotNull(ledger.loadAccount(ACCOUNT))
        val beforeForeign = requireNotNull(ledger.loadAccount(FOREIGN_ACCOUNT))

        val result = AndroidRuntimeAccountPreflight(
            RoomAndroidDurableAccountContextReader(database),
            AndroidContactsRuntimeProbe(context),
        ).check(ACCOUNT) { false } as AndroidInteroperabilityPreflightResult.RepairRequired

        assertEquals(AndroidInteroperabilityRepairReason.CROSS_ACCOUNT_BINDING, result.reason)
        assertEquals(beforePrimary, ledger.loadAccount(ACCOUNT))
        assertEquals(beforeForeign, ledger.loadAccount(FOREIGN_ACCOUNT))
    }

    private companion object {
        const val DATABASE_NAME = "android-runtime-preflight-device.db"
        val ACCOUNT = AccountScope("account")
        val FOREIGN_ACCOUNT = AccountScope("foreign-account")
    }
}
