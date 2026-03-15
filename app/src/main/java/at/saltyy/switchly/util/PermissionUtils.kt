package at.saltyy.switchly.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

object PermissionUtils {

    // Returns true if the given AccessibilityService is enabled for this app.
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val expected = ComponentName(context, serviceClass)
            val expectedLong = expected.flattenToString()
            val expectedShort = expected.flattenToShortString()

            // Real-time system view (most reliable during runtime).
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledByManager = am
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val id = info.id.orEmpty()
                    id.equals(expectedLong, ignoreCase = true) ||
                        id.equals(expectedShort, ignoreCase = true)
                } == true
            if (enabledByManager) return true

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
                    if (s.equals(expectedLong, ignoreCase = true) || s.equals(expectedShort, ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}
