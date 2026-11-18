package com.nuvio.app.features.servers

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_favorite_failed
import org.jetbrains.compose.resources.getString
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * What the app knows of the favourites on the connected media servers, per connection. [writes] goes up each time the
 * app changed a favourite on a server, so screens showing the server's own favourites list know to reload it.
 */
data class ServerFavoritesState(
    val titles: Map<String, List<ServerTitle>> = emptyMap(),
    val loadedKey: String? = null,
    val revision: Int = 0,
    val writes: Int = 0,
) {
    internal val index: Map<String, Set<String>> by lazy {
        titles.mapValues { (_, list) -> list.flatMapTo(mutableSetOf()) { it.favoriteKeys() } }
    }
}

enum class ServerFavoriteResult {
    ADDED,
    REMOVED,

    /** None of the connected servers has this title. */
    NOT_FOUND,
    FAILED,

    /** No connected server keeps favourites. */
    UNAVAILABLE,
}

/**
 * Favourites on Jellyfin and Emby servers. Titles from a server change by their server item; catalog titles (IMDb,
 * TMDB, ...) are matched to the server's copy first, the same way watched state is mirrored.
 */
internal object ServerFavorites {
    private val log = Logger.withTag("ServerFavorites")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(ServerFavoritesState())
    val state: StateFlow<ServerFavoritesState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    @Volatile
    private var loadedAtMs = 0L

    internal var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    fun connections(): List<ServerConnection> =
        ServerRepository.enabledConnections().filter {
            ServerRepository.provider(it)?.supports(ServerCapability.FAVORITES) == true
        }

    fun isAvailable(): Boolean = connections().isNotEmpty()

    /** The name of the server type favourites go to ("Jellyfin"), or null when more than one type is connected. */
    fun serverName(): String? =
        connections().mapNotNull { ServerRepository.provider(it)?.displayName }.distinct().singleOrNull()

    fun supportsType(type: String?): Boolean = ServerMediaKind.fromContentType(type) != null

    fun isFavorite(id: String, type: String?, state: ServerFavoritesState = _state.value): Boolean {
        val keys = keysFor(id, type)
        if (keys.isEmpty()) return false
        val connected = connections().map { it.id }
        return connected.any { connectionId -> state.index[connectionId]?.any(keys::contains) == true }
    }

    /** Loads the favourites from the servers unless a recent copy for the same servers is held. */
    fun ensureFresh(force: Boolean = false) {
        val key = connectionsKey(connections())
        val current = _state.value
        if (!force && current.loadedKey == key && nowMs() - loadedAtMs < STALE_MS) return
        synchronized(lock) {
            if (refreshJob?.isActive == true) return
            refreshJob = scope.launch { refresh() }
        }
    }

    suspend fun refresh() {
        val connections = connections()
        val previous = _state.value.titles
        val titles = coroutineScope {
            connections.map { connection ->
                async { connection.id to (load(connection) ?: previous[connection.id].orEmpty()) }
            }.awaitAll()
        }.toMap()
        loadedAtMs = nowMs()
        _state.update { it.copy(titles = titles, loadedKey = connectionsKey(connections), revision = it.revision + 1) }
    }

    suspend fun toggle(preview: MetaPreview): ServerFavoriteResult =
        setFavorite(preview.id, preview.type, !isFavorite(preview.id, preview.type), preview.name)

    /** Mirrors a library change to the servers in the background, saying so only when a server refused it. */
    fun mirror(id: String, type: String, favorite: Boolean, name: String? = null): Job? {
        if (!supportsType(type) || !isAvailable()) return null
        return scope.launch {
            val result = setFavorite(id, type, favorite, name)
            if (result == ServerFavoriteResult.FAILED) {
                NuvioToastController.show(getString(Res.string.servers_favorite_failed))
            }
        }
    }

