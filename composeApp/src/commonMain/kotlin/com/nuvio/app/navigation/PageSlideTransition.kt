package com.nuvio.app.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.ui.NavDisplay

/**
 * Horizontal push/pop motion for content pages (details, cast/person, catalogs, folders).
 *
 * Opening a page: the new page slides in from the right edge while the screen underneath
 * (home, discover, ...) drifts a little to the left. Going back reverses it: the page slides out
 * to the right and the previous screen drifts back into place. The predictive back gesture
 * drives the same motion so the page follows the finger.
 */
internal object PageSlideTransition {
    const val DurationMillis = 380

    /** How far the covered screen drifts, as a fraction of its width. */
    private const val ParallaxFraction = 0.25f

    private val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    private fun spec(): FiniteAnimationSpec<IntOffset> = tween(DurationMillis, easing = Easing)

    // Predictive back seeks the transition by gesture progress, so keep the curve linear there
    // and let the finger supply the easing.
    private fun gestureSpec(): FiniteAnimationSpec<IntOffset> = tween(DurationMillis, easing = LinearEasing)

    private fun push(): ContentTransform =
        slideInHorizontally(spec()) { fullWidth -> fullWidth } togetherWith
            slideOutHorizontally(spec()) { fullWidth -> -(fullWidth * ParallaxFraction).toInt() }

    private fun pop(animationSpec: FiniteAnimationSpec<IntOffset>): ContentTransform =
        slideInHorizontally(animationSpec) { fullWidth -> -(fullWidth * ParallaxFraction).toInt() } togetherWith
            slideOutHorizontally(animationSpec) { fullWidth -> fullWidth }

    val metadata: Map<String, Any> =
        NavDisplay.transitionSpec { push() } +
            NavDisplay.popTransitionSpec { pop(spec()) } +
            NavDisplay.predictivePopTransitionSpec { _ -> pop(gestureSpec()) }
}
