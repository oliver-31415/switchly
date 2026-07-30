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

package at.saltyy.switchly.blocking

import android.app.Notification
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import at.saltyy.switchly.data.prefs.BlockedInboxStore
import at.saltyy.switchly.data.prefs.BlockedNotificationEvent
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.util.AppBlockSafety
import at.saltyy.switchly.widget.BlockedNotificationsWidgetProvider

/**
 * Hides notifications from apps that are currently blocked by the active profile.
 * This runs only when the user grants Notification Listener Access in system settings.
 */
class NotificationBlockerService : NotificationListenerService() {

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    @Volatile private var cachedEnabled: Boolean = true
    @Volatile private var cachedProfile: String? = null
    @Volatile private var cachedBlockedPrefixes: List<String> = emptyList()
    @Volatile private var cachedAllowMode: Boolean = false
    @Volatile private var cachedAllowEssentials: Boolean = true
    @Volatile private var dirty: Boolean = true

    // Reduce repeated SharedPreferences reads for chatty apps.
    private val tempAllowCache = LruCache<String, Long>(64)

    // Avoid spamming the inbox for noisy apps (log at most once per package every 15s)
    private val inboxThrottle = LruCache<String, Long>(64)

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
            key.startsWith(KEY_BLOCKED_PREFIX) || key.startsWith(KEY_RULE_MODE_PREFIX) || key.startsWith(KEY_ALLOW_ESSENTIALS_PREFIX) -> {
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
        if (!dirty) {
            return
        }

        val profile = ProfileStore.getCurrent(this)
        val allowMode = profile?.let { ProfileRuleModeStore.isAllowMode(this, it) } == true
        val selected = if (profile.isNullOrBlank()) {
            emptyList()
        } else {
            val packages = if (allowMode) {
                ProfileStore.getAllowedForProfile(this, profile)
            } else {
                ProfileStore.getBlockedForProfile(this, profile)
            }
            packages
                .asSequence()
                .filter { it.isNotBlank() }
                .toList()
        }

        cachedProfile = profile
        cachedBlockedPrefixes = selected
        cachedAllowMode = allowMode
        cachedAllowEssentials = profile?.let { ProfileRuleModeStore.shouldAllowEssentialSystemApps(this, it) } ?: true
        dirty = false
    }

    private fun isTempAllowedCached(pkg: String): Boolean {
        val now = System.currentTimeMillis()

        synchronized(tempAllowCache) {
            val cachedUntil = tempAllowCache.get(pkg)
            if (cachedUntil != null) {
                if (cachedUntil > now) {
                    return true
                }
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
        if (!cachedEnabled) {
            return
        }

        // Dynamic policy checks (time-based): must be evaluated live.
        if (EmergencyBypassStore.isActive(this)) {
            return
        }
        if (!SwitchModeStore.isEnabled(this)) {
            return
        }
        if (isTempAllowedCached(pkg)) {
            return
        }

        refreshPolicyIfNeeded()
        val selected = cachedBlockedPrefixes.any { pkg.startsWith(it) }
        val essentialAllowed = cachedAllowMode && cachedAllowEssentials && AppBlockSafety.isProtected(this, pkg)
        val shouldBlockNotification = if (cachedAllowMode) {
            !selected && !essentialAllowed
        } else {
            selected
        }

        if (shouldBlockNotification) {
            // Remove the notification right away.
            // Note: some devices may still briefly show a heads-up before it disappears.
            safelyCancelNotification(sbn.key, pkg)

            // Best-effort: keep a small audit trail (metadata only)
            val now = System.currentTimeMillis()
            synchronized(inboxThrottle) {
                val last = inboxThrottle.get(pkg) ?: 0L
                if (now - last > 15_000L) {
                    inboxThrottle.put(pkg, now)

                    val n = sbn.notification
                    val extras = n.extras
                    val t = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                    val tx = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                    val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                    val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
                    val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()
                    val groupKey = runCatching { sbn.groupKey }.getOrNull()
                    val category = sbn.notification.category
                    val channelId = sbn.notification.channelId

                    fun clip(s: String?, max: Int): String? {
                        val v = s?.replace("\n", " ")?.trim().orEmpty()
                        if (v.isBlank()) {
                            return null
                        }
                        return if (v.length > max) {
                            v.take(max) + "…"
                        } else {
                            v
                        }
                    }

                    BlockedInboxStore.add(
                        ctx = this,
                        event = BlockedNotificationEvent(
                            timeMillis = now,
                            pkg = pkg,
                            profile = cachedProfile.orEmpty(),
                            reason = "Blocked notification",
                            title = clip(t, 200).orEmpty(),
                            text = clip(tx, 280).orEmpty(),
                            bigText = clip(big, 1600).orEmpty(),
                            subText = clip(sub, 240).orEmpty(),
                            summaryText = clip(summary, 240).orEmpty(),
                            groupKey = clip(groupKey, 200).orEmpty(),
                            category = clip(category, 100).orEmpty(),
                            channelId = clip(channelId, 200).orEmpty()
                        )
                    )
                    BlockedNotificationsWidgetProvider.refreshAll(this)
                }
            }
        }
    }

    private fun safelyCancelNotification(key: String, pkg: String) {
        try {
            cancelNotification(key)
        } catch (e: SecurityException) {
            // Some OEM builds can still deliver onNotificationPosted after the listener token was invalidated. Do not crash the service; just skip this notification.
            Log.w(TAG, "Could not cancel blocked notification for $pkg: listener token rejected", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not cancel blocked notification for $pkg", e)
        }
    }

    companion object {
        private const val TAG = "NotificationBlocker"
        private const val PREFS = "switchly_prefs"
        private const val KEY_NOTIF_ENABLED = "block_notifications_enabled"
        private const val KEY_CURRENT_PROFILE = "current_profile"
        private const val KEY_BLOCKED_PREFIX = "blocked_apps_"
        private const val KEY_RULE_MODE_PREFIX = "profile_rule_mode__"
        private const val KEY_ALLOW_ESSENTIALS_PREFIX = "profile_rule_allow_essentials__"
        private const val KEY_TEMP_ALLOW_PREFIX = "temp_allow_"
    }
}
