package com.nuvio.app

import android.app.Application
import android.os.Build
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.network.DnsPlatform
import com.nuvio.app.core.network.SharedOkHttpClient
import com.nuvio.app.core.network.NetworkErrorInterceptor
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import com.nuvio.app.core.cache.AppImageLoaderComponents
import com.nuvio.app.core.cache.CacheSettingsStorage
import com.nuvio.app.core.cache.ContentCacheStrategy
import com.nuvio.app.features.servers.plex.PlexArtworkInterceptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Custom Application class that implements [SingletonImageLoader.Factory] to guarantee
 * the Coil ImageLoader (with [CustomPosterFallbackInterceptor]) is created before any
 * image request. Without this, Coil's auto-init ContentProvider creates the singleton
 * before MainActivity.onCreate, and interceptors registered later are ignored.
 */
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        DnsPlatform.initialize(this)
        CacheSettingsStorage.initialize(this)
    }

    /** One network fetcher (and HTTP client) shared by the app loader and the cache loaders. */
    private val imageNetworkFetcherFactory by lazy {
        KtorNetworkFetcherFactory(
            httpClient = {
                HttpClient(OkHttp) {
                    engine {
                        preconfigured = SharedOkHttpClient.base
                        addInterceptor(NetworkErrorInterceptor())
                    }
                }
            },
            cacheStrategy = { ContentCacheStrategy(CacheControlCacheStrategy()) },
        )
    }

    private fun ComponentRegistry.Builder.addAppImageComponents() {
        add(CustomPosterFallbackInterceptor())
        add(PlexArtworkInterceptor())
        add(SvgDecoder.Factory())
        add(imageNetworkFetcherFactory)
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        AppImageLoaderComponents.register { addAppImageComponents() }
        return ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components { addAppImageComponents() }
            .build()
    }
}
