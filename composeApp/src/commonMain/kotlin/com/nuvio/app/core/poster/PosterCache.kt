package com.nuvio.app.core.poster

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
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
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

/** A poster that should be kept in the poster cache. */
data class PosterCacheTarget(
    val imageUrl: String,
    val fallbackImageUrl: String? = null,
)

/**
 * Dedicated, long-lived cache for posters shown on Home, Discover and Search.
 *
 * - Posters are stored in their own disk cache (separate from backdrops, logos, etc.) so they
 *   can be cleared on their own and are not evicted by other images.
 * - While enabled, a cached poster is used as-is until it is older than the refresh interval
 *   chosen in settings (12/24/36/48 hours), regardless of the server's cache headers. After that
 *   it is downloaded again the next time it is shown or prefetched.
 * - Every poster listed on those screens is prefetched into the cache in the background, not just
 *   the ones scrolled into view.
 */
object PosterCache {

    /** Marks a request as a poster-cache request so [PosterCacheStrategy] applies the poster TTL. */
    val PosterRequestKey: Extras.Key<Boolean> = Extras.Key(default = false)

    private const val CACHED_AT_HEADER = "X-Nuvio-Poster-Cached-At"
    private const val MAX_SIZE_BYTES = 512L * 1024L * 1024L
    private const val PREFETCH_PARALLELISM = 4

    private val lock = SynchronizedObject()

    @Volatile
    private var diskCacheInstance: DiskCache? = null

    @Volatile
    private var loaderInstance: ImageLoader? = null

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefetchPermits = Semaphore(PREFETCH_PARALLELISM)
    private val prefetchLock = SynchronizedObject()
    private val prefetchedAt = HashMap<String, Long>()

    internal fun nowMillis(): Long = GMTDate().timestamp

    private fun diskCache(): DiskCache {
        diskCacheInstance?.let { return it }
        synchronized(lock) {
            diskCacheInstance?.let { return it }
            val cache = DiskCache.Builder()
                .directory(posterCacheDirectory())
                .maxSizeBytes(MAX_SIZE_BYTES)
                .build()
            diskCacheInstance = cache
            return cache
        }
    }

    /**
     * The [ImageLoader] that reads/writes the poster cache. It shares the app loader's components
     * (custom poster fallback, network client, decoders) and memory cache, but uses the dedicated
     * poster disk cache and tags every request with [PosterRequestKey].
     */
    fun imageLoader(context: PlatformContext): ImageLoader {
        loaderInstance?.let { return it }
        synchronized(lock) {
            loaderInstance?.let { return it }
            val base = SingletonImageLoader.get(context)
            val loader = base.newBuilder()
                .diskCache { diskCache() }
                .components(
                    base.components.newBuilder()
                        .add(PosterRequestTagInterceptor)
                        .build(),
                )
                .build()
            loaderInstance = loader
            return loader
        }
    }

    /** Size of the poster cache on disk, in bytes. Call off the main thread. */
    fun sizeBytes(): Long = runCatching { diskCache().size }.getOrDefault(0L)

    /** Deletes every cached poster. */
    suspend fun clear(context: PlatformContext) {
        withContext(Dispatchers.Default) {
            runCatching { diskCache().clear() }
        }
        resetPrefetchHistory()
        // Posters may also be held decoded in memory; drop them so they reload from the network.
        SingletonImageLoader.get(context).memoryCache?.clear()
    }

    internal fun resetPrefetchHistory() {
        synchronized(prefetchLock) { prefetchedAt.clear() }
    }

