package net.pcg.hearth.remote.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import net.pcg.hearth.remote.R

/**
 * Per-app language. An empty locale list follows the phone's system language.
 * [AppCompatDelegate.setApplicationLocales] stores the choice and recreates
 * the activity so every string resource switches immediately.
 */
internal enum class AppLanguage(val tag: String, @StringRes val label: Int) {
    System("", R.string.settings_language_system),
    English("en", R.string.settings_language_english),
    Japanese("ja", R.string.settings_language_japanese);

    fun apply() {
        val locales = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        fun current(): AppLanguage = fromLanguageTags(
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        )

        fun fromLanguageTags(languageTags: String): AppLanguage = when {
            languageTags.startsWith("ja") -> Japanese
            languageTags.startsWith("en") -> English
            else -> System
        }
    }
}
