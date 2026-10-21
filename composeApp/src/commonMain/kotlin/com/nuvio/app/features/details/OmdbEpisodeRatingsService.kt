package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal object OmdbEpisodeRatingsService {
    private const val MaxCachedSeasons = 400
    private const val CompleteSeasonTtlMs = 7L * 24 * 60 * 60 * 1000
    private const val IncompleteSeasonTtlMs = 12L * 60 * 60 * 1000
    private const val MaxEpisodeLookupsPerSeason = 30
    private const val EpisodeLookupParallelism = 6

    private val log = Logger.withTag("OmdbEpisodeRatings")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbIdRegex = Regex("tt\\d+")

    private val seasonCache = LinkedHashMap<String, CachedOmdbSeason>()
    private val cacheLock = Mutex()
    private var hasLoadedFromDisk = false

    val hasApiKey: Boolean
        get() = OmdbSettingsRepository.effectiveApiKey().isNotBlank()

    fun shouldApply(meta: MetaDetails, settings: TmdbSettings): Boolean =
        hasApiKey &&
            (!settings.enabled || settings.useEpisodeRatings) &&
            meta.videos.any { it.season != null && it.episode != null }

    suspend fun applyTo(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: TmdbSettings,
    ): MetaDetails {
        if (!shouldApply(meta, settings)) return meta
        val imdbId = extractImdbId(meta.imdbId, meta.id, fallbackItemId) ?: return meta
        val nowIso = Clock.System.now().toString()
        val episodesBySeason = meta.videos
            .filter { video ->
                val released = video.released?.takeIf { it.isNotBlank() }
                released == null || released <= nowIso
            }
            .mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                season to episode
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, episodes) -> episodes.toSet() }
        val ratings = fetchRatings(
            imdbId = imdbId,
            seasonNumbers = meta.videos.mapNotNull { it.season }.distinct(),
            airedEpisodesBySeason = episodesBySeason,
        )
        if (ratings.isEmpty()) return meta
        return meta.copy(
            videos = meta.videos.map { video ->
                val season = video.season ?: return@map video
                val episode = video.episode ?: return@map video
                ratings[season to episode]?.let { video.copy(imdbRating = it) } ?: video
            },
        )
    }

    fun extractImdbId(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { candidate ->
            candidate?.let(imdbIdRegex::find)?.value
        }

    suspend fun fetchRatings(
        imdbId: String,
        seasonNumbers: List<Int>,
        airedEpisodesBySeason: Map<Int, Set<Int>> = emptyMap(),
    ): Map<Pair<Int, Int>, Double> = withContext(Dispatchers.Default) {
        val apiKey = OmdbSettingsRepository.effectiveApiKey()
        if (apiKey.isBlank()) return@withContext emptyMap()

        val normalizedSeasons = seasonNumbers.distinct().sorted()
        if (normalizedSeasons.isEmpty()) return@withContext emptyMap()

        cacheLock.withLock { ensureLoadedFromDisk() }

        val perSeason = coroutineScope {
            normalizedSeasons.map { season ->
                async {
                    season to fetchSeason(
                        imdbId = imdbId,
                        season = season,
                        apiKey = apiKey,
                        airedEpisodes = airedEpisodesBySeason[season].orEmpty(),
                    )
                }
            }.awaitAll()
        }

        perSeason.flatMap { (season, episodeRatings) ->
            episodeRatings.map { (episode, rating) -> (season to episode) to rating }
        }.toMap()
    }

    private fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun CachedOmdbSeason.isFresh(now: Long): Boolean {
        val ttl = if (missing.isEmpty()) CompleteSeasonTtlMs else IncompleteSeasonTtlMs
        return now - fetchedAt in 0..ttl
    }

    private fun ensureLoadedFromDisk() {
        if (hasLoadedFromDisk) return
        hasLoadedFromDisk = true
        val payload = OmdbEpisodeRatingsStorage.loadPayload()?.trim().orEmpty()
        if (payload.isEmpty()) return
        val decoded = runCatching { json.decodeFromString<Map<String, CachedOmdbSeason>>(payload) }.getOrNull()
            ?: runCatching { json.decodeFromString<Map<String, Map<Int, Double>>>(payload) }
                .getOrNull()
                ?.mapValues { (_, ratings) -> CachedOmdbSeason(ratings = ratings, fetchedAt = 0L) }
        if (decoded == null) {
            log.w { "Failed to load cached IMDb ratings" }
            return
        }
        seasonCache.putAll(decoded)
    }

    private fun persistToDisk() {
        val payload = runCatching { json.encodeToString<Map<String, CachedOmdbSeason>>(seasonCache) }
            .getOrNull() ?: return
        OmdbEpisodeRatingsStorage.savePayload(payload)
    }

    private suspend fun fetchSeason(
        imdbId: String,
        season: Int,
        apiKey: String,
        airedEpisodes: Set<Int>,
    ): Map<Int, Double> {
        val cacheKey = "$imdbId:$season"
        val cached = cacheLock.withLock { seasonCache[cacheKey] }
        val now = nowEpochMs()
        if (cached != null && cached.isFresh(now)) {
            val newlyAired = airedEpisodes - cached.ratings.keys - cached.missing.toSet()
            if (newlyAired.isEmpty()) return cached.ratings
        }

        val url = "https://www.omdbapi.com/?i=$imdbId&Season=$season&apikey=$apiKey"
        val response = runCatching {
            json.decodeFromString<OmdbSeasonResponse>(httpGetText(url))
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "OMDb season request failed for $imdbId season $season: ${error.message}" }
        }.getOrNull() ?: return cached?.ratings.orEmpty()

        if (!response.response.equals("True", ignoreCase = true)) {
            log.w { "OMDb returned no season data for $imdbId season $season: ${response.error}" }
            return cached?.ratings.orEmpty()
        }

        val listedEpisodes = response.episodes.orEmpty()
            .mapNotNull { episode -> episode.episode?.toIntOrNull()?.let { it to episode } }
            .toMap()
        val seasonRatings = listedEpisodes
            .mapNotNull { (episodeNumber, episode) ->
                parseRating(episode.imdbRating)?.let { episodeNumber to it }
            }
            .toMap()

        val knownEpisodes = (airedEpisodes + listedEpisodes.keys).sorted()
        val unrated = knownEpisodes.filter { it !in seasonRatings }
        val lookups = unrated.take(MaxEpisodeLookupsPerSeason)
        val lookedUpRatings = lookupEpisodeRatings(
            imdbId = imdbId,
            season = season,
            episodes = lookups,
            episodeImdbIds = listedEpisodes.mapValues { (_, episode) -> episode.imdbId },
            apiKey = apiKey,
        )

        val merged = cached?.ratings.orEmpty() + seasonRatings + lookedUpRatings
        val missing = knownEpisodes.filter { it !in merged }

        cacheLock.withLock {
            seasonCache.remove(cacheKey)
            seasonCache[cacheKey] = CachedOmdbSeason(
                ratings = merged,
                missing = missing,
                fetchedAt = now,
            )
            while (seasonCache.size > MaxCachedSeasons) {
                val oldestKey = seasonCache.keys.firstOrNull() ?: break
                seasonCache.remove(oldestKey)
            }
            persistToDisk()
        }
        return merged
    }

    private suspend fun lookupEpisodeRatings(
        imdbId: String,
        season: Int,
        episodes: List<Int>,
        episodeImdbIds: Map<Int, String?>,
        apiKey: String,
    ): Map<Int, Double> {
        if (episodes.isEmpty()) return emptyMap()
        val results = mutableMapOf<Int, Double>()
        episodes.chunked(EpisodeLookupParallelism).forEach { batch ->
            val batchResults = coroutineScope {
                batch.map { episode ->
                    async {
                        val episodeImdbId = episodeImdbIds[episode]
                            ?.takeIf { imdbIdRegex.matches(it) }
                        val url = if (episodeImdbId != null) {
                            "https://www.omdbapi.com/?i=$episodeImdbId&apikey=$apiKey"
                        } else {
                            "https://www.omdbapi.com/?i=$imdbId&Season=$season&Episode=$episode&apikey=$apiKey"
                        }
                        val detail = runCatching {
                            json.decodeFromString<OmdbEpisodeDetailResponse>(httpGetText(url))
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                        }.getOrNull()
                        val rating = detail
                            ?.takeIf { it.response.equals("True", ignoreCase = true) }
                            ?.let { parseRating(it.imdbRating) }
                        rating?.let { episode to it }
                    }
                }.awaitAll()
            }
            batchResults.filterNotNull().forEach { (episode, rating) -> results[episode] = rating }
        }
        return results
    }

    private fun parseRating(raw: String?): Double? =
        raw?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
}

@Serializable
internal data class CachedOmdbSeason(
    val ratings: Map<Int, Double> = emptyMap(),
    val missing: List<Int> = emptyList(),
    @SerialName("fetched_at") val fetchedAt: Long = 0L,
)

@Serializable
private data class OmdbSeasonResponse(
    @SerialName("Response") val response: String? = null,
    @SerialName("Error") val error: String? = null,
    @SerialName("Episodes") val episodes: List<OmdbEpisodeEntry>? = null,
)

@Serializable
private data class OmdbEpisodeEntry(
    @SerialName("Episode") val episode: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("imdbID") val imdbId: String? = null,
)

@Serializable
private data class OmdbEpisodeDetailResponse(
    @SerialName("Response") val response: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
)
