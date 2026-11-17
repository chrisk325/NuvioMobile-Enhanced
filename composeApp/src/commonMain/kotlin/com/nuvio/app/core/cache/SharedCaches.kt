package com.nuvio.app.core.cache

import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * App caches outside the content caches that hold the same kind of data, and count towards the
 * app's cache in the system's storage settings:
 *
 * - the app's default image disk cache, which keeps every image loaded outside the poster and
 *   background caches (e.g. before a cache was turned on, while it is off, logos and avatars);
 * - the addon network response cache.
 *
 * The cache "Clear" buttons clear these too, so clearing actually frees the space.
 */
object SharedCaches {

    /** Size of the app's default image disk cache, in bytes. */
    suspend fun imageCacheSizeBytes(context: PlatformContext): Long = withContext(Dispatchers.IO) {
        runCatching { SingletonImageLoader.get(context).diskCache?.size ?: 0L }.getOrDefault(0L)
    }

    /** Deletes everything in the app's default image disk cache. */
    suspend fun clearImageCache(context: PlatformContext) {
        withContext(Dispatchers.IO) {
            runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
        }
    }

    /** Size of the addon network response cache, in bytes. */
    suspend fun networkCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { platformNetworkCacheSizeBytes() }.getOrDefault(0L)
    }

    /** Deletes every cached addon network response. */
    suspend fun clearNetworkCache() {
        withContext(Dispatchers.IO) {
            runCatching { clearPlatformNetworkCache() }
        }
    }
}

/** Size of the platform's HTTP response cache used for addon requests, in bytes. */
internal expect fun platformNetworkCacheSizeBytes(): Long

/** Clears the platform's HTTP response cache used for addon requests. */
internal expect fun clearPlatformNetworkCache()
