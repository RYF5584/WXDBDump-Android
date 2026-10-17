@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.ryf5584.wxdbdump.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import io.github.ryf5584.wxdbdump.core.AppInfo
import io.github.ryf5584.wxdbdump.ui.components.GithubFooter
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.ExportItem
import io.github.ryf5584.wxdbdump.core.formatDuration
import io.github.ryf5584.wxdbdump.core.formatSize
import io.github.ryf5584.wxdbdump.ui.AppViewModel
import io.github.ryf5584.wxdbdump.ui.components.ProgressRing
import io.github.ryf5584.wxdbdump.ui.components.StatChip
import io.github.ryf5584.wxdbdump.ui.components.StatusPill
import io.github.ryf5584.wxdbdump.ui.components.PillTone
import io.github.ryf5584.wxdbdump.ui.theme.LocalSemanticColors
import io.github.ryf5584.wxdbdump.ui.theme.MonoTextStyle
import io.github.ryf5584.wxdbdump.ui.theme.MonoTextStyleSmall
import io.github.ryf5584.wxdbdump.ui.theme.Sizes
import io.github.ryf5584.wxdbdump.ui.theme.Motion
import io.github.ryf5584.wxdbdump.ui.theme.Spacing
import io.github.ryf5584.wxdbdump.ui.theme.Shapes2
import kotlinx.coroutines.delay

