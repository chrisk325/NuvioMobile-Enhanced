package com.nuvio.app.core.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FloatDecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Whether this platform should use [rememberGlideFlingBehavior] instead of the platform default.
 * Android's default fling uses a spline curve that stops fairly abruptly; iOS already ships an
 * exponential, UIScrollView-style decay, so it keeps its native behaviour.
 */
internal expect val nuvioPlatformUsesGlideFling: Boolean

/** Multiplier applied to the release velocity so a flick carries further. */
private const val GlideVelocityBoost = 1.35f

/**
 * Exponential decay rate in 1/s: velocity follows v(t) = v0 * e^(-rate * t).
 * Lower values glide longer. ~2.0 matches UIScrollView's "normal" deceleration.
 */
private const val GlideDecayRatePerSecond = 2.5f

/** The glide ends once it slows below this speed (per second); below it motion is sub-pixel per frame. */
private val GlideStopVelocity = 12.dp

/**
 * Fling with an iOS-style exponential glide: a velocity boost on release, then an
 * ease-out where speed decays exponentially, so the list coasts and settles gently
 * instead of braking hard.
 *
 * Falls back to [ScrollableDefaults.flingBehavior] on platforms where
 * [nuvioPlatformUsesGlideFling] is false.
 */
@Composable
fun rememberGlideFlingBehavior(): FlingBehavior {
    if (!nuvioPlatformUsesGlideFling) return ScrollableDefaults.flingBehavior()
    val density = LocalDensity.current
    return remember(density) {
        val stopVelocityPx = with(density) { GlideStopVelocity.toPx() }
        GlideFlingBehavior(
            decay = ExponentialGlideDecaySpec(
                ratePerSecond = GlideDecayRatePerSecond,
                absVelocityThreshold = stopVelocityPx,
            ).generateDecayAnimationSpec(),
            velocityBoost = GlideVelocityBoost,
        )
    }
}

private class GlideFlingBehavior(
    private val decay: DecayAnimationSpec<Float>,
    private val velocityBoost: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= 1f) return initialVelocity
        // Like the default fling, ignore the system animator duration scale.
        return withContext(UnscaledMotionDurationScale) {
            var velocityLeft = initialVelocity * velocityBoost
            var lastValue = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocityLeft).animateDecay(decay) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = velocity
                // Hit the start/end of the list: stop and hand the rest to overscroll.
                if (abs(delta - consumed) > 0.5f) cancelAnimation()
            }
            velocityLeft
        }
    }
}

private object UnscaledMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/**
 * Closed-form exponential decay, evaluated from elapsed time (not integrated per frame),
 * so a dropped frame never changes where the glide ends up:
 *   x(t) = x0 + v0 / k * (1 - e^(-k t)),  v(t) = v0 * e^(-k t)
 */
private class ExponentialGlideDecaySpec(
    private val ratePerSecond: Float,
    override val absVelocityThreshold: Float,
) : FloatDecayAnimationSpec {

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float {
        val t = playTimeNanos / NanosPerSecond
        return initialValue + initialVelocity / ratePerSecond * (1f - exp(-ratePerSecond * t))
    }

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float {
        val t = playTimeNanos / NanosPerSecond
        return initialVelocity * exp(-ratePerSecond * t)
    }

    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long {
        val speed = abs(initialVelocity)
        if (speed <= absVelocityThreshold) return 0L
        val seconds = ln(speed / absVelocityThreshold) / ratePerSecond
        return (seconds * NanosPerSecond).toLong()
    }

    override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float =
        initialValue + initialVelocity / ratePerSecond

    private companion object {
        const val NanosPerSecond = 1_000_000_000f
    }
}
