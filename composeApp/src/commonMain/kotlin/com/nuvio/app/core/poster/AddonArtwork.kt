package com.nuvio.app.core.poster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.details.AddonArtworkLookup
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.watched.WatchedClock
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Artwork an addon's meta response provides for one title. */
data class AddonArtwork(
    val poster: String? = null,
    val landscapePoster: String? = null,
    val background: String? = null,
    val logo: String? = null,
) {
    val isEmpty: Boolean
        get() = poster == null && landscapePoster == null && background == null && logo == null
}

/**
 * Session cache of addon-provided artwork, used to show the same posters the user's meta addons
 * serve (e.g. rating posters) on surfaces whose items come from elsewhere: the library (Trakt,
 * Simkl, ... lists), Continue Watching and the details screen's recommendations.
 *
 * Lookups are lazy (only for items that are actually shown), deduplicated, limited in
 * concurrency, and reuse the on-disk metadata cache. The cache is dropped whenever the set of
 * enabled meta addons changes (addon edits, profile switches).
 */
object AddonArtworkRepository {
    private const val MAX_CONCURRENT_LOOKUPS = 4
    private const val FAILED_RETRY_AFTER_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val semaphore = Semaphore(MAX_CONCURRENT_LOOKUPS)
    private val lock = SynchronizedObject()

    private val _artwork = MutableStateFlow<Map<String, AddonArtwork>>(emptyMap())
    val artwork: StateFlow<Map<String, AddonArtwork>> = _artwork.asStateFlow()

