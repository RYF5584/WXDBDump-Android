package io.github.ryf5584.wxdbdump.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Theme choice. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

data class AppSettings(
    /** Export root; an empty string means the default. */
    val outputRoot: String = "",
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val dynamicColor: Boolean = true,
    val reduceMotion: Boolean? = null,
    val exportWal: Boolean = true,
    val verifyAfterExport: Boolean = true,
    val parallel: Int = 2,
) {
    /** The root actually in effect. */
    val effectiveRoot: String get() = io.github.ryf5584.wxdbdump.platform.StoragePaths.normalize(outputRoot)
}

/** Settings persistence. Language lives in [io.github.ryf5584.wxdbdump.platform.Locales]. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val outputRoot = stringPreferencesKey("output_root")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val reduceMotion = stringPreferencesKey("reduce_motion")
        val exportWal = booleanPreferencesKey("export_wal")
        val verify = booleanPreferencesKey("verify")
        val parallel = intPreferencesKey("parallel")
    }

    val flow: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            outputRoot = p[Keys.outputRoot] ?: "",
            theme = runCatching { ThemeChoice.valueOf(p[Keys.theme] ?: "SYSTEM") }
                .getOrDefault(ThemeChoice.SYSTEM),
            dynamicColor = p[Keys.dynamicColor] ?: true,
            reduceMotion = when (p[Keys.reduceMotion]) {
                "1" -> true
                "0" -> false
                else -> null
            },
            exportWal = p[Keys.exportWal] ?: true,
            verifyAfterExport = p[Keys.verify] ?: true,
            parallel = (p[Keys.parallel] ?: 2).coerceIn(1, 4),
        )
    }

    /** An empty string restores the default directory. */
    suspend fun setOutputRoot(v: String) = context.settingsStore.edit {
        it[Keys.outputRoot] = if (v.isBlank()) "" else io.github.ryf5584.wxdbdump.platform.StoragePaths.normalize(v)
    }

    suspend fun setTheme(v: ThemeChoice) = context.settingsStore.edit { it[Keys.theme] = v.name }

    suspend fun setDynamicColor(v: Boolean) = context.settingsStore.edit { it[Keys.dynamicColor] = v }

    suspend fun setReduceMotion(v: Boolean?) = context.settingsStore.edit {
        it[Keys.reduceMotion] = when (v) {
            null -> "system"
            true -> "1"
            false -> "0"
        }
    }

    suspend fun setExportWal(v: Boolean) = context.settingsStore.edit { it[Keys.exportWal] = v }

    suspend fun setVerify(v: Boolean) = context.settingsStore.edit { it[Keys.verify] = v }

    suspend fun setParallel(v: Int) = context.settingsStore.edit { it[Keys.parallel] = v.coerceIn(1, 4) }

    /** Clears export and appearance settings only; the language choice lives elsewhere. */
    suspend fun resetAll() = context.settingsStore.edit { it.clear() }
}
