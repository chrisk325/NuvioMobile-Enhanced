package com.nuvio.app

import android.app.Activity
import android.os.Build
import android.view.Display

/**
 * Asks the system to run this window at the display's highest refresh rate (e.g. 120/144 Hz)
 * at the current resolution. Many OEM skins keep apps at 60 Hz unless they ask, which makes
 * scrolling look less fluid. Safe to call repeatedly; it only updates the window when the
 * preferred mode actually changes (e.g. after a fold/unfold or display switch).
 */
internal fun Activity.requestHighestRefreshRate() {
    val display = currentDisplayOrNull() ?: return
    val current = display.mode
    val best = display.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        .maxByOrNull { it.refreshRate }
        ?: return
    val attributes = window.attributes
    if (attributes.preferredDisplayModeId == best.modeId) return
    attributes.preferredDisplayModeId = best.modeId
    window.attributes = attributes
}

private fun Activity.currentDisplayOrNull(): Display? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }
}.getOrNull()