/** Screen 5: export progress. */
@Composable
fun ExportScreen(vm: AppViewModel, onDone: () -> Unit) {
    val sem = LocalSemanticColors.current
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Refresh speed, elapsed and remaining once per second
    LaunchedEffect(vm.exporting) {
        while (vm.exporting) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }

    val elapsed = if (vm.exportStartedAt == 0L) 0L
    else (if (vm.exportFinishedAt > 0) vm.exportFinishedAt else now) - vm.exportStartedAt
    val speed = if (elapsed > 0) vm.exportCopiedBytes * 1000 / elapsed else 0L
    val remainingBytes = (vm.exportTotalBytes - vm.exportCopiedBytes).coerceAtLeast(0)
    val eta = if (speed > 0) remainingBytes * 1000 / speed else -1L

    val ringColor by animateColorAsState(
        targetValue = when {
            vm.exportFailed > 0 && !vm.exporting -> MaterialTheme.colorScheme.error
            vm.exporting -> MaterialTheme.colorScheme.primary
            else -> sem.success
        },
        animationSpec = tween(Motion.MEDIUM3),
        label = "ringColor",
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.export_title)) }) },
        bottomBar = {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (vm.exporting) {
                    OutlinedButton(
                        onClick = { vm.cancelExport() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text(stringResource(R.string.export_cancel)) }
                } else {
                    if (vm.exportFailed > 0) {
                        Button(
                            onClick = { vm.retryFailed() },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) { Text(stringResource(R.string.export_retry_failed)) }
                    }

                    // ---- Open folder: let the system chooser pick an app ----
                    vm.lastExportDir?.let { dir ->
                        OutlinedButton(
                            onClick = { vm.openFolder(dir) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) {
                            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.export_open_dir))
                        }
                        Text(
                            stringResource(R.string.export_dir_path, dir.absolutePath),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Button(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text(stringResource(R.string.common_confirm)) }
                        OutlinedButton(
                            onClick = { vm.showSummary = true },
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text(stringResource(R.string.export_done_title)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // ---- Hero ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProgressRing(
                    progress = vm.exportPercent / 100f,
                    size = Sizes.progressRing,
                    color = ringColor,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(
                            targetState = vm.exporting || vm.exportPercent < 100,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                            label = "center",
                        ) { inProgress ->
                            if (inProgress) {
                                Text(
                                    "${vm.exportPercent}%",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = sem.success,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                        Text(
                            stringResource(
                                R.string.export_files_progress,
                                vm.exportDone,
                                vm.exportItems.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.m))
                Text(
                    stringResource(
                        R.string.export_bytes,
                        formatSize(vm.exportCopiedBytes),
                        formatSize(vm.exportTotalBytes),
                    ) + if (speed > 0) " · " + stringResource(R.string.export_speed, formatSize(speed)) else "",
                    style = MonoTextStyleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.export_elapsed, formatDuration(elapsed)) +
                        if (eta > 0) " · " + stringResource(R.string.export_eta, formatDuration(eta)) else "",
                    style = MonoTextStyleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.l))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    StatChip(stringResource(R.string.export_stat_done), vm.exportDone.toString(), PillTone.SUCCESS, Modifier.weight(1f))
                    StatChip(
                        stringResource(R.string.export_stat_failed),
                        vm.exportFailed.toString(),
                        if (vm.exportFailed > 0) PillTone.ERROR else PillTone.NEUTRAL,
                        Modifier.weight(1f),
                    )
                    StatChip(stringResource(R.string.export_stat_pending), vm.exportPending.toString(), PillTone.NEUTRAL, Modifier.weight(1f))
                }
            }

            // ---- File list ----
            LazyColumn(
                // weight, not fillMaxSize: inside a Column the latter measures against the
                // full parent height, so the last rows end up underneath the bottom bar.
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = Spacing.s, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(vm.exportItems, key = { it.db.path }) { item ->
                    ExportFileRow(item)
                }
                val skipped = vm.exportItems.count { it.status == ExportItem.Status.SKIPPED }
                if (skipped > 0) {
                    item {
                        Text(
                            stringResource(R.string.export_skipped_note, skipped),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.l, vertical = Spacing.m),
                        )
                    }
                }
                item {
                    GithubFooter(onOpen = { openUrl(context, AppInfo.GITHUB_URL) })
                }
            }
        }
    }

    if (vm.showSummary) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { vm.showSummary = false }, sheetState = sheetState) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(
                    stringResource(R.string.export_done_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(
                        R.string.export_done_summary,
                        vm.exportDone,
                        vm.exportFailed,
                        formatSize(vm.exportCopiedBytes),
                        formatDuration(if (vm.exportFinishedAt > 0) vm.exportFinishedAt - vm.exportStartedAt else elapsed),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val failed = vm.exportItems.filter { it.status == ExportItem.Status.FAILED }
                if (failed.isNotEmpty()) {
                    Text(
                        stringResource(R.string.export_stat_failed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    failed.forEach { f ->
                        Column {
                            Text(f.db.name, style = MonoTextStyleSmall)
                            Text(
                                f.reason.ifBlank { f.status.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Skipped entries are informational, not a problem, so they get their own
                // neutral section instead of sitting under "failed".
                val skipped = vm.exportItems.filter { it.status == ExportItem.Status.SKIPPED }
                if (skipped.isNotEmpty()) {
                    Text(
                        stringResource(R.string.export_stat_skipped),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    skipped.forEach { f ->
                        Column {
                            Text(f.db.name, style = MonoTextStyleSmall)
                            Text(
                                f.reason.ifBlank { f.status.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.l))
            }
        }
    }
}

@Composable
private fun ExportFileRow(item: ExportItem) {
    val sem = LocalSemanticColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(item)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(
                    item.outputName.ifBlank { item.db.name },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(formatSize(if (item.status == ExportItem.Status.DONE) item.db.sizeBytes else item.copiedBytes))
                        append(" / ")
                        append(item.db.readableSize)
                        if (item.stage.isNotBlank()) append(" · ").append(item.stage)
                    },
                    style = MonoTextStyleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (item.status) {
                ExportItem.Status.DONE -> StatusPill(stringResource(R.string.export_stat_done), PillTone.SUCCESS)
                ExportItem.Status.FAILED -> StatusPill(stringResource(R.string.export_stat_failed), PillTone.ERROR)
                ExportItem.Status.SKIPPED -> StatusPill("跳过", PillTone.WARNING)
                else -> StatusPill(item.status.name, PillTone.NEUTRAL)
            }
        }
        if (item.status == ExportItem.Status.RUNNING || item.status == ExportItem.Status.SNAPSHOTTING) {
            Spacer(Modifier.height(Spacing.s))
            LinearProgressIndicator(
                progress = { item.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        if (item.reason.isNotBlank() && item.status != ExportItem.Status.DONE) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == ExportItem.Status.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusIcon(item: ExportItem) {
    val sem = LocalSemanticColors.current
    Box(
        Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, Shapes2.chip),
        contentAlignment = Alignment.Center,
    ) {
        when (item.status) {
            ExportItem.Status.DONE -> Icon(Icons.Filled.Check, null, tint = sem.success, modifier = Modifier.size(18.dp))
            ExportItem.Status.FAILED -> Icon(
                Icons.Filled.ErrorOutline, null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp),
            )

            ExportItem.Status.SKIPPED -> Icon(
                Icons.Filled.HourglassEmpty, null,
                tint = sem.warning, modifier = Modifier.size(18.dp),
            )

            ExportItem.Status.RUNNING, ExportItem.Status.SNAPSHOTTING -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
            )

            else -> Icon(
                Icons.Filled.Upload, null,
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp),
            )
        }
    }
}
