package at.saltyy.switchly.blocking

import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.content.edit
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore

/**
 * Hides notifications from apps that are currently blocked by the active profile.
 *
 * This runs only when the user grants Notification Listener Access in system settings.
 */
class NotificationBlockerService : NotificationListenerService() {

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    @Volatile private var cachedEnabled: Boolean = true
    @Volatile private var cachedProfile: String? = null
    @Volatile private var cachedBlockedPrefixes: List<String> = emptyList()
    @Volatile private var dirty: Boolean = true

    // Reduce repeated SharedPreferences reads for chatty apps.
    private val tempAllowCache = LruCache<String, Long>(64)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key.isNullOrBlank()) {
            dirty = true
            return@OnSharedPreferenceChangeListener
        }

        when {
            key == KEY_NOTIF_ENABLED -> {
                cachedEnabled = prefs.getBoolean(KEY_NOTIF_ENABLED, true)
            }
            key == KEY_CURRENT_PROFILE -> {
                dirty = true
            }
            key.startsWith(KEY_BLOCKED_PREFIX) -> {
                dirty = true
            }
            key.startsWith(KEY_TEMP_ALLOW_PREFIX) -> {
                val pkg = key.removePrefix(KEY_TEMP_ALLOW_PREFIX)
                val until = prefs.getLong(key, 0L)
                synchronized(tempAllowCache) {
                    if (until > 0L) tempAllowCache.put(pkg, until) else tempAllowCache.remove(pkg)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cachedEnabled = prefs.getBoolean(KEY_NOTIF_ENABLED, true)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        dirty = true
    }

    override fun onDestroy() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        dirty = true
        refreshPolicyIfNeeded()
    }

    private fun refreshPolicyIfNeeded() {
        if (!dirty) return

        val profile = ProfileStore.getCurrent(this)
        val blocked = if (profile.isNullOrBlank()) {
            emptyList()
        } else {
            ProfileStore.getBlockedForProfile(this, profile)
                .asSequence()
                .filter { it.isNotBlank() }
                .toList()
        }

        cachedProfile = profile
        cachedBlockedPrefixes = blocked
        dirty = false
    }

    private fun isTempAllowedCached(pkg: String): Boolean {
        val now = System.currentTimeMillis()

        synchronized(tempAllowCache) {
            val cachedUntil = tempAllowCache.get(pkg)
            if (cachedUntil != null) {
                if (cachedUntil > now) return true
                tempAllowCache.remove(pkg)
            }
        }

        val key = KEY_TEMP_ALLOW_PREFIX + pkg
        val until = prefs.getLong(key, 0L)
        if (until <= now) {
            // Best-effort cleanup; do not spam writes.
            if (until != 0L) {
                prefs.edit { remove(key) }
            }
            return false
        }

        synchronized(tempAllowCache) {
            tempAllowCache.put(pkg, until)
        }
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        // Global toggle (cached + refreshed via prefs listener)
        if (!cachedEnabled) return

        // Dynamic policy checks (time-based): must be evaluated live.
        if (EmergencyBypassStore.isActive(this)) return
        if (!SwitchModeStore.isEnabled(this)) return
        if (isTempAllowedCached(pkg)) return

        refreshPolicyIfNeeded()
        val blocked = cachedBlockedPrefixes
        if (blocked.any { pkg.startsWith(it) }) {
            // Remove the notification right away.
            // Note: some devices may still briefly show a heads-up before it disappears.
            cancelNotification(sbn.key)
        }
    }

    companion object {
        private const val PREFS = "switchly_prefs"

        private const val KEY_NOTIF_ENABLED = "block_notifications_enabled"
        private const val KEY_CURRENT_PROFILE = "current_profile"
        private const val KEY_BLOCKED_PREFIX = "blocked_apps_"
        private const val KEY_TEMP_ALLOW_PREFIX = "temp_allow_"
    }
}
