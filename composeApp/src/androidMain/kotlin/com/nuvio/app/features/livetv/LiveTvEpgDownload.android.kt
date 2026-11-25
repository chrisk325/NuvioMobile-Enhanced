package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.AddonHttpClientProvider
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val GUIDE_BUFFER_BYTES = 64 * 1024

internal actual suspend fun liveTvStreamGuide(
    url: String,
    headers: Map<String, String>,
    maxBytes: Long,
    onChunk: (bytes: ByteArray, length: Int) -> Unit,
) {
    withContext(Dispatchers.IO) { streamGuide(url, headers, maxBytes, onChunk) }
}

private suspend fun streamGuide(
    url: String,
    headers: Map<String, String>,
    maxBytes: Long,
    onChunk: (bytes: ByteArray, length: Int) -> Unit,
) {
    val request = Request.Builder().url(url).apply {
        // OkHttp asks for gzip itself and unpacks it only when it did.
        headers.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) header(name, value)
        }
    }.get().build()
    // Panels build xmltv.php before sending a byte of it, which can take minutes, and a guide is
    // too big for the HTTP cache.
    val client = AddonHttpClientProvider.get().newBuilder()
        .readTimeout(3, TimeUnit.MINUTES)
        .cache(null)
        .build()
    val call = client.newCall(request)
    val context = currentCoroutineContext()
    val cancelHandle = context[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Guide request failed with HTTP ${response.code}")
            val body = response.body ?: return@use
            val raw = BufferedInputStream(body.byteStream(), GUIDE_BUFFER_BYTES)
            raw.mark(2)
            val isGzip = raw.read() == 0x1F && raw.read() == 0x8B
            raw.reset()
            val input: InputStream = if (isGzip) GZIPInputStream(raw, GUIDE_BUFFER_BYTES) else raw
            input.use { source ->
                val buffer = ByteArray(GUIDE_BUFFER_BYTES)
                var total = 0L
                while (total < maxBytes) {
                    context.ensureActive()
                    val wanted = minOf(buffer.size.toLong(), maxBytes - total).toInt()
                    val read = try {
                        source.read(buffer, 0, wanted)
                    } catch (_: EOFException) {
                        // A cut-off gzip guide: keep what arrived.
                        -1
                    }
                    if (read < 0) break
                    total += read
                    if (read > 0) onChunk(buffer, read)
                }
            }
        }
    } catch (error: IOException) {
        if (call.isCanceled()) throw CancellationException("Cancelled guide request", error)
        throw error
    } finally {
        cancelHandle?.dispose()
    }
}
