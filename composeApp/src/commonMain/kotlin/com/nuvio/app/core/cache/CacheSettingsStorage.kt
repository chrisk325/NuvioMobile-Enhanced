package com.nuvio.app.core.cache

import okio.FileSystem
import okio.Path

/**
 * Device-wide storage for the content cache settings. The caches are shared by every profile on
 * the device, so these values are intentionally not profile scoped.
 */
internal expect object CacheSettingsStorage {
    fun loadBoolean(key: String): Boolean?
    fun saveBoolean(key: String, value: Boolean)
    fun loadInt(key: String): Int?
    fun saveInt(key: String, value: Int)
}

/** Directory that holds the content caches (each cache uses its own sub directory). */
internal expect fun cacheBaseDirectory(): Path

/** File system used by the metadata cache. */
internal expect val cacheFileSystem: FileSystem
