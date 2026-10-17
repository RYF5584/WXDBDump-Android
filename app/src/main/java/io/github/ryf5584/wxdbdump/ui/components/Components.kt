@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.ryf5584.wxdbdump.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ryf5584.wxdbdump.core.DetectionState
import io.github.ryf5584.wxdbdump.core.formatSize
import io.github.ryf5584.wxdbdump.ui.theme.AvatarPalette
import io.github.ryf5584.wxdbdump.ui.theme.LocalReduceMotion
import io.github.ryf5584.wxdbdump.ui.theme.Motion
import io.github.ryf5584.wxdbdump.ui.theme.LocalSemanticColors
import io.github.ryf5584.wxdbdump.ui.theme.Sizes
import io.github.ryf5584.wxdbdump.ui.theme.Spacing
import io.github.ryf5584.wxdbdump.ui.theme.Shapes2

// ---------------------------------------------------------------- Skeleton

/** Shimmer placeholder. Degrades to a static block when motion is reduced. */
@Composable
fun ShimmerBox(modifier: Modifier, shape: Shape = Shapes2.chip) {
    val reduce = LocalReduceMotion.current
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    if (reduce) {
        Box(modifier.background(base, shape))
        return
    }
    val t = rememberInfiniteTransition(label = "shimmer")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmerP",
    )
    Box(
        modifier.background(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(p * 900f - 450f, 0f),
                end = Offset(p * 900f, 0f),
            ),
            shape,
        ),
    )
}

@Composable
fun ShimmerList(rows: Int = 5, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        repeat(rows) {
            ShimmerBox(Modifier.fillMaxWidth().height(76.dp), Shapes2.card)
        }
    }
}

// ---------------------------------------------------------------- Status pill

enum class PillTone { NEUTRAL, SUCCESS, WARNING, ERROR, PRIMARY }

@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.NEUTRAL, modifier: Modifier = Modifier) {
    val sem = LocalSemanticColors.current
    val (bg, fg) = when (tone) {
        PillTone.SUCCESS -> sem.successContainer to sem.onSuccessContainer
        PillTone.WARNING -> sem.warningContainer to sem.onWarningContainer
        PillTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        PillTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PillTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .background(bg, Shapes2.pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun Badge(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(bg, Shapes2.pill).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Status card

/**
 * Environment status card, one per channel.
 *
 * State changes animate through AnimatedContent with a scale and fade transition, and
 * height changes use animateContentSize so the layout does not jump.
 */
@Composable
fun WxStatusCard(
    title: String,
    icon: @Composable () -> Unit,
    state: DetectionState,
    availableText: String,
    unavailableText: String,
    checkingText: String,
    elapsedMs: Long,
    availableSubtitle: String,
    detail: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sem = LocalSemanticColors.current
    val reduce = LocalReduceMotion.current
    val container = MaterialTheme.colorScheme.surfaceContainerLow

    val accent by animateColorAsState(
        targetValue = when (state) {
            is DetectionState.Available -> sem.success
            is DetectionState.Unavailable -> MaterialTheme.colorScheme.outline
            is DetectionState.Error -> MaterialTheme.colorScheme.error
            DetectionState.Checking -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(Motion.MEDIUM2),
        label = "accent",
    )

    Column(
        modifier
            .fillMaxWidth()
            .background(container, Shapes2.card)
            .border(1.dp, accent.copy(alpha = if (state is DetectionState.Checking) 0.35f else 0.5f), Shapes2.card)
            .padding(Spacing.m)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(accent.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Spacer(Modifier.width(Spacing.m))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))

            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (scaleIn(initialScale = 0.9f, animationSpec = tween(Motion.MEDIUM2)) + fadeIn(
                        tween(Motion.MEDIUM2)
                    )) togetherWith (scaleOut(targetScale = 0.9f, animationSpec = tween(Motion.SHORT3)) + fadeOut(
                        tween(Motion.SHORT3)
                    ))
                },
                label = "pill",
            ) { s ->
                when (s) {
                    DetectionState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        StatusPill(checkingText, PillTone.NEUTRAL)
                    }

                    is DetectionState.Available -> StatusPill(availableText, PillTone.SUCCESS)
                    is DetectionState.Unavailable -> StatusPill(unavailableText, PillTone.NEUTRAL)
                    is DetectionState.Error -> StatusPill(unavailableText, PillTone.ERROR)
                }
            }
        }

        when (state) {
            DetectionState.Checking -> {
                Text(
                    text = String.format(java.util.Locale.US, "%.1f s", elapsedMs / 1000.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IndeterminateBar()
            }

            is DetectionState.Available -> Text(
                availableSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is DetectionState.Unavailable, is DetectionState.Error -> {
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (actionText != null && onAction != null) {
                        CompactTextButton(actionText, onAction)
                    }
                    Spacer(Modifier.weight(1f))
                    CompactTextButton(
                        androidx.compose.ui.res.stringResource(io.github.ryf5584.wxdbdump.R.string.common_retry),
                        onRetry,
                    )
                }
            }
        }
    }
}

/**
 * Text button with a compact touch height.
 *
 * <p>Material's default 48dp minimum makes status cards look half empty when the only
 * content is a retry link, so the cards use this instead.
 */
@Composable
private fun CompactTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Spacing.m, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun IndeterminateBar() {
    val reduce = LocalReduceMotion.current
    val c = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val t = rememberInfiniteTransition(label = "bar")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "barP",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            // The progress segment slides from off-screen left to off-screen right, so clipping to
            // the bar keeps it from spilling over the card.
            .clipToBounds()
            .semantics { contentDescription = "indeterminate progress" },
    ) {
        val corner = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = corner)
        val segW = size.width * 0.35f
        if (reduce) {
            drawRoundRect(
                color = c,
                topLeft = Offset(0f, 0f),
                size = Size(segW, size.height),
                cornerRadius = corner,
            )
        } else {
            val start = (size.width + segW) * p - segW
            drawRoundRect(
                color = c,
                topLeft = Offset(start, 0f),
                size = Size(segW, size.height),
                cornerRadius = corner,
            )
        }
    }
}

