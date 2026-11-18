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

/**
 * Adds [external] entries (a media server's continue-watching list) to Continue watching. One for the same video
 * as an entry already shown replaces it only when fresher, so playing it here takes over; one for a title the
 * tracker has ([trackedParentIds]) is left out, as the tracker is the source then.
 */
internal fun mergeExternalProgressEntries(
    entries: List<WatchProgressEntry>,
    external: List<WatchProgressEntry>,
    trackedParentIds: Set<String>,
): List<WatchProgressEntry> {
    if (external.isEmpty()) return entries
    val shownByVideo = entries.associateBy { it.videoId }
    val replaced = mutableSetOf<String>()
    val added = external.filter { entry ->
        if (entry.parentMetaId in trackedParentIds) return@filter false
        val shown = shownByVideo[entry.videoId] ?: return@filter true
        entry.isFresherThan(shown).also { fresher -> if (fresher) replaced += entry.videoId }
    }
    return entries.filterNot { it.videoId in replaced } + added
}
