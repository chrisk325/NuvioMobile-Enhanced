package com.nuvio.app.core.poster

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

internal actual object PosterCacheSettingsStorage {
    private const val enabledKey = "poster_cache_enabled"
    private const val refreshIntervalKey = "poster_cache_refresh_interval_hours"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual fun loadEnabled(): Boolean? =
        if (defaults.objectForKey(enabledKey) != null) defaults.boolForKey(enabledKey) else null

    actual fun saveEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = enabledKey)
    }

    actual fun loadRefreshIntervalHours(): Int? =
        if (defaults.objectForKey(refreshIntervalKey) != null) {
            defaults.integerForKey(refreshIntervalKey).toInt()
        } else {
            null
        }

    actual fun saveRefreshIntervalHours(hours: Int) {
        defaults.setInteger(hours.toLong(), forKey = refreshIntervalKey)
    }
}

internal actual fun posterCacheDirectory(): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
    return caches?.let { "$it/poster_cache".toPath() }
        ?: (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "poster_cache")
}
