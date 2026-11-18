package com.nuvio.app.features.servers

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerResumeImportTest {
    @AfterTest
    fun tearDown() {
        removeFakeServer()
        ServerResumeImport.reset()
        ServerResumeImport.nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }

    private fun show(connectionId: String) = ServerTitle(
        preview = MetaPreview(id = ServerItemRef(connectionId, "500").encode(), type = "series", name = "One Piece"),
        externalIds = TrackingExternalIds(imdb = "tt0388629", tmdb = 37854),
    )

    private fun episodeState(connectionId: String, played: Boolean = false, lastPlayed: Long? = 1_000L) = ServerUserState(
        videoId = ServerItemRef(connectionId, "ep1").encode(),
        positionMs = 600_000L,
        durationMs = 1_440_000L,
        played = played,
        lastPlayedEpochMs = lastPlayed,
        season = 1,
        episode = 4,
        title = "Luffy's Past!",
    )

    @Test
    fun aServerShowResumesItsEpisodeFromTheServer() {
        val connection = installFakeServer()

        val entry = ServerResumeImport.entries(connection, listOf(ServerResume(show(connection.id), episodeState(connection.id)))).single()

        assertEquals(ServerItemRef(connection.id, "500").encode(), entry.parentMetaId)
        assertEquals(ServerItemRef(connection.id, "ep1").encode(), entry.videoId)
        assertEquals(1 to 4, entry.seasonNumber to entry.episodeNumber)
        assertEquals("Luffy's Past!", entry.episodeTitle)
        assertEquals(600_000L to 1_440_000L, entry.lastPositionMs to entry.durationMs)
        assertEquals(1_000L, entry.lastUpdatedEpochMs)
        assertEquals("server:${connection.id}", entry.providerAddonId)
    }

    @Test
    fun aShowWithAddonMetadataResumesAsACatalogEpisode() {
        val connection = installFakeServer()
        ServerRepository.setCatalogMetadata(connection.id, true)

        val entry = ServerResumeImport.entries(
            ServerRepository.connection(connection.id)!!,
            listOf(ServerResume(show(connection.id), episodeState(connection.id))),
        ).single()

        assertEquals("tmdb:37854", entry.parentMetaId)
        assertEquals("tmdb:37854:1:4", entry.videoId)
    }

    @Test
    fun aMovieResumesItself() {
        val connection = installFakeServer()
        val movie = ServerTitle(MetaPreview(id = ServerItemRef(connection.id, "7").encode(), type = "movie", name = "Heat"))
        val state = episodeState(connection.id).copy(videoId = movie.preview.id, season = null, episode = null)

        val entry = ServerResumeImport.entries(connection, listOf(ServerResume(movie, state))).single()

        assertEquals(movie.preview.id, entry.videoId)
        assertEquals(null, entry.seasonNumber)
    }

    @Test
    fun skipsFinishedAndUnstartedItems() {
        val connection = installFakeServer()
        val finished = episodeState(connection.id, played = true)
        val unstarted = episodeState(connection.id).copy(positionMs = 0L)

        val entries = ServerResumeImport.entries(
            connection,
            listOf(ServerResume(show(connection.id), finished), ServerResume(show(connection.id), unstarted)),
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun keepsTheFirstSeenTimeWhenTheServerSendsNoLastPlayedDate() {
        val connection = installFakeServer()
        var now = 5_000L
        ServerResumeImport.nowMs = { now }
        val resume = listOf(ServerResume(show(connection.id), episodeState(connection.id, lastPlayed = null)))

        val first = ServerResumeImport.entries(connection, resume).single()
        val kept = ServerResumeImport.diff(ServerResumeCache(), listOf(first), complete = true, fetchedAtMs = now).cache
        now = 9_000L
        val second = ServerResumeImport.entries(connection, resume, kept).single()

        assertEquals(5_000L, first.lastUpdatedEpochMs)
        assertEquals(5_000L, second.lastUpdatedEpochMs)
    }

    @Test
    fun appliesOnlyWhatChangedSinceTheLastImport() {
        val first = listOf(imported("c1", "ep1"), imported("c1", "ep2"))
        val kept = ServerResumeImport.diff(ServerResumeCache(), first, complete = true, fetchedAtMs = 1_000L)
        assertEquals(listOf("ep1", "ep2"), kept.changed.map { it.videoId }, "the first import brings everything")

        val moved = imported("c1", "ep2").copy(lastPositionMs = 50L, lastUpdatedEpochMs = 2_000L)
        val next = ServerResumeImport.diff(kept.cache, listOf(imported("c1", "ep1"), moved, imported("c1", "ep3")), true, 3_000L)

        assertEquals(listOf("ep2", "ep3"), next.changed.map { it.videoId })
        assertTrue(next.dropped.isEmpty())
        assertEquals(3_000L, next.cache.fetchedAtMs)
    }

    @Test
    fun leavesContinueWatchingAloneWhenNothingChanged() {
        val entries = listOf(imported("c1", "ep1"))
        val kept = ServerResumeImport.diff(ServerResumeCache(), entries, complete = true, fetchedAtMs = 1_000L).cache

        val again = ServerResumeImport.diff(kept, entries, complete = true, fetchedAtMs = 5_000L)

        assertTrue(again.changed.isEmpty())
        assertTrue(again.dropped.isEmpty())
    }

    @Test
    fun dropsOnlyWhatTheServerListedBefore() {
        val kept = ServerResumeImport.diff(ServerResumeCache(), listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 1_000L).cache

        val next = ServerResumeImport.diff(kept, listOf(imported("c1", "ep2")), complete = true, fetchedAtMs = 2_000L)
        val cutOff = ServerResumeImport.diff(kept, listOf(imported("c1", "ep2")), complete = false, fetchedAtMs = 2_000L)

        assertEquals(setOf("ep1"), next.dropped)
        assertEquals(setOf("ep2"), next.cache.items.keys)
        assertTrue(cutOff.dropped.isEmpty(), "a full list may have cut ep1 off")
        assertEquals(setOf("ep1", "ep2"), cutOff.cache.items.keys)
    }

    private fun imported(connectionId: String, videoId: String, updatedAt: Long = 1_000L) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = videoId,
        title = "One Piece",
        lastPositionMs = 10L,
        durationMs = 100L,
        lastUpdatedEpochMs = updatedAt,
        providerAddonId = "server:$connectionId",
    )

    @Test
    fun dropsWhatTheServerNoLongerLists() {
        val gone = imported("c1", "ep1")

        assertTrue(ServerResumeImport.isStale(gone, "c1", listed = setOf("ep2"), fetchedAtMs = 5_000L))
        assertFalse(ServerResumeImport.isStale(imported("c1", "ep2"), "c1", listed = setOf("ep2"), fetchedAtMs = 5_000L))
    }

    @Test
    fun leavesOtherProgressAlone() {
        val listed = emptySet<String>()

        // Another server's, the app's own, and one being played right now.
        assertFalse(ServerResumeImport.isStale(imported("c2", "ep1"), "c1", listed, fetchedAtMs = 5_000L))
        assertFalse(ServerResumeImport.isStale(imported("c1", "ep1").copy(providerAddonId = null), "c1", listed, 5_000L))
        assertFalse(ServerResumeImport.isStale(imported("c1", "ep1", updatedAt = 6_000L), "c1", listed, 5_000L))
    }

    @Test
    fun keepsProgressFromPlayingHere() {
        val playedHere = imported("c1", "ep1").copy(lastSourceUrl = "https://fake.example/ep1")

        assertFalse(ServerResumeImport.isStale(playedHere, "c1", listed = emptySet(), fetchedAtMs = 5_000L))
    }

    @Test
    fun importingServersDropTheirOwnContinueWatchingRow() {
        val connection = installFakeServer(FakeServerProvider(userStateRead = true))
        assertTrue(ServerResumeImport.connections().isEmpty(), "nothing is imported until the setting is on")

        ServerRepository.setImportWatchState(connection.id, true)

        assertEquals(listOf(connection.id), ServerResumeImport.connections().map { it.id })
        assertTrue(ServerCatalog.homeDefinitions().none { it.catalogId == "resume" })
    }
}
