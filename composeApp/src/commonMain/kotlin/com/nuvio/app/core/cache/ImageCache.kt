package com.nuvio.app.core.cache

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import coil3.Canvas
import coil3.Extras
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import io.ktor.util.date.GMTDate
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** An image that should be kept in one of the image caches. */
data class ImageCacheTarget(
    val imageUrl: String,
    val fallbackImageUrl: String? = null,
)

internal fun cacheNowMillis(): Long = GMTDate().timestamp

/**
 * Dedicated, long-lived image caches for posters ([CacheType.Posters]) and background artwork
 * ([CacheType.Backgrounds]).
 *
 * - Each cache has its own disk cache (separate from other images) so it can be cleared on its
 *   own and is not evicted by unrelated images.
 * - While a cache is enabled, a cached image is used as-is until it is older than the refresh
 *   interval chosen in settings (12/24/36/48 hours), regardless of the server's cache headers.
 *   After that it is downloaded again the next time it is shown or prefetched.
 * - Screens prefetch every image they list into the cache in the background, not just the ones
 *   scrolled into view.
 */
object ImageCache {

    /** Marks a request as belonging to an image cache so [ContentCacheStrategy] applies its TTL. */
    val CacheTypeKey: Extras.Key<CacheType?> = Extras.Key(default = null)

    private const val CACHED_AT_HEADER = "X-Nuvio-Cached-At"
    private const val PREFETCH_PARALLELISM = 4

    private val lock = SynchronizedObject()

    @Volatile
    private var diskCaches: Map<CacheType, DiskCache> = emptyMap()

    @Volatile
    private var loaders: Map<CacheType, ImageLoader> = emptyMap()

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefetchPermits = Semaphore(PREFETCH_PARALLELISM)
    private val prefetchLock = SynchronizedObject()
    private val prefetchedAt = HashMap<String, Long>()

    private fun requireImageType(type: CacheType) {
        require(type != CacheType.Metadata) { "Metadata is not an image cache" }
    }

    private fun diskCache(type: CacheType): DiskCache {
        requireImageType(type)
        diskCaches[type]?.let { return it }
        synchronized(lock) {
            diskCaches[type]?.let { return it }
            val cache = DiskCache.Builder()
                .directory(cacheBaseDirectory() / type.directoryName)
                .maxSizeBytes(type.maxSizeBytes)
                .build()
            diskCaches = diskCaches + (type to cache)
            return cache
        }
    }

    /**
     * The [ImageLoader] that reads/writes the [type] cache. It shares the app loader's components
     * (custom poster fallback, network client, decoders) and memory cache, but uses the cache's
     * own disk cache and tags every request with [CacheTypeKey].
     */
    fun imageLoader(type: CacheType, context: PlatformContext): ImageLoader {
        requireImageType(type)
        loaders[type]?.let { return it }
        synchronized(lock) {
            loaders[type]?.let { return it }
            val base = SingletonImageLoader.get(context)
            val loader = base.newBuilder()
                .diskCache { diskCache(type) }
                .components(
                    base.components.newBuilder()
                        .add(CacheTypeTagInterceptor(type))
                        .build(),
                )
                .build()
            loaders = loaders + (type to loader)
            return loader
        }
    }

    /** [type]'s cache loader while that cache is enabled, otherwise the app's default loader. */
    fun loaderFor(type: CacheType, context: PlatformContext): ImageLoader =
        if (CacheSettingsRepository.isEnabled(type)) imageLoader(type, context) else SingletonImageLoader.get(context)

    /** Size of the [type] cache on disk, in bytes. Call off the main thread. */
    fun sizeBytes(type: CacheType): Long = runCatching { diskCache(type).size }.getOrDefault(0L)

    /** Deletes every image in the [type] cache. */
    suspend fun clear(type: CacheType, context: PlatformContext) {
        withContext(Dispatchers.Default) {
            runCatching { diskCache(type).clear() }
        }
        synchronized(prefetchLock) {
            prefetchedAt.keys.removeAll { it.startsWith(prefetchKeyPrefix(type)) }
        }
        // Images may also be held decoded in memory; drop them so they reload from the network.
        SingletonImageLoader.get(context).memoryCache?.clear()
    }

    internal fun resetPrefetchHistory() {
        synchronized(prefetchLock) { prefetchedAt.clear() }
    }

    private fun prefetchKeyPrefix(type: CacheType) = "${type.name}|"

    /**
     * Downloads [targets] into the [type] cache in the background. Images already prefetched
     * within the current refresh interval are skipped. Does nothing while the cache is disabled.
     */
    fun prefetch(type: CacheType, context: PlatformContext, targets: List<ImageCacheTarget>) {
        if (targets.isEmpty() || !CacheSettingsRepository.isEnabled(type)) return
        val now = cacheNowMillis()
        val refreshMillis = CacheSettingsRepository.refreshIntervalMillis
        val prefix = prefetchKeyPrefix(type)
        val pending = synchronized(prefetchLock) {
            targets
                .asSequence()
                .filter { it.imageUrl.isNotBlank() }
                .distinctBy { it.imageUrl }
                .filter { target ->
                    val key = prefix + target.imageUrl
                    val last = prefetchedAt[key]
                    if (last != null && now - last < refreshMillis) {
                        false
                    } else {
                        prefetchedAt[key] = now
                        true
                    }
                }
                .toList()
        }
        if (pending.isEmpty()) return
        val loader = imageLoader(type, context)
        pending.forEach { target ->
            prefetchScope.launch {
                prefetchPermits.withPermit {
                    if (!CacheSettingsRepository.isEnabled(type)) return@withPermit
                    val result = runCatching {
                        loader.execute(prefetchRequest(context, target))
                    }.getOrNull()
                    if (result == null || result is ErrorResult) {
                        // Allow a later retry instead of remembering a failed download.
                        synchronized(prefetchLock) { prefetchedAt.remove(prefix + target.imageUrl) }
                    }
                }
            }
        }
    }

