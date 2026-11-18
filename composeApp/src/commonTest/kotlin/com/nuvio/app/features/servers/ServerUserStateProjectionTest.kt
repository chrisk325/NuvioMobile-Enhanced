package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUserStateProjectionTest {
    private val id = ServerItemRef("cfake", "7").encode()

    @AfterTest
    fun tearDown() {
        WatchProgressRepository.setExternalEntries(emptyList())
        WatchProgressRepository.onExternalEntriesRemoved = {}
        WatchProgressRepository.clearProgress(id)
        removeFakeServer()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun keepsServerProgressOutOfTheSyncedProgress() {
        val connection = installFakeServer()
        ServerRepository.setImportWatchState(connection.id, true)
        val details = ServerItemDetails(
            meta = MetaDetails(id = id, type = "movie", name = "Item 7"),
            externalIds = TrackingExternalIds(),
            userStates = listOf(
                ServerUserState(id, positionMs = 30_000L, durationMs = 60_000L, played = false, lastPlayedEpochMs = 5_000L),
            ),
        )

        ServerUserStateProjection.apply(details)

        // It reaches Continue watching through the server's continue-watching list instead, which a sync can't wipe.
        assertNull(WatchProgressRepository.progressForVideo(id))
    }

    @Test
    fun showsAndRemovesProgressKeptOutsideTheSyncedProgress() {
        val entry = WatchProgressEntry(
            contentType = "movie",
            parentMetaId = id,
            parentMetaType = "movie",
            videoId = id,
            title = "Item 7",
            lastPositionMs = 30_000L,
            durationMs = 60_000L,
            lastUpdatedEpochMs = 5_000L,
            providerAddonId = "server:cfake",
        )
        val removed = mutableListOf<WatchProgressEntry>()
        WatchProgressRepository.onExternalEntriesRemoved = { removed += it }

        WatchProgressRepository.setExternalEntries(listOf(entry))
        assertEquals(30_000L, WatchProgressRepository.progressForVideo(id)?.lastPositionMs)

        WatchProgressRepository.clearProgress(id)
        assertNull(WatchProgressRepository.progressForVideo(id))
        assertEquals(listOf(entry), removed)
    }
}
