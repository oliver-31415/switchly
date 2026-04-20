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
