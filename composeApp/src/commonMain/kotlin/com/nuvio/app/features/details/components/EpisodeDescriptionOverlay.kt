package com.nuvio.app.features.details.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.glass.isLiquidGlassAvailable
import com.nuvio.app.core.ui.glass.liquidGlassBlur
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.formatRuntimeFromMinutes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.episode_description_title
import nuvio.composeapp.generated.resources.episode_description_unavailable
import org.jetbrains.compose.resources.stringResource

private val DescriptionEnterSpring = spring<Float>(
    dampingRatio = 0.82f,
    stiffness = 380f,
    visibilityThreshold = 0.001f,
)
private val DescriptionImageShape = RoundedCornerShape(NuvioTokens.Space.s24)
private val DescriptionCardShape = RoundedCornerShape(NuvioTokens.Space.s24)
private val DescriptionMaxWidth = 560.dp

/**
 * Full-screen "read the whole synopsis" view for an episode, opened from the
 * episode long-press menu. The screen behind is blurred and dimmed, the
 * episode still sits at the top and the full overview lives in a scrollable
 * card underneath. Enters with a staggered spring (image first, then card)
 * and reverses on dismiss.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeDescriptionOverlay(
    episode: MetaVideo,
    seasonLabel: String,
    fallbackImageUrl: String?,
    blurArtwork: Boolean,
    backdrop: Backdrop?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val currentOnDismissed by rememberUpdatedState(onDismissed)

    val scrim = remember { Animatable(0f) }
    val image = remember { Animatable(0f) }
    val card = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val glassAvailable = remember { backdrop != null && isLiquidGlassAvailable() }

    LaunchedEffect(Unit) {
        launch { scrim.animateTo(1f, tween(durationMillis = 280, easing = NuvioTokens.Motion.standard)) }
        launch { image.animateTo(1f, DescriptionEnterSpring) }
        launch {
            delay(70)
            card.animateTo(1f, DescriptionEnterSpring)
        }
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            coroutineScope {
                launch { card.animateTo(0f, tween(durationMillis = 160, easing = NuvioTokens.Motion.accelerate)) }
                launch {
                    delay(50)
                    image.animateTo(0f, tween(durationMillis = 200, easing = NuvioTokens.Motion.accelerate))
                }
                launch {
                    delay(90)
                    scrim.animateTo(0f, tween(durationMillis = 240, easing = NuvioTokens.Motion.standard))
                }
            }
            currentOnDismissed()
        }
    }

    PlatformBackHandler(enabled = true) { close() }

    val title = localizedEpisodeTitle(episode.title, episode.episode) ?: episode.title
    val episodeCode = localizedSeasonEpisodeCode(
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
    )
    val runtimeLabel = remember(episode.runtime) {
        episode.runtime?.takeIf { it > 0 }?.let(::formatRuntimeFromMinutes)?.takeIf { it.isNotBlank() }
    }
    val releaseLabel = remember(episode.released) {
        episode.released?.takeIf { it.isNotBlank() }?.let(::formatReleaseDateForDisplay)
    }
    val metaChips = listOfNotNull(episodeCode ?: seasonLabel, runtimeLabel, releaseLabel)
    val imageUrl = episode.thumbnail ?: fallbackImageUrl
    val overview = episode.overview?.trim()?.takeIf { it.isNotEmpty() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { close() } },
    ) {
        val contentWidth = min(DescriptionMaxWidth, maxWidth - NuvioTokens.Space.s32)

        // Blurred snapshot of the screen behind, plus a dim so text stays legible
        // even on devices where the blur isn't available.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassBlur(
                    backdrop = backdrop,
                    radius = 40.dp,
                    alphaProvider = { scrim.value.coerceIn(0f, 1f) },
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrim.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = if (glassAvailable) 0.5f else 0.78f)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = NuvioTokens.Space.s24)
                .width(contentWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Thumbnail header.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .graphicsLayer {
                        val progress = image.value
                        alpha = progress.coerceIn(0f, 1f)
                        val scale = 0.86f + 0.14f * progress
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - progress) * NuvioTokens.Space.s24.toPx()
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        shadowElevation = NuvioTokens.Space.s24.toPx() * progress.coerceIn(0f, 1f)
                        shape = DescriptionImageShape
                        clip = true
                    }
                    .background(tokens.colors.surfaceCard)
                    .consumeTaps(),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (blurArtwork) Modifier.blur(NuvioTokens.Space.s18) else Modifier),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(NuvioTokens.Space.s16),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (metaChips.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                        ) {
                            metaChips.forEach { label -> DescriptionMetaChip(label) }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NuvioTokens.Space.s10)
                        .size(NuvioTokens.Space.s36)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(NuvioTokens.Space.hairline, Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable(onClick = ::close),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = Color.White,
                        modifier = Modifier.size(NuvioTokens.Icon.md),
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTokens.Space.s14))

            // Full description card. Takes the remaining height and scrolls.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .graphicsLayer {
                        val progress = card.value
                        alpha = progress.coerceIn(0f, 1f)
                        val scale = 0.94f + 0.06f * progress
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - progress) * NuvioTokens.Space.s32.toPx()
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        shape = DescriptionCardShape
                        clip = true
                    }
                    .background(tokens.colors.surfaceElevated.copy(alpha = 0.92f))
                    .border(
                        width = NuvioTokens.Space.hairline,
                        color = tokens.colors.textPrimary.copy(alpha = 0.10f),
                        shape = DescriptionCardShape,
                    )
                    .consumeTaps(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = NuvioTokens.Space.s18,
                            end = NuvioTokens.Space.s18,
                            top = NuvioTokens.Space.s18,
                            bottom = NuvioTokens.Space.s10,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                        modifier = Modifier.size(NuvioTokens.Icon.sm),
                    )
                    Text(
                        text = stringResource(Res.string.episode_description_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s18)
                        .height(NuvioTokens.Space.hairline)
                        .background(tokens.colors.textPrimary.copy(alpha = 0.08f)),
                )
                Text(
                    text = overview ?: stringResource(Res.string.episode_description_unavailable),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    color = if (overview != null) tokens.colors.textPrimary else tokens.colors.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = NuvioTokens.Space.s18,
                            end = NuvioTokens.Space.s18,
                            top = NuvioTokens.Space.s14,
                            bottom = NuvioTokens.Space.s20,
                        ),
                )
            }
        }
    }
}

@Composable
private fun DescriptionMetaChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.92f),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.full))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s4),
    )
}

/** Swallows taps so touching the content doesn't fall through to the dismiss scrim. */
@Composable
private fun Modifier.consumeTaps(): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = {},
    )
}
