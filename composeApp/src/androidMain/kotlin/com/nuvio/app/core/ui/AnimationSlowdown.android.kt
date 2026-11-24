package com.nuvio.app.core.ui

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale

/** Every animation in the app plays 25% slower than its spec, so transitions look smoother. */
internal actual val nuvioAnimationSlowdown: Float = 1.25f

/**
 * App-wide [MotionDurationScale] handed to MainActivity's recomposer. Compose multiplies every
 * animation's play time by [scaleFactor], so this slows all of them at once without touching
 * individual specs.
 *
 * It replaces Compose's default scale, which follows the system "Animator duration scale"
 * setting, so that setting is read here too: 0 (animations off) still turns animations off.
 * Scrolling flings are unaffected because they run with their own fixed scale.
 */
internal object AppMotionDurationScale : MotionDurationScale {
    @Volatile
    private var systemScale = 1f
    private var resolver: ContentResolver? = null

    override val scaleFactor: Float
        get() = systemScale * nuvioAnimationSlowdown

    fun get(context: Context): AppMotionDurationScale {
        if (resolver == null) {
            val contentResolver = context.applicationContext.contentResolver
            resolver = contentResolver
            systemScale = readSystemScale(contentResolver)
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        systemScale = readSystemScale(contentResolver)
                    }
                },
            )
        }
        return this
    }

    private fun readSystemScale(contentResolver: ContentResolver): Float =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}
