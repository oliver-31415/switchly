package at.saltyy.switchly.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.google.firebase.FirebaseApp
import at.saltyy.switchly.SwitchlyCore
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.platform.receiver.bluetooth.BluetoothTriggerMonitor
import at.saltyy.switchly.platform.receiver.wifi.WifiTriggerMonitor

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Firebase (Crashlytics/Auth) - safe even if google-services.json is missing
        runCatching { FirebaseApp.initializeApp(this) }

        // language
        LocaleHelper.setLanguage(this, LocaleHelper.getSavedLanguage(this))

        // theme
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        when (prefs.getString("pref_theme", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark"  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else    -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        // start/stop trigger monitors based on active rules
        WifiTriggerMonitor.ensureStarted(this)
        BluetoothTriggerMonitor.ensureStarted(this)

        // auto-start blocking runtime if enabled and Accessibility is available
        val enabled = SwitchModeStore.isEnabled(this)
        val canRun = BlockingRuntime.isAccessibilityActive(this)
        if (enabled && canRun) {
            SwitchlyCore.ensureRunning(this)
        }
    }
}
