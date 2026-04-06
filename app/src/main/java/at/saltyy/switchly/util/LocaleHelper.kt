/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.util

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat

/**
 * Helper for managing the app-specific language setting.
 * Uses AppCompatDelegate application locales instead of a custom configuration wrapper (modern, lint-clean approach).
 */
object LocaleHelper {

    private const val PREFS = "switchly_prefs"

    // Stored language value: "system" = follow system language therwise a language tag like "en", "de", ...
    private const val KEY_LANG = "app_language"

    /**
     * Returns the persisted app language setting.
     * Defaults to "system" if none is stored.
     */
    fun getSavedLanguage(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_LANG, "system") ?: "system"
    }

    /**
     * Persists the selected language and applies it globally.
     */
    fun setLanguage(app: Application, lang: String) {
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putString(KEY_LANG, lang) }
        applyLocale(lang)
    }

    /**
     * Returns a simple ContextWrapper.
     * No custom Configuration wrapping is needed anymore because
     * AppCompatDelegate handles per-app locales internally.
     */
    fun wrapContext(base: Context): ContextWrapper {
        return ContextWrapper(base)
    }

    /**
     * Applies the given language using AppCompatDelegate.
     * An empty language tag means "follow system locale".
     */
    private fun applyLocale(lang: String) {
        val tags = if (lang == "system") "" else lang
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(tags)
        )
    }
}
