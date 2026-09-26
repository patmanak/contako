package com.patmanak.contako.android.sync

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.sync.AndroidSyncWorkScheduler
import com.patmanak.contako.domain.sync.AccountSyncRunner
import com.patmanak.contako.domain.sync.SyncPassOutcome
import com.patmanak.contako.domain.sync.SyncRequestDisposition
import com.patmanak.contako.domain.sync.SyncTrigger

fun interface AndroidAccountSyncRunnerResolver {
    fun resolve(account: Account): AccountSyncRunner?
}

internal class ContakoContactsSyncAdapter(
    context: Context,
    private val runnerResolver: AndroidAccountSyncRunnerResolver,
) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        if (authority != ContakoAndroidAccountContract.CONTACTS_AUTHORITY ||
            account.type != ContakoAndroidAccountContract.ACCOUNT_TYPE
        ) {
            syncResult.stats.numAuthExceptions++
            return
        }
        val runner = runnerResolver.resolve(account)
        if (runner == null) {
            syncResult.stats.numAuthExceptions++
            return
        }
        runSyncAdapterBlocking {
            val triggers = extras.getString(AndroidSyncWorkScheduler.EXTRA_TRIGGERS)
                ?.split(',')
                ?.mapNotNull { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: setOf(if (extras.getBoolean("upload")) SyncTrigger.ANDROID_UPLOAD else SyncTrigger.ANDROID_PERIODIC)
            var invalidated = false
            triggers.forEach { trigger ->
                when (runner.request(trigger)) {
                    SyncRequestDisposition.ACCOUNT_INVALIDATED -> invalidated = true
                    SyncRequestDisposition.STARTED,
                    SyncRequestDisposition.COALESCED,
                    -> Unit
                }
            }
            if (invalidated) {
                syncResult.stats.numAuthExceptions++
                return@runSyncAdapterBlocking
            }
            runner.awaitIdle()
            when (runner.lastCompletion.value?.outcome) {
                SyncPassOutcome.SUCCESS -> syncResult.stats.numEntries++
                SyncPassOutcome.RETRY_WAITING -> syncResult.stats.numIoExceptions++
                SyncPassOutcome.ACTION_REQUIRED -> syncResult.stats.numAuthExceptions++
                SyncPassOutcome.FAILED -> syncResult.databaseError = true
                SyncPassOutcome.CANCELLED, null -> Unit
            }
        }
    }
}

/** Application runtime supplies the same account runner used by manual/background entry points. */
internal interface ContakoSyncAdapterRuntime {
    val androidAccountSyncRunnerResolver: AndroidAccountSyncRunnerResolver
}

class ContakoContactsSyncAdapterService : Service() {
    private lateinit var adapter: ContakoContactsSyncAdapter

    override fun onCreate() {
        super.onCreate()
        val runtime = application as? ContakoSyncAdapterRuntime
        adapter = ContakoContactsSyncAdapter(
            applicationContext,
            runtime?.androidAccountSyncRunnerResolver ?: AndroidAccountSyncRunnerResolver { null },
        )
    }

    override fun onBind(intent: Intent?): IBinder? =
        adapter.syncAdapterBinder.takeIf {
            intent?.action == ContakoAndroidAccountContract.SYNC_ADAPTER_ACTION
        }
}
