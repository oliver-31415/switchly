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

package at.saltyy.switchly.feature.usage

import android.graphics.Color
import android.view.MenuItem
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object UsageInfoAction {
    fun attach(
        activity: AppCompatActivity,
        toolbar: MaterialToolbar,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
    ) {
        val iconColor = if (MaterialColors.isColorLight(AccentColor.getToolbarColor(activity))) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        toolbar.menu.add(titleRes).apply {
            setIcon(R.drawable.info_24)
            icon?.mutate()?.setTint(iconColor)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(titleRes)
                    .setMessage(messageRes)
                    .setPositiveButton(android.R.string.ok, null)
                    .showAccented()
                true
            }
        }
    }
}
