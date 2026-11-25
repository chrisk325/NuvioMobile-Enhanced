package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the main video url of one playback is fetched over parallel connections. */
internal data class ParallelPlaybackPlan(
    val connections: Int,
    val chunkSizeKb: Int,
    /** True when automatic mode chose this, false when the user turned Parallel connections on. */
    val automatic: Boolean,
)

/**
 * Decides whether a playback uses parallel connections.
 *
 * A single TCP connection tops out at its receive window over the round trip, so a far debrid
 * server can hold one connection under a UHD remux's bitrate on a fast line; ranged requests over
 * several connections each get their own window. The user's Parallel connections switch applies it
 * to every progressive stream. With it off, automatic mode applies it only to large remote HTTPS
 * files without addon headers, on devices with at least [LOW_RAM_DEVICE_MB] of RAM.
 */
internal object ParallelPlaybackPolicy {
    /** Roughly 25 Mbps over two hours: below this one connection already keeps up. */
    const val AUTO_MIN_FILE_BYTES: Long = 20L * 1024L * 1024L * 1024L
    const val AUTO_CONNECTIONS = ParallelConnectionDefaults.MAX_CONNECTIONS
    const val AUTO_CHUNK_SIZE_KB = ParallelConnectionDefaults.CHUNK_SIZE_KB
    /** Step tried when a server refuses more connections but may still take fewer. */
    const val FALLBACK_CONNECTIONS = ParallelConnectionDefaults.MIN_CONNECTIONS
    const val LOW_RAM_DEVICE_MB = 3 * 1024

    /** Connections to retry with after a source error on [current]: 4 to 2, then 1 (no parallel). */
    fun stepDown(current: Int): Int = if (current > FALLBACK_CONNECTIONS) FALLBACK_CONNECTIONS else 1

    /** 404/410 say the file is gone, not that the server refused the connections. */
    fun shouldStepDownFor(httpStatus: Int?): Boolean = httpStatus != 404 && httpStatus != 410

    fun plan(
        manualEnabled: Boolean,
        manualConnections: Int,
        manualChunkSizeMb: Int,
        autoEnabled: Boolean,
        /** Most connections allowed after earlier failures of this url; null when none failed. */
        connectionCap: Int?,
        supported: Boolean,
        deviceMemoryMb: Int,
        progressive: Boolean,
        liveStream: Boolean,
        youtubeChunked: Boolean,
        hasResponseHeaderOverrides: Boolean,
        url: String,
        requestHeaders: Map<String, String>,
        fileSizeBytes: Long?,
    ): ParallelPlaybackPlan? {
        if (!supported || !progressive || liveStream || youtubeChunked || hasResponseHeaderOverrides) return null
        val cap = connectionCap ?: Int.MAX_VALUE
        if (cap <= 1) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (isLoopbackHost(parsed.host)) return null

        if (manualEnabled) {
            val connections = manualConnections
                .coerceIn(ParallelConnectionDefaults.MIN_CONNECTIONS, ParallelConnectionDefaults.MAX_CONNECTIONS)
                .coerceAtMost(cap)
            if (connections < 2) return null
            val chunkMb = manualChunkSizeMb.coerceIn(
                ParallelConnectionDefaults.MIN_CHUNK_SIZE_MB,
                ParallelConnectionDefaults.MAX_CHUNK_SIZE_MB,
            )
            return ParallelPlaybackPlan(connections, chunkMb * 1024, automatic = false)
        }

        if (!autoEnabled || deviceMemoryMb < LOW_RAM_DEVICE_MB) return null
        // The parallel path forces HTTP/1.1; streams that need addon headers keep the normal client.
        if (requestHeaders.isNotEmpty()) return null
        if (fileSizeBytes == null || fileSizeBytes < AUTO_MIN_FILE_BYTES) return null
        if (!isRemoteHttps(url)) return null
        return ParallelPlaybackPlan(AUTO_CONNECTIONS.coerceAtMost(cap), AUTO_CHUNK_SIZE_KB, automatic = true)
    }

    internal fun isRemoteHttps(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        val host = parsed.host.lowercase()
        // IPv6 literals: not worth classifying, leave them on one connection.
        if (host.contains(':')) return false
        if (!host.contains('.')) return false
        if (isLoopbackHost(host) || LOCAL_SUFFIXES.any { host.endsWith(it) }) return false
        ipv4Octets(host)?.let { return !isPrivateIpv4(it) }
        return true
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")

    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateIpv4(o: IntArray): Boolean = when {
        o[0] == 10 || o[0] == 127 || o[0] == 0 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 100 && o[1] in 64..127 -> true
        else -> false
    }
}

/**
 * Sends opens of [sourceUrl] through [ParallelRangeDataSource] and everything else (subtitles, a
 * separate audio url) through this factory unchanged. Without a [plan] the factory is returned as is.
 */
@UnstableApi
internal fun DataSource.Factory.withParallelConnections(
    sourceUrl: String,
    plan: ParallelPlaybackPlan?,
    requestHeaders: Map<String, String>,
): DataSource.Factory {
    if (plan == null) return this
    val plainFactory = this
    // One factory per playback: it carries the startup cache that lets the first reopen skip a request.
    val parallelFactory = ParallelRangeDataSource.Factory(
        upstreamFactory = PlayerPlaybackNetworking.createParallelHttpDataSourceFactory(requestHeaders),
        parallelConnections = plan.connections,
        chunkSize = plan.chunkSizeKb.toLong() * 1024L,
    )
    return DataSource.Factory {
        SourceUrlRoutingDataSource(plainFactory.createDataSource(), parallelFactory, sourceUrl)
    }
}

@UnstableApi
private class SourceUrlRoutingDataSource(
    private val plain: DataSource,
    private val parallelFactory: DataSource.Factory,
    private val sourceUrl: String,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var parallel: DataSource? = null
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        plain.addTransferListener(transferListener)
        parallel?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.toString() == sourceUrl) {
            parallel ?: parallelFactory.createDataSource().also { created ->
                listeners.forEach(created::addTransferListener)
                parallel = created
            }
        } else {
            plain
        }
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
    }
}

/** The HTTP status of the first bad-status response in this error's cause chain, if any. */
internal fun Throwable.invalidResponseCode(): Int? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current.cause
        depth++
    }
    return null
}
