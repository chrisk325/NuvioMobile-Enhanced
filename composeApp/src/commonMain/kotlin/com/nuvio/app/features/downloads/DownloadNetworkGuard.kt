package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.StateFlow

/**
 * Snapshot of the device's current network transport, used to gate starting a download
 * when the user hasn't opted in to using mobile data for downloads.
 */
internal expect object DownloadNetworkGuard {
    /** True when the current connection is Wi-Fi (or no metered/cellular transport is active). */
    fun isOnWifi(): Boolean

    /** Live [isOnWifi] value that changes with the network, so callers can react to switches. */
    val transportState: StateFlow<Boolean>
}
