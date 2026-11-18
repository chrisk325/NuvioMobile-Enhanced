package com.nuvio.app.features.servers

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerFavoritesTest {
    @AfterTest
    fun tearDown() {
        removeFakeServer()
        ServerMatcher.shared.clear()
        ServerFavorites.reset()
    }

    private fun provider(favorites: Boolean = true) = FakeServerProvider(favorites = favorites).apply {
        indexedIds["7"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
        indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947", tmdb = 1399)
    }

    // The matcher and the server calls run on real dispatchers.
    private suspend fun <T> onServer(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(15_000L) { block() } }

    @Test
    fun favouritesACatalogMovieThroughTheServerCopy() = runTest {
        val provider = provider()
        installFakeServer(provider)

        val result = onServer { ServerFavorites.setFavorite("tt0111161", "movie", favorite = true) }

        assertEquals(ServerFavoriteResult.ADDED, result)
        assertEquals(listOf("7" to true), provider.favoriteChanges)
        assertTrue(ServerFavorites.isFavorite("tt0111161", "movie"))
        assertEquals(1, ServerFavorites.state.value.writes)
    }

    @Test
    fun favouritesTheShowRatherThanAnEpisode() = runTest {
        val provider = provider()
        installFakeServer(provider)

        val result = onServer { ServerFavorites.setFavorite("tt0944947", "series", favorite = true) }

        assertEquals(ServerFavoriteResult.ADDED, result)
        assertEquals(listOf(FakeServerProvider.SHOW_ID to true), provider.favoriteChanges)
    }

    @Test
    fun readsTheServersFavouritesByAnyOfTheirIds() = runTest {
        val provider = provider().apply { favoriteIds += "7" }
        val connection = installFakeServer(provider)

        onServer { ServerFavorites.refresh() }

        assertTrue(ServerFavorites.isFavorite("tt0111161", "movie"))
        assertTrue(ServerFavorites.isFavorite("tmdb:278", "movie"))
        assertTrue(ServerFavorites.isFavorite(ServerItemRef(connection.id, "7").encode(), "movie"))
        assertFalse(ServerFavorites.isFavorite("tmdb:278", "series"), "TMDB numbers a show and a movie separately")
        assertFalse(ServerFavorites.isFavorite("tt0944947", "series"))
    }

    @Test
    fun unfavouritesWhatTheServerAlreadyHas() = runTest {
        val provider = provider().apply { favoriteIds += "7" }
        installFakeServer(provider)
        onServer { ServerFavorites.refresh() }

        val result = onServer { ServerFavorites.setFavorite("tmdb:278", "movie", favorite = false) }

        assertEquals(ServerFavoriteResult.REMOVED, result)
        assertEquals(listOf("7" to false), provider.favoriteChanges)
        assertFalse(ServerFavorites.isFavorite("tt0111161", "movie"))
    }

    @Test
    fun toggleFlipsAServerTitleDirectly() = runTest {
        val provider = provider()
        val connection = installFakeServer(provider)
        val preview = com.nuvio.app.features.home.MetaPreview(
            id = ServerItemRef(connection.id, "7").encode(),
            type = "movie",
            name = "Item 7",
        )

        assertEquals(ServerFavoriteResult.ADDED, onServer { ServerFavorites.toggle(preview) })
        assertEquals(ServerFavoriteResult.REMOVED, onServer { ServerFavorites.toggle(preview) })

        assertEquals(listOf("7" to true, "7" to false), provider.favoriteChanges)
    }

    @Test
    fun saysWhenNoServerHasTheTitle() = runTest {
        val provider = provider()
        installFakeServer(provider)

        val result = onServer { ServerFavorites.setFavorite("tt9999999", "movie", favorite = true) }

        assertEquals(ServerFavoriteResult.NOT_FOUND, result)
        assertTrue(provider.favoriteChanges.isEmpty())
    }

    @Test
    fun reportsAFailedWrite() = runTest {
        val provider = provider().apply { failingFavorites += "7" }
        installFakeServer(provider)

        val result = onServer { ServerFavorites.setFavorite("tt0111161", "movie", favorite = true) }

        assertEquals(ServerFavoriteResult.FAILED, result)
        assertFalse(ServerFavorites.isFavorite("tt0111161", "movie"))
        assertEquals(0, ServerFavorites.state.value.writes)
    }

    @Test
    fun staysOutOfTheWayOfServersWithoutFavourites() = runTest {
        installFakeServer(provider(favorites = false))

        assertFalse(ServerFavorites.isAvailable())
        assertNull(ServerFavorites.mirror("tt0111161", "movie", favorite = true))
        assertEquals(ServerFavoriteResult.UNAVAILABLE, ServerFavorites.setFavorite("tt0111161", "movie", favorite = true))
        assertTrue(ServerCatalog.favoriteLibraries().isEmpty())
    }

    @Test
    fun mirrorsALibraryChange() = runTest {
        val provider = provider()
        installFakeServer(provider)

        val job = assertNotNull(ServerFavorites.mirror("tt0111161", "movie", favorite = true))
        onServer { job.join() }

        assertEquals(listOf("7" to true), provider.favoriteChanges)
        assertNull(ServerFavorites.mirror("tt0111161", "collection", favorite = true))
    }

    @Test
    fun listsFavouritesAsALibraryShelf() = runTest {
        val provider = provider().apply { favoriteIds += listOf("7", FakeServerProvider.SHOW_ID) }
        val connection = installFakeServer(provider)

        val shelf = ServerCatalog.favoriteLibraries().single()
        assertEquals(ServerCatalog.FAVORITES_ID, shelf.library.id)
        val page = onServer {
            ServerCatalog.page(CatalogTarget.Server(connection.id, ServerCatalog.FAVORITES_ID, "mixed"), skip = 0)
        }

        assertEquals(
            listOf(ServerItemRef(connection.id, "7").encode(), ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()),
            page.items.map { it.id },
        )
        assertNull(page.nextSkip)
    }
}
