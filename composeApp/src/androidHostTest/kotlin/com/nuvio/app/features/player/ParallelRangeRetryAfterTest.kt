package com.nuvio.app.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ParallelRangeRetryAfterTest {

    @Test
    fun `reads delta-seconds`() {
        assertEquals(30_000L, ParallelRangeRetryAfter.parseHeaderMs("30"))
        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs("0"))
        assertEquals(1_000L, ParallelRangeRetryAfter.parseHeaderMs("1"))
    }

    @Test
    fun `reads an RFC 1123 HTTP-date`() {
        val now = 1_700_000_000_000L
        val header = httpDate(now + 45_000L)
        assertEquals(45_000L, ParallelRangeRetryAfter.parseHeaderMs(header, nowEpochMs = now))
        assertEquals(45_000L, ParallelRangeRetryAfter.parseHeaderMs("Tue, 14 Nov 2023 22:14:05 GMT", 1_700_000_000_000L))
    }

    @Test
    fun `returns null for missing or garbage`() {
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(null))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(""))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs("not-a-date"))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs("Tue, 14 Nov 2023 22:14:05 GMT trailing"))
    }

    @Test
    fun `clamps a past HTTP-date to zero`() {
        val now = 1_700_000_000_000L
        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs(httpDate(now - 60_000L), nowEpochMs = now))
    }

    private fun httpDate(epochMs: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME
        .withLocale(Locale.US)
        .withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(epochMs))
}
