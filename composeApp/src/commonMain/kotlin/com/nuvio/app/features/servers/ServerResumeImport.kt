package com.nuvio.app.features.servers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.MEDIA_SERVER_PROVIDER_PREFIX
import com.nuvio.app.features.watchprogress.buildContinueWatchingEpisodeSubtitle
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * One server's continue-watching list as last fetched, kept on the device. [dismissed] holds the videos removed
 * from Continue watching here; they stay out until the server changes them.
 */
@Serializable
internal data class ServerResumeCache(
    val fetchedAtMs: Long = 0L,
    val entries: List<WatchProgressEntry> = emptyList(),
    val dismissed: Set<String> = emptySet(),
    val nextUp: List<ServerNextUpEntry> = emptyList(),
    /** When Next up was last asked for; 0 for lists kept by versions that didn't ask, so they ask right away. */
    val nextUpFetchedAtMs: Long = 0L,
) {
    val shown: List<WatchProgressEntry>
        get() = entries.filterNot { it.videoId in dismissed }
}

/** One show's next episode from a server's Next up, kept on the device and shown in the app's Next Up. */
@Serializable
internal data class ServerNextUpEntry(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    /** The server's wide card artwork (Jellyfin/Emby "Thumb"). */
    val landscapePoster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val released: String? = null,
    val lastActivityEpochMs: Long = 0L,
) {
    fun toContinueWatchingItem(): ContinueWatchingItem = ContinueWatchingItem(
        parentMetaId = contentId,
        parentMetaType = contentType,
        videoId = videoId,
        title = title,
        subtitle = buildContinueWatchingEpisodeSubtitle(
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
        ),
        imageUrl = thumbnail ?: background ?: poster,
        logo = logo,
        poster = poster,
        background = background,
        landscapePoster = landscapePoster,
        mediaServerArtwork = true,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = thumbnail,
        pauseDescription = overview,
        released = released,
        isNextUp = true,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = 0f,
    )
}

/**
 * Shows each server's continue-watching list in the app's own Continue watching, for servers with
 * "Import watch progress" on.
 *
 * The list is kept on the device and shown from there, apart from the app's synced progress, so restarts and
 * syncs leave it alone. The server is asked again at most every few minutes, and Continue watching changes only
 * when the server's list did: new and moved items show up, and items the server finished or dropped go.
 */
internal object ServerResumeImport {
    private val log = Logger.withTag("ServerResumeImport")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    private val caches = mutableMapOf<String, ServerResumeCache>()
    private val nextUpRetries = mutableMapOf<String, Job>()
    private var listening = false
    private var cleanedUp = false
    private val _nextUp = MutableStateFlow<List<ServerNextUpEntry>>(emptyList())

    /** The Next up lists of the current servers, most recently watched show first. */
    val nextUp: StateFlow<List<ServerNextUpEntry>> = _nextUp.asStateFlow()

    internal var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    fun connections(): List<ServerConnection> =
        ServerRepository.enabledConnections().filter { connection ->
            connection.importWatchState &&
                connection.selectedLibraries.isNotEmpty() &&
                ServerRepository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true
        }

