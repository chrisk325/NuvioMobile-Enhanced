package com.nuvio.app.features.player.aiostreams

import android.graphics.Rect
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShownVideoTest {
    private val stage = Rect(0, 0, 2000, 900)
    private val wide = 16f / 9f

    @Test
    fun fitsAVideoIntoAFrameLeftTheStagesSize() {
        assertEquals(Rect(200, 0, 1800, 900), shownVideo(stage, wide, AspectRatioFrameLayout.RESIZE_MODE_FIT))
    }

    @Test
    fun keepsAFrameThatAlreadyHasTheVideosShape() {
        val frame = Rect(200, 0, 1800, 900)
        assertEquals(frame, shownVideo(frame, wide, AspectRatioFrameLayout.RESIZE_MODE_FIT))
    }

    @Test
    fun zoomCoversTheFrame() {
        assertEquals(Rect(0, -112, 2000, 1013), shownVideo(stage, wide, AspectRatioFrameLayout.RESIZE_MODE_ZOOM))
    }

    @Test
    fun fillStretchesOverTheFrame() {
        assertEquals(stage, shownVideo(stage, wide, AspectRatioFrameLayout.RESIZE_MODE_FILL))
    }

    @Test
    fun keepsTheFrameUntilTheVideoSizeIsKnown() {
        assertEquals(stage, shownVideo(stage, 0f, AspectRatioFrameLayout.RESIZE_MODE_FIT))
    }
}
