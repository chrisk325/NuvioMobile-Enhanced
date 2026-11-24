package com.nuvio.app.features.player.skip

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveEpisodeSkipImdbIdTest {

    private suspend fun resolve(
        vararg ids: String?,
        metaImdbId: String? = null,
        tmdb: Map<Int, String> = emptyMap(),
        tvdb: Map<Int, String> = emptyMap(),
    ) = resolveEpisodeSkipImdbId(
        ids = ids.toList(),
        metaImdbId = metaImdbId,
        resolveTmdb = { tmdb[it] },
        resolveTvdb = { tvdb[it] },
    )

    @Test
    fun imdbVideoIdIsUsedDirectly() = runTest {
        assertEquals("tt0944947", resolve("tt0944947:1:2", "tt0944947"))
    }

    @Test
    fun aioMetadataTmdbIdResolvesThroughTmdb() = runTest {
        assertEquals("tt0944947", resolve("tmdb:1399:1:2", "tmdb:1399", tmdb = mapOf(1399 to "tt0944947")))
    }

    @Test
    fun aioMetadataTvdbIdResolvesThroughTvdb() = runTest {
        assertEquals("tt0944947", resolve("tvdb:121361:1:2", "tvdb:121361", tvdb = mapOf(121361 to "tt0944947")))
    }

    @Test
    fun customAddonIdFallsBackToMetaImdbId() = runTest {
        assertEquals("tt0944947", resolve("jellyfin:abc123:1:2", "jellyfin:abc123", metaImdbId = "tt0944947"))
    }

    @Test
    fun metaImdbIdIsPreferredOverNetworkLookup() = runTest {
        assertEquals("tt1", resolve("tmdb:1399:1:2", metaImdbId = "tt1", tmdb = mapOf(1399 to "tt2")))
    }

    @Test
    fun unresolvableIdsReturnNull() = runTest {
        assertNull(resolve("jellyfin:abc123:1:2", null))
    }
}
