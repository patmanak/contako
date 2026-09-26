package com.patmanak.contako.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal enum class NetworkState { WIFI, NON_WIFI }

internal fun interface NetworkStateProvider {
    fun current(): NetworkState
}

internal class AndroidNetworkStateProvider(context: Context) : NetworkStateProvider {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    override fun current(): NetworkState {
        val network = connectivityManager.activeNetwork ?: return NetworkState.NON_WIFI
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkState.NON_WIFI
        return if (
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) NetworkState.WIFI else NetworkState.NON_WIFI
    }
}

internal class FakeNetworkStateProvider(
    var state: NetworkState,
) : NetworkStateProvider {
    override fun current(): NetworkState = state
}
