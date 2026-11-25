package com.nuvio.app.features.livetv

/**
 * Downloads a guide and hands its body to [onChunk] part by part as it arrives, gunzipped when it
 * is gzip (a `.gz` guide, or a panel that sends gzip without saying so). [onChunk] may reuse its
 * array once it returns. Stops after [maxBytes] of guide text.
 */
internal expect suspend fun liveTvStreamGuide(
    url: String,
    headers: Map<String, String>,
    maxBytes: Long,
    onChunk: (bytes: ByteArray, length: Int) -> Unit,
)
