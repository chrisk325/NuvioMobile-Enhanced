package com.nuvio.app.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How many items of a horizontal shelf are composed ahead of time when the vertical list
 * prefetches the row it is about to scroll on screen. Compose's default is 2, so a row of
 * 4–5 visible posters built the rest on the frame it appeared, which showed up as a hitch at
 * the moment a new row entered the screen.
 */
internal const val ShelfNestedPrefetchItemCount = 5

/** Posters beyond the composed/prefetched ones whose images are warmed up while a row scrolls. */
private const val ShelfImagePrefetchAheadCount = 6

/**
 * Items right after the last visible one are already composed by the row's own prefetch and
 * start loading their image themselves, so the image warm-up starts after them to avoid
 * fetching the same poster twice.
 */
private const val ShelfImagePrefetchSkipCount = 2

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberShelfRowListState(): LazyListState =
    rememberLazyListState(
        prefetchStrategy = remember {
            LazyListPrefetchStrategy(nestedPrefetchItemCount = ShelfNestedPrefetchItemCount)
        },
    )

/**
 * The model a poster card hands to Coil. Shared with [PosterRowImagePrefetchEffect] so a
 * prefetched poster lands under exactly the memory-cache key the card will look up.
 */
internal fun posterImageModel(
    platformContext: PlatformContext,
    imageUrl: String,
    fallbackImageUrl: String?,
): Any =
    if (fallbackImageUrl != null) {
        ImageRequest.Builder(platformContext)
            .data(imageUrl)
            .memoryCacheKeyExtras(
                mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallbackImageUrl),
            )
            .build()
    } else {
        imageUrl
    }

internal data class PosterPrefetchTarget(
    val imageUrl: String,
    val fallbackImageUrl: String?,
    val shape: NuvioPosterShape,
)

/**
 * Loads the images of the next few posters of a shelf into Coil's caches while the row is on
 * screen, decoded at the card's own size, so they are ready (a memory-cache hit, drawn without
 * waiting) by the time they scroll into view.
 *
 * [targets] must be index-aligned with the row's items.
 */
@Composable
internal fun PosterRowImagePrefetchEffect(
    state: LazyListState,
    targets: List<PosterPrefetchTarget?>,
) {
    if (targets.isEmpty()) return
    val platformContext = LocalPlatformContext.current
    val density = LocalDensity.current
    val basePosterWidthDp = rememberPosterCardStyleUiState().widthDp

    LaunchedEffect(state, platformContext, density, basePosterWidthDp, targets) {
        val imageLoader = SingletonImageLoader.get(platformContext)
        val enqueued = HashSet<Int>()
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .filter { it >= 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val items = targets
                val from = lastVisible + 1 + ShelfImagePrefetchSkipCount
                val to = min(items.lastIndex, lastVisible + ShelfImagePrefetchSkipCount + ShelfImagePrefetchAheadCount)
                for (index in from..to) {
                    if (!enqueued.add(index)) continue
                    val target = items[index] ?: continue
                    val widthPx = with(density) { target.shape.cardWidth(basePosterWidthDp).toPx() }
                    val heightPx = widthPx / target.shape.aspectRatio
                    if (widthPx <= 0f || heightPx <= 0f) continue
                    val model = posterImageModel(
                        platformContext = platformContext,
                        imageUrl = target.imageUrl,
                        fallbackImageUrl = target.fallbackImageUrl,
                    )
                    val builder = when (model) {
                        is ImageRequest -> model.newBuilder()
                        else -> ImageRequest.Builder(platformContext).data(model)
                    }
                    imageLoader.enqueue(
                        builder
                            .size(Size(widthPx.roundToInt(), heightPx.roundToInt()))
                            .build(),
                    )
                }
            }
    }
}
