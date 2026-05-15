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

package at.saltyy.switchly.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import at.saltyy.switchly.R
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Google Play requires a prominent in-app disclosure and explicit consent before
 * sending users to the system Accessibility permission screen.
 */
object AccessibilityDisclosure {
    private const val PREFS = "accessibility_disclosure"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    // Bump this when the disclosure text materially changes.
    private const val DISCLOSURE_VERSION = 1

    fun openSettingsWithDisclosure(activity: Activity, forceShow: Boolean = false) {
        if (!forceShow && hasAcceptedDisclosure(activity)) {
            openAccessibilitySettings(activity)
            return
        }

        showDisclosure(activity)
    }

    fun hasAcceptedDisclosure(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED_VERSION, 0) >= DISCLOSURE_VERSION
    }

    private fun rememberAccepted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_ACCEPTED_VERSION, DISCLOSURE_VERSION) }
    }

    private fun showDisclosure(activity: Activity) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = dp(activity, 20)
            val topPadding = dp(activity, 8)
            setPadding(horizontalPadding, topPadding, horizontalPadding, 0)
        }

        val message = TextView(activity).apply {
            text = activity.getString(R.string.accessibility_disclosure_message)
            textSize = 14f
            setLineSpacing(0f, 1.08f)
        }

        val checkbox = CheckBox(activity).apply {
            text = activity.getString(R.string.accessibility_disclosure_checkbox)
            textSize = 14f
            setPadding(0, dp(activity, 12), 0, 0)
        }

        container.addView(
            message,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            checkbox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(activity).apply {
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.accessibility_disclosure_title)
            .setView(scroll)
            .setNegativeButton(R.string.accessibility_disclosure_decline, null)
            .setPositiveButton(R.string.accessibility_disclosure_accept, null)
            .setCancelable(false)
            .showAccented()

        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        fun applyPositiveState(checked: Boolean) {
            positive.isEnabled = checked
            positive.alpha = if (checked) 1f else 0.45f
        }

        applyPositiveState(checkbox.isChecked)

        checkbox.setOnCheckedChangeListener { _, checked ->
            applyPositiveState(checked)
        }

        positive.setOnClickListener {
            if (!checkbox.isChecked) return@setOnClickListener
            rememberAccepted(activity)
            dialog.dismiss()
            openAccessibilitySettings(activity)
        }
    }

    private fun openAccessibilitySettings(activity: Activity) {
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
