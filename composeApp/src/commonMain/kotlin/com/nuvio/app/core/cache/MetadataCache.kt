package com.nuvio.app.core.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.Path

/**
 * Disk cache for metadata responses: addon catalogs (Home rows, Discover, Search), addon meta
 * (details screen) and TMDB enrichment.
 *
 * While enabled:
 * - A cached response is returned without a network request until it is older than the refresh
 *   interval (12/24/36/48 hours); then it is fetched again and the cache is updated.
 * - A forced refresh (e.g. pull to refresh) always goes to the network and updates the cache.
 * - If the network request fails, the last cached response is used even if it is stale, so
 *   previously seen content still loads offline.
 */
object MetadataCache {
    private const val TRIM_EVERY_WRITES = 25

    private val writeMutex = Mutex()
    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writesSinceTrim = 0

    private val directory: Path
        get() = cacheBaseDirectory() / CacheType.Metadata.directoryName

    private fun fileFor(url: String): Path = directory / (url.encodeUtf8().sha256().hex() + ".json")

    val isEnabled: Boolean
        get() = CacheSettingsRepository.isEnabled(CacheType.Metadata)

    /**
     * Returns the response for [url], using the cache as described on [MetadataCache].
     * [fetcher] performs the actual network request and must throw on failure.
     */
    suspend fun fetch(
        url: String,
        forceRefresh: Boolean = false,
        fetcher: suspend () -> String,
    ): String {
        if (!isEnabled) return fetcher()
        val cached = read(url)
        if (!forceRefresh && cached != null && cached.isFresh()) return cached.payload
        val payload = try {
            fetcher()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return cached?.payload ?: throw error
        }
        if (payload.isNotBlank()) write(url, payload)
        return payload
    }

    /** Trims the cache to its (new) share of the storage limit in the background. */
    internal fun onStorageLimitChanged() {
        trimScope.launch {
            writeMutex.withLock { runCatching { trimToMaxSize() } }
        }
    }

    /** Size of the metadata cache on disk, in bytes. */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        runCatching {
            val fs = cacheFileSystem
            if (!fs.exists(directory)) return@runCatching 0L
            fs.list(directory).sumOf { path -> fs.metadataOrNull(path)?.size ?: 0L }
        }.getOrDefault(0L)
    }

    /** Deletes every cached metadata response. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching { cacheFileSystem.deleteRecursively(directory, mustExist = false) }
                writesSinceTrim = 0
            }
        }
    }

    private class CachedPayload(val cachedAt: Long, val payload: String) {
        fun isFresh(): Boolean {
            val age = cacheNowMillis() - cachedAt
            return age in 0L until CacheSettingsRepository.refreshIntervalMillis
        }
    }

    private suspend fun read(url: String): CachedPayload? = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(url)
            val fs = cacheFileSystem
            if (!fs.exists(file)) return@runCatching null
            fs.read(file) {
                val cachedAt = readUtf8LineStrict().toLong()
                CachedPayload(cachedAt = cachedAt, payload = readUtf8())
            }
        }.getOrNull()
    }

    private suspend fun write(url: String, payload: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching {
                    val fs = cacheFileSystem
                    fs.createDirectories(directory)
                    val target = fileFor(url)
                    val temp = directory / (target.name + ".tmp")
                    fs.write(temp) {
                        writeUtf8(cacheNowMillis().toString())
                        writeUtf8("\n")
                        writeUtf8(payload)
                    }
                    fs.atomicMove(temp, target)
                }
                writesSinceTrim += 1
                if (writesSinceTrim >= TRIM_EVERY_WRITES) {
                    writesSinceTrim = 0
                    runCatching { trimToMaxSize() }
                }
            }
        }
    }

    /** Deletes the least recently written responses until the cache fits its size limit. */
    private fun trimToMaxSize() {
        val fs = cacheFileSystem
        if (!fs.exists(directory)) return
        val entries = fs.list(directory).mapNotNull { path ->
            val metadata = fs.metadataOrNull(path) ?: return@mapNotNull null
            Triple(path, metadata.size ?: 0L, metadata.lastModifiedAtMillis ?: 0L)
        }
        var total = entries.sumOf { it.second }
        val maxBytes = CacheSettingsRepository.maxSizeBytes(CacheType.Metadata)
        if (total <= maxBytes) return
        val targetBytes = maxBytes * 9 / 10
        for ((path, size, _) in entries.sortedBy { it.third }) {
            if (total <= targetBytes) break
            fs.delete(path, mustExist = false)
            total -= size
        }
    }
}
