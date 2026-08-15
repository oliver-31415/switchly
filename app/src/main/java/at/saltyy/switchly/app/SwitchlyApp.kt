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

package at.saltyy.switchly.app

import android.app.Application
import at.saltyy.switchly.BuildConfig
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.statistics.StatsPersistence
import at.saltyy.switchly.feature.entry.QuickShortcutRegistrar
import at.saltyy.switchly.platform.receiver.bluetooth.BluetoothTriggerMonitor
import at.saltyy.switchly.platform.receiver.location.LocationTriggerMonitor
import at.saltyy.switchly.platform.receiver.wifi.WifiTriggerMonitor
import at.saltyy.switchly.security.AppLockManager
import at.saltyy.switchly.security.PlayIntegrityRuntime
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.ManagedDevicePolicyHelper
import at.saltyy.switchly.util.PersistentStatusNotifier
import com.google.firebase.FirebaseApp
import java.util.concurrent.Executors

class SwitchlyApp : Application() {
    private val startupExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SwitchlyStartup").apply { isDaemon = true }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Firebase (Auth/Cloud Sync) is only initialized for Firebase-enabled APK builds.
        // Offline/file-backup builds skip Firebase startup completely.
        if (BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            runCatching { FirebaseApp.initializeApp(this) }
        }

        // language
        LocaleHelper.setLanguage(this, LocaleHelper.getSavedLanguage(this))

        // theme
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        when (prefs.getString("pref_theme", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark"  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else    -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        AppLockManager.register(this)

        // ShortcutManagerCompat uses ShortcutService binder calls.
        // Keep it off the main startup path and only refresh when the app/shortcut spec changed.
        QuickShortcutRegistrar.refreshAsync(this)

        val appContext = applicationContext

        // Startup work below can touch system services, Google Play services or disk.
        // Do it after Application.onCreate() returns so Android/Samsung cold starts do not get stuck in finishAttachApplication or slow binder calls.
        startupExecutor.execute {
            // Initialize the durable statistics archive before monitors can emit new counters.
            runCatching { StatsPersistence.initialize(appContext) }

            // One-time sanity cleanup for old inflated usage imports.
            runCatching { UsageStore.sanitizeImpossibleDailyTotals(appContext) }

            // Start/stop trigger monitors based on active rules.
            // These may register receivers/services/geofences and should not run on the main thread.
            runCatching { WifiTriggerMonitor.ensureStarted(appContext) }
            runCatching { BluetoothTriggerMonitor.ensureStarted(appContext) }
            runCatching { LocationTriggerMonitor.ensureStarted(appContext) }

            runCatching { ManagedDevicePolicyHelper.syncSelfUninstallBlock(appContext) }

            // Diagnostic-only Play Integrity probe. Never blocks users.
            runCatching { PlayIntegrityRuntime.requestSoftCheck(appContext, "app_start") }

            // Accessibility checks may call system services via binder.
            val enabled = SwitchModeStore.isEnabled(appContext)
            val canRun = BlockingRuntime.isAccessibilityActive(appContext)
            if (enabled && canRun) {
                BlockingRuntime.ensureRunning(appContext)
            }
            PersistentStatusNotifier.refresh(appContext)
        }
    }
}
