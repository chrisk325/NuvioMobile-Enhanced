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
 * Brings each server's continue-watching list into the app's own Continue watching, for servers with
 * "Import watch progress" on, and takes out what the server finished or dropped. Opening a title already imports
 * its progress; this covers everything in progress.
 *
 * The last list from each server is kept on the device, so the server is asked at most every few minutes (across
 * restarts too) and only what changed since is added, updated or taken out.
 */
@Serializable
internal data class ServerResumeCache(
    val fetchedAtMs: Long = 0L,
    val items: Map<String, Item> = emptyMap(),
) {
    @Serializable
    data class Item(val positionMs: Long, val durationMs: Long, val updatedAtMs: Long)
}

/** What one import changes: the entries to add or update, and the videos the server dropped since last time. */
internal class ServerResumeDiff(
    val changed: List<WatchProgressEntry>,
    val dropped: Set<String>,
    val cache: ServerResumeCache,
)

internal object ServerResumeImport {
    private val log = Logger.withTag("ServerResumeImport")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    private val caches = mutableMapOf<String, ServerResumeCache>()

    internal var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    fun connections(): List<ServerConnection> =
        ServerRepository.enabledConnections().filter { connection ->
            connection.importWatchState &&
                connection.selectedLibraries.isNotEmpty() &&
                ServerRepository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true
        }

    /** Imports in the background, at most once every few minutes per server unless [force]. */
    fun refresh(force: Boolean = false) {
        val due = connections().filter { connection -> force || nowMs() - cache(connection.id).fetchedAtMs >= MIN_INTERVAL_MS }
        if (due.isEmpty()) return
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { due.forEach { import(it) } }
        }
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
        val diff = diff(previous, entries(connection, resume, previous), complete = resume.size < LIMIT, fetchedAtMs)
        saveCache(connection.id, diff.cache)
        if (diff.changed.isNotEmpty()) WatchProgressRepository.applyExternalProgress(diff.changed)
        val removed = if (diff.dropped.isEmpty()) {
            0
        } else {
            WatchProgressRepository.removeExternalProgress {
                it.videoId in diff.dropped && isStale(it, connection.id, emptySet(), fetchedAtMs)
            }
        }
        log.i {
            "Continue watching from ${connection.providerId}: ${resume.size} listed, ${diff.changed.size} new or " +
                "changed, $removed dropped"
        }
    }

    /**
     * Compares [entries] with the list kept from the last import: only new or changed items are applied, and only
     * items the server listed last time and no longer does are dropped. A [complete] list is needed to drop anything.
     */
    internal fun diff(
        previous: ServerResumeCache,
        entries: List<WatchProgressEntry>,
        complete: Boolean,
        fetchedAtMs: Long,
    ): ServerResumeDiff {
        val items = entries.associate { entry ->
            entry.videoId to ServerResumeCache.Item(entry.lastPositionMs, entry.durationMs, entry.lastUpdatedEpochMs)
        }
        val changed = entries.filter { previous.items[it.videoId] != items.getValue(it.videoId) }
        val dropped = if (complete) previous.items.keys - items.keys else emptySet()
        // Items an incomplete list left out are kept, so their first-seen time and their removal still count later.
        val kept = if (complete) items else previous.items + items
        return ServerResumeDiff(changed, dropped, ServerResumeCache(fetchedAtMs, kept))
    }

    /**
     * True for an entry this server put in Continue watching that its list no longer has: finished or removed on
     * the server. Progress from playing here keeps the stream it played and stays, as does anything updated since
     * the list was fetched.
     */
    internal fun isStale(entry: WatchProgressEntry, connectionId: String, listed: Set<String>, fetchedAtMs: Long): Boolean =
        entry.providerAddonId == "$MEDIA_SERVER_PROVIDER_PREFIX$connectionId" &&
            entry.lastSourceUrl == null &&
            entry.videoId !in listed &&
            entry.lastUpdatedEpochMs < fetchedAtMs

    internal fun entries(
        connection: ServerConnection,
        resume: List<ServerResume>,
        previous: ServerResumeCache = ServerResumeCache(),
    ): List<WatchProgressEntry> {
        val present = connection.presenter()
        val label = ServerRepository.sourceLabel(connection)
        val now = nowMs()
        return resume.mapNotNull { (title, state) ->
            if (state.played || state.positionMs <= 0L || state.durationMs <= 0L) return@mapNotNull null
            entry(present(title), state, label, connection.id) { videoId -> previous.items[videoId]?.updatedAtMs ?: now }
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

    /** Forgets a server's last list, so its next import brings everything in again. */
    fun forget(connectionId: String) {
        synchronized(lock) { caches.remove(connectionId) }
        runCatching { ServerStorage.write(cacheKey(connectionId), null) }
    }

    internal fun reset() {
        synchronized(lock) {
            job?.cancel()
            job = null
            caches.keys.forEach { runCatching { ServerStorage.write(cacheKey(it), null) } }
            caches.clear()
        }
    }

    private const val LIMIT = 50
    private const val MIN_INTERVAL_MS = 3 * 60_000L
}
