package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeCatalogLoadMoreTest {
    @Test
    fun `truncated preview reloads the full first page`() {
        val page = CatalogPage(items = metas(0 until 18), rawItemCount = 100, nextSkip = 100)

        assertEquals(0, initialHomeNextSkip(previewItemCount = 18, page = page, supportsPagination = true))
        assertEquals(0, initialHomeNextSkip(previewItemCount = 18, page = page, supportsPagination = false))
    }

    @Test
    fun `untruncated preview continues from next page only when paginated`() {
        val page = CatalogPage(items = metas(0 until 10), rawItemCount = 10, nextSkip = 10)

        assertEquals(10, initialHomeNextSkip(previewItemCount = 10, page = page, supportsPagination = true))
        assertNull(initialHomeNextSkip(previewItemCount = 10, page = page, supportsPagination = false))
    }

    @Test
    fun `appending skips items already in the row and advances the cursor`() {
        val section = section(items = metas(0 until 18), nextSkip = 0)
        val page = CatalogPage(items = metas(0 until 100), rawItemCount = 100, nextSkip = 100)

        val appended = section.appendCatalogPage(page, requestedSkip = 0, supportsPagination = true)

        assertEquals(metas(0 until 100), appended.items)
        assertEquals(100, appended.nextSkip)
        assertEquals(0, appended.consecutiveDuplicatePages)
    }

    @Test
    fun `non paginated catalog stops after the full first page`() {
        val section = section(items = metas(0 until 18), nextSkip = 0)
        val page = CatalogPage(items = metas(0 until 40), rawItemCount = 40, nextSkip = 40)

        val appended = section.appendCatalogPage(page, requestedSkip = 0, supportsPagination = false)

        assertEquals(40, appended.items.size)
        assertNull(appended.nextSkip)
    }

    @Test
    fun `empty page ends pagination`() {
        val section = section(items = metas(0 until 100), nextSkip = 100)
        val page = CatalogPage(items = emptyList(), rawItemCount = 0, nextSkip = null)

        val appended = section.appendCatalogPage(page, requestedSkip = 100, supportsPagination = true)

        assertEquals(100, appended.items.size)
        assertNull(appended.nextSkip)
    }

    @Test
    fun `repeated duplicate pages eventually stop pagination`() {
        var section = section(items = metas(0 until 20), nextSkip = 20)
        val duplicatePage = CatalogPage(items = metas(0 until 20), rawItemCount = 20, nextSkip = null)
        repeat(10) {
            val skip = section.nextSkip ?: return@repeat
            section = section.appendCatalogPage(
                page = duplicatePage.copy(nextSkip = skip + 20),
                requestedSkip = skip,
                supportsPagination = true,
            )
        }

        assertEquals(20, section.items.size)
        assertNull(section.nextSkip)
    }

    private fun metas(range: IntRange): List<MetaPreview> =
        range.map { index -> MetaPreview(id = "tt$index", type = "movie", name = "Movie $index") }

    private fun section(items: List<MetaPreview>, nextSkip: Int?): HomeCatalogSection =
        HomeCatalogSection(
            key = "addon:movie:popular",
            title = "Popular",
            subtitle = "Addon",
            addonName = "Addon",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = "popular",
                supportsPagination = true,
            ),
            items = items,
            hasMore = true,
            nextSkip = nextSkip,
        )
}
