package com.nuvio.app.features.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// 480px wide keeps the glass crisp enough for a "light blur" look; ~30fps keeps it in step with the video.
private const val VideoFrameCaptureWidth = 480
private const val VideoFrameCaptureIntervalMs = 33L
private const val VideoFrameBufferCount = 3

/** View tag carried by SurfaceViews that sit over the video (e.g. the libass subtitle layer). */
internal const val PlayerOverlaySurfaceTag = "nuvio:player-overlay-surface"

@Composable
internal actual fun rememberVideoFrameBackdrop(enabled: Boolean): VideoFrameBackdrop? {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val view = LocalView.current
    val backdrop = remember { VideoFrameBackdrop() }
    LaunchedEffect(backdrop, view) {
        val handler = Handler(Looper.getMainLooper())
        val buffers = arrayOfNulls<Bitmap>(VideoFrameBufferCount)
        var nextBuffer = 0
        snapshotFlow { backdrop.consumers > 0 }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (!active) {
                    backdrop.clearFrame()
                    return@collectLatest
                }
                while (currentCoroutineContext().isActive) {
                    val surfaceView = view.rootView.findVideoSurfaceView()
                    if (surfaceView != null) {
                        val width = VideoFrameCaptureWidth
                        val height = (width * surfaceView.height / surfaceView.width.toFloat())
                            .roundToInt()
                            .coerceIn(1, width * 4)
                        val bitmap = buffers[nextBuffer]?.takeIf { it.width == width && it.height == height }
                            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                buffers[nextBuffer] = it
                            }
                        if (surfaceView.copyFrameInto(bitmap, handler)) {
                            val location = IntArray(2)
                            surfaceView.getLocationOnScreen(location)
                            backdrop.publishFrame(
                                image = bitmap.asImageBitmap(),
                                boundsOnScreen = Rect(
                                    left = location[0].toFloat(),
                                    top = location[1].toFloat(),
                                    right = (location[0] + surfaceView.width).toFloat(),
                                    bottom = (location[1] + surfaceView.height).toFloat(),
                                ),
                            )
                            nextBuffer = (nextBuffer + 1) % buffers.size
                        }
                    }
                    delay(VideoFrameCaptureIntervalMs)
                }
            }
    }
    DisposableEffect(backdrop) {
        onDispose { backdrop.clearFrame() }
    }
    return backdrop
}

/**
 * Finds the SurfaceView that actually carries decoded video.
 *
 * ExoPlayer's [PlayerView] is asked directly for its video surface. Otherwise (libmpv, whose view is a
 * SurfaceView itself) the largest visible SurfaceView wins. Overlay surfaces tagged with
 * [PlayerOverlaySurfaceTag] (the full-screen, transparent libass layer) are skipped: when the video is
 * letterboxed they are larger than the video surface and used to be picked, which made the glass empty.
 */
private fun View.findVideoSurfaceView(): SurfaceView? {
    var best: SurfaceView? = null
    var bestArea = 0
    var fromPlayerView: SurfaceView? = null
    fun SurfaceView.isUsable(): Boolean =
        isShown && width > 0 && height > 0 && holder.surface?.isValid == true
    fun visit(candidate: View) {
        if (fromPlayerView != null) return
        if (candidate.visibility != View.VISIBLE) return
        if (candidate is PlayerView) {
            val videoSurface = candidate.videoSurfaceView as? SurfaceView
            if (videoSurface != null && videoSurface.isUsable()) {
                fromPlayerView = videoSurface
                return
            }
        }
        if (candidate is SurfaceView) {
            if (candidate.tag == PlayerOverlaySurfaceTag) return
            val area = candidate.width * candidate.height
            if (area > bestArea && candidate.isUsable()) {
                best = candidate
                bestArea = area
            }
        } else if (candidate is ViewGroup) {
            for (index in 0 until candidate.childCount) {
                visit(candidate.getChildAt(index))
            }
        }
    }
    visit(this)
    return fromPlayerView ?: best
}

private suspend fun SurfaceView.copyFrameInto(bitmap: Bitmap, handler: Handler): Boolean {
    if (width <= 0 || height <= 0 || holder.surface?.isValid != true) return false
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(
                this,
                bitmap,
                { result ->
                    if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                },
                handler,
            )
        } catch (_: IllegalArgumentException) {
            if (continuation.isActive) continuation.resume(false)
        }
    }
}
