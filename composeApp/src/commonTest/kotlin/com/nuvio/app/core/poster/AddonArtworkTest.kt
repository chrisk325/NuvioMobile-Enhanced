package com.nuvio.app.core.poster

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals

class AddonArtworkTest {
    private val artwork = AddonArtwork(
        poster = "addon-poster",
        landscapePoster = "addon-landscape",
        background = "addon-background",
        logo = "addon-logo",
    )

    private fun item(mediaServerArtwork: Boolean, landscapePoster: String? = null) = ContinueWatchingItem(
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:1",
        title = "Show",
        subtitle = "",
        imageUrl = "own-background",
        poster = "own-poster",
        background = "own-background",
        landscapePoster = landscapePoster,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = 0f,
        mediaServerArtwork = mediaServerArtwork,
    )

    @Test
    fun addonArtworkReplacesTrackerArtwork() {
        val result = item(mediaServerArtwork = false).withAddonArtwork(artwork)

        assertEquals("addon-poster", result.poster)
        assertEquals("addon-landscape", result.landscapePoster)
        assertEquals("addon-background", result.background)
    }

    @Test
    fun mediaServerArtworkIsKeptAndOnlyGapsAreFilled() {
        val withThumb = item(mediaServerArtwork = true, landscapePoster = "server-thumb").withAddonArtwork(artwork)
        assertEquals("own-poster", withThumb.poster)
        assertEquals("server-thumb", withThumb.landscapePoster)
        assertEquals("own-background", withThumb.background)

        val withoutThumb = item(mediaServerArtwork = true).withAddonArtwork(artwork)
        assertEquals("own-poster", withoutThumb.poster)
        assertEquals("addon-landscape", withoutThumb.landscapePoster)
    }
}
