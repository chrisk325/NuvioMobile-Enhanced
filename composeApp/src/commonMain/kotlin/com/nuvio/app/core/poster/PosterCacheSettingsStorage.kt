package com.nuvio.app.core.poster

import okio.Path

/**
 * Device-wide storage for the poster cache settings. The cache itself is shared by every
 * profile on the device, so these values are intentionally not profile scoped.
 */
internal expect object PosterCacheSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadRefreshIntervalHours(): Int?
    fun saveRefreshIntervalHours(hours: Int)
}

/** Directory that holds the dedicated poster disk cache. */
internal expect fun posterCacheDirectory(): Path
