package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

/**
 * Global toggle for hiding notifications from apps that are currently blocked by the active profile.
 * Note: the user must grant Notification Listener Access in system settings for this to work.
 */
object NotificationBlockStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_ENABLED = "block_notifications_enabled"

    // Default = true (feature on by default).
    fun isEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLED, enabled) }
    }

    // True if the user granted Notification Listener Access to Switchly.
    fun hasListenerAccess(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }
}
