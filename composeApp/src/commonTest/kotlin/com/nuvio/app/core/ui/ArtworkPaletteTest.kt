package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import com.kmpalette.palette.graphics.Palette
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkPaletteTest {

    @Test
    fun `secondary colour uses a different hue from the dominant colour`() {
        val palette = Palette.from(
            listOf(
                Palette.Swatch(rgb = 0xFF6B7A1F.toInt(), population = 900), // olive (dominant)
                Palette.Swatch(rgb = 0xFFC2621B.toInt(), population = 400), // orange
                Palette.Swatch(rgb = 0xFF2A1A0C.toInt(), population = 300), // deep brown
            ),
        ).toArtworkPalette()

        assertNotNull(palette)
        assertNotEquals(palette.primary, palette.secondary)
        assertTrue(palette.secondary.red > palette.secondary.green, "secondary should be the orange swatch")
        assertTrue(palette.deep.luminance() < palette.primary.luminance(), "deep colour should be darker")
    }

    @Test
    fun `single hue artwork still produces a darker gradient`() {
        val palette = Palette.from(
            listOf(Palette.Swatch(rgb = 0xFF8A1C24.toInt(), population = 1000)),
        ).toArtworkPalette()

        assertNotNull(palette)
        assertTrue(palette.secondary.luminance() < palette.primary.luminance())
        assertTrue(palette.deep.luminance() < palette.secondary.luminance())
    }

    @Test
    fun `greyscale artwork yields no palette`() {
        val palette = Palette.from(
            listOf(
                Palette.Swatch(rgb = 0xFF808080.toInt(), population = 1000),
                Palette.Swatch(rgb = 0xFF101010.toInt(), population = 500),
            ),
        ).toArtworkPalette()

        assertNull(palette)
    }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
