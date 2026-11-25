package com.nuvio.app.features.player

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads a Retry-After header: delta-seconds, or an RFC 1123 HTTP-date. SimpleDateFormat rather than
 * java.time, which the app's minSdk does not have without desugaring.
 */
internal object ParallelRangeRetryAfter {
    fun parseHeaderMs(
        header: String?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Long? {
        if (header.isNullOrEmpty()) return null
        header.trim().toLongOrNull()?.let { seconds ->
            return (seconds * 1000L).coerceAtLeast(0L)
        }
        val format = SimpleDateFormat(HTTP_DATE_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        val text = header.trim()
        val position = ParsePosition(0)
        val target = format.parse(text, position) ?: return null
        if (position.index != text.length) return null
        return (target.time - nowEpochMs).coerceAtLeast(0L)
    }

    private const val HTTP_DATE_PATTERN = "EEE, d MMM yyyy HH:mm:ss zzz"
}
