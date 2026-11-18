package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.nuvio.app.features.settings.ThemeSettingsRepository

val LocalDynamicArtworkBackgroundActive = staticCompositionLocalOf { false }

/** The colour at the top of the dynamic background (where the hero artwork fades out). */
val LocalDynamicArtworkBackgroundColor = staticCompositionLocalOf<State<Color>?> { null }

/** The full dynamic background gradient, for surfaces that need to fade seamlessly into it. */
val LocalDynamicArtworkWash = staticCompositionLocalOf<DynamicArtworkWash?> { null }

// How strongly each extracted colour is mixed into the theme background. The top is the most
// saturated and the gradient settles towards a deep, mostly-neutral tone at the bottom.
private const val PRIMARY_BLEND_FRACTION = 0.42f
private const val SECONDARY_BLEND_FRACTION = 0.36f
private const val DEEP_BLEND_FRACTION = 0.22f

// Vertical positions (fraction of the viewport) of the gradient stops.
private const val PRIMARY_HOLD_STOP = 0.16f
private const val SECONDARY_STOP = 0.56f

// Colour samples used when a surface fades into the wash; the gradient between samples is linear,
// so a handful is visually indistinguishable from the real background.
private const val FADE_SAMPLE_COUNT = 9

private const val COLOR_TRANSITION_MS = 600

@Composable
fun rememberDynamicArtworkBackgroundEnabled(): Boolean {
    val enabled by ThemeSettingsRepository.dynamicArtworkBackgroundEnabled.collectAsState()
    return enabled
}

/**
 * A vertical gradient anchored to the viewport (it does not scroll with content), built from the
 * colours of the active artwork.
 */
@Stable
class DynamicArtworkWash internal constructor() {
    private lateinit var topState: State<Color>
    private lateinit var middleState: State<Color>
    private lateinit var bottomState: State<Color>

    internal fun bind(top: State<Color>, middle: State<Color>, bottom: State<Color>) {
        topState = top
        middleState = middle
        bottomState = bottom
    }

    internal var originY by mutableFloatStateOf(0f)
    internal var height by mutableFloatStateOf(0f)

    val top: Color get() = topState.value

    internal fun brush(): Brush {
        val topColor = topState.value
        return Brush.verticalGradient(
            0f to topColor,
            PRIMARY_HOLD_STOP to topColor,
            SECONDARY_STOP to middleState.value,
            1f to bottomState.value,
            startY = 0f,
            endY = height.coerceAtLeast(1f),
        )
    }

    /** The background colour at [rootY] (a y coordinate in root/window space). */
    fun colorAtRootY(rootY: Float): Color {
        val h = height
        if (h <= 0f) return top
        val fraction = ((rootY - originY) / h).coerceIn(0f, 1f)
        return when {
            fraction <= PRIMARY_HOLD_STOP -> topState.value
            fraction <= SECONDARY_STOP -> topState.value.blendTowards(
                middleState.value,
                (fraction - PRIMARY_HOLD_STOP) / (SECONDARY_STOP - PRIMARY_HOLD_STOP),
            )
            else -> middleState.value.blendTowards(
                bottomState.value,
                (fraction - SECONDARY_STOP) / (1f - SECONDARY_STOP),
            )
        }
    }
}

@Composable
fun DynamicArtworkBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val active = rememberDynamicArtworkBackgroundEnabled()
    if (!active) {
        CompositionLocalProvider(LocalDynamicArtworkBackgroundActive provides false) {
            content()
        }
        return
    }

    val baseColor = MaterialTheme.colorScheme.background
    val tint = rememberArtworkTint(artworkUrl)
    var lastPalette by remember { mutableStateOf<ArtworkPalette?>(null) }
    val currentPalette: ArtworkPalette? = when (tint) {
        ArtworkTint.Pending -> lastPalette
        ArtworkTint.None -> null
        is ArtworkTint.Tinted -> tint.palette
    }
    if (tint != ArtworkTint.Pending) lastPalette = currentPalette

    val topColor = animateWashColor(
        target = currentPalette?.let { baseColor.blendTowards(it.primary, PRIMARY_BLEND_FRACTION) } ?: baseColor,
        label = "dynamic_artwork_wash_top",
    )
    val middleColor = animateWashColor(
        target = currentPalette?.let { baseColor.blendTowards(it.secondary, SECONDARY_BLEND_FRACTION) } ?: baseColor,
        label = "dynamic_artwork_wash_middle",
    )
    val bottomColor = animateWashColor(
        target = currentPalette?.let { baseColor.blendTowards(it.deep, DEEP_BLEND_FRACTION) } ?: baseColor,
        label = "dynamic_artwork_wash_bottom",
    )
    val wash = remember { DynamicArtworkWash() }
    wash.bind(topColor, middleColor, bottomColor)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                wash.originY = coordinates.positionInRoot().y
                wash.height = coordinates.size.height.toFloat()
            }
            .drawBehind { drawRect(wash.brush()) },
    ) {
        CompositionLocalProvider(
            LocalDynamicArtworkBackgroundActive provides true,
            LocalDynamicArtworkBackgroundColor provides topColor,
            LocalDynamicArtworkWash provides wash,
        ) {
            content()
        }
    }
}

@Composable
private fun animateWashColor(target: Color, label: String): State<Color> =
    animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = COLOR_TRANSITION_MS, easing = LinearOutSlowInEasing),
        label = label,
    )

/**
 * Tracks where a surface sits on screen so [drawArtworkWashFade] can match the background gradient
 * behind it. Returns a modifier that must be applied to the surface doing the drawing.
 */
@Stable
class ArtworkWashFadeAnchor {
    var rootY by mutableFloatStateOf(0f)
        private set

    val modifier: Modifier = Modifier.onGloballyPositioned { rootY = it.positionInRoot().y }
}

@Composable
fun rememberArtworkWashFadeAnchor(): ArtworkWashFadeAnchor = remember { ArtworkWashFadeAnchor() }

/**
 * Draws a vertical fade into the dynamic background. [alphas] are evenly spaced from the top to the
 * bottom of this draw area. With no [wash], fades into the solid [fallback] colour instead.
 */
fun DrawScope.drawArtworkWashFade(
    wash: DynamicArtworkWash?,
    anchor: ArtworkWashFadeAnchor,
    fallback: Color,
    alphas: FloatArray,
) {
    if (wash == null) {
        drawRect(Brush.verticalGradient(colors = alphas.map { fallback.copy(alpha = it) }))
        return
    }
    val originY = anchor.rootY
    val height = size.height
    val stops = Array(FADE_SAMPLE_COUNT) { index ->
        val fraction = index / (FADE_SAMPLE_COUNT - 1f)
        val color = wash.colorAtRootY(originY + fraction * height)
        fraction to color.copy(alpha = alphas.interpolate(fraction))
    }
    drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = height))
}

private fun FloatArray.interpolate(fraction: Float): Float {
    if (size == 1) return this[0]
    val position = fraction.coerceIn(0f, 1f) * (size - 1)
    val index = position.toInt().coerceAtMost(size - 2)
    val local = position - index
    return this[index] + (this[index + 1] - this[index]) * local
}

private fun Color.blendTowards(target: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * clamped,
        green = green + (target.green - green) * clamped,
        blue = blue + (target.blue - blue) * clamped,
        alpha = alpha + (target.alpha - alpha) * clamped,
    )
}
