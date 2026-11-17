package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.player.skip.SimklIdResolver
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tracking.TrackingAnimeIdMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val AnimeMappingTimeoutMs = 15_000L
    private const val MaxAnimeMappingsPerMeta = 6
    private const val TvdbLookupTimeoutMs = 10_000L
    private const val MaxAlignedSeasons = 40
    private val tvdbIdRegex = Regex("(?i)tvdb[:_](\\d+)")
    private val tvdbImdbCache = mutableMapOf<String, String?>()
    private val animeIdSources = setOf("kitsu", "mal", "anilist", "anidb")

    private data class EpisodeTarget(
        val imdbId: String,
        val season: Int,
        val episode: Int,
    )

    private data class ResolvedTarget(
        val target: EpisodeTarget,
        val fromAddonNumbers: Boolean,
    )

    private data class SeasonData(
        val ratings: Map<Int, Double>,
        val airDates: Map<Int, String> = emptyMap(),
        val totalSeasons: Int? = null,
    )

    private data class AnimeVideoRef(
        val source: String,
        val id: Long,
        val episode: Int,
    )

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
            meta.type != "movie" &&
            meta.videos.any { video ->
                (video.season != null && video.episode != null) ||
                    (video.imdbSeason != null && video.imdbEpisode != null) ||
                    animeVideoRef(video.id) != null
            }

    suspend fun applyTo(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: TmdbSettings,
    ): MetaDetails {
        if (!shouldApply(meta, settings)) return meta
        val defaultImdbId = extractImdbId(meta.imdbId, meta.id, fallbackItemId)
            ?: resolveImdbFromTvdb(meta, fallbackItemId)
        val animeMappings = resolveAnimeMappings(meta)
        val resolvedTargets = meta.videos.map { video ->
            targetFor(video = video, defaultImdbId = defaultImdbId, animeMappings = animeMappings)
        }
        val targets = resolvedTargets.map { it?.target }
        if (targets.all { it == null }) return meta

        val nowIso = Clock.System.now().toString()
        val airedTargets = meta.videos.indices
            .filter { index ->
                val released = meta.videos[index].released?.takeIf { it.isNotBlank() }
                released == null || released <= nowIso
            }
            .mapNotNull { index -> targets[index] }

        val ratings = mutableMapOf<EpisodeTarget, Double>()
        val seasonDataByImdb = mutableMapOf<String, Map<Int, SeasonData>>()
        targets.filterNotNull().groupBy { it.imdbId }.forEach { (imdbId, imdbTargets) ->
            val seasonData = fetchSeasonData(
                imdbId = imdbId,
                seasonNumbers = imdbTargets.map { it.season }.distinct(),
                airedEpisodesBySeason = airedTargets
                    .filter { it.imdbId == imdbId }
                    .groupBy({ it.season }, { it.episode })
                    .mapValues { (_, episodes) -> episodes.toSet() },
            )
            seasonDataByImdb[imdbId] = seasonData
            seasonData.forEach { (season, data) ->
                data.ratings.forEach { (episode, rating) ->
                    ratings[EpisodeTarget(imdbId = imdbId, season = season, episode = episode)] = rating
                }
            }
        }

        // Episodes that only have the addon's own numbering may be split into different
        // seasons than IMDb uses (e.g. anime: 2 addon seasons vs 5 on IMDb). Re-pair them.
        val addonNumberedIndices = resolvedTargets.indices.filter { resolvedTargets[it]?.fromAddonNumbers == true }
        val alignedRatings = defaultImdbId
            ?.takeIf { addonNumberedIndices.isNotEmpty() }
            ?.let { imdbId ->
                alignAddonNumberedRatings(
                    imdbId = imdbId,
                    addonVideos = addonNumberedIndices.map { meta.videos[it] },
                    fetched = seasonDataByImdb[imdbId].orEmpty(),
                )
            }

        if (ratings.isEmpty() && alignedRatings.isNullOrEmpty()) return meta
        return meta.copy(
            videos = meta.videos.mapIndexed { index, video ->
                val resolved = resolvedTargets[index]
                val rating = if (alignedRatings != null && resolved?.fromAddonNumbers == true) {
                    val season = video.season
                    val episode = video.episode
                    if (season != null && episode != null) alignedRatings[season to episode] else null
                } else {
                    resolved?.target?.let(ratings::get)
                }
                rating?.let { video.copy(imdbRating = it) } ?: video
            },
        )
    }

    /**
     * Returns ratings keyed by the addon's season/episode numbers when the addon's seasons
     * differ from IMDb's, or null when the plain number lookup already lines up.
     */
    private suspend fun alignAddonNumberedRatings(
        imdbId: String,
        addonVideos: List<MetaVideo>,
        fetched: Map<Int, SeasonData>,
    ): Map<Pair<Int, Int>, Double>? {
        val totalSeasons = fetched.values.firstNotNullOfOrNull { it.totalSeasons }
            ?.takeIf { it in 1..MaxAlignedSeasons }
            ?: return null
        val addonSeasons = addonVideos.mapNotNull { video -> video.season?.takeIf { it > 0 } }.toSet()
        val imdbSeasons = (1..totalSeasons).toSet()
        if (addonSeasons.isEmpty() || addonSeasons == imdbSeasons) return null

        val missingSeasons = (imdbSeasons - fetched.keys).toList()
        val allSeasons = fetched + if (missingSeasons.isEmpty()) {
            emptyMap()
        } else {
            fetchSeasonData(imdbId = imdbId, seasonNumbers = missingSeasons)
        }
        val externalEpisodes = allSeasons
            .filterKeys { it == 0 || it in imdbSeasons }
            .flatMap { (season, data) ->
                (data.airDates.keys + data.ratings.keys).map { episode ->
                    ExternalEpisodeRating(
                        season = season,
                        episode = episode,
                        airDate = data.airDates[episode],
                        rating = data.ratings[episode],
                    )
                }
            }
        if (externalEpisodes.isEmpty()) return null
        return alignEpisodeRatings(
            addonEpisodes = addonVideos.map { AddonEpisodeAirDate(it.season, it.episode, it.released) },
            externalEpisodes = externalEpisodes,
        )
    }

    private fun targetFor(
        video: MetaVideo,
        defaultImdbId: String?,
        animeMappings: Map<Pair<String, Long>, TrackingAnimeIdMapper.AnimeImdbMapping>,
    ): ResolvedTarget? {
        val mappedSeason = video.imdbSeason
        val mappedEpisode = video.imdbEpisode?.takeIf { it > 0 }
        if (mappedSeason != null && mappedSeason >= 0 && mappedEpisode != null) {
            val imdbId = extractImdbId(video.imdbId) ?: defaultImdbId
            if (imdbId != null) {
                return ResolvedTarget(EpisodeTarget(imdbId = imdbId, season = mappedSeason, episode = mappedEpisode), false)
            }
        }
        val animeRef = animeVideoRef(video.id)
        if (animeRef != null) {
            val mapping = animeMappings[animeRef.source to animeRef.id]
            if (mapping != null) {
                val (season, episode) = mapping.imdbEpisodeFor(animeRef.episode) ?: return null
                return ResolvedTarget(EpisodeTarget(imdbId = mapping.imdb, season = season, episode = episode), false)
            }
        }
        val season = video.season ?: return null
        val episode = video.episode ?: return null
        val imdbId = defaultImdbId ?: return null
        return ResolvedTarget(EpisodeTarget(imdbId = imdbId, season = season, episode = episode), true)
    }

    private fun extractTvdbId(meta: MetaDetails, fallbackItemId: String): String? {
        meta.tvdbId?.trim()?.takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) && id.trimStart('0').isNotEmpty() }
            ?.let { return it }
        return listOfNotNull(meta.id, fallbackItemId, meta.videos.firstOrNull()?.id)
            .firstNotNullOfOrNull { candidate ->
                tvdbIdRegex.find(candidate)?.groupValues?.getOrNull(1)?.takeIf { it.trimStart('0').isNotEmpty() }
            }
    }

    private suspend fun resolveImdbFromTvdb(meta: MetaDetails, fallbackItemId: String): String? {
        val tvdbId = extractTvdbId(meta, fallbackItemId) ?: return null
        cacheLock.withLock {
            if (tvdbImdbCache.containsKey(tvdbId)) return tvdbImdbCache[tvdbId]
        }
        val resolved = withTimeoutOrNull(TvdbLookupTimeoutMs) {
            SimklIdResolver.resolveIds("tvdb", tvdbId)
        }
        val imdbId = extractImdbId(resolved?.imdb)
        if (resolved != null) {
            cacheLock.withLock { tvdbImdbCache[tvdbId] = imdbId }
        }
        if (imdbId == null) {
            log.w { "No IMDb id found for tvdb:$tvdbId" }
        }
        return imdbId
    }

    private suspend fun resolveAnimeMappings(
        meta: MetaDetails,
    ): Map<Pair<String, Long>, TrackingAnimeIdMapper.AnimeImdbMapping> {
        val refs = meta.videos
            .filter { video -> video.imdbSeason == null || video.imdbEpisode == null }
            .mapNotNull { video -> animeVideoRef(video.id) }
            .map { ref -> ref.source to ref.id }
            .distinct()
            .take(MaxAnimeMappingsPerMeta)
        if (refs.isEmpty()) return emptyMap()
        return refs.mapNotNull { ref ->
            val mapping = withTimeoutOrNull(AnimeMappingTimeoutMs) {
                TrackingAnimeIdMapper.animeImdbMapping(ref.first, ref.second)
            }
            if (mapping == null) {
                log.w { "No IMDb mapping found for anime ${ref.first}:${ref.second}" }
            }
            mapping?.let { ref to it }
        }.toMap()
    }

    private fun animeVideoRef(videoId: String): AnimeVideoRef? {
        val parts = videoId.trim().split(':')
        if (parts.size < 3) return null
        val source = parts[0].lowercase().takeIf { it in animeIdSources } ?: return null
        val id = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val episode = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return AnimeVideoRef(source = source, id = id, episode = episode)
    }

    fun extractImdbId(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { candidate ->
            candidate?.let(imdbIdRegex::find)?.value
        }

    suspend fun fetchRatings(
        imdbId: String,
        seasonNumbers: List<Int>,
        airedEpisodesBySeason: Map<Int, Set<Int>> = emptyMap(),
    ): Map<Pair<Int, Int>, Double> =
        fetchSeasonData(imdbId, seasonNumbers, airedEpisodesBySeason)
            .flatMap { (season, data) ->
                data.ratings.map { (episode, rating) -> (season to episode) to rating }
            }
            .toMap()

    private suspend fun fetchSeasonData(
        imdbId: String,
        seasonNumbers: List<Int>,
        airedEpisodesBySeason: Map<Int, Set<Int>> = emptyMap(),
    ): Map<Int, SeasonData> = withContext(Dispatchers.Default) {
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

        perSeason.toMap()
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
    ): SeasonData {
        val cacheKey = "$imdbId:$season"
        val cached = cacheLock.withLock { seasonCache[cacheKey] }
        val cachedData = cached?.toSeasonData() ?: SeasonData(ratings = emptyMap())
        val now = nowEpochMs()
        // Entries cached before air dates were stored lack totalSeasons; refresh those once.
        if (cached != null && cached.isFresh(now) && cached.totalSeasons != null) {
            val newlyAired = airedEpisodes - cached.ratings.keys - cached.missing.toSet()
            if (newlyAired.isEmpty()) return cachedData
        }

        val url = "https://www.omdbapi.com/?i=$imdbId&Season=$season&apikey=$apiKey"
        val response = runCatching {
            json.decodeFromString<OmdbSeasonResponse>(httpGetText(url))
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "OMDb season request failed for $imdbId season $season: ${error.message}" }
        }.getOrNull() ?: return cachedData

        if (!response.response.equals("True", ignoreCase = true)) {
            log.w { "OMDb returned no season data for $imdbId season $season: ${response.error}" }
            return cachedData
        }

        val listedEpisodes = response.episodes.orEmpty()
            .mapNotNull { episode -> episode.episode?.toIntOrNull()?.let { it to episode } }
            .toMap()
        val seasonRatings = listedEpisodes
            .mapNotNull { (episodeNumber, episode) ->
                parseRating(episode.imdbRating)?.let { episodeNumber to it }
            }
            .toMap()
        val airDates = listedEpisodes
            .mapNotNull { (episodeNumber, episode) ->
                episode.released?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }
                    ?.let { episodeNumber to it }
            }
            .toMap()
        val totalSeasons = response.totalSeasons?.trim()?.toIntOrNull()?.takeIf { it > 0 }

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
                airDates = cached?.airDates.orEmpty() + airDates,
                totalSeasons = totalSeasons ?: cached?.totalSeasons,
            )
            while (seasonCache.size > MaxCachedSeasons) {
                val oldestKey = seasonCache.keys.firstOrNull() ?: break
                seasonCache.remove(oldestKey)
            }
            persistToDisk()
        }
        return SeasonData(
            ratings = merged,
            airDates = cached?.airDates.orEmpty() + airDates,
            totalSeasons = totalSeasons ?: cached?.totalSeasons,
        )
    }

    private fun CachedOmdbSeason.toSeasonData(): SeasonData =
        SeasonData(ratings = ratings, airDates = airDates, totalSeasons = totalSeasons)

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
    @SerialName("air_dates") val airDates: Map<Int, String> = emptyMap(),
    @SerialName("total_seasons") val totalSeasons: Int? = null,
)

@Serializable
private data class OmdbSeasonResponse(
    @SerialName("Response") val response: String? = null,
    @SerialName("Error") val error: String? = null,
    @SerialName("totalSeasons") val totalSeasons: String? = null,
    @SerialName("Episodes") val episodes: List<OmdbEpisodeEntry>? = null,
)

@Serializable
private data class OmdbEpisodeEntry(
    @SerialName("Episode") val episode: String? = null,
    @SerialName("Released") val released: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("imdbID") val imdbId: String? = null,
)

@Serializable
private data class OmdbEpisodeDetailResponse(
    @SerialName("Response") val response: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
)
