@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package io.github.ryf5584.wxdbdump.ui.screen

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.AppInfo
import io.github.ryf5584.wxdbdump.core.DbFile
import io.github.ryf5584.wxdbdump.core.formatSize
import io.github.ryf5584.wxdbdump.ui.AppViewModel
import io.github.ryf5584.wxdbdump.ui.components.Badge
import io.github.ryf5584.wxdbdump.ui.components.BannerSeverity
import io.github.ryf5584.wxdbdump.ui.components.EmptyState
import io.github.ryf5584.wxdbdump.ui.components.ErrorSurface
import io.github.ryf5584.wxdbdump.ui.components.GithubFooter
import io.github.ryf5584.wxdbdump.ui.components.InlineBanner
import io.github.ryf5584.wxdbdump.ui.components.LoadingBanner
import io.github.ryf5584.wxdbdump.ui.components.PillTone
import io.github.ryf5584.wxdbdump.ui.components.ShimmerList
import io.github.ryf5584.wxdbdump.ui.components.StatusPill
import io.github.ryf5584.wxdbdump.ui.theme.LocalSemanticColors
import io.github.ryf5584.wxdbdump.ui.theme.MonoTextStyle
import io.github.ryf5584.wxdbdump.ui.theme.Shapes2
import io.github.ryf5584.wxdbdump.ui.theme.Spacing

/**
 * Database picker.
 *
 * <p>The search field is outside the LazyColumn for the same reason as on the account screen.
 * The default sort is by size descending, which puts the main database first.
 */
@Composable
fun DatabasesScreen(
    vm: AppViewModel,
    @Suppress("UNUSED_PARAMETER") animatedVisibilityScope: SharedTransitionScope,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    var sortMenu by remember { mutableStateOf(false) }

    // A missing permission is stated outright with a one-tap grant entry
    if (vm.exportPermissionBlocked) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissPermissionBlocked() },
            title = { Text(stringResource(R.string.err_no_dir_permission)) },
            text = { Text(stringResource(R.string.settings_all_files_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPermissionBlocked()
                    vm.requestAllFilesAccess()
                }) { Text(stringResource(R.string.settings_all_files_grant)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPermissionBlocked() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.selectedAccount?.displayName ?: stringResource(R.string.databases_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshDatabases() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.common_refresh))
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Filled.Sort, stringResource(R.string.databases_sort))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ArrowDownward, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(Spacing.s))
                                        Text(stringResource(R.string.databases_sort_size))
                                    }
                                },
                                onClick = {
                                    if (!vm.sortBySize) vm.toggleSort()
                                    sortMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ArrowUpward, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(Spacing.s))
                                        Text(stringResource(R.string.databases_sort_name))
                                    }
                                },
                                onClick = {
                                    if (vm.sortBySize) vm.toggleSort()
                                    sortMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.databases.isNotEmpty()) {
                ExportBar(
                    selected = vm.selectedPaths.size,
                    total = vm.sortedDatabases.size,
                    bytes = vm.selectedBytes,
                    allSelected = vm.selectedPaths.size == vm.sortedDatabases.size &&
                        vm.sortedDatabases.isNotEmpty(),
                    onToggleAll = {
                        if (vm.selectedPaths.size == vm.sortedDatabases.size &&
                            vm.sortedDatabases.isNotEmpty()
                        ) {
                            vm.clearSelection()
                        } else {
                            vm.selectAll()
                        }
                    },
                    onExport = onExport,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---- Search field, fixed ----
            OutlinedTextField(
                value = vm.dbQuery,
                onValueChange = { vm.dbQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                placeholder = { Text(stringResource(R.string.databases_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (vm.dbQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.dbQuery = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.databases_search_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = Shapes2.chip,
                supportingText = {
                    if (vm.dbQuery.isNotBlank()) {
                        Text(
                            stringResource(
                                R.string.databases_search_result,
                                vm.dbMatchCount,
                                vm.databases.size,
                            ),
                        )
                    }
                },
            )

            // ---- Live loading progress ----
            if (vm.dbsLoading) {
                LoadingBanner(
                    progress = vm.dbsProgress,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }

            when {
                vm.dbsError != null ->
                    ErrorSurface(vm.dbsError!!, onRetry = { vm.refreshDatabases() })

                vm.dbsLoading && vm.databases.isEmpty() -> {
                    Spacer(Modifier.height(Spacing.s))
                    ShimmerList(rows = 6)
                }

                vm.databases.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        title = stringResource(R.string.databases_empty_title),
                        description = stringResource(R.string.databases_empty_desc),
                        primaryText = stringResource(R.string.common_retry),
                        onPrimary = { vm.refreshDatabases() },
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.l),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                            Text(
                                stringResource(R.string.databases_total, vm.databases.size) +
                                    " · " +
                                    stringResource(R.string.databases_total_size, formatSize(vm.databases.sumOf { it.sizeBytes })),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.databases_output_to, vm.exportDirLabel()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Explicit guidance when no key was captured
                    item {
                        if (vm.databases.any { it.encrypted && !it.hasKey }) {
                            // The two modes fail for different reasons, so the text changes with the mode: Xposed
                            // means nothing was captured and root means the uin could not be derived.
                            val accountNote = vm.selectedAccount?.note.orEmpty()
                            InlineBanner(
                                text = accountNote.ifBlank {
                                    stringResource(R.string.databases_need_key_hint)
                                },
                                severity = BannerSeverity.WARNING,
                                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                            )
                        }
                    }

                    items(vm.sortedDatabases, key = { it.path }) { db ->
                        DbRow(
                            db = db,
                            checked = db.path in vm.selectedPaths,
                            onToggle = { vm.togglePath(db.path) },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    if (vm.sortedDatabases.isEmpty()) {
                        item {
                            EmptyState(
                                title = stringResource(R.string.databases_search_empty),
                                description = stringResource(R.string.databases_search_empty_desc),
                                secondaryText = stringResource(R.string.databases_search_clear),
                                onSecondary = { vm.dbQuery = "" },
                            )
                        }
                    }
                    item {
                        GithubFooter(onOpen = { openUrl(context, AppInfo.GITHUB_URL) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportBar(
    selected: Int,
    total: Int,
    bytes: Long,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggleAll) {
                Text(
                    stringResource(
                        if (allSelected) R.string.common_clear_selection else R.string.common_select_all,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onExport, enabled = selected > 0) {
                Text(stringResource(R.string.databases_export_n, selected, formatSize(bytes)))
            }
        }
    }
}

@Composable
private fun DbRow(db: DbFile, checked: Boolean, onToggle: () -> Unit) {
    val sem = LocalSemanticColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(Spacing.s))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    db.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Spacing.s))
                StatusPill(
                    text = io.github.ryf5584.wxdbdump.payload.model.DbCatalog.categoryLabel(db.categoryKey),
                    tone = when (db.categoryKey) {
                        "CHAT" -> PillTone.PRIMARY
                        "SNS", "FAVORITE" -> PillTone.SUCCESS
                        else -> PillTone.NEUTRAL
                    },
                )
            }
            // Purpose of the database
            if (db.description.isNotBlank()) {
                Text(
                    db.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (db.encrypted) {
                    Badge(
                        stringResource(R.string.databases_encrypted),
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (!db.hasKey) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = stringResource(R.string.databases_no_key),
                            tint = sem.warning,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else {
                    Badge(
                        stringResource(R.string.databases_plaintext),
                        sem.warningContainer,
                        sem.onWarningContainer,
                    )
                }
                if (db.hasWal) {
                    Badge(
                        stringResource(R.string.databases_wal),
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    db.readableSize,
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
        }
    }
}
