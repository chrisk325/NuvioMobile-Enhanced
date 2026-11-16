package com.nuvio.app.core.cache

import coil3.ComponentRegistry
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import kotlin.concurrent.Volatile

/**
 * The app's own image components (interceptors, decoders, network fetcher), registered by
 * whoever builds the app's singleton ImageLoader.
 *
 * The cache loaders must be built from these rather than from `ImageLoader.components`: the
 * finished registry of a built loader also contains that loader's internal engine interceptor,
 * which would run the request against the singleton (and its disk cache) instead of the cache
 * loader.
 */
object AppImageLoaderComponents {
    @Volatile
    private var registrar: (ComponentRegistry.Builder.() -> Unit)? = null

    /** Registers the app's components. Call this from the singleton ImageLoader factory. */
    fun register(block: ComponentRegistry.Builder.() -> Unit) {
        registrar = block
    }

    /** Adds the registered app components to [builder] (or a minimal default set). */
    internal fun addTo(builder: ComponentRegistry.Builder) {
        val block = registrar
        if (block != null) {
            builder.block()
        } else {
            builder.add(CustomPosterFallbackInterceptor())
            builder.add(
                KtorNetworkFetcherFactory(
                    cacheStrategy = { ContentCacheStrategy(CacheControlCacheStrategy()) },
                ),
            )
        }
    }
}