    /**
     * Downloads [targets] into the poster cache in the background. Posters that were already
     * prefetched within the current refresh interval are skipped. Does nothing while disabled.
     */
    fun prefetch(context: PlatformContext, targets: List<PosterCacheTarget>) {
        if (targets.isEmpty() || !PosterCacheSettingsRepository.isEnabled) return
        val now = nowMillis()
        val refreshMillis = PosterCacheSettingsRepository.refreshIntervalMillis
        val pending = synchronized(prefetchLock) {
            targets
                .asSequence()
                .filter { it.imageUrl.isNotBlank() }
                .distinctBy { it.imageUrl }
                .filter { target ->
                    val last = prefetchedAt[target.imageUrl]
                    if (last != null && now - last < refreshMillis) {
                        false
                    } else {
                        prefetchedAt[target.imageUrl] = now
                        true
                    }
                }
                .toList()
        }
        if (pending.isEmpty()) return
        val loader = imageLoader(context)
        pending.forEach { target ->
            prefetchScope.launch {
                prefetchPermits.withPermit {
                    if (!PosterCacheSettingsRepository.isEnabled) return@withPermit
                    val result = runCatching {
                        loader.execute(prefetchRequest(context, target))
                    }.getOrNull()
                    if (result == null || result is coil3.request.ErrorResult) {
                        // Allow a later retry instead of remembering a failed download.
                        synchronized(prefetchLock) { prefetchedAt.remove(target.imageUrl) }
                    }
                }
            }
        }
    }

    private fun prefetchRequest(context: PlatformContext, target: PosterCacheTarget): ImageRequest {
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
        builder.extras[PosterRequestKey] = true
        return builder.build()
    }

    internal fun isFresh(cacheResponse: NetworkResponse): Boolean {
        if (cacheResponse.code !in 200..299) return false
        val cachedAt = cacheResponse.headers[CACHED_AT_HEADER]?.toLongOrNull()
            ?: cacheResponse.responseMillis.takeIf { it > 0L }
            ?: return false
        val age = nowMillis() - cachedAt
        return age in 0L until PosterCacheSettingsRepository.refreshIntervalMillis
    }

    internal fun stamp(networkResponse: NetworkResponse): NetworkResponse =
        networkResponse.copy(
            headers = networkResponse.headers.newBuilder()
                .set(CACHED_AT_HEADER, nowMillis().toString())
                .build(),
        )
}

/**
 * Cache strategy installed on the app's network fetcher. Poster-cache requests (tagged with
 * [PosterCache.PosterRequestKey]) are kept for the configured refresh interval; every other
 * request uses [delegate] (HTTP cache-control semantics).
 */
class PosterCacheStrategy(
    private val delegate: CacheStrategy,
) : CacheStrategy {

    private fun appliesTo(options: Options): Boolean =
        options.getExtra(PosterCache.PosterRequestKey) && PosterCacheSettingsRepository.isEnabled

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult {
        if (!appliesTo(options)) return delegate.read(cacheResponse, networkRequest, options)
        return if (PosterCache.isFresh(cacheResponse)) {
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
            CacheStrategy.WriteResult(PosterCache.stamp(networkResponse))
        } else {
            CacheStrategy.WriteResult.DISABLED
        }
    }
}

/** Tags every request made through the poster loader as a poster-cache request. */
private object PosterRequestTagInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.extras[PosterCache.PosterRequestKey] == true) return chain.proceed()
        val tagged = request.newBuilder()
            .apply { extras[PosterCache.PosterRequestKey] = true }
            .build()
        return chain.withRequest(tagged).proceed()
    }
}

/** Decoder for prefetch requests: the fetch already wrote the poster to disk, so skip decoding. */
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
 * The image loader to use for posters on Home, Discover and Search: the poster cache loader when
 * the poster cache is enabled, otherwise the app's default loader.
 */
@Composable
fun rememberPosterImageLoader(): ImageLoader {
    val context = LocalPlatformContext.current
    val enabled by remember {
        PosterCacheSettingsRepository.ensureLoaded()
        PosterCacheSettingsRepository.enabled
    }.collectAsState()
    return remember(context, enabled) {
        if (enabled) PosterCache.imageLoader(context) else SingletonImageLoader.get(context)
    }
}

/** Prefetches every poster in [targets] into the poster cache while it is enabled. */
@Composable
fun PosterCachePrefetchEffect(targets: List<PosterCacheTarget>) {
    val context = LocalPlatformContext.current
    val enabled by remember {
        PosterCacheSettingsRepository.ensureLoaded()
        PosterCacheSettingsRepository.enabled
    }.collectAsState()
    LaunchedEffect(context, enabled, targets) {
        if (enabled) PosterCache.prefetch(context, targets)
    }
}
