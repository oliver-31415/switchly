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
import androidx.annotation.StringRes
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object EditingLockGuard {
    fun isLocked(ctx: Context): Boolean =
        SwitchModeStore.isEnabled(ctx) ||
            SwitchModeStore.isBaseEnabled(ctx) ||
            SwitchModeStore.hasActiveTemporaryOverride(ctx)

    fun showLockedDialog(ctx: Context, @StringRes messageRes: Int) {
        val hint = runCatching { ctx.getString(messageRes) }.getOrDefault("").trim()
        val body = if (hint.isBlank()) {
            ctx.getString(R.string.edit_locked_currently_active_body)
        } else {
            ctx.getString(R.string.edit_locked_currently_active_body_with_hint, hint)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.edit_locked_while_switchly_on_title)
            .setMessage(body)
            .setIcon(R.drawable.lock_24)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    fun blockWithDialog(activity: Activity, @StringRes messageRes: Int): Boolean {
        if (!isLocked(activity)) return false

        val hint = runCatching { activity.getString(messageRes) }.getOrDefault("").trim()
        val body = if (hint.isBlank()) {
            activity.getString(R.string.edit_locked_currently_active_body)
        } else {
            activity.getString(R.string.edit_locked_currently_active_body_with_hint, hint)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.edit_locked_while_switchly_on_title)
            .setMessage(body)
            .setIcon(R.drawable.lock_24)
            .setPositiveButton(R.string.ok) { _, _ ->
                activity.finish()
            }
            .setOnCancelListener {
                activity.finish()
            }
            .showAccented()

        return true
    }
}
