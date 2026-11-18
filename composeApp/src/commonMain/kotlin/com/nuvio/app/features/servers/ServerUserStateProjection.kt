package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository

internal object ServerUserStateProjection {
    fun apply(details: ServerItemDetails) {
        val meta = details.meta
        val ref = ServerItemRef.parse(meta.id) ?: return
        val connection = ServerRepository.connection(ref.connectionId)?.takeIf { it.importWatchState } ?: return
        val isSeries = meta.type == ServerMediaKind.SERIES.contentType

        // Progress comes from the server's continue-watching list, kept apart from the app's synced progress.
        if (details.userStates.any { !it.played && it.positionMs > 0L }) ServerResumeImport.refreshSoon(connection.id)

        val (played, unplayed) = details.userStates
            .filter { !isSeries || (it.season != null && it.episode != null) }
            .partition { it.played }
        WatchedRepository.markWatchedLocally(
            played.filterNot { it.isWatched(meta, isSeries) }.map { it.toWatchedItem(meta, isSeries) },
        )
        WatchedRepository.unmarkWatchedLocally(
            unplayed.filter { it.isWatched(meta, isSeries) }.map { it.toWatchedItem(meta, isSeries) },
        )
    }

    private fun ServerUserState.isWatched(meta: MetaDetails, isSeries: Boolean): Boolean =
        WatchedRepository.isWatched(meta.id, meta.type, season.takeIf { isSeries }, episode.takeIf { isSeries })

    private fun ServerUserState.toWatchedItem(meta: MetaDetails, isSeries: Boolean): WatchedItem = WatchedItem(
        id = meta.id,
        type = meta.type,
        name = meta.name,
        poster = meta.poster,
        season = season.takeIf { isSeries },
        episode = episode.takeIf { isSeries },
        videoId = videoId.takeIf { isSeries },
        markedAtEpochMs = lastPlayedEpochMs ?: 0L,
    )
}
