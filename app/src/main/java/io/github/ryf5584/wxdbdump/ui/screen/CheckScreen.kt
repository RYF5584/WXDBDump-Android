package io.github.ryf5584.wxdbdump.ui.screen

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.AppInfo
import io.github.ryf5584.wxdbdump.core.DetectionState
import io.github.ryf5584.wxdbdump.core.XposedFailure
import io.github.ryf5584.wxdbdump.ui.AppViewModel
import io.github.ryf5584.wxdbdump.ui.components.AppLogo
import io.github.ryf5584.wxdbdump.ui.components.AuthorFooter
import io.github.ryf5584.wxdbdump.ui.components.GithubFooter
import io.github.ryf5584.wxdbdump.ui.components.WxStatusCard
import io.github.ryf5584.wxdbdump.ui.theme.Motion
import io.github.ryf5584.wxdbdump.ui.theme.Sizes
import io.github.ryf5584.wxdbdump.ui.theme.Spacing
import io.github.ryf5584.wxdbdump.ui.theme.Spacing as S
import androidx.compose.foundation.background
import io.github.ryf5584.wxdbdump.ui.theme.Shapes2

/**
 * Landing screen: detects both channels, then lets the user continue.
 *
 * <p>Layout notes:
 * <ul>
 *   <li>No [androidx.compose.material3.TopAppBar] — the settings action floats in the
 *       corner of the content instead, so the whole header area is usable space.</li>
 *   <li>Everything is tuned to fit one screen on a 6.1" device; scrolling stays as a
 *       safety net for large font settings.</li>
 * </ul>
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CheckScreen(
    vm: AppViewModel,
    animatedVisibilityScope: SharedTransitionScope,
    onReady: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val env = vm.env
    var guideOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // No Scaffold, so the content would otherwise sit under the status bar
                // and the settings action would be unreachable.
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = S.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(S.s))
            SettingsRow(onSettings)

            // ---- Brand ----
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(Motion.LONG2)) +
                    slideInVertically(tween(Motion.LONG2)) { it / 6 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppLogo(size = 60.dp)
                    Spacer(Modifier.height(S.m))
                    Text(
                        AppInfo.NAME,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(S.xs))
                    Text(
                        stringResource(R.string.check_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(S.l))

            // ---- Root channel ----
            WxStatusCard(
                title = stringResource(R.string.check_card_root),
                icon = {
                    Icon(
                        Icons.Filled.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                state = env.root,
                availableText = stringResource(R.string.check_state_available),
                unavailableText = stringResource(R.string.check_state_unavailable),
                checkingText = stringResource(R.string.check_state_checking),
                elapsedMs = vm.detectElapsedMs,
                availableSubtitle = stringResource(R.string.check_root_ok_desc),
                detail = rootDetail(env.root),
                actionText = null,
                onAction = null,
                onRetry = { vm.detect() },
            )

            Spacer(Modifier.height(S.s))

            // ---- Xposed channel ----
            WxStatusCard(
                title = stringResource(R.string.check_card_xposed),
                icon = {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                state = env.xposed,
                availableText = stringResource(R.string.check_state_available),
                unavailableText = stringResource(R.string.check_state_unavailable),
                checkingText = stringResource(R.string.check_state_checking),
                elapsedMs = vm.detectElapsedMs,
                availableSubtitle = if (env.wechatVersion.isNotBlank()) {
                    stringResource(R.string.check_xposed_ok_desc) + " · " +
                        stringResource(R.string.check_wechat_version, env.wechatVersion)
                } else {
                    stringResource(R.string.check_xposed_ok_desc)
                },
                detail = xposedDetail(env.xposedFailure, env.wechatInstalled),
                actionText = if (moduleNeedsActivation(env)) {
                    stringResource(R.string.check_activate_module)
                } else {
                    null
                },
                onAction = if (moduleNeedsActivation(env)) ({ openLsposed(context) }) else null,
                onRetry = { vm.detect() },
            )

            // ---- WeChat not running: offer to launch it, the hook loads on start ----
            if (env.settled && env.xposed is DetectionState.Unavailable &&
                env.wechatInstalled && env.xposedFailure == XposedFailure.WECHAT_NOT_RUNNING
            ) {
                Spacer(Modifier.height(S.s))
                OutlinedButton(
                    onClick = { launchWeChat(context) },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    Text(stringResource(R.string.check_open_wechat))
                }
            }

            // ---- Setup guide while nothing works yet ----
            if (env.settled && !env.ready) {
                Spacer(Modifier.height(S.s))
                GuideCard(open = guideOpen, onToggle = { guideOpen = !guideOpen })
            }

            // ---- Primary action ----
            AnimatedVisibility(visible = env.ready) {
                Column {
                    Spacer(Modifier.height(S.l))
                    Button(
                        onClick = onReady,
                        modifier = Modifier.fillMaxWidth().height(Sizes.touchTarget),
                    ) {
                        Text(stringResource(R.string.check_action_enter))
                    }
                }
            }

            Spacer(Modifier.height(S.s))
            AuthorFooter(onOpen = { openUrl(context, it) })
        }
    }
}

/**
 * Settings affordance that replaces the top app bar.
 *
 * <p>Rendered as part of the scrollable content so the header contributes no extra
 * vertical chrome; the icon sits on its own right-aligned line with no background.
 */