    private val _generation = MutableStateFlow(0)
    /** Bumped whenever the cache is dropped, so shown items request their artwork again. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val inFlight = mutableSetOf<String>()
    private val unavailable = mutableSetOf<String>()
    private val failedAtByKey = mutableMapOf<String, Long>()
    private var observingAddons = false

    fun key(type: String, id: String): String = "${type.trim().lowercase()}:${id.trim()}"

    fun peek(type: String, id: String): AddonArtwork? = _artwork.value[key(type, id)]

    /** Starts a lookup for the item unless its artwork is cached, pending or known to be missing. */
    fun request(type: String, id: String) {
        if (!supports(type, id)) return
        startObservingAddons()
        val key = key(type, id)
        val requestGeneration = synchronized(lock) {
            if (key in _artwork.value || key in inFlight || key in unavailable) return
            val failedAt = failedAtByKey[key]
            if (failedAt != null && WatchedClock.nowEpochMs() - failedAt < FAILED_RETRY_AFTER_MS) return
            inFlight += key
            _generation.value
        }
        scope.launch {
            val lookup = try {
                semaphore.withPermit { MetaDetailsRepository.fetchAddonArtwork(type = type.trim(), id = id.trim()) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                AddonArtworkLookup.Failed
            }
            synchronized(lock) {
                // After a reset the key may already be pending again under the new generation.
                if (requestGeneration != _generation.value) return@launch
                inFlight -= key
                when (lookup) {
                    is AddonArtworkLookup.Loaded -> {
                        failedAtByKey -= key
                        _artwork.value = _artwork.value + (key to lookup.artwork)
                    }
                    AddonArtworkLookup.Unavailable -> unavailable += key
                    AddonArtworkLookup.Failed -> failedAtByKey[key] = WatchedClock.nowEpochMs()
                }
                Unit
            }
        }
    }

    private fun supports(type: String, id: String): Boolean {
        if (type.isBlank() || id.isBlank()) return false
        if (type.equals(CloudLibraryContentType, ignoreCase = true)) return false
        return ServerItemRef.parse(id) == null
    }

    private fun startObservingAddons() {
        synchronized(lock) {
            if (observingAddons) return
            observingAddons = true
        }
        scope.launch {
            AddonRepository.uiState
                .map { state ->
                    state.addons.enabledAddons()
                        .mapNotNull { addon -> addon.manifest }
                        .filter { manifest -> manifest.resources.any { it.name == "meta" } }
                        .joinToString("|") { manifest -> manifest.transportUrl }
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { clear() }
        }
    }

    private fun clear() {
        synchronized(lock) {
            inFlight.clear()
            unavailable.clear()
            failedAtByKey.clear()
            _artwork.value = emptyMap()
            _generation.value += 1
        }
    }
}

/** The addon artwork for one item, requesting it when first shown. Null until (or unless) it loads. */
@Composable
fun rememberAddonArtwork(type: String, id: String, enabled: Boolean = true): AddonArtwork? {
    val key = AddonArtworkRepository.key(type, id)
    val flow = remember(key, enabled) {
        AddonArtworkRepository.artwork.map { if (enabled) it[key] else null }.distinctUntilChanged()
    }
    val artwork by flow.collectAsStateWithLifecycle(
        initialValue = if (enabled) AddonArtworkRepository.peek(type, id) else null,
    )
    val generation by AddonArtworkRepository.generation.collectAsStateWithLifecycle()
    LaunchedEffect(key, enabled, generation) {
        if (enabled) AddonArtworkRepository.request(type, id)
    }
    return artwork
}

/** Addon artwork for several items at once (e.g. one row of a poster grid), keyed by [MetaPreview.addonArtworkKey]. */
@Composable
fun rememberAddonArtworks(items: List<MetaPreview>, enabled: Boolean = true): Map<String, AddonArtwork> {
    val keys = remember(items) { items.map { it.addonArtworkKey() } }
    val flow = remember(keys, enabled) {
        AddonArtworkRepository.artwork
            .map { all -> if (enabled) keys.mapNotNull { key -> all[key]?.let { key to it } }.toMap() else emptyMap() }
            .distinctUntilChanged()
    }
    val artwork by flow.collectAsStateWithLifecycle(
        initialValue = if (enabled) {
            val all = AddonArtworkRepository.artwork.value
            keys.mapNotNull { key -> all[key]?.let { key to it } }.toMap()
        } else {
            emptyMap()
        },
    )
    val generation by AddonArtworkRepository.generation.collectAsStateWithLifecycle()
    LaunchedEffect(keys, enabled, generation) {
        if (enabled) items.forEach { item -> AddonArtworkRepository.request(item.type, item.id) }
    }
    return artwork
}

fun MetaPreview.addonArtworkKey(): String = AddonArtworkRepository.key(type, id)

/**
 * Shows the addon's poster, landscape poster and backdrop. Apply it before a custom poster URL
 * pattern; where the pattern was applied already, pass [preserveCustomPoster] so the poster the
 * user chose (tracked through [MetaPreview.rawPosterUrl]) is kept.
 */
fun MetaPreview.withAddonArtwork(
    artwork: AddonArtwork?,
    preserveCustomPoster: Boolean = false,
): MetaPreview {
    if (artwork == null || artwork.isEmpty) return this
    val keepPoster = preserveCustomPoster && rawPosterUrl != null
    return copy(
        poster = if (keepPoster || posterShape != PosterShape.Poster) poster else artwork.poster ?: poster,
        landscapePoster = if (keepPoster) landscapePoster else artwork.landscapePoster ?: landscapePoster,
        banner = artwork.background ?: banner,
        logo = artwork.logo ?: logo,
    )
}

/**
 * Shows the addon's poster on Continue Watching, and its landscape poster as the wide card artwork.
 * Episode thumbnails keep their place when the user prefers them, artwork from a custom poster URL
 * pattern is left as is, and a media server's own artwork is kept, with the addon only filling gaps.
 */
fun ContinueWatchingItem.withAddonArtwork(artwork: AddonArtwork?): ContinueWatchingItem {
    if (artwork == null || artwork.isEmpty) return this
    val fillOnly = mediaServerArtwork
    val customLandscapeApplied = rawBackgroundUrl != null
    val keepPoster = rawPosterUrl != null || (fillOnly && !poster.isNullOrBlank())
    val keepLandscape = customLandscapeApplied || (fillOnly && !landscapePoster.isNullOrBlank())
    val keepBackground = customLandscapeApplied || (fillOnly && !background.isNullOrBlank())
    val newPoster = if (keepPoster) poster else artwork.poster ?: poster
    val newLandscape = if (keepLandscape) landscapePoster else artwork.landscapePoster ?: landscapePoster
    val newBackground = if (keepBackground) background else artwork.background ?: background
    val newImageUrl = when (imageUrl) {
        null -> newLandscape ?: newBackground ?: newPoster
        background -> newBackground
        poster -> newPoster
        else -> imageUrl
    }
    return copy(
        poster = newPoster,
        background = newBackground,
        landscapePoster = newLandscape,
        imageUrl = newImageUrl,
        logo = if (fillOnly) logo ?: artwork.logo else artwork.logo ?: logo,
    )
}
