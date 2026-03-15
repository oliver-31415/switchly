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
