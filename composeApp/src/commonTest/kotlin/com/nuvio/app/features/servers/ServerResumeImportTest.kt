package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val kept = ServerResumeImport.next(ServerResumeCache(), listOf(first), complete = true, fetchedAtMs = now)
        now = 9_000L
        val second = ServerResumeImport.entries(connection, resume, kept).single()

        assertEquals(5_000L, first.lastUpdatedEpochMs)
        assertEquals(first, second, "an unchanged item comes back identical, so Continue watching doesn't change")
    }

    private fun imported(connectionId: String, videoId: String, position: Long = 10L, updatedAt: Long = 1_000L) =
        WatchProgressEntry(
            contentType = "series",
            parentMetaId = "show",
            parentMetaType = "series",
            videoId = videoId,
            title = "One Piece",
            lastPositionMs = position,
            durationMs = 100L,
            lastUpdatedEpochMs = updatedAt,
            providerAddonId = "server:$connectionId",
        )

    @Test
    fun keepsTheServersListAsItIs() {
        val listed = listOf(imported("c1", "ep1"), imported("c1", "ep2"))

        val kept = ServerResumeImport.next(ServerResumeCache(), listed, complete = true, fetchedAtMs = 3_000L)

        assertEquals(listed, kept.shown)
        assertEquals(3_000L, kept.fetchedAtMs)
    }

    @Test
    fun dropsWhatTheServerNoLongerListsUnlessTheListWasCutOff() {
        val kept = ServerResumeImport.next(ServerResumeCache(), listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 1_000L)

        val complete = ServerResumeImport.next(kept, listOf(imported("c1", "ep2")), complete = true, fetchedAtMs = 2_000L)
        val cutOff = ServerResumeImport.next(kept, listOf(imported("c1", "ep2")), complete = false, fetchedAtMs = 2_000L)

        assertEquals(listOf("ep2"), complete.shown.map { it.videoId })
        assertEquals(setOf("ep1", "ep2"), cutOff.shown.mapTo(mutableSetOf()) { it.videoId })
    }

    @Test
    fun aDismissedItemStaysOutUntilTheServerChangesIt() {
        val first = ServerResumeImport.next(ServerResumeCache(), listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 1_000L)
        val dismissed = first.copy(dismissed = setOf("ep1"))

        val unchanged = ServerResumeImport.next(dismissed, listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 2_000L)
        val watchedFurther = ServerResumeImport.next(
            dismissed,
            listOf(imported("c1", "ep1", position = 40L, updatedAt = 1_500L), imported("c1", "ep2")),
            complete = true,
            fetchedAtMs = 2_000L,
        )

        assertEquals(listOf("ep2"), unchanged.shown.map { it.videoId })
        assertEquals(listOf("ep1", "ep2"), watchedFurther.shown.map { it.videoId })
        assertTrue(watchedFurther.dismissed.isEmpty())
    }

    @Test
    fun aDismissedItemTheServerDroppedIsForgotten() {
        val dismissed = ServerResumeImport.next(ServerResumeCache(), listOf(imported("c1", "ep1")), true, 1_000L)
            .copy(dismissed = setOf("ep1"))

        val next = ServerResumeImport.next(dismissed, emptyList(), complete = true, fetchedAtMs = 2_000L)

        assertTrue(next.dismissed.isEmpty())
    }

    @Test
    fun keepsTheServersListApartFromTheAppsOwnProgress() {
        val own = imported("c1", "ep1", updatedAt = 5_000L).copy(providerAddonId = null, lastSourceUrl = "https://x/ep1")
        val fromServer = listOf(imported("c1", "ep1", updatedAt = 1_000L), imported("c1", "ep2"))

        val merged = com.nuvio.app.features.watchprogress.mergeExternalProgressEntries(listOf(own), fromServer, emptySet())

        // Playing ep1 here is newer, so it wins; ep2 only the server has.
        assertEquals(listOf(own, fromServer[1]), merged)
        assertEquals(
            listOf(own),
            com.nuvio.app.features.watchprogress.mergeExternalProgressEntries(listOf(own), fromServer, setOf("show")),
            "with a tracker that has the show, the tracker's progress is the one shown",
        )
    }

    @Test
    fun importingServersDropTheirOwnContinueWatchingRow() {
        val connection = installFakeServer(FakeServerProvider(userStateRead = true))
        assertTrue(ServerResumeImport.connections().isEmpty(), "nothing is imported until the setting is on")

        ServerRepository.setImportWatchState(connection.id, true)

        assertEquals(listOf(connection.id), ServerResumeImport.connections().map { it.id })
        assertTrue(ServerCatalog.homeDefinitions().none { it.catalogId == "resume" })
    }

    private fun nextEpisode(connectionId: String) = MetaVideo(
        id = ServerItemRef(connectionId, "ep5").encode(),
        title = "The Man Who Will Become King",
        released = "2026-01-02",
        thumbnail = "https://server/ep5.jpg",
        season = 1,
        episode = 5,
        overview = "Luffy sets sail.",
    )

    @Test
    fun aServersNextUpPlaysItsEpisodeFromTheServer() {
        val connection = installFakeServer()

        val entry = ServerResumeImport.nextUpEntries(
            connection,
            listOf(ServerNextUp(show(connection.id), nextEpisode(connection.id), lastActivityEpochMs = 5_000L)),
        ).single()

        assertEquals(ServerItemRef(connection.id, "500").encode(), entry.contentId)
        assertEquals(ServerItemRef(connection.id, "ep5").encode(), entry.videoId)
        assertEquals(1 to 5, entry.season to entry.episode)
        assertEquals(5_000L, entry.lastActivityEpochMs)
        val item = entry.toContinueWatchingItem()
        assertTrue(item.isNextUp)
        assertEquals("https://server/ep5.jpg", item.imageUrl)
        assertEquals("The Man Who Will Become King", item.episodeTitle)
    }

    @Test
    fun serverArtworkIncludingItsThumbReachesContinueWatching() {
        val connection = installFakeServer()
        val title = show(connection.id).let { title ->
            title.copy(
                preview = title.preview.copy(
                    poster = "https://server/poster.jpg",
                    banner = "https://server/backdrop.jpg",
                    landscapePoster = "https://server/thumb.jpg",
                ),
            )
        }

        val resume = ServerResumeImport.entries(connection, listOf(ServerResume(title, episodeState(connection.id)))).single()
        val resumeItem = resume.toContinueWatchingItem()
        assertEquals("https://server/thumb.jpg", resumeItem.landscapePoster)
        assertEquals("https://server/poster.jpg", resumeItem.poster)
        assertTrue(resumeItem.mediaServerArtwork)

        val nextUp = ServerResumeImport.nextUpEntries(
            connection,
            listOf(ServerNextUp(title, nextEpisode(connection.id))),
        ).single().toContinueWatchingItem()
        assertEquals("https://server/thumb.jpg", nextUp.landscapePoster)
        assertTrue(nextUp.mediaServerArtwork)
    }

    @Test
    fun aServersNextUpWithAddonMetadataPlaysACatalogEpisode() {
        val connection = installFakeServer()
        ServerRepository.setCatalogMetadata(connection.id, true)

        val entry = ServerResumeImport.nextUpEntries(
            ServerRepository.connection(connection.id)!!,
            listOf(ServerNextUp(show(connection.id), nextEpisode(connection.id))),
        ).single()

        assertEquals("tmdb:37854", entry.contentId)
        assertEquals("tmdb:37854:1:5", entry.videoId)
    }

    @Test
    fun aServersNextUpKeepsTheServersOrderWithoutLastPlayedDates() {
        val connection = installFakeServer()
        ServerResumeImport.nowMs = { 10_000L }
        val other = ServerTitle(
            preview = MetaPreview(id = ServerItemRef(connection.id, "600").encode(), type = "series", name = "Lupin"),
            externalIds = TrackingExternalIds(),
        )

        val entries = ServerResumeImport.nextUpEntries(
            connection,
            listOf(
                ServerNextUp(other, nextEpisode(connection.id).copy(id = ServerItemRef(connection.id, "lupin2").encode())),
                ServerNextUp(show(connection.id), nextEpisode(connection.id)),
            ),
        )

        assertEquals(listOf("Lupin", "One Piece"), entries.sortedByDescending { it.lastActivityEpochMs }.map { it.title })
    }
}
