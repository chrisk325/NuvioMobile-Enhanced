package com.nuvio.app.core.cache

import platform.Foundation.NSURLCache

// The addon client uses the default URL session configuration, i.e. the shared URL cache.

internal actual fun platformNetworkCacheSizeBytes(): Long =
    NSURLCache.sharedURLCache.currentDiskUsage.toLong()

internal actual fun clearPlatformNetworkCache() {
    NSURLCache.sharedURLCache.removeAllCachedResponses()
}
