package com.nuvio.app.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelPlaybackPolicyTest {

    private val remux = 60L * 1024L * 1024L * 1024L
    private val debridUrl = "https://aiostreams.example.ink/api/v1/debrid/playback/abc/Movie.2160p.REMUX"

    private fun plan(
        manualEnabled: Boolean = false,
        manualConnections: Int = 2,
        manualChunkSizeMb: Int = 16,
        autoEnabled: Boolean = true,
        connectionCap: Int? = null,
        supported: Boolean = true,
        deviceMemoryMb: Int = 8 * 1024,
        progressive: Boolean = true,
        liveStream: Boolean = false,
        youtubeChunked: Boolean = false,
        hasResponseHeaderOverrides: Boolean = false,
        url: String = debridUrl,
        requestHeaders: Map<String, String> = emptyMap(),
        fileSizeBytes: Long? = remux,
    ) = ParallelPlaybackPolicy.plan(
        manualEnabled, manualConnections, manualChunkSizeMb, autoEnabled, connectionCap, supported,
        deviceMemoryMb, progressive, liveStream, youtubeChunked, hasResponseHeaderOverrides, url,
        requestHeaders, fileSizeBytes,
    )

    @Test
    fun `large remote file gets four automatic connections`() {
        assertEquals(ParallelPlaybackPlan(4, 16 * 1024, automatic = true), plan())
    }

    @Test
    fun `automatic mode skips small, unknown, local and header streams`() {
        assertNull(plan(fileSizeBytes = ParallelPlaybackPolicy.AUTO_MIN_FILE_BYTES - 1))
        assertNull(plan(fileSizeBytes = null))
        assertNull(plan(url = "http://cdn.example.com/movie.mkv"))
        assertNull(plan(url = "https://192.168.1.20/movie.mkv"))
        assertNull(plan(url = "https://nas.local/movie.mkv"))
        assertNull(plan(url = "https://jellyfin/movie.mkv"))
        assertNull(plan(requestHeaders = mapOf("Referer" to "https://example.com")))
        assertNull(plan(deviceMemoryMb = 2 * 1024))
        assertNull(plan(autoEnabled = false))
    }

    @Test
    fun `nothing parallel where the source cannot take it`() {
        assertNull(plan(supported = false))
        assertNull(plan(progressive = false))
        assertNull(plan(liveStream = true))
        assertNull(plan(youtubeChunked = true))
        assertNull(plan(hasResponseHeaderOverrides = true))
        assertNull(plan(url = "http://127.0.0.1:11470/stream.mkv", manualEnabled = true))
        assertNull(plan(url = "not a url", manualEnabled = true))
    }

    @Test
    fun `manual switch applies the user's values to any progressive stream`() {
        assertEquals(
            ParallelPlaybackPlan(3, 8 * 1024, automatic = false),
            plan(manualEnabled = true, manualConnections = 3, manualChunkSizeMb = 8, fileSizeBytes = null,
                url = "http://192.168.1.20/movie.mkv", requestHeaders = mapOf("Referer" to "x")),
        )
        assertEquals(4, plan(manualEnabled = true, manualConnections = 9)?.connections)
        assertEquals(32 * 1024, plan(manualEnabled = true, manualChunkSizeMb = 64)?.chunkSizeKb)
        assertEquals(4 * 1024, plan(manualEnabled = true, manualChunkSizeMb = 1)?.chunkSizeKb)
    }

    @Test
    fun `failures cap the connections down to a single plain connection`() {
        assertEquals(2, ParallelPlaybackPolicy.stepDown(4))
        assertEquals(2, ParallelPlaybackPolicy.stepDown(3))
        assertEquals(1, ParallelPlaybackPolicy.stepDown(2))
        assertEquals(2, plan(connectionCap = 2)?.connections)
        assertNull(plan(connectionCap = 1))
        assertEquals(2, plan(manualEnabled = true, manualConnections = 4, connectionCap = 2)?.connections)
        assertNull(plan(manualEnabled = true, connectionCap = 1))
    }

    @Test
    fun `a missing file does not step down`() {
        assertFalse(ParallelPlaybackPolicy.shouldStepDownFor(404))
        assertFalse(ParallelPlaybackPolicy.shouldStepDownFor(410))
        assertTrue(ParallelPlaybackPolicy.shouldStepDownFor(403))
        assertTrue(ParallelPlaybackPolicy.shouldStepDownFor(509))
        assertTrue(ParallelPlaybackPolicy.shouldStepDownFor(null))
    }

    @Test
    fun `an unexpected loader failure does not step down`() {
        assertFalse(ParallelPlaybackPolicy.shouldStepDownFor(null, unexpectedLoaderFailure = true))
        assertFalse(ParallelPlaybackPolicy.shouldStepDownFor(503, unexpectedLoaderFailure = true))
        assertTrue(ParallelPlaybackPolicy.shouldStepDownFor(503, unexpectedLoaderFailure = false))
    }

    @Test
    fun `public addresses count as remote`() {
        assertTrue(ParallelPlaybackPolicy.isRemoteHttps("https://203.0.113.7/movie.mkv"))
        assertTrue(ParallelPlaybackPolicy.isRemoteHttps("https://172.32.0.1/movie.mkv"))
        assertFalse(ParallelPlaybackPolicy.isRemoteHttps("https://172.20.0.1/movie.mkv"))
        assertFalse(ParallelPlaybackPolicy.isRemoteHttps("https://[::1]/movie.mkv"))
    }
}
