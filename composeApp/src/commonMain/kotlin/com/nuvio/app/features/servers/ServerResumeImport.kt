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
import kotlin.time.Clock

/**
 * Brings each server's continue-watching list into the app's own Continue watching, for servers with
 * "Import watch progress" on, and takes out what the server finished or dropped. Opening a title already imports
 * its progress; this covers everything in progress.
 */
internal object ServerResumeImport {
    private val log = Logger.withTag("ServerResumeImport")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private var job: Job? = null
    private val importedAtMs = mutableMapOf<String, Long>()
    private val firstSeenAtMs = mutableMapOf<String, Long>()

    internal var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    fun connections(): List<ServerConnection> =
        ServerRepository.enabledConnections().filter { connection ->
            connection.importWatchState &&
                connection.selectedLibraries.isNotEmpty() &&
                ServerRepository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true
        }

    /** Imports in the background, at most once every few minutes per server unless [force]. */
    fun refresh(force: Boolean = false) {
        val due = connections().filter { connection ->
            force || synchronized(lock) { importedAtMs[connection.id] }?.let { nowMs() - it >= MIN_INTERVAL_MS } != false
        }
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
        synchronized(lock) { importedAtMs[connection.id] = nowMs() }
        val entries = entries(connection, resume)
        if (entries.isNotEmpty()) WatchProgressRepository.applyExternalProgress(entries)
        // A full list may have cut off older items, so it can't tell what the server dropped.
        val removed = if (resume.size >= LIMIT) {
            0
        } else {
            val listed = entries.mapTo(mutableSetOf()) { it.videoId }
            WatchProgressRepository.removeExternalProgress { isStale(it, connection.id, listed, fetchedAtMs) }
        }
        log.i {
            "Imported ${entries.size} of ${resume.size} continue-watching items from ${connection.providerId}, " +
                "dropped $removed the server no longer lists"
        }
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

    internal fun entries(connection: ServerConnection, resume: List<ServerResume>): List<WatchProgressEntry> {
        val present = connection.presenter()
        val label = ServerRepository.sourceLabel(connection)
        return resume.mapNotNull { (title, state) ->
            if (state.played || state.positionMs <= 0L || state.durationMs <= 0L) return@mapNotNull null
            entry(present(title), state, label, connection.id)
        }
    }

    private fun entry(preview: MetaPreview, state: ServerUserState, label: String, connectionId: String): WatchProgressEntry? {
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
            lastUpdatedEpochMs = state.lastPlayedEpochMs ?: firstSeen(videoId),
            providerName = label,
            providerAddonId = "$MEDIA_SERVER_PROVIDER_PREFIX$connectionId",
        )
    }

    // Without a last-played date an item keeps the time it was first seen, so it neither sinks nor jumps each import.
    private fun firstSeen(videoId: String): Long = synchronized(lock) { firstSeenAtMs.getOrPut(videoId) { nowMs() } }

    internal fun reset() {
        synchronized(lock) {
            job?.cancel()
            job = null
            importedAtMs.clear()
            firstSeenAtMs.clear()
        }
    }

    private const val LIMIT = 50
    private const val MIN_INTERVAL_MS = 3 * 60_000L
}
