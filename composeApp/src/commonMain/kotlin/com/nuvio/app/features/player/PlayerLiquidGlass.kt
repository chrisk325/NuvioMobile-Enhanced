package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.nuvio.app.core.ui.glass.LiquidGlassBackdropHost
import com.nuvio.app.core.ui.glass.ambientLiquidGlass
import kotlin.math.roundToInt

internal val PlayerGlassTint = Color(0xFF101014).copy(alpha = 0.52f)

// ---------------------------------------------------------------------------------------------
// Player liquid glass: clear glass with a light blur over the live video.
// ---------------------------------------------------------------------------------------------

/** Light blur so the video stays readable through the glass. */
internal val PlayerGlassBlur: Dp = 6.dp

/** Very low tint: mostly transparent, just enough to keep white icons legible on bright scenes. */
internal val PlayerGlassSurfaceTint = Color.Black.copy(alpha = 0.12f)

/** Used when there is no live backdrop (Android < 12, protected/DRM video, glass turned off). */
internal val PlayerGlassFallback = Color.Black.copy(alpha = 0.38f)

/** Hairline rim that sells the glass edge on top of Kyant's highlight. */
internal val PlayerGlassRim = Color.White.copy(alpha = 0.16f)

/**
 * The one glass recipe every player control uses: Kyant backdrop with vibrancy, a light blur, lens
 * refraction and edge highlight, sampled from the live video frame provided by
 * [rememberVideoFrameBackdrop]. Falls back to a translucent fill when the backdrop isn't available.
 */
@Composable
internal fun Modifier.playerLiquidGlass(
    shape: Shape,
    blurRadius: Dp = PlayerGlassBlur,
    refractionHeight: Dp = 8.dp,
    refractionAmount: Dp = 12.dp,
    tint: Color = PlayerGlassSurfaceTint,
    fallback: Color = PlayerGlassFallback,
    rim: Boolean = true,
): Modifier {
    val glass = clip(shape).ambientLiquidGlass(
        shape = shape,
        tint = tint,
        fallback = fallback,
        blurRadius = blurRadius,
        refractionHeight = refractionHeight,
        refractionAmount = refractionAmount,
        withVibrancy = true,
        withHighlight = true,
        withShadow = false,
    )
    return if (rim) glass.border(0.75.dp, PlayerGlassRim, shape) else glass
}

// Keep the captured frame close to the real picture so the glass reads as transparent;
// only a touch of dimming so it sits naturally under the controls' scrims.
private val VideoFrameColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0.95f)
        timesAssign(ColorMatrix().apply { setToScale(0.88f, 0.88f, 0.88f, 1f) })
    },
)

@Stable
class VideoFrameBackdrop : Backdrop, LiquidGlassBackdropHost {

    override val isCoordinatesDependent: Boolean = true

    private var frame: ImageBitmap? by mutableStateOf(null, neverEqualPolicy())

    private var frameBounds: Rect = Rect.Zero

    private var ready: Boolean by mutableStateOf(false)

    override val isReady: Boolean
        get() = ready

    internal var consumers by mutableIntStateOf(0)
        private set

    override fun acquire() {
        consumers += 1
    }

    override fun release() {
        consumers = (consumers - 1).coerceAtLeast(0)
    }

    internal fun publishFrame(image: ImageBitmap, boundsOnScreen: Rect) {
        frameBounds = boundsOnScreen
        frame = image
        if (!ready) ready = true
    }

    internal fun clearFrame() {
        frame = null
        frameBounds = Rect.Zero
        if (ready) ready = false
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val target = coordinates ?: return
        if (!target.isAttached) return
        val image = frame ?: return
        val bounds = frameBounds
        if (bounds.isEmpty) return
        val onScreen = target.positionOnScreen()
        val origin = if (onScreen.isSpecified) onScreen else target.positionInWindow()
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(
                (bounds.left - origin.x).roundToInt(),
                (bounds.top - origin.y).roundToInt(),
            ),
            dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
            colorFilter = VideoFrameColorFilter,
            filterQuality = FilterQuality.Medium,
        )
    }
}

@Composable
internal expect fun rememberVideoFrameBackdrop(enabled: Boolean): VideoFrameBackdrop?
