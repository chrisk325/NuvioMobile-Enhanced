package com.nuvio.app.core.cache

import android.content.Context
import android.content.SharedPreferences
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

internal actual object CacheSettingsStorage {
    private const val preferencesName = "nuvio_content_cache"

    private var preferences: SharedPreferences? = null
    internal var baseDirectory: File? = null
        private set

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        baseDirectory = appContext.cacheDir
    }

    actual fun loadBoolean(key: String): Boolean? =
        preferences?.takeIf { it.contains(key) }?.getBoolean(key, false)

    actual fun saveBoolean(key: String, value: Boolean) {
        preferences?.edit()?.putBoolean(key, value)?.apply()
    }

    actual fun loadInt(key: String): Int? =
        preferences?.takeIf { it.contains(key) }?.getInt(key, 0)

    actual fun saveInt(key: String, value: Int) {
        preferences?.edit()?.putInt(key, value)?.apply()
    }
}

internal actual fun cacheBaseDirectory(): Path {
    val directory = CacheSettingsStorage.baseDirectory
        ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    return directory.absolutePath.toPath()
}

internal actual val cacheFileSystem: FileSystem
    get() = FileSystem.SYSTEM