    /** Shows the kept lists, then asks the servers whose list is older than a few minutes, unless [force]. */
    fun refresh(force: Boolean = false) {
        show()
        val due = connections().filter { connection ->
            val cache = cache(connection.id)
            force || cache.nextUpFetchedAtMs == 0L || nowMs() - cache.fetchedAtMs >= MIN_INTERVAL_MS
        }
        if (due.isEmpty()) return
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { due.forEach { import(it) } }
        }
    }

    /** After a title's details came from [connectionId], asks again soon so its progress shows. */
    fun refreshSoon(connectionId: String) {
        val connection = connections().firstOrNull { it.id == connectionId } ?: return
        if (nowMs() - cache(connection.id).fetchedAtMs < SOON_INTERVAL_MS) return
        scope.launch { import(connection) }
    }

    /** Puts the kept lists of the current servers in Continue watching; nothing changes when they are the same. */
    fun show() {
        listen()
        cleanUpOnce()
        val current = connections()
        WatchProgressRepository.setExternalEntries(current.flatMap { cache(it.id).shown })
        val nextUp = current.flatMap { cache(it.id).nextUp }
            .sortedByDescending { it.lastActivityEpochMs }
            .distinctBy { it.contentId }
        if (nextUp != _nextUp.value) _nextUp.value = nextUp
    }

    suspend fun import(connection: ServerConnection) {
        val fetchedAtMs = nowMs()
        val resume = try {
            ServerRepository.call(connection.id) { provider, session -> provider.resumeProgress(session, LIMIT) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Loading continue watching from ${connection.providerId} failed" }
            return
        }
        val previous = cache(connection.id)
        val fetched = fetchNextUp(connection, previous)
        val nextUp = when {
            fetched == null -> previous.nextUp
            // AIOMetadata answers with nothing while it is still building the list, so keep the last one and ask
            // again once it is built.
            fetched.isEmpty() -> previous.nextUp.also { retryNextUpSoon(connection) }
            else -> fetched
        }
        // A full list may have cut off older items, so it can't tell what the server dropped.
        val next = next(previous, entries(connection, resume, previous), complete = resume.size < LIMIT, fetchedAtMs)
            .copy(nextUp = nextUp, nextUpFetchedAtMs = fetchedAtMs)
        saveCache(connection.id, next)
        show()
        log.i {
            "Continue watching from ${connection.providerId}: ${resume.size} listed, ${next.shown.size} shown, " +
                "${nextUp.size} next up"
        }
    }

    /** The server's Next up; empty when it keeps none, null when it could not be asked. */
    private suspend fun fetchNextUp(connection: ServerConnection, previous: ServerResumeCache): List<ServerNextUpEntry>? =
        try {
            ServerRepository.call(connection.id) { provider, session -> provider.nextUpProgress(session, NEXT_UP_LIMIT) }
                .let { nextUpEntries(connection, it, previous) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if ((error as? ServerException)?.failure != ServerFailure.UNSUPPORTED) {
                log.w(error) { "Loading next up from ${connection.providerId} failed" }
            }
            null
        }

    /** Asks [connection] for its Next up once more shortly, and takes what it answers then, empty or not. */
    private fun retryNextUpSoon(connection: ServerConnection) {
        synchronized(lock) {
            if (nextUpRetries[connection.id]?.isActive == true) return
            nextUpRetries[connection.id] = scope.launch {
                delay(NEXT_UP_RETRY_MS)
                val askedAtMs = nowMs()
                val fetched = fetchNextUp(connection, cache(connection.id)) ?: return@launch
                val current = cache(connection.id)
                // A full import that finished meanwhile already has the newer list.
                if (current.nextUpFetchedAtMs > askedAtMs) return@launch
                saveCache(connection.id, current.copy(nextUp = fetched, nextUpFetchedAtMs = askedAtMs))
                show()
                log.i { "Next up from ${connection.providerId} on a second ask: ${fetched.size}" }
            }
        }
    }

    internal fun nextUpEntries(
        connection: ServerConnection,
        nextUp: List<ServerNextUp>,
        previous: ServerResumeCache = ServerResumeCache(),
    ): List<ServerNextUpEntry> {
        val present = connection.presenter()
        val seen = previous.nextUp.associate { it.contentId to it.lastActivityEpochMs }
        val now = nowMs()
        return nextUp.mapIndexedNotNull { index, (title, episode, lastActivityEpochMs) ->
            val preview = present(title)
            if (preview.type != ServerMediaKind.SERIES.contentType) return@mapIndexedNotNull null
            val videoId = when {
                // A show from the server plays its episode from the server.
                ServerItemRef.isServerId(preview.id) -> episode.id
                // A show shown with addon metadata plays "<show id>:<season>:<episode>", like any catalog episode.
                episode.season != null && episode.episode != null -> "${preview.id}:${episode.season}:${episode.episode}"
                else -> return@mapIndexedNotNull null
            }
            ServerNextUpEntry(
                contentId = preview.id,
                contentType = preview.type,
                videoId = videoId,
                title = preview.name,
                logo = preview.logo?.takeIf { it.isNotBlank() },
                poster = preview.poster?.takeIf { it.isNotBlank() },
                background = preview.banner?.takeIf { it.isNotBlank() },
                landscapePoster = preview.landscapePoster?.takeIf { it.isNotBlank() },
                season = episode.season,
                episode = episode.episode,
                episodeTitle = episode.title.takeIf { it.isNotBlank() },
                thumbnail = episode.thumbnail?.takeIf { it.isNotBlank() },
                overview = episode.overview?.takeIf { it.isNotBlank() },
                released = episode.released?.takeIf { it.isNotBlank() },
                // Without a last-played date, keep the server's order: it lists the most recently watched first.
                lastActivityEpochMs = lastActivityEpochMs ?: ((seen[preview.id] ?: now) - index),
            )
        }.distinctBy { it.contentId }
    }

    /**
     * The list to keep after an import: the server's [entries], plus, when the list wasn't [complete], what it may
     * have cut off. A video stays dismissed while the server reports it unchanged.
     */
    internal fun next(
        previous: ServerResumeCache,
        entries: List<WatchProgressEntry>,
        complete: Boolean,
        fetchedAtMs: Long,
    ): ServerResumeCache {
        val listed = entries.mapTo(mutableSetOf()) { it.videoId }
        val kept = if (complete) entries else entries + previous.entries.filterNot { it.videoId in listed }
        val before = previous.entries.associateBy { it.videoId }
        val dismissed = previous.dismissed.filterTo(mutableSetOf()) { videoId ->
            val old = before[videoId]
            val new = kept.firstOrNull { it.videoId == videoId }
            old != null && new != null &&
                old.lastPositionMs == new.lastPositionMs && old.lastUpdatedEpochMs == new.lastUpdatedEpochMs
        }
        return ServerResumeCache(fetchedAtMs, kept, dismissed)
    }

    internal fun entries(
        connection: ServerConnection,
        resume: List<ServerResume>,
        previous: ServerResumeCache = ServerResumeCache(),
    ): List<WatchProgressEntry> {
        val present = connection.presenter()
        val label = ServerRepository.sourceLabel(connection)
        val seen = previous.entries.associate { it.videoId to it.lastUpdatedEpochMs }
        val now = nowMs()
        return resume.mapNotNull { (title, state) ->
            if (state.played || state.positionMs <= 0L || state.durationMs <= 0L) return@mapNotNull null
            entry(present(title), state, label, connection.id) { videoId -> seen[videoId] ?: now }
        }.distinctBy { it.videoId }
    }

    private fun entry(
        preview: MetaPreview,
        state: ServerUserState,
        label: String,
        connectionId: String,
        firstSeen: (String) -> Long,
    ): WatchProgressEntry? {
        val isSeries = preview.type == ServerMediaKind.SERIES.contentType
        val videoId = when {
            !isSeries -> preview.id
            // A show from the server plays its episode from the server.
            ServerItemRef.isServerId(preview.id) -> state.videoId
            // A show shown with addon metadata plays "<show id>:<season>:<episode>", like any catalog episode.
            state.season != null && state.episode != null -> "${preview.id}:${state.season}:${state.episode}"
            else -> return null
        }
        return WatchProgressEntry(
            contentType = preview.type,
            parentMetaId = preview.id,
            parentMetaType = preview.type,
            videoId = videoId,
            title = preview.name,
            logo = preview.logo,
            poster = preview.poster,
            background = preview.banner,
            landscapePoster = preview.landscapePoster?.takeIf { it.isNotBlank() },
            seasonNumber = state.season.takeIf { isSeries },
            episodeNumber = state.episode.takeIf { isSeries },
            episodeTitle = state.title.takeIf { isSeries },
            lastPositionMs = state.positionMs,
            durationMs = state.durationMs,
            // Without a last-played date an item keeps the time it was first seen, so it neither sinks nor jumps.
            lastUpdatedEpochMs = state.lastPlayedEpochMs ?: firstSeen(videoId),
            providerName = label,
            providerAddonId = "$MEDIA_SERVER_PROVIDER_PREFIX$connectionId",
        )
    }

    /** Remembers what Continue watching removed, so it stays out until the server changes it. */
    internal fun dismiss(removed: List<WatchProgressEntry>) {
        removed.groupBy { it.providerAddonId?.removePrefix(MEDIA_SERVER_PROVIDER_PREFIX) }.forEach { (connectionId, entries) ->
            if (connectionId == null) return@forEach
            val cache = cache(connectionId)
            saveCache(connectionId, cache.copy(dismissed = cache.dismissed + entries.map { it.videoId }))
        }
    }

    private fun listen() {
        synchronized(lock) {
            if (listening) return
            listening = true
        }
        WatchProgressRepository.onExternalEntriesRemoved = ::dismiss
    }

    // Earlier versions stored these entries with the app's synced progress, where syncs wiped them.
    private fun cleanUpOnce() {
        synchronized(lock) {
            if (cleanedUp) return
            cleanedUp = true
        }
        val tags = connections().mapTo(mutableSetOf()) { "$MEDIA_SERVER_PROVIDER_PREFIX${it.id}" }
        if (tags.isEmpty()) return
        WatchProgressRepository.removeExternalProgress { it.providerAddonId in tags && it.lastSourceUrl == null }
    }

    private fun cache(connectionId: String): ServerResumeCache = synchronized(lock) {
        caches.getOrPut(connectionId) {
            runCatching { ServerStorage.read(cacheKey(connectionId)) }.getOrNull()
                ?.let { runCatching { json.decodeFromString(ServerResumeCache.serializer(), it) }.getOrNull() }
                ?: ServerResumeCache()
        }
    }

    private fun saveCache(connectionId: String, cache: ServerResumeCache) {
        synchronized(lock) { caches[connectionId] = cache }
        runCatching { ServerStorage.write(cacheKey(connectionId), json.encodeToString(ServerResumeCache.serializer(), cache)) }
    }

    // "resume2": the list earlier versions kept under "resume" held no entries to show.
    private fun cacheKey(connectionId: String) = "resume2.$connectionId"

    /** Forgets a server's kept list and takes it out of Continue watching; its next import brings everything in. */
    fun forget(connectionId: String) {
        synchronized(lock) { caches.remove(connectionId) }
        runCatching { ServerStorage.write(cacheKey(connectionId), null) }
        show()
    }

    internal fun reset() {
        synchronized(lock) {
            job?.cancel()
            job = null
            nextUpRetries.values.forEach { it.cancel() }
            nextUpRetries.clear()
            caches.keys.forEach { runCatching { ServerStorage.write(cacheKey(it), null) } }
            caches.clear()
            listening = false
            cleanedUp = false
        }
        _nextUp.value = emptyList()
        WatchProgressRepository.setExternalEntries(emptyList())
    }

    private const val LIMIT = 50
    private const val NEXT_UP_LIMIT = 30
    private const val NEXT_UP_RETRY_MS = 20_000L
    private const val MIN_INTERVAL_MS = 3 * 60_000L
    private const val SOON_INTERVAL_MS = 30_000L
}
