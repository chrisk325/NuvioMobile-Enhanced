package com.nuvio.app.core.cache

import com.nuvio.app.features.addons.AddonHttpClientProvider

internal actual fun platformNetworkCacheSizeBytes(): Long =
    AddonHttpClientProvider.get().cache?.size() ?: 0L

internal actual fun clearPlatformNetworkCache() {
    AddonHttpClientProvider.get().cache?.evictAll()
}
