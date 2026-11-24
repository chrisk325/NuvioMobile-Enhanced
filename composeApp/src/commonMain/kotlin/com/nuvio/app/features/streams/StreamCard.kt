package com.nuvio.app.features.streams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.SkeletonBlock
import com.nuvio.app.isIos
import com.nuvio.app.features.debrid.DebridProviders
import androidx.compose.ui.graphics.compositeOver
import com.nuvio.app.core.ui.glass.ambientLiquidGlass
import com.nuvio.app.core.ui.glass.isAmbientLiquidGlassActive
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow

internal val LocalStreamHighlight = staticCompositionLocalOf<(StreamItem) -> String?> { { null } }

internal data class SharedStream(
    val label: String,
    val stream: StreamItem,
)

internal val LocalSharedStream = staticCompositionLocalOf<SharedStream?> { null }

@Composable
internal fun StreamCard(
    stream: StreamItem,
    enabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    currentLabel: String? = null,
    outlined: Boolean = false,
) {
    val partyLabel = if (isCurrent) null else LocalStreamHighlight.current(stream)
    val cardText = rememberStreamCardText()
    val cardShape = RoundedCornerShape(12.dp)
    val badgeImages = stream.badges.filter { it.imageURL.isNotBlank() }
    val hasBadges = badgeImages.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints.videoSize != null)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = cardText.minHeight)
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.04f),
            )
            .clip(cardShape)
            .ambientLiquidGlass(
                shape = cardShape,
                tint = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        .compositeOver(Color.Black.copy(alpha = StreamGlassTintAlpha))
                } else {
                    StreamGlassTint
                },
                fallback = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.White.copy(alpha = 0.05f)
                },
                blurRadius = 18.dp,
                staticFromBackdrop = true,
            )
            .then(
                if (isCurrent || outlined) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.52f),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = cardText.verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
                Spacer(modifier = Modifier.height(cardText.gap(6f)))
            }

            StreamNameWithInstantService(
                stream = stream,
                appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                cardText = cardText,
            ) {
                if (isCurrent && !currentLabel.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CurrentStreamBadge(label = currentLabel, cardText = cardText)
                }
                if (partyLabel != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = partyLabel,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = cardText.subtitle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val subtitle = stream.streamSubtitle
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(cardText.gap(2f)))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = cardText.subtitle,
                        lineHeight = cardText.subtitleLineHeight,
                        shadow = streamGlassTextShadow(),
                    ),
                    color = Color.White,
                )
            }

            if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                Spacer(modifier = Modifier.height(cardText.gap(5f)))
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
            }
        }

        if (showAddonLogo) {
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!stream.addonLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = stream.addonLogo,
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = cardText.addon, shadow = streamGlassTextShadow()),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun StreamCardSkeleton(modifier: Modifier = Modifier) {
    val cardText = rememberStreamCardText()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = cardText.minHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = cardText.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(cardText.gap(8f), Alignment.CenterVertically),
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.55f), height = cardText.gap(14f), cornerRadius = 4.dp)
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f), height = cardText.gap(10f), cornerRadius = 4.dp)
    }
}

@Composable
private fun StreamCardBadgeRow(
    badgeImages: List<StreamBadge>,
    stream: StreamItem,
    showFileSizeBadges: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badgeImages.forEach { badge ->
            StreamBadgeImage(badge = badge)
        }
        if (showFileSizeBadges) {
            StreamFileSizeBadge(stream = stream)
        }
    }
}

@Composable
private fun StreamNameWithInstantService(
    stream: StreamItem,
    appendInstantServiceToDefaultName: Boolean,
    cardText: StreamCardText,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = cardText.name,
        fontWeight = FontWeight.Bold,
        lineHeight = cardText.nameLineHeight,
        letterSpacing = 0.sp,
        shadow = streamGlassTextShadow(),
    )
    val instantLabel = if (appendInstantServiceToDefaultName) {
        stream.instantServiceLabel()
    } else {
        null
    }
    val showInstantLabel = instantLabel != null
    val visibleState = remember(stream.streamLabel) {
        MutableTransitionState(showInstantLabel)
    }
    visibleState.targetState = showInstantLabel

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stream.streamLabel,
            modifier = Modifier.weight(1f, fill = false),
            style = nameStyle,
            color = Color.White,
        )
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 260)) +
                expandHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                shrinkHorizontally(
                    animationSpec = tween(durationMillis = 120),
                    shrinkTowards = Alignment.Start,
                ),
            label = "streamNameInstantService",
        ) {
            Text(
                text = " ${instantLabel.orEmpty()}",
                style = nameStyle,
                color = Color.White,
            )
        }
        trailingContent()
    }
}

@Composable
private fun CurrentStreamBadge(label: String, cardText: StreamCardText) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = cardText.badge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Stream card text sizes, scaled by the user's stream card text size setting. On iOS the list reads a step larger.
 * The card's height, vertical padding and line gaps follow the same scale so the card grows and shrinks with its text.
 */
internal class StreamCardText(private val scale: Float) {
    private val step = if (isIos) 1.5f else 0f
    val minHeight: Dp = (68f * scale).dp
    val verticalPadding: Dp = (14f * scale).dp
    fun gap(base: Float): Dp = (base * scale).dp
    private fun size(base: Float): TextUnit = ((base + step) * scale).sp
    val name = size(14f)
    val nameLineHeight = size(20f)
    val subtitle = size(12f)
    val subtitleLineHeight = size(18f)
    val addon = size(10f)
    val badge = size(11f)
}

@Composable
private fun rememberStreamCardText(): StreamCardText {
    val settings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val percent = settings.cardTextScalePercent
    return remember(percent) { StreamCardText(percent / 100f) }
}

internal const val StreamGlassTintAlpha = 0.45f

internal val StreamGlassTint: Color = Color.White.copy(alpha = 0.05f)
    .compositeOver(Color.Black.copy(alpha = StreamGlassTintAlpha))

private val StreamGlassTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.6f),
    offset = Offset(0f, 1f),
    blurRadius = 4f,
)

@Composable
internal fun streamGlassTextShadow(): Shadow? =
    if (isAmbientLiquidGlassActive()) StreamGlassTextShadow else null

private fun StreamItem.instantServiceLabel(): String? {
    val status = debridCacheStatus ?: return null
    if (status.state != StreamDebridCacheState.CACHED) return null
    val providerLabel = DebridProviders.shortName(status.providerId)
        .ifBlank { status.providerName.trim() }
        .ifBlank { DebridProviders.displayName(status.providerId) }
    return "- $providerLabel Instant"
}
