package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DetailMetaInfo
import com.nuvio.app.features.details.components.DetailSeriesContent
import com.nuvio.app.features.details.components.DetailSeriesListEpisode
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TMDB
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RatingsVisibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overallVisibilityRespectsMdbListProvidersWithoutStandardRatingFallback() {
        val overall = mutableStateOf(true)
        val mdbActive = mutableStateOf(false)
        val externalRatings = mutableStateOf(listOf(MetaExternalRating(PROVIDER_TMDB, 65.0)))
        compose.setContent {
            NuvioTheme {
                DetailMetaInfo(
                    meta = MetaDetails("tt1234567", "movie", "Movie", imdbRating = "7.8", externalRatings = externalRatings.value),
                    showOverallRatings = overall.value,
                    isMdbListActive = mdbActive.value,
                )
            }
        }
        compose.onNodeWithText("7.8").assertIsDisplayed()
        compose.onNodeWithText("65").assertDoesNotExist()

        compose.runOnIdle { overall.value = false }
        compose.onNodeWithText("7.8").assertDoesNotExist()

        compose.runOnIdle { mdbActive.value = true }
        compose.onNodeWithText("65").assertIsDisplayed()
        compose.onNodeWithText("7.8").assertDoesNotExist()

        compose.runOnIdle {
            externalRatings.value = emptyList()
            overall.value = true
        }
        compose.onNodeWithText("7.8").assertDoesNotExist()
        compose.onNodeWithText("65").assertDoesNotExist()

        compose.runOnIdle { mdbActive.value = false }
        compose.onNodeWithText("7.8").assertIsDisplayed()
    }

    @Test
    fun allEpisodeLayoutsRespectVisibilityForImdbAndTmdbRatings() {
        // Episode ratings arrive on the episode itself: OMDb enrichment fills imdbRating and TMDB
        // enrichment fills tmdbRating. Every layout shows both and applies the visibility setting.
        val visibility = mutableStateOf(EpisodeRatingsVisibility.SHOW_ALL)
        val watchedKeys = mutableStateOf(emptySet<String>())
        val progress = mutableStateOf(emptyMap<String, WatchProgressEntry>())
        val layout = mutableStateOf(0)
        val episode = MetaVideo(
            id = "tt1234567:1:1",
            title = "Episode",
            season = 1,
            episode = 1,
            imdbRating = 8.4,
            tmdbRating = 6.2,
        )
        val meta = MetaDetails(id = "tt1234567", type = "series", name = "Series", videos = listOf(episode))
        compose.setContent {
            NuvioTheme {
                if (layout.value == 2) {
                    DetailSeriesListEpisode(
                        meta = meta,
                        episode = episode,
                        progressByVideoId = progress.value,
                        watchedKeys = watchedKeys.value,
                        episodeRatings = emptyMap(),
                        episodeRatingsVisibility = visibility.value,
                        blurUnwatchedEpisodes = false,
                        onEpisodeClick = null,
                        onEpisodeLongPress = null,
                    )
                } else {
                    DetailSeriesContent(
                        meta = meta,
                        episodeCardStyle = if (layout.value == 0) MetaEpisodeCardStyle.Horizontal else MetaEpisodeCardStyle.List,
                        progressByVideoId = progress.value,
                        watchedKeys = watchedKeys.value,
                        episodeRatings = emptyMap(),
                        episodeRatingsVisibility = visibility.value,
                    )
                }
            }
        }
        fun assertRatingsShown(shown: Boolean) {
            listOf("8.4", "6.2").forEach { rating ->
                if (shown) {
                    compose.onNodeWithText(rating).assertIsDisplayed()
                } else {
                    compose.onNodeWithText(rating).assertDoesNotExist()
                }
            }
        }
        for (currentLayout in 0..2) {
            compose.runOnIdle {
                layout.value = currentLayout
                visibility.value = EpisodeRatingsVisibility.SHOW_ALL
                watchedKeys.value = emptySet()
                progress.value = emptyMap()
            }
            assertRatingsShown(true)

            compose.runOnIdle { visibility.value = EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES }
            assertRatingsShown(false)

            compose.runOnIdle { watchedKeys.value = watchedItemKeys(meta.type, meta.id, 1, 1).toSet() }
            assertRatingsShown(true)

            compose.runOnIdle { visibility.value = EpisodeRatingsVisibility.HIDE_EPISODES }
            assertRatingsShown(false)

            compose.runOnIdle {
                visibility.value = EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES
                watchedKeys.value = emptySet()
                progress.value = mapOf(episode.id to WatchProgressEntry(
                    contentType = meta.type,
                    parentMetaId = meta.id,
                    parentMetaType = meta.type,
                    videoId = episode.id,
                    title = meta.name,
                    seasonNumber = 1,
                    episodeNumber = 1,
                    lastPositionMs = 1_140_000,
                    durationMs = 1_200_000,
                    lastUpdatedEpochMs = 1,
                ))
            }
            assertRatingsShown(true)
        }
    }
}
