package com.nuvio.app.features.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioShelfSectionHeader
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerLibraryRef
import com.nuvio.app.features.servers.message
import com.nuvio.app.features.servers.serverFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.library_server_load_failed
import nuvio.composeapp.generated.resources.library_servers_empty_message
import nuvio.composeapp.generated.resources.library_servers_empty_title
import nuvio.composeapp.generated.resources.library_servers_no_favorites_message
import nuvio.composeapp.generated.resources.library_servers_no_favorites_title
import org.jetbrains.compose.resources.stringResource

internal data class LibraryServerShelf(
    val ref: ServerLibraryRef,
    val section: HomeCatalogSection? = null,
    val failure: ServerFailure? = null,
)

internal suspend fun loadServerShelves(): List<LibraryServerShelf> = coroutineScope {
    ServerCatalog.libraryShelves().map { ref ->
        async {
            try {
                LibraryServerShelf(ref, section = ServerCatalog.librarySection(ref, SERVER_SHELF_LIMIT))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LibraryServerShelf(ref, failure = error.serverFailure())
            }
        }
    }.awaitAll()
}

internal fun LazyListScope.serverLibraryContent(
    shelves: List<LibraryServerShelf>?,
    layoutMode: LibraryLayoutMode,
    columns: Int,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onCatalogClick: ((HomeCatalogSection) -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onRetry: () -> Unit,
) {
    when {
        shelves == null -> if (layoutMode == LibraryLayoutMode.VERTICAL) {
            libraryVerticalSkeletonItems(columns)
        } else {
            items(3) { HomeSkeletonRow(horizontalPadding = 16.dp) }
        }

        shelves.isEmpty() -> item {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = stringResource(Res.string.library_servers_empty_title),
                message = stringResource(Res.string.library_servers_empty_message),
            )
        }

        shelves.none { it.failure != null || it.section?.items?.isNotEmpty() == true } -> item {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = stringResource(Res.string.library_servers_no_favorites_title),
                message = stringResource(Res.string.library_servers_no_favorites_message),
            )
        }

        else -> shelves.forEach { shelf ->
            val section = shelf.section
            if (section != null && section.items.isNotEmpty() && layoutMode == LibraryLayoutMode.VERTICAL) {
                serverShelfGrid(
                    section = section,
                    columns = columns,
                    watchedKeys = watchedKeys,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    onViewAllClick = onCatalogClick?.takeIf { section.hasMore }?.let { open -> { open(section) } },
                    onPosterClick = onPosterClick,
                    onPosterLongClick = onPosterLongClick,
                )
            } else if (section != null && section.items.isNotEmpty()) {
                item(key = "server-shelf:${section.key}") {
                    HomeCatalogRowSection(
                        section = section,
                        watchedKeys = watchedKeys,
                        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                        onViewAllClick = onCatalogClick?.takeIf { section.hasMore }?.let { open -> { open(section) } },
                        onPosterClick = onPosterClick,
                        onPosterLongClick = onPosterLongClick,
                    )
                }
            } else if (shelf.failure != null) {
                item(key = "server-shelf-error:${shelf.ref.connection.id}:${shelf.ref.library.id}") {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = stringResource(Res.string.library_server_load_failed, shelf.ref.title),
                        message = stringResource(shelf.failure.message()),
                        actionLabel = stringResource(Res.string.action_retry),
                        onActionClick = onRetry,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.serverShelfGrid(
    section: HomeCatalogSection,
    columns: Int,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onViewAllClick: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    item(key = "server-grid-header:${section.key}", contentType = "server_grid_header") {
        NuvioShelfSectionHeader(
            title = section.title,
            modifier = libraryContentTransitionModifier().padding(horizontal = 16.dp),
            onViewAllClick = onViewAllClick,
            viewAllPillSize = NuvioViewAllPillSize.Compact,
        )
    }
    items(
        items = section.items.chunked(columns),
        key = { row -> "server-grid:${section.key}:${row.first().stableKey()}" },
        contentType = { "server_grid_row" },
    ) { row ->
        PosterGridRow(
            items = row,
            columns = columns,
            modifier = libraryContentTransitionModifier().padding(horizontal = 16.dp),
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onPosterClick = onPosterClick,
            onPosterLongClick = onPosterLongClick,
        )
    }
}

private const val SERVER_SHELF_LIMIT = 18