// ---------------------------------------------------------------- Progress ring

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = Sizes.progressRing,
    stroke: androidx.compose.ui.unit.Dp = Sizes.progressRingStroke,
    color: Color = MaterialTheme.colorScheme.primary,
    center: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(if (reduce) 0 else 400),
        label = "ring",
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(size)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(animated, 0f..1f)
                },
        ) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(
                color = Color(0x33000000).copy(alpha = 0.10f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
        center()
    }
}

// ---------------------------------------------------------------- Statistics

@Composable
fun StatChip(label: String, value: String, tone: PillTone = PillTone.NEUTRAL, modifier: Modifier = Modifier) {
    val sem = LocalSemanticColors.current
    val (bg, fg) = when (tone) {
        PillTone.SUCCESS -> sem.successContainer to sem.onSuccessContainer
        PillTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        PillTone.WARNING -> sem.warningContainer to sem.onWarningContainer
        PillTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PillTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier
            .background(bg, Shapes2.chip)
            .padding(vertical = Spacing.s, horizontal = Spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

// ---------------------------------------------------------------- Empty, banner, error

@Composable
fun EmptyState(
    title: String,
    description: String,
    primaryText: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        DatabaseIllustration()
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (primaryText != null && onPrimary != null) {
                Button(onClick = onPrimary) { Text(primaryText) }
            }
            if (secondaryText != null && onSecondary != null) {
                OutlinedButton(onClick = onSecondary) { Text(secondaryText) }
            }
        }
    }
}

/** Empty-state illustration of a database and a magnifier, drawn in Compose. */
@Composable
private fun DatabaseIllustration() {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(96.dp)) {
        val w = size.width
        val h = size.height
        val rx = w * 0.24f
        val ry = h * 0.075f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        val left = w * 0.18f
        val top = h * 0.24f
        val bodyW = w * 0.52f
        val bodyH = h * 0.46f
        // Top cap
        drawOval(accent, topLeft = Offset(left, top), size = Size(bodyW, ry * 2), style = stroke)
        // Sides
        drawLine(accent, Offset(left, top + ry), Offset(left, top + ry + bodyH), strokeWidth = 3f)
        drawLine(accent, Offset(left + bodyW, top + ry), Offset(left + bodyW, top + ry + bodyH), strokeWidth = 3f)
        // Bottom cap
        drawArc(
            accent,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(left, top + ry + bodyH - ry),
            size = Size(bodyW, ry * 2),
            style = stroke,
        )
        // Two middle bands
        drawArc(
            outline,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(left, top + ry + bodyH * 0.35f),
            size = Size(bodyW, ry * 2),
            style = stroke,
        )
        // Magnifier
        val cx = w * 0.70f
        val cy = h * 0.70f
        val r = w * 0.14f
        drawCircle(accent, radius = r, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        drawLine(
            accent,
            Offset(cx + r * 0.72f, cy + r * 0.72f),
            Offset(cx + r * 1.7f, cy + r * 1.7f),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )
    }
}

enum class BannerSeverity { INFO, WARNING, ERROR }

@Composable
fun InlineBanner(
    text: String,
    severity: BannerSeverity = BannerSeverity.INFO,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sem = LocalSemanticColors.current
    val (bg, fg, icon) = when (severity) {
        BannerSeverity.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Info,
        )

        BannerSeverity.WARNING -> Triple(sem.warningContainer, sem.onWarningContainer, Icons.Filled.WarningAmber)
        BannerSeverity.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.ErrorOutline,
        )
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg, Shapes2.chip)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.weight(1f))
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionText, color = fg) }
        }
    }
}

@Composable
fun ErrorSurface(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) {
                Text(androidx.compose.ui.res.stringResource(io.github.ryf5584.wxdbdump.R.string.common_retry))
            }
        }
    }
}

/** Permission-denied state that lists concrete steps instead of a bare failure. */

// ---------------------------------------------------------------- Avatar and grouping

@Composable
fun AccountAvatar(initial: String, paletteIndex: Int, size: androidx.compose.ui.unit.Dp = Sizes.avatar, modifier: Modifier = Modifier) {
    val pair = AvatarPalette[paletteIndex % AvatarPalette.size]
    Box(
        modifier
            .size(size)
            .background(pair.second, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.titleMedium,
            color = pair.first,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

