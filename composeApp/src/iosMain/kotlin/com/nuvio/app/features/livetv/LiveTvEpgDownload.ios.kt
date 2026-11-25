package com.nuvio.app.features.livetv

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val GUIDE_BUFFER_BYTES = 64 * 1024
private const val GUIDE_MAX_GZIP_BYTES = 64 * 1024 * 1024
private const val GUIDE_MAX_GUNZIPPED_BYTES = 256 * 1024 * 1024

// Panels build xmltv.php before sending a byte of it, which can take minutes, and a whole guide
// can take longer than the addon client's one minute to arrive.
private val guideHttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15L * 60L * 1000L
        connectTimeoutMillis = 30_000L
        socketTimeoutMillis = 3L * 60L * 1000L
    }
    expectSuccess = false
}

internal actual suspend fun liveTvStreamGuide(
    url: String,
    headers: Map<String, String>,
    maxBytes: Long,
    onChunk: (bytes: ByteArray, length: Int) -> Unit,
) {
    guideHttpClient.prepareGet(url) {
        headers.forEach { (name, value) -> header(name, value) }
    }.execute { response ->
        check(response.status.isSuccess()) { "Guide request failed with HTTP ${response.status.value}" }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(GUIDE_BUFFER_BYTES)
        var total = 0L
        var isFirstRead = true
        // Content-Encoding gzip is unpacked by the system; a gzip file is gathered and unpacked whole.
        var gzipParts: MutableList<ByteArray>? = null
        while (total < maxBytes) {
            currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            if (isFirstRead) {
                isFirstRead = false
                if (read >= 2 && buffer[0] == 0x1F.toByte() && buffer[1] == 0x8B.toByte()) gzipParts = mutableListOf()
            }
            total += read
            val parts = gzipParts
            if (parts != null) {
                if (total > GUIDE_MAX_GZIP_BYTES) break
                parts += buffer.copyOf(read)
            } else {
                onChunk(buffer, read)
            }
        }
        val parts = gzipParts ?: return@execute
        val compressed = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { part ->
            part.copyInto(compressed, offset)
            offset += part.size
        }
        val xml = liveTvGunzip(compressed, minOf(maxBytes, GUIDE_MAX_GUNZIPPED_BYTES.toLong()).toInt())
        var start = 0
        while (start < xml.size) {
            val end = minOf(start + GUIDE_BUFFER_BYTES, xml.size)
            xml.copyInto(buffer, 0, start, end)
            onChunk(buffer, end - start)
            start = end
        }
    }
}
