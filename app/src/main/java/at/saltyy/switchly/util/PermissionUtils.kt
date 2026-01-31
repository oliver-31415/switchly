package at.saltyy.switchly.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object PermissionUtils {

    /**
     * Returns true if the given AccessibilityService is enabled for this app.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val expected = ComponentName(context, serviceClass).flattenToString()

            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            if (!enabled) return false

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            // Enabled services are stored as colon separated component names
            TextUtils.SimpleStringSplitter(':').apply {
                setString(enabledServices)
                for (s in this) {
                    if (s.equals(expected, ignoreCase = true)) return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}
