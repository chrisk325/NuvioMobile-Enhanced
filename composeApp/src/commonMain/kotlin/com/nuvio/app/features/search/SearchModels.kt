package com.nuvio.app.features.search

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.servers.ServerGenre

enum class SearchEmptyStateReason {
    NoActiveAddons,
    NoSearchCatalogs,
    NoResults,
    RequestFailed,
}

data class SearchUiState(
    val isLoading: Boolean = false,
    val sections: List<HomeCatalogSection> = emptyList(),
    val emptyStateReason: SearchEmptyStateReason? = null,
    val errorMessage: String? = null,
)

enum class DiscoverEmptyStateReason {
    NoActiveAddons,
    NoDiscoverCatalogs,
    NoResults,
    RequestFailed,
}

data class DiscoverCatalogOption(
    val key: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val genreOptions: List<String> = emptyList(),
    val genreRequired: Boolean = false,
    val supportsPagination: Boolean = false,
    val server: CatalogTarget.Server? = null,
)

data class DiscoverUiState(
    val typeOptions: List<String> = emptyList(),
    val selectedType: String? = null,
    val catalogOptions: List<DiscoverCatalogOption> = emptyList(),
    val selectedCatalogKey: String? = null,
    val selectedGenre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val nextSkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    val emptyStateReason: DiscoverEmptyStateReason? = null,
    val errorMessage: String? = null,
    /** Genres loaded from media servers, by the server catalog option's key. */
    val serverGenres: Map<String, List<ServerGenre>> = emptyMap(),
) {
    val selectedCatalog: DiscoverCatalogOption?
        get() = catalogOptions.firstOrNull { it.key == selectedCatalogKey }

    val genreOptions: List<String>
        get() = genreOptionsFor(selectedCatalog)

    fun genreOptionsFor(catalog: DiscoverCatalogOption?): List<String> =
        when {
            catalog == null -> emptyList()
            catalog.server != null -> serverGenres[catalog.key].orEmpty().map { it.name }
            else -> catalog.genreOptions
        }

    fun serverGenreId(catalog: DiscoverCatalogOption, genre: String?): String? =
        genre?.let { name -> serverGenres[catalog.key]?.firstOrNull { it.name == name }?.id }

    val canLoadMore: Boolean
        get() = nextSkip != null
}
