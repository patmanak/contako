package com.patmanak.contako.data.sync

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.ProtonContactGroupGateway
import com.patmanak.contako.data.gateway.ProtonContactInventoryGateway
import com.patmanak.contako.data.gateway.ProtonContactMutationGateway
import com.patmanak.contako.data.gateway.ProtonSessionGateway
import com.patmanak.contako.data.gateway.ProtonVerifiedContactCardGateway
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.proton.ProtonEmailGroupMembershipReader
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionSharedSyncRuntimeDeviceTest {
    private lateinit var context: Context
    private lateinit var database: ContakoDatabase
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = ContakoDatabase.create(context, DATABASE_NAME)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun compositionIsDormantAndResolverReturnsTheSingleRunnerOnlyForExactBinding() = runBlocking {
        val account = AccountScope("composition-account")
        val androidName = "exact@example.test"
        val ledger = RoomAndroidProjectionLedger(database)
        val initial = ledger.ensureAccount(account)
        ledger.bindAndroidAccountName(account, initial.revision, androidName)
        var gatewayCalls = 0
        val runtime = composeProductionSharedSyncRuntime(
            context,
            database,
            ProductionSyncDependencies(
                account = account,
                session = sentinel { gatewayCalls++ },
                inventory = sentinel { gatewayCalls++ },
                verifiedCards = sentinel { gatewayCalls++ },
                contactMutations = sentinel { gatewayCalls++ },
                groups = sentinel { gatewayCalls++ },
                emailLabels = sentinel { gatewayCalls++ },
                membershipReader = sentinel { gatewayCalls++ },
                localSessionCleanup = sentinel { gatewayCalls++ },
            ),
            scope,
        )

        val exact = Account(androidName, ContakoAndroidAccountContract.ACCOUNT_TYPE)
        assertSame(runtime.runner, runtime.androidAccountSyncRunnerResolver.resolve(exact))
        assertSame(runtime.runner, runtime.androidAccountSyncRunnerResolver.resolve(exact))
        assertNull(runtime.androidAccountSyncRunnerResolver.resolve(Account("other", exact.type)))
        assertNull(runtime.androidAccountSyncRunnerResolver.resolve(Account(androidName, "foreign")))
        org.junit.Assert.assertEquals(0, gatewayCalls)
    }

    @Test fun missingDurableBindingFailsClosedWithoutCallingGateway() {
        var gatewayCalls = 0
        val account = AccountScope("composition-account")
        val runtime = composeProductionSharedSyncRuntime(
            context,
            database,
            ProductionSyncDependencies(
                account,
                sentinel { gatewayCalls++ },
                sentinel { gatewayCalls++ },
                sentinel { gatewayCalls++ },
                sentinel { gatewayCalls++ },
                sentinel { gatewayCalls++ },
                sentinel { gatewayCalls++ },
                sentinel { gatewayCalls++ },
                sentinel { gatewayCalls++ },
            ),
            scope,
        )

        assertNull(runtime.androidAccountSyncRunnerResolver.resolve(
            Account("unbound@example.test", ContakoAndroidAccountContract.ACCOUNT_TYPE),
        ))
        org.junit.Assert.assertEquals(0, gatewayCalls)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> sentinel(crossinline invoked: () -> Unit): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            invoked()
            error("GATEWAY_CALLED_DURING_COMPOSITION")
        } as T

    private companion object {
        const val DATABASE_NAME = "production-shared-sync-runtime-test.db"
    }
}
