package com.nuvio.app.core.poster

import android.content.Context
import android.content.SharedPreferences
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

internal actual object PosterCacheSettingsStorage {
    private const val preferencesName = "nuvio_poster_cache"
    private const val enabledKey = "poster_cache_enabled"
    private const val refreshIntervalKey = "poster_cache_refresh_interval_hours"

    private var preferences: SharedPreferences? = null
    internal var cacheDirectory: File? = null
        private set

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        cacheDirectory = File(appContext.cacheDir, "poster_cache")
    }

    actual fun loadEnabled(): Boolean? =
        preferences?.takeIf { it.contains(enabledKey) }?.getBoolean(enabledKey, false)

    actual fun saveEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(enabledKey, enabled)?.apply()
    }

    actual fun loadRefreshIntervalHours(): Int? =
        preferences?.takeIf { it.contains(refreshIntervalKey) }?.getInt(refreshIntervalKey, 0)

    actual fun saveRefreshIntervalHours(hours: Int) {
        preferences?.edit()?.putInt(refreshIntervalKey, hours)?.apply()
    }
}

internal actual fun posterCacheDirectory(): Path {
    val directory = PosterCacheSettingsStorage.cacheDirectory
        ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "poster_cache")
    return directory.absolutePath.toPath()
}
