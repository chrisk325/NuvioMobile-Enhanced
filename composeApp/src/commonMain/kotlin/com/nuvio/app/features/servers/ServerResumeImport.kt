package com.nuvio.app.features.servers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.MEDIA_SERVER_PROVIDER_PREFIX
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
) {
    val shown: List<WatchProgressEntry>
        get() = entries.filterNot { it.videoId in dismissed }
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
    private var listening = false
    private var cleanedUp = false

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
        val due = connections().filter { connection -> force || nowMs() - cache(connection.id).fetchedAtMs >= MIN_INTERVAL_MS }
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
        WatchProgressRepository.setExternalEntries(connections().flatMap { cache(it.id).shown })
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
        // A full list may have cut off older items, so it can't tell what the server dropped.
        val next = next(previous, entries(connection, resume, previous), complete = resume.size < LIMIT, fetchedAtMs)
        saveCache(connection.id, next)
        show()
        log.i { "Continue watching from ${connection.providerId}: ${resume.size} listed, ${next.shown.size} shown" }
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

    private fun cacheKey(connectionId: String) = "resume.$connectionId"

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
            caches.keys.forEach { runCatching { ServerStorage.write(cacheKey(it), null) } }
            caches.clear()
            listening = false
            cleanedUp = false
        }
        WatchProgressRepository.setExternalEntries(emptyList())
    }

    private const val LIMIT = 50
    private const val MIN_INTERVAL_MS = 3 * 60_000L
    private const val SOON_INTERVAL_MS = 30_000L
}
