package com.patmanak.contako.data.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.android.RoomAndroidDurableAccountContextReader
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.domain.sync.SyncTrigger
import java.io.Closeable

internal class AndroidSyncWorkScheduler(
    private val automaticSyncState: AndroidAutomaticSyncState,
) : SyncWorkScheduler {
    override fun ensurePeriodic(accountName: String, intervalSeconds: Long) {
        automaticSyncState.ensurePeriodic(accountName, intervalSeconds)
    }

    override fun request(accountName: String, triggers: Set<SyncTrigger>) {
        automaticSyncState.request(accountName, triggers)
    }

    internal companion object {
        const val EXTRA_TRIGGERS = "com.patmanak.contako.SYNC_TRIGGERS"
    }
}

internal class AndroidSyncAccountEligibility(
    context: Context,
    database: ContakoDatabase,
    private val account: AccountScope,
    private val automaticSyncState: AndroidAutomaticSyncState,
) : SyncAccountEligibility {
    private val applicationContext = context.applicationContext
    private val durableReader = RoomAndroidDurableAccountContextReader(database)

    override suspend fun current(): EligibleSyncAccount? {
        val durable = durableReader.load(account) ?: return null
        val name = durable.androidAccountName ?: return null
        if (durable.hasCrossAccountBinding) return null
        val androidAccount = Account(name, ContakoAndroidAccountContract.ACCOUNT_TYPE)
        if (AccountManager.get(applicationContext).accounts.none { it == androidAccount }) return null
        return EligibleSyncAccount(
            androidAccountName = name,
            automaticSyncEnabled = automaticSyncState.isEnabled(name),
        )
    }
}

internal class AndroidSchedulingNetworkMonitor(context: Context) : SchedulingNetworkMonitor {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun current(): SchedulingNetworkState =
        if (connectivity.activeNetwork.isOnline()) SchedulingNetworkState.ONLINE else SchedulingNetworkState.OFFLINE

    override fun observe(listener: (SchedulingNetworkState) -> Unit): Closeable {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = listener(SchedulingNetworkState.ONLINE)
            override fun onLost(network: Network) = listener(current())
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                listener(if (capabilities.isOnline()) SchedulingNetworkState.ONLINE else current())
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        return Closeable { connectivity.unregisterNetworkCallback(callback) }
    }

    private fun Network?.isOnline(): Boolean = this != null &&
        connectivity.getNetworkCapabilities(this)?.isOnline() == true

    private fun NetworkCapabilities.isOnline(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
