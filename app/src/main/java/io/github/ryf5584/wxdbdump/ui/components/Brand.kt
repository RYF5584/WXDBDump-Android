package io.github.ryf5584.wxdbdump.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.Account
import androidx.compose.ui.unit.sp
import io.github.ryf5584.wxdbdump.core.AppInfo
import io.github.ryf5584.wxdbdump.core.LoadProgress
import io.github.ryf5584.wxdbdump.ui.theme.AvatarPalette
import io.github.ryf5584.wxdbdump.ui.theme.MonoTextStyleSmall
import io.github.ryf5584.wxdbdump.ui.theme.Sizes
import io.github.ryf5584.wxdbdump.ui.theme.Spacing
import io.github.ryf5584.wxdbdump.ui.theme.Shapes2
import java.io.File
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew

// Logo palette: flat colours, no gradients and no shadows
private val LogoFlat = Color(0xFF07C160)
private val LogoFlatTopFace = Color(0xFF0BC666)

/**
 * App logo: a WeChat bubble over a database cylinder, drawn flat.
 * Flat colours only, matching the launcher icon's shapes.
 */
@Composable
fun AppLogo(size: Dp = 56.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 108f
        fun p(v: Float) = v * u

        drawRoundRect(color = LogoFlat, cornerRadius = CornerRadius(p(24f), p(24f)))

        // Nudged up to sit optically centred
        translate(0f, -p(2f)) {
            // Bubble: a rounded rectangle with a tail at the lower left
            val bubble = Path().apply {
                addRoundRect(RoundRect(p(25f), p(27f), p(83f), p(71f), CornerRadius(p(14f), p(14f))))
                moveTo(p(40f), p(66f))
                lineTo(p(40f), p(86f))
                lineTo(p(54f), p(71f))
                close()
            }
            drawPath(bubble, Color.White)

            // The cylinder body uses the background colour, so the bubble's white defines its edge
            //
            // Three details that are easy to get wrong:
            // 1. 22 by 24 keeps the ratio near 1:1.09; flatter reads as a bowl
            // 2. The grooves bulge downward; straight lines look like tape
            // 3. The top face is one step lighter than the body, or it reads as a lid
            drawRect(color = LogoFlat, topLeft = Offset(p(43f), p(36f)), size = Size(p(22f), p(24f)))
            drawOval(color = LogoFlat, topLeft = Offset(p(43f), p(56.2f)), size = Size(p(22f), p(7.6f)))
            drawOval(color = LogoFlatTopFace, topLeft = Offset(p(43f), p(32.2f)), size = Size(p(22f), p(7.6f)))

            val ringStroke = Stroke(width = p(1.4f))
            for (dy in listOf(0f, 8f)) {
                drawArc(
                    color = Color.White,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(p(43f), p(40.2f + dy)),
                    size = Size(p(22f), p(7.6f)),
                    style = ringStroke,
                )
            }
        }
    }
}

/** Account avatar: the image when available, otherwise an initial on a coloured block. */
@Composable
fun AccountAvatar(
    account: Account,
    file: File?,
    size: Dp = Sizes.avatar,
    modifier: Modifier = Modifier,
) {
    if (file != null && file.exists() && file.length() > 0) {
        val bmp = remember(file.path, file.length(), file.lastModified()) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.size(size).clip(CircleShape),
            )
            return
        }
    }
    InitialAvatar(account.initial, account.paletteIndex, size, modifier)
}

/** Initial-based avatar. */
@Composable
fun InitialAvatar(
    initial: String,
    paletteIndex: Int,
    size: Dp = Sizes.avatar,
    modifier: Modifier = Modifier,
) {
    val pair = AvatarPalette[paletteIndex % AvatarPalette.size]
    Box(
        modifier.size(size).background(pair.second, CircleShape),
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

/** Loading bar with the current stage. Indeterminate when the total is unknown. */
@Composable
fun LoadingProgress(progress: LoadProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        Spacer(Modifier.height(Spacing.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.stage.ifBlank { stringResource(R.string.common_loading) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (progress.total > 0) {
                Text(
                    "${progress.done}/${progress.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Loading bar on a card background, pinned at the top of a screen. */
@Composable
fun LoadingBanner(progress: LoadProgress, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, Shapes2.card)
            .padding(Spacing.l),
    ) {
        LoadingProgress(progress)
    }
}

/** Footer with the GitHub entry and the usage notice, centred. */
@Composable
fun GithubFooter(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .clip(Shapes2.pill)
                .clickable(onClick = onOpen)
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (compact) 16.dp else 20.dp),
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                AppInfo.GITHUB_HANDLE,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.footer_notice),
            style = MonoTextStyleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        )
    }
}

/**
 * Author block.
 *
 * <p>GitHub gets a full-width row because it is the primary entry point; the other
 * channels sit in a compact row. WeChat and QQ have no reliable deep link, so tapping
 * them copies the handle instead of opening a dead page; Telegram opens its profile.
 */
@Composable
fun AuthorFooter(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val av = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.author_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = av.copy(alpha = 0.7f),
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(Spacing.s))

        // GitHub: full-width row, opens the repository.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Shapes2.card)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onOpen(AppInfo.GITHUB_URL) }
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                tint = av,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                AppInfo.GITHUB_HANDLE,
                style = MaterialTheme.typography.labelLarge,
                color = av,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = av.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp),
            )
        }

        Spacer(Modifier.height(Spacing.s))

        // Other channels: tap to copy, except Telegram which opens its profile.
        Row(Modifier.fillMaxWidth()) {
            ChannelChip(
                icon = R.drawable.ic_wechat,
                label = AppInfo.WECHAT,
                onClick = { copyToClipboard(context, AppInfo.WECHAT) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.s))
            ChannelChip(
                icon = R.drawable.ic_qq,
                label = AppInfo.QQ,
                onClick = { copyToClipboard(context, AppInfo.QQ) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.s))
            ChannelChip(
                icon = R.drawable.ic_telegram,
                label = AppInfo.TELEGRAM,
                onClick = { onOpen(AppInfo.TELEGRAM_URL) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.footer_notice),
            style = MonoTextStyleSmall,
            color = av.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        )
    }
}

/** Icon above handle, used for the copy-to-clipboard channels. */
@Composable
private fun ChannelChip(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val av = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clip(Shapes2.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s, horizontal = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = av,
            modifier = Modifier.size(Sizes.authorIcon),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = av.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Copy a handle and confirm it with a toast. */
private fun copyToClipboard(context: Context, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("WXDB-Dump-Android", value))
    Toast.makeText(context, context.getString(R.string.author_copied, value), Toast.LENGTH_SHORT).show()
}

/** Try the primary link, then the fallback, then hand it to the caller. */
private fun openBrandLink(
    context: Context,
    url: String,
    fallback: String?,
    onOpen: (String) -> Unit,
) {
    val launched = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (launched) return
    onOpen(fallback ?: url)
}
