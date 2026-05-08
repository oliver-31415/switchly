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

package at.saltyy.switchly.security

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.feature.settings.AppLockActivity
import at.saltyy.switchly.nfc.NfcEntryActivity
import at.saltyy.switchly.util.ActivityTransitionCompat
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

object AppLockManager {
    private var startedActivities: Int = 0
    private val sessionUnlocked = AtomicBoolean(false)
    private val promptShowing = AtomicBoolean(false)

    @Volatile
    private var lastPromptActivityRef: WeakReference<Activity>? = null

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
            override fun onActivityDestroyed(activity: Activity) {
                if (lastPromptActivityRef?.get() === activity) {
                    lastPromptActivityRef = null
                }
            }
        })
    }

    fun maybeRequestUnlock(activity: Activity): Boolean {
        lastPromptActivityRef = WeakReference(activity)
        if (!shouldProtect(activity)) return false
        if (!AppLockStore.isEnabled(activity)) return false
        if (sessionUnlocked.get()) return false
        if (!promptShowing.compareAndSet(false, true)) return true

        AppLogStore.append(activity, "AppLock", "Lock triggered package=${activity.javaClass.simpleName}")
        activity.startActivity(
            Intent(activity, AppLockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        ActivityTransitionCompat.finishWithoutAnimation(activity)
        return true
    }

    fun markUnlocked() {
        lastPromptActivityRef?.get()?.let { AppLogStore.append(it, "AppLock", "Unlock success method=pin_or_biometric") }
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