@Composable
private fun SettingsRow(onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = S.s),
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.common_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Whether the Xposed channel still needs a manual step before it can work.
 *
 * <p>Two distinct states are surfaced differently: WeChat not running is handled by an
 * "open WeChat" button below the card, while an inactive module is handled by the card's
 * own action, which jumps to the LSPosed manager.
 */
private fun moduleNeedsActivation(env: io.github.ryf5584.wxdbdump.core.EnvironmentSnapshot): Boolean =
    env.settled && env.xposed is DetectionState.Unavailable &&
        env.wechatInstalled && env.xposedFailure != XposedFailure.WECHAT_NOT_RUNNING

/** Start WeChat so the module can inject into its process. */
private fun launchWeChat(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(WE_CHAT_PACKAGE) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val WE_CHAT_PACKAGE = "com.tencent.mm"

@Composable
private fun GuideCard(open: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, Shapes2.card)
            .padding(S.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.check_guide),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.TextButton(onClick = onToggle) {
                Text(
                    if (open) stringResource(R.string.common_back)
                    else stringResource(R.string.common_open),
                )
            }
        }
        AnimatedVisibility(visible = open) {
            Text(
                stringResource(R.string.check_guide_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = S.s),
            )
        }
    }
}

@Composable
private fun rootDetail(state: DetectionState): String = when (state) {
    is DetectionState.Unavailable -> state.reason.ifBlank { stringResource(R.string.root_denied) }
    is DetectionState.Error -> state.message
    else -> ""
}

@Composable
private fun xposedDetail(failure: XposedFailure, installed: Boolean): String = when {
    !installed -> stringResource(R.string.check_wechat_missing)
    failure == XposedFailure.WECHAT_NOT_RUNNING -> stringResource(R.string.check_wechat_not_running)
    failure == XposedFailure.TOKEN_MISMATCH -> stringResource(R.string.check_xposed_token)
    failure == XposedFailure.CONNECT_FAILED || failure == XposedFailure.TIMEOUT ->
        stringResource(R.string.check_xposed_timeout)
    else -> stringResource(R.string.check_module_inactive)
}

/** Jump to the LSPosed manager so the user can enable this module. */
internal fun openLsposed(context: Context) {
    for (pkg in LSPOSED_PACKAGES) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
}

/** Open an arbitrary link in whatever app can handle it. */
internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private val LSPOSED_PACKAGES = listOf(
    "org.lsposed.manager",
    "de.robv.android.xposed.installer",
    "com.solohsu.android.edxp.manager",
)
