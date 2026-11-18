package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import com.kmpalette.palette.graphics.Palette
import kotlin.math.abs

private const val MIN_SWATCH_LIGHTNESS = 0.22f
private const val MAX_SWATCH_LIGHTNESS = 0.92f
private const val MIN_SWATCH_SATURATION = 0.08f
private const val TARGET_MIN_LIGHTNESS = 0.45f

val isBrightDominantSwatch: (Palette.Swatch) -> Boolean = { swatch ->
    val hsl = swatch.hsl
    hsl[2] in MIN_SWATCH_LIGHTNESS..MAX_SWATCH_LIGHTNESS && hsl[1] >= MIN_SWATCH_SATURATION
}

fun Color.brightenedDominant(fallback: Color): Color {
    if (this == fallback) return this
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return this
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f))
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    val nl = maxOf(l, TARGET_MIN_LIGHTNESS).coerceAtMost(0.8f)
    val ns = s
    val c = (1f - abs(2f * nl - 1f)) * ns
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = nl - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m, alpha)
}

private const val MIN_SECONDARY_SATURATION = 0.15f
private const val MIN_SECONDARY_HUE_SHIFT = 24f
private const val MAX_DEEP_LIGHTNESS = 0.45f
private const val MIN_DEEP_SATURATION = 0.08f
private const val FALLBACK_SECONDARY_DARKEN = 0.72f
private const val FALLBACK_DEEP_DARKEN = 0.4f

/**
 * The colours used to paint the dynamic home background gradient, extracted from one artwork.
 *
 * - [primary]: the brightened dominant colour (the same colour the old flat wash used); it sits at
 *   the top of the screen, where the hero artwork fades out.
 * - [secondary]: a second prominent colour with a visibly different hue, used for the middle
 *   of the gradient so it reads as a gradient rather than a single tint.
 * - [deep]: a dark swatch from the artwork that the bottom of the screen settles into.
 */
data class ArtworkPalette(
    val primary: Color,
    val secondary: Color,
    val deep: Color,
)

/** Builds an [ArtworkPalette] from a generated [Palette], or null when no usable colour exists. */
fun Palette.toArtworkPalette(): ArtworkPalette? {
    val byPopulation = swatches.sortedByDescending { it.population }
    val primarySwatch = byPopulation.firstOrNull(isBrightDominantSwatch) ?: return null
    val primary = Color(primarySwatch.rgb).brightenedDominant(fallback = Color.Transparent)
    val primaryHue = primarySwatch.hsl[0]

    val secondarySwatch = (
        listOfNotNull(vibrantSwatch, mutedSwatch, darkVibrantSwatch, lightVibrantSwatch, lightMutedSwatch) +
            byPopulation
        ).firstOrNull { swatch ->
            swatch !== primarySwatch &&
                isBrightDominantSwatch(swatch) &&
                swatch.hsl[1] >= MIN_SECONDARY_SATURATION &&
                hueDistance(swatch.hsl[0], primaryHue) >= MIN_SECONDARY_HUE_SHIFT
        }
    val secondary = secondarySwatch
        ?.let { Color(it.rgb).brightenedDominant(fallback = Color.Transparent) }
        ?: primary.scaled(FALLBACK_SECONDARY_DARKEN)

    // The deep colour must come from another swatch, otherwise the gradient would not darken.
    val deepSwatch = (
        listOfNotNull(darkVibrantSwatch, darkMutedSwatch) +
            byPopulation.filter { it.hsl[2] <= MAX_DEEP_LIGHTNESS && it.hsl[1] >= MIN_DEEP_SATURATION }
        ).firstOrNull { it !== primarySwatch && it !== secondarySwatch }
    val deep = deepSwatch?.let { Color(it.rgb) } ?: secondary.scaled(FALLBACK_DEEP_DARKEN)

    return ArtworkPalette(primary = primary, secondary = secondary, deep = deep)
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

private fun Color.scaled(factor: Float): Color =
    Color(red * factor, green * factor, blue * factor, alpha)
