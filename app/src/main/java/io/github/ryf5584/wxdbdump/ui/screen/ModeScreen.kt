package io.github.ryf5584.wxdbdump.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.AppInfo
import io.github.ryf5584.wxdbdump.core.RunMode
import io.github.ryf5584.wxdbdump.ui.AppViewModel
import io.github.ryf5584.wxdbdump.ui.components.GithubFooter
import io.github.ryf5584.wxdbdump.ui.theme.Spacing as S

/**
 * Extraction mode picker.
 *
 * <p>Reached only when both channels are available; a single usable channel is selected
 * automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeScreen(
    vm: AppViewModel,
    onPicked: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.mode_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = S.l),
        ) {
            Spacer(Modifier.height(S.m))
            Text(
                stringResource(R.string.mode_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(S.l))

            for (m in vm.env.availableModes) {
                ModeCard(
                    mode = m,
                    selected = vm.mode == m,
                    onClick = {
                        vm.chooseMode(m)
                        onPicked()
                    },
                )
                Spacer(Modifier.height(S.m))
            }

            Spacer(Modifier.weight(1f))
            GithubFooter(onOpen = { openUrl(context, AppInfo.GITHUB_URL) })
        }
    }
}

@Composable
private fun ModeCard(mode: RunMode, selected: Boolean, onClick: () -> Unit) {
    val title = stringResource(
        when (mode) {
            RunMode.ROOT -> R.string.mode_root
            RunMode.XPOSED -> R.string.mode_xposed
        },
    )
    val desc = stringResource(
        when (mode) {
            RunMode.ROOT -> R.string.mode_root_desc
            RunMode.XPOSED -> R.string.mode_xposed_desc
        },
    )
    val tag = stringResource(
        when (mode) {
            RunMode.ROOT -> R.string.mode_root_tag
            RunMode.XPOSED -> R.string.mode_xposed_tag
        },
    )

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(S.l),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = when (mode) {
                    RunMode.ROOT -> Icons.Filled.Security
                    RunMode.XPOSED -> Icons.Filled.Extension
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(S.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(S.s))
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(S.xs))
                // The description is capped at three lines so long text cannot stretch the card
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
