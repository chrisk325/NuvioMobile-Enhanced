package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchProgressSourceProjectionTest {
    @Test
    fun `remote source excludes every Nuvio progress entry`() {
        val nuvioEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 200L),
            entry(parentMetaId = "nuvio-only", updatedAt = 300L),
        )
        val providerEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 100L),
        )

        listOf(WatchProgressSource.TRAKT, WatchProgressSource.SIMKL).forEach { source ->
            val projected = projectWatchProgressSourceEntries(
                source = source,
                nuvioEntries = nuvioEntries,
                providerEntries = providerEntries,
            )

            assertEquals(providerEntries, projected)
        }
    }

    @Test
    fun `remote source keeps progress imported from a media server unless it tracks the title`() {
        val fromServer = entry(parentMetaId = "server-only").copy(providerAddonId = "server:c1")
        val alsoTracked = entry(parentMetaId = "shared").copy(providerAddonId = "server:c1")
        val providerEntries = listOf(entry(parentMetaId = "shared", updatedAt = 100L))

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.TRAKT,
            nuvioEntries = listOf(fromServer, alsoTracked, entry(parentMetaId = "nuvio-only")),
            providerEntries = providerEntries,
        )

        assertEquals(providerEntries + fromServer, projected)
    }

    @Test
    fun `Nuvio source excludes every provider progress entry`() {
        val nuvioEntries = listOf(entry(parentMetaId = "nuvio"))
        val providerEntries = listOf(entry(parentMetaId = "provider"))

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.NUVIO_SYNC,
            nuvioEntries = nuvioEntries,
            providerEntries = providerEntries,
        )

        assertEquals(nuvioEntries, projected)
    }

    private fun entry(
        parentMetaId: String,
        updatedAt: Long = 1L,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = "$parentMetaId:1:1",
        title = parentMetaId,
        seasonNumber = 1,
        episodeNumber = 1,
        lastPositionMs = 10L,
        durationMs = 100L,
        lastUpdatedEpochMs = updatedAt,
    )
}
