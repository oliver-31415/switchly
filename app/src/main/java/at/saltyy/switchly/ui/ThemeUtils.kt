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

package at.saltyy.switchly.ui

import android.app.Activity
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.CustomAccentApplier

object ThemeUtils {

    fun applyAccentTheme(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val accent = prefs.getString("pref_accent", "default") ?: "default"

        val themeRes = when (accent) {
            "blue"   -> R.style.Theme_Switchly_Accent_Blue
            "orange" -> R.style.Theme_Switchly_Accent_Orange
            "purple" -> R.style.Theme_Switchly_Accent_Purple
            "pink"   -> R.style.Theme_Switchly_Accent_Pink
            "teal"   -> R.style.Theme_Switchly_Accent_Teal
            "red"    -> R.style.Theme_Switchly_Accent_Red
            "amber"  -> R.style.Theme_Switchly_Accent_Amber
            "gray"   -> R.style.Theme_Switchly_Accent_Gray
            "custom" -> R.style.Theme_Switchly
            else     -> R.style.Theme_Switchly
        }

        activity.setTheme(themeRes)

        // Runtime fallback for arbitrary custom accent colors.
        // This retints remaining default-accent widgets after inflation.
        if (accent == "custom") {
            activity.window?.decorView?.post {
                runCatching { CustomAccentApplier.applyIfNeeded(activity) }
            }
        }
    }
}
