@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package io.github.ryf5584.wxdbdump.ui.screen

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.ryf5584.wxdbdump.core.Account
import io.github.ryf5584.wxdbdump.core.AppInfo
import io.github.ryf5584.wxdbdump.core.formatSize
import io.github.ryf5584.wxdbdump.ui.AppViewModel
import io.github.ryf5584.wxdbdump.ui.components.AccountAvatar
import io.github.ryf5584.wxdbdump.ui.components.EmptyState
import io.github.ryf5584.wxdbdump.ui.components.ErrorSurface
import io.github.ryf5584.wxdbdump.ui.components.GithubFooter
import io.github.ryf5584.wxdbdump.ui.components.LoadingBanner
import io.github.ryf5584.wxdbdump.ui.components.PillTone
import io.github.ryf5584.wxdbdump.ui.components.ShimmerList
import io.github.ryf5584.wxdbdump.ui.components.StatusPill
import io.github.ryf5584.wxdbdump.ui.theme.Sizes
import io.github.ryf5584.wxdbdump.ui.theme.Spacing
import io.github.ryf5584.wxdbdump.ui.theme.Shapes2

/**
 * Account picker.
 *
 * <p>Two deliberate layout choices:
 * <ol>
 *   <li>The search field sits outside the LazyColumn. As a list item it lost focus when
 *       recycled, which made the keyboard fail to appear intermittently.</li>
 *   <li>A progress bar with the live stage text sits above the list, so the user can see
 *       that work is happening.</li>
 * </ol>
 */
@Composable
fun AccountsScreen(
    vm: AppViewModel,
    @Suppress("UNUSED_PARAMETER") animatedVisibilityScope: SharedTransitionScope,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onOpenAccount: (Account) -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadAccounts() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.common_refresh))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.common_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---- Search field, fixed outside the list ----
            OutlinedTextField(
                value = vm.accountQuery,
                onValueChange = { vm.accountQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                placeholder = { Text(stringResource(R.string.accounts_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (vm.accountQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.accountQuery = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.databases_search_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = Shapes2.chip,
            )

            // ---- Live loading progress ----
            if (vm.accountsLoading) {
                LoadingBanner(
                    progress = vm.accountsProgress,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }

            when {
                vm.accountsError != null ->
                    ErrorSurface(vm.accountsError!!, onRetry = { vm.loadAccounts() })

                vm.accountsLoading && vm.accounts.isEmpty() -> {
                    Spacer(Modifier.height(Spacing.s))
                    ShimmerList(rows = 3)
                }

                vm.accounts.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        title = stringResource(R.string.accounts_empty_title),
                        description = stringResource(R.string.accounts_empty_desc),
                        primaryText = stringResource(R.string.common_retry),
                        onPrimary = { vm.loadAccounts() },
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.l),
                ) {
                    item {
                        Text(
                            stringResource(
                                R.string.accounts_summary,
                                vm.accounts.size,
                                formatSize(vm.accounts.sumOf { it.totalBytes }),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = Spacing.l,
                                top = Spacing.xs,
                                bottom = Spacing.s,
                            ),
                        )
                    }
                    items(vm.filteredAccounts, key = { it.dirName }) { a ->
                        AccountRow(
                            account = a,
                            avatar = vm.avatarFile(a),
                            onClick = { onOpenAccount(a) },
                        )
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
private fun AccountRow(account: Account, avatar: java.io.File?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(account = account, file = avatar, size = Sizes.avatar)

        Spacer(Modifier.width(Spacing.m))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // A missing nickname marks a historical account; global preferences cover only the last one
                if (account.nickname.isBlank()) {
                    Spacer(Modifier.width(Spacing.s))
                    StatusPill(stringResource(R.string.accounts_hist), PillTone.NEUTRAL)
                }
            }
            if (account.subtitle.isNotBlank()) {
                Text(
                    account.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                stringResource(
                    R.string.accounts_dbs_meta,
                    account.dbCount,
                    formatSize(account.totalBytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,)
            if (account.note.isNotBlank()) {
                Text(
                    account.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
