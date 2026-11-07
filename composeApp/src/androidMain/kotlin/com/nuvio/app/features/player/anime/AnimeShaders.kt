package com.nuvio.app.features.player.anime

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal object AnimeShaders {
    private const val TAG = "AnimeShaders"
    private const val ASSET_DIR = "anime4k"
    private const val LINE_SHARPEN_SHADER = "Anime4K_Restore_CNN_S.glsl"
    private const val UPSCALE_SHADER = "Anime4K_Upscale_CNN_x2_S.glsl"

    fun shaderNames(upscaleEnabled: Boolean, lineSharpenEnabled: Boolean): List<String> = buildList {
        if (lineSharpenEnabled) add(LINE_SHARPEN_SHADER)
        if (upscaleEnabled) add(UPSCALE_SHADER)
    }

    fun mpvShaderPaths(context: Context, upscaleEnabled: Boolean, lineSharpenEnabled: Boolean): List<String> {
        val names = shaderNames(upscaleEnabled, lineSharpenEnabled)
        if (names.isEmpty()) return emptyList()
        val directory = File(context.filesDir, ASSET_DIR)
        return names.mapNotNull { name ->
            runCatching {
                val bytes = context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                directory.mkdirs()
                val file = File(directory, name)
                if (!file.exists() || !file.readBytes().contentEquals(bytes)) {
                    file.writeBytes(bytes)
                }
                file.absolutePath
            }.onFailure { error ->
                Log.w(TAG, "Unable to prepare $name for libmpv", error)
            }.getOrNull()
        }
    }

    @UnstableApi
    fun exoVideoEffects(context: Context, upscaleEnabled: Boolean, lineSharpenEnabled: Boolean): List<Effect> {
        val names = shaderNames(upscaleEnabled, lineSharpenEnabled)
        if (names.isEmpty()) return emptyList()
        return runCatching {
            val passes = names.flatMap { name ->
                val source = context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
                MpvUserShaderParser.parse(source)
            }
            val (longSide, shortSide) = displaySize(context)
            listOf<Effect>(
                AnimeShaderEffect(
                    passes = passes,
                    displayLongSide = longSide,
                    displayShortSide = shortSide,
                ),
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to prepare anime shaders for ExoPlayer", error)
        }.getOrDefault(emptyList())
    }

    private fun displaySize(context: Context): Pair<Int, Int> {
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            if (bounds != null) {
                bounds.width() to bounds.height()
            } else {
                context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
            }
        } else {
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        return max(width, height) to min(width, height)
    }
}
