package com.nuvio.app.core.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The content caches the user can turn on, refresh and clear from settings. */
enum class CacheType(
    internal val enabledKey: String,
    internal val directoryName: String,
    /** Share (percent) of the user's maximum cache storage this cache may use. */
    internal val storageSharePercent: Int,
) {
    /** Posters on Home, Discover and Search. */
    Posters("poster_cache_enabled", "poster_cache", 35),

    /** Background artwork (backdrops) on the Home hero, details and streams screens. */
    Backgrounds("background_cache_enabled", "background_cache", 55),

    /** Addon catalog / meta responses and TMDB metadata. */
    Metadata("metadata_cache_enabled", "metadata_cache", 10),
}

/**
 * Settings for the content caches. Each cache can be turned on separately; all of them share one
 * refresh interval, after which cached entries are fetched again on next use.
 */
object CacheSettingsRepository {
    val refreshIntervalOptions: List<Int> = listOf(12, 24, 36, 48)
    const val DEFAULT_REFRESH_INTERVAL_HOURS = 24
    private const val REFRESH_INTERVAL_KEY = "cache_refresh_interval_hours"

    /** Maximum storage (all caches together) the user can pick, in MB, and the slider step. */
    const val MIN_MAX_STORAGE_MB = 256
    const val MAX_MAX_STORAGE_MB = 5 * 1024
    const val MAX_STORAGE_STEP_MB = 256
    const val DEFAULT_MAX_STORAGE_MB = 2 * 1024
    private const val MAX_STORAGE_KEY = "cache_max_storage_mb"

    private val _maxStorageMb = MutableStateFlow(DEFAULT_MAX_STORAGE_MB)

    /** Maximum storage all caches may use together, in MB. */
    val maxStorageMb: StateFlow<Int> = _maxStorageMb.asStateFlow()

    private val enabledFlows: Map<CacheType, MutableStateFlow<Boolean>> =
        CacheType.entries.associateWith { MutableStateFlow(false) }

    private val _refreshIntervalHours = MutableStateFlow(DEFAULT_REFRESH_INTERVAL_HOURS)
    val refreshIntervalHours: StateFlow<Int> = _refreshIntervalHours.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        CacheType.entries.forEach { type ->
            enabledFlows.getValue(type).value = CacheSettingsStorage.loadBoolean(type.enabledKey) ?: false
        }
        _refreshIntervalHours.value = CacheSettingsStorage.loadInt(REFRESH_INTERVAL_KEY)
            ?.takeIf { it in refreshIntervalOptions }
            ?: DEFAULT_REFRESH_INTERVAL_HOURS
        _maxStorageMb.value = normalizeMaxStorageMb(
            CacheSettingsStorage.loadInt(MAX_STORAGE_KEY) ?: DEFAULT_MAX_STORAGE_MB,
        )
    }

    /** Rounds [mb] to the slider step and clamps it to the allowed range. */
    fun normalizeMaxStorageMb(mb: Int): Int {
        val stepped = ((mb + MAX_STORAGE_STEP_MB / 2) / MAX_STORAGE_STEP_MB) * MAX_STORAGE_STEP_MB
        return stepped.coerceIn(MIN_MAX_STORAGE_MB, MAX_MAX_STORAGE_MB)
    }

    /** The most [type] may store on disk: its share of the user's maximum cache storage. */
    fun maxSizeBytes(type: CacheType): Long {
        ensureLoaded()
        return _maxStorageMb.value * 1024L * 1024L * type.storageSharePercent / 100L
    }

    /**
     * Sets the maximum storage all caches may use together. Caches over their new share drop
     * their least recently used entries straight away.
     */
    fun setMaxStorageMb(mb: Int) {
        ensureLoaded()
        val normalized = normalizeMaxStorageMb(mb)
        if (_maxStorageMb.value == normalized) return
        _maxStorageMb.value = normalized
        CacheSettingsStorage.saveInt(MAX_STORAGE_KEY, normalized)
        ImageCache.onStorageLimitChanged()
        MetadataCache.onStorageLimitChanged()
    }

    fun enabled(type: CacheType): StateFlow<Boolean> {
        ensureLoaded()
        return enabledFlows.getValue(type).asStateFlow()
    }

    fun isEnabled(type: CacheType): Boolean {
        ensureLoaded()
        return enabledFlows.getValue(type).value
    }

    val refreshIntervalMillis: Long
        get() {
            ensureLoaded()
            return _refreshIntervalHours.value * 60L * 60L * 1000L
        }

    fun setEnabled(type: CacheType, enabled: Boolean) {
        ensureLoaded()
        val flow = enabledFlows.getValue(type)
        if (flow.value == enabled) return
        flow.value = enabled
        CacheSettingsStorage.saveBoolean(type.enabledKey, enabled)
    }

    fun setRefreshIntervalHours(hours: Int) {
        ensureLoaded()
        if (hours !in refreshIntervalOptions || _refreshIntervalHours.value == hours) return
        _refreshIntervalHours.value = hours
        CacheSettingsStorage.saveInt(REFRESH_INTERVAL_KEY, hours)
        // Entries are re-validated against the new interval on their next read; drop the
        // prefetch bookkeeping so screens are re-checked straight away.
        ImageCache.resetPrefetchHistory()
    }
}
