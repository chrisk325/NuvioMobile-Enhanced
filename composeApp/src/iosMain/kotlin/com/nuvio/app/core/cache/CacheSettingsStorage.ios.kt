package com.nuvio.app.core.cache

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

internal actual object CacheSettingsStorage {
    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual fun loadBoolean(key: String): Boolean? =
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null

    actual fun saveBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    actual fun loadInt(key: String): Int? =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else null

    actual fun saveInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }
}

internal actual fun cacheBaseDirectory(): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
    return caches?.toPath() ?: FileSystem.SYSTEM_TEMPORARY_DIRECTORY
}

internal actual val cacheFileSystem: FileSystem
    get() = FileSystem.SYSTEM
