package at.saltyy.switchly.util

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object EditingLockGuard {
    fun isLocked(ctx: Context): Boolean = SwitchModeStore.isEnabled(ctx)

    fun showLockedDialog(ctx: Context, @StringRes messageRes: Int) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.edit_locked_while_switchly_on_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    fun blockWithDialog(activity: Activity, @StringRes messageRes: Int): Boolean {
        if (!isLocked(activity)) return false
        showLockedDialog(activity, messageRes)
        activity.finish()
        return true
    }
}
