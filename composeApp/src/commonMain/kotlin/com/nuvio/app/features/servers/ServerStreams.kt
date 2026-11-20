package com.nuvio.app.features.servers

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.compose.resources.getString

internal class ServerStreamSource(
    val addonId: String,
    val addonName: String,
    val preferred: Boolean,
    private val loader: suspend () -> List<StreamItem>,
) {
    fun loadingGroup(): AddonStreamGroup =
        AddonStreamGroup(addonName = addonName, addonId = addonId, streams = emptyList(), isLoading = true)

    suspend fun load(): AddonStreamGroup = try {
        AddonStreamGroup(addonName = addonName, addonId = addonId, streams = loader(), isLoading = false)
    } catch (error: Throwable) {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        AddonStreamGroup(
            addonName = addonName,
            addonId = addonId,
            streams = emptyList(),
            isLoading = false,
            error = getString(error.serverFailure().message()),
        )
    }
}

internal object ServerStreams {
    fun isNativeRequest(videoId: String): Boolean = ServerItemRef.isServerId(videoId)

    /**
     * Whether a server may play [videoId]. An episode is told by its [season] and [episode] (or an id carrying them)
     * and its show's [parentId], as for [sources].
     */
    fun canServe(
        type: String,
        videoId: String,
        parentId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): Boolean {
        (ServerItemRef.parse(videoId) ?: ServerItemRef.parse(parentId))?.let { ref ->
            return ServerRepository.connection(ref.connectionId)?.enabled == true
        }
        val connections = ServerRepository.enabledConnections().ifEmpty { return false }
        val request = ServerMatcher.shared.request(type, videoId, season, episode, parentId) ?: return false
        return connections.any { ServerMatcher.shared.supports(it, request.kind) }
    }

    fun isServerSourceId(addonId: String?): Boolean = addonId?.startsWith(GROUP_PREFIX) == true

    fun sources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false,
        parentId: String? = null,
    ): List<ServerStreamSource> {
        ServerItemRef.parse(videoId)?.let { ref ->
            val connection = ServerRepository.connection(ref.connectionId) ?: return emptyList()
            return listOf(source(connection, preferred = true) { candidates(ref) })
        }
        val connections = ServerRepository.enabledConnections().ifEmpty { return emptyList() }
        val request = ServerMatcher.shared.request(type, videoId, season, episode, parentId) ?: return emptyList()
        return connections
            .filter { ServerMatcher.shared.supports(it, request.kind) }
            .map { connection ->
                source(connection, preferred = connection.useCatalogMetadata) {
                    ServerMatcher.shared.match(connection, request, forceRefresh).flatMap { candidates(it) }
                }
            }
    }

    private fun source(
        connection: ServerConnection,
        preferred: Boolean,
        loader: suspend () -> List<StreamItem>,
    ): ServerStreamSource =
        ServerStreamSource(
            addonId = groupId(connection.id),
            addonName = ServerRepository.sourceLabel(connection),
            preferred = preferred,
            loader = loader,
        )

    suspend fun candidates(ref: ServerItemRef): List<StreamItem> {
        val connection = ServerRepository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val label = ServerRepository.sourceLabel(connection)
        return ServerRepository.call(ref.connectionId) { provider, session -> provider.candidates(session, ref.itemId) }
            .map { candidate ->
                val (name, description) = splitFormattedTitle(candidate.title, candidate.description)
                StreamItem(
                    name = name,
                    description = description,
                    sourceName = label,
                    addonName = label,
                    addonId = groupId(connection.id),
                    behaviorHints = StreamBehaviorHints(
                        filename = candidate.filename,
                        videoSize = candidate.sizeBytes,
                    ),
                    serverTarget = candidate.target.takeUnless { candidate.informational },
                )
            }
    }

    /**
     * A server source named by an addon formatter (e.g. an AIOStreams-backed plugin) carries the formatter's name and
     * description as one multi-line title. Split it back so the card shows it the way the addon itself would: the
     * first line as the stream name and the rest as its description.
     */
    internal fun splitFormattedTitle(title: String, description: String?): Pair<String, String?> {
        val lines = title.replace("\r\n", "\n").replace('\r', '\n').lines()
        val nameIndex = lines.indexOfFirst { it.isNotBlank() }
        if (nameIndex < 0) return title to description
        val rest = lines.drop(nameIndex + 1).joinToString("\n").trim('\n').takeIf { it.isNotBlank() }
            ?: return lines[nameIndex].trim() to description
        return lines[nameIndex].trim() to listOfNotNull(rest, description?.takeIf { it.isNotBlank() }).joinToString("\n")
    }

    private fun groupId(connectionId: String) = "$GROUP_PREFIX$connectionId"

    private const val GROUP_PREFIX = "server:"
}
