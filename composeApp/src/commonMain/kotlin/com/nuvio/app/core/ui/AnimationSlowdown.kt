package com.nuvio.app.core.ui

/**
 * How many times slower than their specs every Compose animation plays on this platform
 * (transitions, AnimatedVisibility/Content, animate*AsState, springs, ...). On Android it is
 * applied once, app-wide, through MainActivity's recomposer; see AppMotionDurationScale.
 *
 * Animations pick it up automatically. Only plain timers (`delay`) that must line up with an
 * animation's end need to multiply by it.
 */
internal expect val nuvioAnimationSlowdown: Float
