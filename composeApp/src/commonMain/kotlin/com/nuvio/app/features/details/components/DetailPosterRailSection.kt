package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.poster.CustomPosterScreen
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.rememberAddonArtwork
import com.nuvio.app.core.poster.withAddonArtwork
import com.nuvio.app.core.ui.nuvioHorizontalScrollBleed
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.SkeletonPoster
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository

@Composable
fun DetailPosterRailSection(
    title: String,
    items: List<MetaPreview>,
    watchedKeys: Set<String>,
    modifier: Modifier = Modifier,
    fullyWatchedSeriesKeys: Set<String> = emptySet(),
    showHeader: Boolean = true,
    headerHorizontalPadding: Dp = 0.dp,
    horizontalScrollPadding: Dp = 0.dp,
    sourceLabel: String? = null,
    onViewAllClick: (() -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    /** Show the posters the user's meta addons provide instead of the items' own (TMDB/Trakt/Simkl) artwork. */
    useAddonArtwork: Boolean = false,
) {
    if (items.isEmpty()) return
    val posterCardStyle = rememberPosterCardStyleUiState()
    val preserveCustomPoster = remember(useAddonArtwork) {
        useAddonArtwork && CustomPosterUrlRepository.patternForScreen(CustomPosterScreen.DETAILS).isNotBlank()
    }
    val tmdbSettings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth()) {
        NuvioShelfSection(
            title = if (showHeader) title else "",
            entries = items,
            headerHorizontalPadding = headerHorizontalPadding,
            rowContentPadding = PaddingValues(
                horizontal = headerHorizontalPadding + horizontalScrollPadding,
            ),
            rowModifier = Modifier.nuvioHorizontalScrollBleed(horizontalScrollPadding),
            onViewAllClick = onViewAllClick.takeIf { showHeader },
            key = { item -> item.stableKey() },
        ) { item ->
            val landscape = posterCardStyle.catalogLandscapeModeEnabled || item.posterShape == PosterShape.Landscape
            val addonArtwork = rememberAddonArtwork(type = item.type, id = item.id, enabled = useAddonArtwork)
            val hasAddonLandscapeArt = addonArtwork?.landscapePoster != null || addonArtwork?.background != null
            val localizedBackdrop = rememberDetailBackdrop(
                item = item,
                language = tmdbSettings.language,
                enabled = landscape && !hasAddonLandscapeArt &&
                    (item.id.startsWith("tmdb:") || tmdbSettings.enabled && tmdbSettings.useArtwork),
            )
            if (localizedBackdrop.isLoading) {
                SkeletonPoster(
                    modifier = Modifier.width(landscapePosterWidth(posterCardStyle.widthDp)),
                    aspectRatio = PosterLandscapeAspectRatio,
                    cornerRadius = posterCardStyle.cornerRadiusDp.dp,
                    showLabels = !posterCardStyle.hideLabelsEnabled,
                    showDetail = false,
                )
            } else {
                val cardItem = remember(item, localizedBackdrop.url, addonArtwork, preserveCustomPoster) {
                    (localizedBackdrop.url?.let { item.copy(banner = it) } ?: item)
                        .withAddonArtwork(addonArtwork, preserveCustomPoster = preserveCustomPoster)
                }
                HomePosterCard(
                    item = cardItem,
                    useLandscapeBackdropMode = landscape,
                    showLandscapeOverlay = false,
                    isWatched = WatchingState.isPosterWatched(
                        watchedKeys = watchedKeys,
                        item = item,
                        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    ),
                    onClick = onPosterClick?.let { { it(item) } },
                    onLongClick = onPosterLongClick?.let { { it(item) } },
                )
            }
        }

        sourceLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
                Text(
                    text = label,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = headerHorizontalPadding, top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}

@Composable
private fun rememberDetailBackdrop(item: MetaPreview, language: String, enabled: Boolean): DetailBackdrop {
    var backdrop by remember(item.id, item.type, language, enabled) {
        mutableStateOf(DetailBackdrop(isLoading = enabled))
    }
    LaunchedEffect(item.id, item.type, language, enabled) {
        if (enabled) {
            backdrop = DetailBackdrop(
                url = TmdbMetadataService.fetchLocalizedBackdrop(item.id, item.type, language),
                isLoading = false,
            )
        }
    }
    return backdrop
}

private data class DetailBackdrop(val url: String? = null, val isLoading: Boolean)