    suspend fun setFavorite(id: String, type: String, favorite: Boolean, name: String? = null): ServerFavoriteResult {
        val connections = connections()
        if (connections.isEmpty()) return ServerFavoriteResult.UNAVAILABLE
        val kind = ServerMediaKind.fromContentType(type) ?: return ServerFavoriteResult.NOT_FOUND
        val (targets, lookupFailed) = targets(id, type, favorite, connections)
        if (targets.isEmpty()) {
            return when {
                lookupFailed -> ServerFavoriteResult.FAILED
                favorite -> ServerFavoriteResult.NOT_FOUND
                else -> ServerFavoriteResult.REMOVED
            }
        }
        val written = coroutineScope {
            targets.map { ref -> async { ref.takeIf { write(it, favorite) } } }.awaitAll().filterNotNull()
        }
        if (written.isNotEmpty()) {
            val ids = if (ServerItemRef.isServerId(id)) null else parseTrackingExternalIds(id)
            _state.update { state ->
                val titles = state.titles.toMutableMap()
                written.groupBy { it.connectionId }.forEach { (connectionId, refs) ->
                    val encoded = refs.map { it.encode() }.toSet()
                    val kept = titles[connectionId].orEmpty().filterNot { it.preview.id in encoded }
                    titles[connectionId] = if (favorite) {
                        kept + refs.map { ref ->
                            ServerTitle(
                                preview = MetaPreview(id = ref.encode(), type = kind.contentType, name = name.orEmpty()),
                                externalIds = ids ?: TrackingExternalIds(),
                            )
                        }
                    } else {
                        kept
                    }
                }
                state.copy(titles = titles, revision = state.revision + 1, writes = state.writes + 1)
            }
            log.i { "Set favorite=$favorite on ${written.size} server items for $type $id" }
        }
        if (written.size < targets.size || lookupFailed) {
            log.w { "Could not set favorite=$favorite on ${targets.size - written.size} of ${targets.size} server items" }
            return ServerFavoriteResult.FAILED
        }
        return if (favorite) ServerFavoriteResult.ADDED else ServerFavoriteResult.REMOVED
    }

    private suspend fun targets(
        id: String,
        type: String,
        favorite: Boolean,
        connections: List<ServerConnection>,
    ): Pair<List<ServerItemRef>, Boolean> {
        ServerItemRef.parse(id)?.let { ref ->
            return (if (connections.any { it.id == ref.connectionId }) listOf(ref) else emptyList()) to false
        }
        val known = knownRefs(id, type, connections)
        if (!favorite && known.isNotEmpty()) return known to false
        val request = ServerMatcher.shared.titleRequest(type, id) ?: return known to false
        var lookupFailed = false
        val matched = connections.filter { ServerMatcher.shared.supports(it, request.kind) }.flatMap { connection ->
            try {
                ServerMatcher.shared.matchTitle(connection, request, forceRefresh = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lookupFailed = true
                log.w(error) { "Looking up $type $id on ${connection.providerId} failed" }
                emptyList()
            }
        }
        return (known + matched).distinct() to lookupFailed
    }

    private fun knownRefs(id: String, type: String, connections: List<ServerConnection>): List<ServerItemRef> {
        val keys = keysFor(id, type)
        val state = _state.value
        return connections.flatMap { connection ->
            state.titles[connection.id].orEmpty()
                .filter { title -> title.favoriteKeys().any(keys::contains) }
                .mapNotNull { ServerItemRef.parse(it.preview.id) }
        }
    }

    private suspend fun write(ref: ServerItemRef, favorite: Boolean): Boolean =
        try {
            ServerRepository.call(ref.connectionId) { provider, session ->
                if (!provider.supports(ServerCapability.FAVORITES)) throw ServerException(ServerFailure.UNSUPPORTED)
                provider.setFavorite(session, ref.itemId, favorite)
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Setting favorite=$favorite on item ${ref.itemId} failed" }
            false
        }

    private suspend fun load(connection: ServerConnection): List<ServerTitle>? =
        try {
            ServerRepository.call(connection.id) { provider, session ->
                provider.favoritesPage(session, start = 0, limit = INDEX_LIMIT).items
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Loading favorites from ${connection.providerId} failed" }
            null
        }

    internal fun reset() {
        synchronized(lock) {
            refreshJob?.cancel()
            refreshJob = null
        }
        loadedAtMs = 0L
        _state.value = ServerFavoritesState()
    }

    private fun connectionsKey(connections: List<ServerConnection>): String =
        connections.joinToString(",") { "${it.id}@${it.remoteUserId}" }

    private const val INDEX_LIMIT = 500
    private const val STALE_MS = 2 * 60_000L
}

/** Keys a title is known by: its server item id, and "<type>|<catalog id>" for each catalog id the server has for it. */
internal fun ServerTitle.favoriteKeys(): Set<String> =
    buildSet {
        add(preview.id)
        externalIds.catalogIds().forEach { add("${preview.type}|${it.lowercase()}") }
    }

internal fun keysFor(id: String, type: String?): Set<String> {
    if (ServerItemRef.isServerId(id)) return setOf(id)
    val kinds = ServerMediaKind.fromContentType(type)?.let(::listOf) ?: listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES)
    val catalogIds = parseTrackingExternalIds(id).catalogIds()
    return kinds.flatMapTo(mutableSetOf()) { kind -> catalogIds.map { "${kind.contentType}|${it.lowercase()}" } }
}
