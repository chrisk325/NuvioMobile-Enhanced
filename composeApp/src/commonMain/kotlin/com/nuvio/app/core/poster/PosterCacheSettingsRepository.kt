package com.nuvio.app.core.poster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings for the dedicated poster cache used by Home, Discover and Search.
 *
 * When enabled, every poster shown (or listed) on those screens is stored in its own disk cache
 * and served from it until it is older than [refreshIntervalHours], after which it is downloaded
 * again on next use.
 */
object PosterCacheSettingsRepository {
    val refreshIntervalOptions: List<Int> = listOf(12, 24, 36, 48)
    const val DEFAULT_REFRESH_INTERVAL_HOURS = 24

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _refreshIntervalHours = MutableStateFlow(DEFAULT_REFRESH_INTERVAL_HOURS)
    val refreshIntervalHours: StateFlow<Int> = _refreshIntervalHours.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _enabled.value = PosterCacheSettingsStorage.loadEnabled() ?: false
        _refreshIntervalHours.value = PosterCacheSettingsStorage.loadRefreshIntervalHours()
            ?.takeIf { it in refreshIntervalOptions }
            ?: DEFAULT_REFRESH_INTERVAL_HOURS
    }

    val isEnabled: Boolean
        get() {
            ensureLoaded()
            return _enabled.value
        }

    val refreshIntervalMillis: Long
        get() {
            ensureLoaded()
            return _refreshIntervalHours.value * 60L * 60L * 1000L
        }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        PosterCacheSettingsStorage.saveEnabled(enabled)
    }

    fun setRefreshIntervalHours(hours: Int) {
        ensureLoaded()
        if (hours !in refreshIntervalOptions || _refreshIntervalHours.value == hours) return
        _refreshIntervalHours.value = hours
        PosterCacheSettingsStorage.saveRefreshIntervalHours(hours)
        // Entries are re-validated against the new interval on their next read; drop the
        // prefetch bookkeeping so rows are re-checked straight away.
        PosterCache.resetPrefetchHistory()
    }
}
