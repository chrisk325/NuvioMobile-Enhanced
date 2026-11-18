package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource

internal fun projectWatchProgressSourceEntries(
    source: WatchProgressSource,
    nuvioEntries: Collection<WatchProgressEntry>,
    providerEntries: Collection<WatchProgressEntry>,
): List<WatchProgressEntry> = if (source.providerId == null) {
    nuvioEntries.toList()
} else {
    // Progress imported from a media server lives only in the app, so it stays unless the tracker has the title too.
    val tracked = providerEntries.mapTo(mutableSetOf()) { it.parentMetaId }
    providerEntries.toList() + nuvioEntries.filter { it.isFromMediaServer && it.parentMetaId !in tracked }
}

internal val WatchProgressEntry.isFromMediaServer: Boolean
    get() = providerAddonId?.startsWith(MEDIA_SERVER_PROVIDER_PREFIX) == true

/** The providerAddonId prefix of progress imported from a media server ("server:<connection id>"). */
internal const val MEDIA_SERVER_PROVIDER_PREFIX = "server:"
