package at.saltyy.switchly.security

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import at.saltyy.switchly.feature.settings.AppLockActivity
import at.saltyy.switchly.nfc.NfcEntryActivity
import java.util.concurrent.atomic.AtomicBoolean

object AppLockManager {
    private var startedActivities: Int = 0
    private val sessionUnlocked = AtomicBoolean(false)
    private val promptShowing = AtomicBoolean(false)

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
            }

            override fun onActivityResumed(activity: Activity) {
                maybeRequestUnlock(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    sessionUnlocked.set(false)
                    promptShowing.set(false)
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun maybeRequestUnlock(activity: Activity): Boolean {
        if (!shouldProtect(activity)) return false
        if (!AppLockStore.isEnabled(activity)) return false
        if (sessionUnlocked.get()) return false
        if (!promptShowing.compareAndSet(false, true)) return true

        activity.startActivity(
            Intent(activity, AppLockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        activity.overridePendingTransition(0, 0)
        return true
    }

    fun markUnlocked() {
        sessionUnlocked.set(true)
        promptShowing.set(false)
    }

    fun clearPromptFlag() {
        promptShowing.set(false)
    }

    private fun shouldProtect(activity: Activity): Boolean {
        return activity !is AppLockActivity && activity !is NfcEntryActivity
    }
}
