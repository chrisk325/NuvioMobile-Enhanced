package com.nuvio.app.features.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object DownloadNetworkGuard {
    private var connectivityManager: ConnectivityManager? = null
    private val _transportState = MutableStateFlow(true)
    actual val transportState: StateFlow<Boolean> = _transportState.asStateFlow()

    fun initialize(context: Context) {
        if (connectivityManager != null) return
        val manager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        connectivityManager = manager
        _transportState.value = isOnWifi()
        runCatching {
            // Use the capabilities passed in; activeNetwork can be stale while callbacks are delivered.
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    manager.getNetworkCapabilities(network)?.let { _transportState.value = !it.isCellular() }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _transportState.value = !capabilities.isCellular()
                }

                override fun onLost(network: Network) {
                    _transportState.value = isOnWifi()
                }
            })
        }
    }

    actual fun isOnWifi(): Boolean {
        val manager = connectivityManager ?: return true
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.isCellular()
    }

    private fun NetworkCapabilities.isCellular(): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}
