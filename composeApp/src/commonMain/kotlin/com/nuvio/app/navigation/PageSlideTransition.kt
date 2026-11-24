package com.nuvio.app.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.ui.NavDisplay

/**
 * Horizontal push/pop motion for content pages (details, streams, cast/person, catalogs, folders).
 *
 * Opening a page: the new page slides in from the right edge while the screen underneath
 * (home, discover, ...) drifts a little to the left. Going back reverses it: the page slides out
 * to the right and the previous screen drifts back into place.
 *
 * Android predictive back: while the back gesture is held, the page tracks the finger and the
 * previous screen peeks in from the left. Releasing finishes the slide from where the finger
 * left it, and cancelling slides the page back into place.
 */
internal object PageSlideTransition {
    /** How far the covered screen drifts, as a fraction of its width. */
    private const val ParallaxFraction = 0.25f

    /**
     * Push/pop on tap use a critically damped spring rather than a fixed-duration tween: there is
     * no overshoot, the motion settles gently, and if the user goes back while a push is still
     * running the slide reverses from its current velocity instead of jumping.
     */
    private val tapSpec: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 450f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    /**
     * Predictive back seeks the transition directly by gesture progress, which Android already
     * eases, so the curve stays linear here to keep the page locked to the finger. The duration
     * only sets how long the release/cancel settle takes for the remaining distance.
     */
    private const val GestureDurationMillis = 420
    private val gestureSpec: FiniteAnimationSpec<IntOffset> =
        tween(GestureDurationMillis, easing = LinearEasing)

    private fun parallaxOffset(fullWidth: Int): Int = -(fullWidth * ParallaxFraction).toInt()

    // ContentTransform carries mutable fields (zIndex, sizeTransform), so build a fresh one per
    // transition instead of sharing instances.
    private fun push(): ContentTransform =
        slideInHorizontally(tapSpec) { fullWidth -> fullWidth } togetherWith
            slideOutHorizontally(tapSpec, ::parallaxOffset)

    private fun pop(animationSpec: FiniteAnimationSpec<IntOffset>): ContentTransform =
        slideInHorizontally(animationSpec, ::parallaxOffset) togetherWith
            slideOutHorizontally(animationSpec) { fullWidth -> fullWidth }

    val metadata: Map<String, Any> =
        NavDisplay.transitionSpec { push() } +
            NavDisplay.popTransitionSpec { pop(tapSpec) } +
            NavDisplay.predictivePopTransitionSpec { _ -> pop(gestureSpec) }
}
