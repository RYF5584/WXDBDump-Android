@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.ryf5584.wxdbdump.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ryf5584.wxdbdump.BuildConfig
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.AppInfo
import io.github.ryf5584.wxdbdump.core.DetectionState
import io.github.ryf5584.wxdbdump.platform.LangChoice
import io.github.ryf5584.wxdbdump.platform.Locales
import io.github.ryf5584.wxdbdump.platform.StoragePaths
import io.github.ryf5584.wxdbdump.data.ThemeChoice
import io.github.ryf5584.wxdbdump.ui.AppViewModel
import io.github.ryf5584.wxdbdump.ui.components.BannerSeverity
import io.github.ryf5584.wxdbdump.ui.components.GithubFooter
import io.github.ryf5584.wxdbdump.ui.components.InlineBanner
import io.github.ryf5584.wxdbdump.ui.components.SectionHeader
import io.github.ryf5584.wxdbdump.ui.theme.MonoTextStyleSmall
import io.github.ryf5584.wxdbdump.ui.theme.Shapes2
import io.github.ryf5584.wxdbdump.ui.theme.Spacing

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Local draft, resynced whenever settings change
    var rootDraft by remember(settings.outputRoot) { mutableStateOf(settings.outputRoot) }

    val hasAllFiles = vm.hasAllFilesAccess()
    val (effectiveRoot, fallback) = vm.resolvedRoot()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {

            // ================= Export location =================
            SectionHeader(stringResource(R.string.settings_section_output))
            Column(
                Modifier.padding(horizontal = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                if (!hasAllFiles) {
                    InlineBanner(
                        text = stringResource(R.string.settings_all_files_title) + "\n" +
                            stringResource(R.string.settings_all_files_desc),
                        severity = BannerSeverity.WARNING,
                        actionText = stringResource(R.string.settings_all_files_grant),
                        onAction = { vm.requestAllFilesAccess() },
                    )
                    Text(
                        stringResource(R.string.settings_fallback_warning, effectiveRoot.absolutePath),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.settings_all_files_ok),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    stringResource(R.string.settings_output_root),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = rootDraft,
                    onValueChange = { rootDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(StoragePaths.defaultRoot()) },
                    singleLine = true,
                    shape = Shapes2.chip,
                    supportingText = {
                        Text(
                            stringResource(R.string.settings_output_hint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Text(
                    stringResource(R.string.settings_output_default) + "：" + StoragePaths.defaultRoot(),
                    style = MonoTextStyleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Button(
                        onClick = { vm.setOutputRoot(rootDraft) },
                        enabled = rootDraft != settings.outputRoot,
                    ) { Text(stringResource(R.string.settings_output_apply)) }
                    OutlinedButton(onClick = {
                        vm.resetOutputRoot()
                        rootDraft = ""
                    }) { Text(stringResource(R.string.settings_output_reset)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    TextButton(onClick = {
                        val p = StoragePaths.defaultRoot()
                        rootDraft = p
                        vm.setOutputRoot(p)
                    }) { Text(stringResource(R.string.settings_output_use_default)) }
                }
                Text(
                    stringResource(R.string.databases_output_to, effectiveRoot.absolutePath),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ================= Export defaults =================
            SectionHeader(stringResource(R.string.settings_section_defaults))
            SwitchRow(stringResource(R.string.settings_export_wal), settings.exportWal, vm::setExportWal)
            SwitchRow(stringResource(R.string.settings_verify), settings.verifyAfterExport, vm::setVerify)
            DiagRow(stringResource(R.string.settings_parallel), settings.parallel.toString())

            // ================= Appearance =================
            SectionHeader(stringResource(R.string.settings_section_appearance))
            ChoiceRow(
                label = stringResource(R.string.settings_theme),
                options = listOf(
                    ThemeChoice.SYSTEM to stringResource(R.string.settings_theme_system),
                    ThemeChoice.LIGHT to stringResource(R.string.settings_theme_light),
                    ThemeChoice.DARK to stringResource(R.string.settings_theme_dark),
                ),
                selected = settings.theme,
                onSelect = { vm.setTheme(it) },
            )
            SwitchRow(
                stringResource(R.string.settings_dynamic_color),
                settings.dynamicColor,
                vm::setDynamicColor,
            )
            SwitchRow(
                stringResource(R.string.settings_reduce_motion),
                settings.reduceMotion ?: false,
                { vm.setReduceMotion(if (it) true else null) },
            )

            // ================= Language =================
            SectionHeader(stringResource(R.string.settings_section_language))
            var langChoice by remember { mutableStateOf(Locales.readChoice(context)) }
            ChoiceRow(
                label = null,
                options = listOf(
                    LangChoice.SYSTEM to stringResource(R.string.settings_lang_system),
                    LangChoice.ZH to stringResource(R.string.settings_lang_zh),
                    LangChoice.EN to stringResource(R.string.settings_lang_en),
                ),
                selected = langChoice,
                onSelect = { l ->
                    if (l == langChoice) return@ChoiceRow
                    langChoice = l
                    Locales.saveChoice(context, l)
                    // The locale is applied through the Context configuration, so a recreate suffices
                    (context as? android.app.Activity)?.recreate()
                },
            )

            // Diagnostics are a development aid: a release build should not expose the
            // loopback endpoint or a self-test that decrypts the live database.
            if (BuildConfig.DEBUG) {
                SectionHeader(stringResource(R.string.settings_section_diag))
                DiagRow(
                    stringResource(R.string.settings_diag_loopback),
                    if (vm.env.xposed is DetectionState.Available) {
                        stringResource(R.string.settings_diag_connected, "127.0.0.1")
                    } else {
                        stringResource(R.string.settings_diag_disconnected)
                    },
                )
            }
            Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                if (BuildConfig.DEBUG) {
                    Button(onClick = { vm.runKeySelfTest() }, enabled = !vm.selfTestRunning) {
                        if (vm.selfTestRunning) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.s))
                        }
                        Text(stringResource(R.string.settings_selftest))
                    }
                    vm.selfTestResult?.let { r ->
                        Text(
                            r,
                            style = MonoTextStyleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.s),
                        )
                    }
                }
                TextButton(onClick = { vm.resetSettings() }) {
                    Text(stringResource(R.string.settings_reset))
                }
            }

            // ================= About =================
            SectionHeader(stringResource(R.string.settings_section_about))
            DiagRow(
                label = stringResource(R.string.app_name),
                value = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            )
            Text(
                stringResource(R.string.settings_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
            GithubFooter(onOpen = { openUrl(context, AppInfo.GITHUB_URL) })
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** Three-way choice rendered as a row of outlined buttons instead of a dialog. */
@Composable
private fun <T> ChoiceRow(
    label: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            options.forEach { (value, text) ->
                val active = value == selected
                if (active) {
                    Button(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) {
                        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) {
                        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
