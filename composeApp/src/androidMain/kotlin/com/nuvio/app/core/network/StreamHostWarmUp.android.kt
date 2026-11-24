package com.nuvio.app.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal actual fun warmUpStreamHosts(urls: List<String>) {
    if (urls.isEmpty()) return
    PlaybackDns.prefetch(urls.mapNotNull { it.toHttpUrlOrNull()?.host })
}
