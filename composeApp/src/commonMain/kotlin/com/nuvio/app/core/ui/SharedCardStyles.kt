package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.app.features.settings.ThemeSettingsRepository

/**
 * One app-wide subscription to the card style settings, shared by every poster, shelf header and
 * card-depth surface below it.
 *
 * Without this, each poster card on screen opened its own StateFlow collectors for the poster
 * style and the card depth style, and each shelf header one for the catalog accent. On a home
 * screen with many rows that is hundreds of collectors created and torn down while scrolling.
 *
 * The locals carry [State] objects whose identity never changes, so providing them never
 * invalidates the tree: only the composables that actually read `.value` recompose when a
 * setting changes.
 */
internal val LocalPosterCardStyleState = staticCompositionLocalOf<State<PosterCardStyleUiState>?> { null }
internal val LocalCardDepthStyleState = staticCompositionLocalOf<State<CardDepthStyleUiState>?> { null }
internal val LocalShowCatalogAccentState = staticCompositionLocalOf<State<Boolean>?> { null }

@Composable
internal fun SharedCardStylesProvider(content: @Composable () -> Unit) {
    PosterCardStyleRepository.ensureLoaded()
    CardDepthStyleRepository.ensureLoaded()
    val posterCardStyle = PosterCardStyleRepository.uiState.collectAsState()
    val cardDepthStyle = CardDepthStyleRepository.uiState.collectAsState()
    val showCatalogAccent = ThemeSettingsRepository.showCatalogAccentEnabled.collectAsState()
    CompositionLocalProvider(
        LocalPosterCardStyleState provides posterCardStyle,
        LocalCardDepthStyleState provides cardDepthStyle,
        LocalShowCatalogAccentState provides showCatalogAccent,
        content = content,
    )
}

@Composable
internal fun rememberShowCatalogAccentEnabled(): Boolean {
    val shared = LocalShowCatalogAccentState.current
    if (shared != null) return shared.value
    return ThemeSettingsRepository.showCatalogAccentEnabled.collectAsState().value
}
