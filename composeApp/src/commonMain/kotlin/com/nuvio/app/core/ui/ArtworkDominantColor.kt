package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.kmpalette.generatePalette
import com.kmpalette.loader.PainterLoader
import com.nuvio.app.features.details.components.loadedBackdropImageBitmap
import kotlinx.coroutines.CancellationException

private const val PALETTE_SAMPLE_PX = 96
private const val CACHE_CAPACITY = 256

/** Cached marker for artwork that was sampled but has no usable colour. */
private val NoPalette = ArtworkPalette(Color.Transparent, Color.Transparent, Color.Transparent)

object ArtworkDominantColorCache {
    private val entries = LinkedHashMap<String, ArtworkPalette>()

    fun get(url: String): ArtworkPalette? {
        val palette = entries.remove(url) ?: return null
        entries[url] = palette
        return palette
    }

    fun put(url: String, palette: ArtworkPalette) {
        entries.remove(url)
        entries[url] = palette
        if (entries.size > CACHE_CAPACITY) {
            entries.remove(entries.keys.first())
        }
    }
}

sealed interface ArtworkTint {
    data object Pending : ArtworkTint
    data object None : ArtworkTint
    data class Tinted(val palette: ArtworkPalette) : ArtworkTint {
        val value: Color get() = palette.primary
    }
}

@Composable
fun rememberArtworkDominantColor(url: String?, enabled: Boolean = true): Color? =
    (rememberArtworkTint(url, enabled) as? ArtworkTint.Tinted)?.value

@Composable
fun rememberArtworkTint(url: String?, enabled: Boolean = true): ArtworkTint {
    val key = url?.takeIf { it.isNotBlank() }
    var palette by remember(key) { mutableStateOf(key?.let(ArtworkDominantColorCache::get)) }
    var failed by remember(key) { mutableStateOf(false) }
    val sampler = rememberArtworkPaletteSampler()

    LaunchedEffect(key, enabled) {
        if (!enabled || key == null || palette != null) return@LaunchedEffect
        val sampled = sampler.sample(key)
        if (sampled != null) palette = sampled else failed = true
    }

    val resolved = palette
    return when {
        key == null -> ArtworkTint.None
        resolved == null -> if (failed) ArtworkTint.None else ArtworkTint.Pending
        resolved == NoPalette -> ArtworkTint.None
        else -> ArtworkTint.Tinted(resolved)
    }
}

@Composable
fun PrefetchArtworkDominantColors(urls: List<String>) {
    val sampler = rememberArtworkPaletteSampler()
    LaunchedEffect(urls) {
        urls.filter { it.isNotBlank() }.distinct().forEach { url ->
            if (ArtworkDominantColorCache.get(url) == null) {
                sampler.sample(url)
            }
        }
    }
}

@Composable
private fun rememberArtworkPaletteSampler(): ArtworkPaletteSampler {
    val platformContext = LocalPlatformContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return remember(platformContext, density, layoutDirection) {
        ArtworkPaletteSampler(platformContext, PainterLoader(density, layoutDirection))
    }
}

private class ArtworkPaletteSampler(
    private val platformContext: PlatformContext,
    private val painterLoader: PainterLoader,
) {
    /** Returns the sampled palette (cached, possibly [NoPalette]), or null if the image failed to load. */
    suspend fun sample(url: String): ArtworkPalette? {
        val request = ImageRequest.Builder(platformContext)
            .data(url)
            .size(PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX)
            .build()
        // Backdrop artwork: read it through the background cache (when enabled) so it is only
        // downloaded once for both display and colour sampling.
        val result = com.nuvio.app.core.cache.ImageCache
            .loaderFor(com.nuvio.app.core.cache.CacheType.Backgrounds, platformContext)
            .execute(request) as? SuccessResult ?: return null
        val generated = try {
            val bitmap = loadedBackdropImageBitmap(result)
                ?: painterLoader.load(result.image.asPainter(platformContext))
            bitmap.generatePalette()
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            return null
        }
        val resolved = generated.toArtworkPalette() ?: NoPalette
        ArtworkDominantColorCache.put(url, resolved)
        return resolved
    }
}