    private fun prefetchRequest(context: PlatformContext, target: ImageCacheTarget): ImageRequest {
        val builder = ImageRequest.Builder(context)
            .data(target.imageUrl)
            // Only the downloaded bytes matter here: skip the memory cache and decoding.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .decoderFactory(DownloadOnlyDecoderFactory)
        val fallback = target.fallbackImageUrl
        if (!fallback.isNullOrBlank() && fallback != target.imageUrl) {
            builder.memoryCacheKeyExtras(
                mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallback),
            )
        }
        return builder.build()
    }

    internal fun isFresh(cacheResponse: NetworkResponse): Boolean {
        if (cacheResponse.code !in 200..299) return false
        val cachedAt = cacheResponse.headers[CACHED_AT_HEADER]?.toLongOrNull()
            ?: cacheResponse.responseMillis.takeIf { it > 0L }
            ?: return false
        val age = cacheNowMillis() - cachedAt
        return age in 0L until CacheSettingsRepository.refreshIntervalMillis
    }

    internal fun stamp(networkResponse: NetworkResponse): NetworkResponse =
        networkResponse.copy(
            headers = networkResponse.headers.newBuilder()
                .set(CACHED_AT_HEADER, cacheNowMillis().toString())
                .build(),
        )
}

/**
 * Cache strategy installed on the app's network fetcher. Requests made through an image cache
 * loader (tagged with [ImageCache.CacheTypeKey]) are kept for the configured refresh interval;
 * every other request uses [delegate] (HTTP cache-control semantics).
 */
class ContentCacheStrategy(
    private val delegate: CacheStrategy,
) : CacheStrategy {

    private fun appliesTo(options: Options): Boolean {
        val type = options.getExtra(ImageCache.CacheTypeKey) ?: return false
        return CacheSettingsRepository.isEnabled(type)
    }

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult {
        if (!appliesTo(options)) return delegate.read(cacheResponse, networkRequest, options)
        return if (ImageCache.isFresh(cacheResponse)) {
            CacheStrategy.ReadResult(cacheResponse)
        } else {
            CacheStrategy.ReadResult(networkRequest)
        }
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        if (!appliesTo(options)) {
            return delegate.write(cacheResponse, networkRequest, networkResponse, options)
        }
        return if (networkResponse.code in 200..299) {
            CacheStrategy.WriteResult(ImageCache.stamp(networkResponse))
        } else {
            CacheStrategy.WriteResult.DISABLED
        }
    }
}

/** Tags every request made through an image cache loader with that cache's [CacheType]. */
private class CacheTypeTagInterceptor(private val type: CacheType) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.extras[ImageCache.CacheTypeKey] == type) return chain.proceed()
        val tagged = request.newBuilder()
            .apply { extras[ImageCache.CacheTypeKey] = type }
            .build()
        return chain.withRequest(tagged).proceed()
    }
}

/** Decoder for prefetch requests: the fetch already wrote the image to disk, so skip decoding. */
private object DownloadOnlyDecoderFactory : Decoder.Factory {
    override fun create(
        result: coil3.fetch.SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder = object : Decoder {
        override suspend fun decode(): DecodeResult {
            result.source.close()
            return DecodeResult(image = EmptyImage, isSampled = false)
        }
    }
}

private object EmptyImage : Image {
    override val size: Long = 0L
    override val width: Int = 1
    override val height: Int = 1
    override val shareable: Boolean = true
    override fun draw(canvas: Canvas) = Unit
}

/**
 * The image loader to use for images belonging to the [type] cache: that cache's loader while it
 * is enabled, otherwise the app's default loader.
 */
@Composable
fun rememberCachedImageLoader(type: CacheType): ImageLoader {
    val context = LocalPlatformContext.current
    val enabled by remember(type) { CacheSettingsRepository.enabled(type) }.collectAsState()
    return remember(context, type, enabled) {
        if (enabled) ImageCache.imageLoader(type, context) else SingletonImageLoader.get(context)
    }
}

/** Loader for posters on Home, Discover and Search. */
@Composable
fun rememberPosterImageLoader(): ImageLoader = rememberCachedImageLoader(CacheType.Posters)

/** Loader for background artwork (backdrops). */
@Composable
fun rememberBackgroundImageLoader(): ImageLoader = rememberCachedImageLoader(CacheType.Backgrounds)

/** Prefetches every image in [targets] into the [type] cache while it is enabled. */
@Composable
fun ImageCachePrefetchEffect(type: CacheType, targets: List<ImageCacheTarget>) {
    val context = LocalPlatformContext.current
    val enabled by remember(type) { CacheSettingsRepository.enabled(type) }.collectAsState()
    LaunchedEffect(context, type, enabled, targets) {
        if (enabled) ImageCache.prefetch(type, context, targets)
    }
}
