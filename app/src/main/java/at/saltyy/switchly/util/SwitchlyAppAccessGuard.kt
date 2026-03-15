package at.saltyy.switchly.util

import android.app.Activity
import android.content.Context
import android.widget.Toast
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.SwitchModeStore

object SwitchlyAppAccessGuard {

    fun isLocked(context: Context): Boolean {
        return SwitchModeStore.isEnabled(context) &&
            AutomationModeStore.isSwitchlyAppAccessLockEnabled(context)
    }

    fun showLockedToast(context: Context) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_switchly_settings_locked_while_enabled),
            Toast.LENGTH_LONG
        ).show()
    }

    fun blockIfLocked(activity: Activity): Boolean {
        if (!isLocked(activity)) return false
        showLockedToast(activity)
        activity.finish()
        activity.overridePendingTransition(0, 0)
        return true
    }
}
