package io.github.ryf5584.wxdbdump.platform

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** Language options. */
enum class LangChoice { SYSTEM, ZH, EN }

/**
 * Locale resolution.
 *
 * Chinese-script system locales map to Simplified Chinese and everything else to English.
 * The locale is applied by wrapping the Context, so a change only needs a recreate.
 */
object Locales {

    private const val PREF = "locale"
    private const val KEY_LANG = "lang"

    /** Chinese-script locales map to Simplified Chinese. */
    private val CHINESE_FAMILY = setOf(
        "zh",  // Simplified, Traditional, Hong Kong and Taiwan
        "bo",  // Tibetan
        "ug",  // Uyghur
        "mn",  // Mongolian
        "za",  // Zhuang
        "ii",  // Yi
        "yue", // Cantonese
        "cmn", "cdo", "cjy", "cpx", "czh", "czo", "cnp", // Chinese topolects
        "hak", "nan", "wuu", "lzh",
    )

    fun systemIsChinese(): Boolean {
        val lang = runCatching {
            Resources.getSystem().configuration.locales[0].language.lowercase(Locale.ROOT)
        }.getOrDefault("en")
        return lang in CHINESE_FAMILY
    }

    /** Choice to actual language tag. */
    fun resolveTag(choice: LangChoice): String = when (choice) {
        LangChoice.ZH -> "zh-CN"
        LangChoice.EN -> "en"
        LangChoice.SYSTEM -> if (systemIsChinese()) "zh-CN" else "en"
    }

    fun readChoice(context: Context): LangChoice {
        val v = prefs(context).getString(KEY_LANG, null)
        return runCatching { LangChoice.valueOf(v ?: "") }.getOrDefault(LangChoice.SYSTEM)
    }

    fun saveChoice(context: Context, choice: LangChoice) {
        prefs(context).edit().putString(KEY_LANG, choice.name).apply()
    }

    /**
     * Wraps the Context in the target locale.
     *
     * Wrapping the Context rather than using AppCompatDelegate keeps this free of AppCompat
     * below API 33, which a Compose activity does not otherwise pull in.
     */
    fun wrap(base: Context, choice: LangChoice = readChoice(base)): Context {
        val locale = Locale.forLanguageTag(resolveTag(choice))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
