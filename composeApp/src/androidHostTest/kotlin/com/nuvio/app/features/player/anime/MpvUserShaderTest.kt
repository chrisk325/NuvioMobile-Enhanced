package com.nuvio.app.features.player.anime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvUserShaderTest {
    private fun shader(name: String): List<MpvUserShaderPass> =
        MpvUserShaderParser.parse(File("src/androidMain/assets/anime4k/$name").readText())

    private val upscale = shader("Anime4K_Upscale_CNN_x2_S.glsl")
    private val restore = shader("Anime4K_Restore_CNN_S.glsl")

    @Test
    fun parsesBundledAnime4kShaders() {
        assertEquals(5, upscale.size)
        assertEquals(4, restore.size)
        assertEquals(listOf("MAIN", "conv2d_last_tf"), upscale.last().binds)
        assertEquals("MAIN", upscale.last().save)
        assertEquals("conv2d_last_tf.w 2 *", upscale.last().width)
        assertTrue(upscale.all { it.hook == "MAIN" && it.body.contains("vec4 hook()") })
        assertTrue(restore.none { it.body.contains("//!") })
    }

    @Test
    fun upscalesSmallVideoTwiceWhenTheScreenIsLarger() {
        val plan = AnimeShaderEffect.planFor(restore + upscale, 1280, 720, 2400, 1080)
        assertEquals(9, plan.passes.size)
        assertEquals(MpvTextureSize(2560, 1440), plan.outputSize)
        assertEquals("MAIN", plan.passes[3].target)
        assertEquals(MpvTextureSize(1280, 720), plan.passes[3].size)
    }

    @Test
    fun skipsUpscaleWhenTheVideoAlreadyFillsTheScreen() {
        val upscaleOnly = AnimeShaderEffect.planFor(upscale, 1920, 1080, 2400, 1080)
        assertTrue(upscaleOnly.passes.isEmpty())
        assertEquals(MpvTextureSize(1920, 1080), upscaleOnly.outputSize)

        val withRestore = AnimeShaderEffect.planFor(restore + upscale, 1920, 1080, 2400, 1080)
        assertEquals(4, withRestore.passes.size)
        assertEquals(MpvTextureSize(1920, 1080), withRestore.outputSize)
    }

    @Test
    fun keepsVideoOpaqueOnlyForPassesThatWriteMain() {
        val plan = AnimeShaderEffect.planFor(upscale, 1280, 720, 2400, 1080)
        assertTrue(animePassFragmentShader(plan.passes.first()).contains("nuvioFragColor = hook();"))
        assertTrue(animePassFragmentShader(plan.passes.last()).contains("nuvioFragColor = vec4(hook().rgb, 1.0);"))
        assertTrue(animePassFragmentShader(plan.passes.last()).contains("#define MAIN_tex(pos) texture(MAIN_raw, vec2((pos).x, 1.0 - (pos).y))"))
    }

    @Test
    fun fitsPortraitVideoToTheDisplay() {
        assertEquals(
            MpvTextureSize(1080, 1920),
            AnimeShaderEffect.fitToDisplay(MpvTextureSize(540, 960), 2400, 1080),
        )
    }

    @Test
    fun evaluatesMpvExpressions() {
        val sizes = mapOf("MAIN" to (1280f to 720f), "OUTPUT" to (2560f to 1440f))
        val lookup = { name: String, dimension: Char ->
            sizes[name]?.let { if (dimension == 'w') it.first else it.second }
        }
        assertEquals(1f, MpvUserShaderParser.evaluate("OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *", lookup))
        assertEquals(2560f, MpvUserShaderParser.evaluate("MAIN.width 2 *", lookup))
        assertEquals(0f, MpvUserShaderParser.evaluate("1 !", lookup))
        assertNull(MpvUserShaderParser.evaluate("MISSING.w", lookup))
        assertNull(MpvUserShaderParser.evaluate("1 +", lookup))
    }

    @Test
    fun rejectsUnsupportedDirectives() {
        assertFailsWith<IllegalArgumentException> {
            MpvUserShaderParser.parse("//!TEXTURE LUT\n//!SIZE 4 4\n//!HOOK MAIN\nvec4 hook() { return vec4(0.0); }\n")
        }
    }
}
