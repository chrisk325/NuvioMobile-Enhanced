package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreFoundation.CFRelease
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
actual object DownloadNetworkGuard {
    private val _transportState = MutableStateFlow(isOnWifi())
    actual val transportState: StateFlow<Boolean> = _transportState.asStateFlow()

    // Expensive paths are cellular and personal hotspots; no connectivity has nothing to gate,
    // matching isOnWifi(). The monitor only reports changes.
    private val monitor = nw_path_monitor_create().also { monitor ->
        nw_path_monitor_set_update_handler(monitor) { path ->
            _transportState.value = nw_path_get_status(path) != nw_path_status_satisfied || !nw_path_is_expensive(path)
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    actual fun isOnWifi(): Boolean {
        val reachability = SCNetworkReachabilityCreateWithName(null, "www.apple.com") ?: return true
        try {
            memScoped {
                val flagsVar = alloc<SCNetworkReachabilityFlagsVar>()
                val ok = SCNetworkReachabilityGetFlags(reachability, flagsVar.ptr)
                if (!ok) return true

                val flags = flagsVar.value
                val isReachable = (flags and kSCNetworkReachabilityFlagsReachable.toUInt()) != 0u
                if (!isReachable) return true // No connectivity at all: nothing to gate.

                val isCellular = (flags and kSCNetworkReachabilityFlagsIsWWAN.toUInt()) != 0u
                return !isCellular
            }
        } finally {
            CFRelease(reachability)
        }
    }
}
