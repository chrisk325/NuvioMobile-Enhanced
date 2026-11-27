package com.nuvio.app.features.library

import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerLibrary
import com.nuvio.app.features.servers.ServerLibraryRef
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Servers shelves last loaded in full, kept on the device so the tab shows them at once after the app starts,
 * while they load again from the servers. Kept per profile and tagged with the server setup they were loaded for
 * ([setupKey]); a different setup reads as nothing kept.
 */
internal object LibraryServerShelfStore {
    private val json = Json { ignoreUnknownKeys = true }

    /** What decides which shelves the tab shows: each enabled server's user and selected libraries. */
    private fun setupKey(connections: List<ServerConnection>): String =
        connections.joinToString(",") { connection ->
            "${connection.id}@${connection.remoteUserId}:${connection.useCatalogMetadata}:" +
                connection.selectedLibraries.joinToString("+") { it.id }
        }

    fun read(): List<LibraryServerShelf>? = runCatching {
        val connections = ServerRepository.enabledConnections()
        if (connections.isEmpty()) return@runCatching null
        val raw = ServerStorage.read(storageKey()) ?: return@runCatching null
        val stored = json.decodeFromString(StoredShelves.serializer(), raw)
        if (stored.version != VERSION || stored.setupKey != setupKey(connections)) return@runCatching null
        val byId = connections.associateBy { it.id }
        stored.shelves.map { shelf ->
            // A server missing now means a different setup; show nothing rather than a partial list.
            val connection = byId[shelf.connectionId] ?: return@runCatching null
            val ref = ServerLibraryRef(connection, shelf.library)
            LibraryServerShelf(ref, section = shelf.section.toSection(ref))
        }
    }.getOrNull()

    /** Keeps [shelves]; a load in which any shelf failed is not kept, so the last full one stays. */
    fun write(shelves: List<LibraryServerShelf>) {
        if (shelves.any { it.failure != null || it.section == null }) return
        runCatching {
            val stored = StoredShelves(
                version = VERSION,
                setupKey = setupKey(ServerRepository.enabledConnections()),
                shelves = shelves.map { shelf ->
                    StoredShelf(shelf.ref.connection.id, shelf.ref.library, shelf.section!!.toStored())
                },
            )
            ServerStorage.write(storageKey(), json.encodeToString(StoredShelves.serializer(), stored))
        }
    }

    private fun storageKey() = "libraryShelves.${ProfileRepository.activeProfileId}"

    private const val VERSION = 1
}

@Serializable
private data class StoredShelves(
    val version: Int,
    val setupKey: String,
    val shelves: List<StoredShelf> = emptyList(),
)

@Serializable
private data class StoredShelf(
    val connectionId: String,
    val library: ServerLibrary,
    val section: StoredSection,
)

@Serializable
private data class StoredSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val addonName: String,
    val items: List<StoredPreview> = emptyList(),
    val availableItemCount: Int = 0,
    val hasMore: Boolean = false,
) {
    fun toSection(ref: ServerLibraryRef) = HomeCatalogSection(
        key = key,
        title = title,
        subtitle = subtitle,
        addonName = addonName,
        target = ref.target,
        items = items.map(StoredPreview::toPreview),
        availableItemCount = availableItemCount,
        hasMore = hasMore,
    )
}

/** The parts of a title a server shelf shows and opens; the rest arrives with the next load. */
@Serializable
private data class StoredPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val posterShape: String = PosterShape.Poster.name,
    val description: String? = null,
    val releaseInfo: String? = null,
    val rawReleaseDate: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val rawPosterUrl: String? = null,
    val landscapePoster: String? = null,
    val rawLandscapePosterUrl: String? = null,
    val runtime: String? = null,
    val status: String? = null,
    val ageRating: String? = null,
    val language: String? = null,
) {
    fun toPreview() = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        posterShape = PosterShape.entries.firstOrNull { it.name == posterShape } ?: PosterShape.Poster,
        description = description,
        releaseInfo = releaseInfo,
        rawReleaseDate = rawReleaseDate,
        imdbRating = imdbRating,
        genres = genres,
        rawPosterUrl = rawPosterUrl,
        landscapePoster = landscapePoster,
        rawLandscapePosterUrl = rawLandscapePosterUrl,
        runtime = runtime,
        status = status,
        ageRating = ageRating,
        language = language,
    )
}

private fun HomeCatalogSection.toStored() = StoredSection(
    key = key,
    title = title,
    subtitle = subtitle,
    addonName = addonName,
    items = items.map(MetaPreview::toStored),
    availableItemCount = availableItemCount,
    hasMore = hasMore,
)

private fun MetaPreview.toStored() = StoredPreview(
    id = id,
    type = type,
    name = name,
    poster = poster,
    banner = banner,
    logo = logo,
    posterShape = posterShape.name,
    description = description,
    releaseInfo = releaseInfo,
    rawReleaseDate = rawReleaseDate,
    imdbRating = imdbRating,
    genres = genres,
    rawPosterUrl = rawPosterUrl,
    landscapePoster = landscapePoster,
    rawLandscapePosterUrl = rawLandscapePosterUrl,
    runtime = runtime,
    status = status,
    ageRating = ageRating,
    language = language,
)
