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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object HomeModeDialogHelper {

    private data class CustomHomeOption(
        val key: String,
        val titleRes: Int,
        val summaryRes: Int,
        val iconRes: Int,
        val defaultValue: Boolean
    )

    fun currentHomeLayoutMode(context: Context): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return when (sp.getString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, null)) {
            ToggleOptionsActivity.HOME_MODE_DEFAULT -> ToggleOptionsActivity.HOME_MODE_DEFAULT
            ToggleOptionsActivity.HOME_MODE_SIMPLE -> ToggleOptionsActivity.HOME_MODE_SIMPLE
            ToggleOptionsActivity.HOME_MODE_ADVANCED -> ToggleOptionsActivity.HOME_MODE_ADVANCED
            ToggleOptionsActivity.HOME_MODE_FOCUS -> ToggleOptionsActivity.HOME_MODE_SIMPLE
            ToggleOptionsActivity.HOME_MODE_CUSTOM -> ToggleOptionsActivity.HOME_MODE_CUSTOM
            else -> if (sp.contains(ToggleOptionsActivity.KEY_HOME_LAYOUT_DETAILED)) {
                if (sp.getBoolean(ToggleOptionsActivity.KEY_HOME_LAYOUT_DETAILED, true)) {
                    ToggleOptionsActivity.HOME_MODE_ADVANCED
                } else {
                    ToggleOptionsActivity.HOME_MODE_SIMPLE
                }
            } else {
                ToggleOptionsActivity.HOME_MODE_DEFAULT
            }
        }
    }

    fun homeLayoutModeLabel(context: Context, mode: String = currentHomeLayoutMode(context)): String = when (mode) {
        ToggleOptionsActivity.HOME_MODE_DEFAULT -> context.getString(R.string.home_layout_default)
        ToggleOptionsActivity.HOME_MODE_SIMPLE -> context.getString(R.string.home_layout_minimal)
        ToggleOptionsActivity.HOME_MODE_ADVANCED -> context.getString(R.string.home_layout_detailed)
        ToggleOptionsActivity.HOME_MODE_CUSTOM -> context.getString(R.string.home_layout_custom)
        else -> context.getString(R.string.home_layout_detailed)
    }

    fun showHomeLayoutModeDialog(context: Context, onChanged: (() -> Unit)? = null) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val modes = listOf(
            ToggleOptionsActivity.HOME_MODE_DEFAULT,
            ToggleOptionsActivity.HOME_MODE_SIMPLE,
            ToggleOptionsActivity.HOME_MODE_ADVANCED,
            ToggleOptionsActivity.HOME_MODE_CUSTOM
        )
        val currentMode = currentHomeLayoutMode(context)
        val options = modes.map { mode ->
            val summaryRes = when (mode) {
                ToggleOptionsActivity.HOME_MODE_DEFAULT -> R.string.home_mode_default_card_summary
                ToggleOptionsActivity.HOME_MODE_SIMPLE -> R.string.home_mode_simple_card_summary
                ToggleOptionsActivity.HOME_MODE_ADVANCED -> R.string.home_mode_advanced_card_summary
                ToggleOptionsActivity.HOME_MODE_CUSTOM -> R.string.home_mode_custom_card_summary
                else -> R.string.pref_home_layout_mode_summary
            }
            SwitchlyDialogOption(
                title = homeLayoutModeLabel(context, mode),
                summary = context.getString(summaryRes),
                iconRes = when (mode) {
                    ToggleOptionsActivity.HOME_MODE_DEFAULT -> R.drawable.dashboard_24
                    ToggleOptionsActivity.HOME_MODE_SIMPLE -> R.drawable.toggle_on_24
                    ToggleOptionsActivity.HOME_MODE_ADVANCED -> R.drawable.tune_24
                    ToggleOptionsActivity.HOME_MODE_CUSTOM -> R.drawable.palette_24
                    else -> R.drawable.dashboard_24
                },
                selected = mode == currentMode
            )
        }

        context.showSwitchlyOptionDialog(
            title = context.getString(R.string.pref_home_layout_mode_title),
            options = options
        ) { index ->
            val mode = modes.getOrNull(index) ?: return@showSwitchlyOptionDialog
            if (mode == ToggleOptionsActivity.HOME_MODE_CUSTOM) {
                sp.edit { putString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, ToggleOptionsActivity.HOME_MODE_CUSTOM) }
                onChanged?.invoke()
                showCustomizeHomeDialog(context, onChanged)
                return@showSwitchlyOptionDialog
            }

            sp.edit {
                putString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, mode)
                putBoolean(ToggleOptionsActivity.KEY_HOME_LAYOUT_DETAILED, mode == ToggleOptionsActivity.HOME_MODE_ADVANCED)
            }
            onChanged?.invoke()
        }
    }

    fun showCustomizeHomeDialog(context: Context, onChanged: (() -> Unit)? = null) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val options = listOf(
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_TIMER, R.string.home_custom_active_timer, R.string.home_custom_active_timer_summary, R.drawable.schedule_24, true),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_MAIN_BUTTON, R.string.home_custom_main_button, R.string.home_custom_main_button_summary, R.drawable.toggle_on_24, true),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_PROFILE, R.string.home_custom_active_profile, R.string.home_custom_active_profile_summary, R.drawable.account_box_24, true),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_CONTROL_MODE, R.string.home_custom_control_mode, R.string.home_custom_control_mode_summary, R.drawable.tune_24, true),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_PICK_APPS, R.string.home_custom_pick_apps, R.string.home_custom_pick_apps_summary, R.drawable.apps_24, true),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_TEMPORARY, R.string.home_custom_temporary, R.string.home_custom_temporary_summary, R.drawable.alarm_24, false),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_EMERGENCY, R.string.home_custom_emergency, R.string.home_custom_emergency_summary, R.drawable.lock_open_24, false),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_QUICK_ACTIONS, R.string.home_custom_quick_actions, R.string.home_custom_quick_actions_summary, R.drawable.dashboard_24, false),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_NEXT_SCHEDULE, R.string.home_custom_next_schedule, R.string.home_custom_next_schedule_summary, R.drawable.schedule_24, false),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_BLOCKED_NOW, R.string.home_custom_blocked_now, R.string.home_custom_blocked_now_summary, R.drawable.info_24, false),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_BLOCKED_APPS, R.string.home_custom_blocked_apps, R.string.home_custom_blocked_apps_summary, R.drawable.apps_24, false),
            CustomHomeOption(ToggleOptionsActivity.KEY_HOME_CUSTOM_PROFILE_DROPDOWN, R.string.home_custom_profile_dropdown, R.string.home_custom_profile_dropdown_summary, R.drawable.switch_account_24, false)
        )

        val accent = AccentColor.getAccentColorInt(context)
        val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT)
        val surfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, surface)
        val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, ColorUtils.setAlphaComponent(onSurface, 0x44))
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

        val checked = BooleanArray(options.size) { index ->
            sp.getBoolean(options[index].key, options[index].defaultValue)
        }

        lateinit var countText: TextView
        val cards = mutableListOf<MaterialCardView>()

        fun updateRowState(index: Int) {
            val card = cards.getOrNull(index) ?: return
            val isChecked = checked[index]
            card.strokeWidth = dp(if (isChecked) 2 else 1)
            card.strokeColor = if (isChecked) accent else outline
            card.setCardBackgroundColor(if (isChecked) ColorUtils.setAlphaComponent(accent, 0x14) else surfaceVariant)
            countText.text = context.resources.getQuantityString(R.plurals.pref_home_layout_custom_selected_count, options.size, checked.count { it }, options.size)
        }

        val content = ScrollView(context).apply {
            isFillViewport = false
            setPadding(dp(8), dp(10), dp(8), dp(2))
        }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            list,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val intro = TextView(context).apply {
            text = context.getString(R.string.pref_home_layout_custom_builder_intro)
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xCC))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        list.addView(intro)

        countText = TextView(context).apply {
            setTextColor(accent)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        list.addView(countText)

        options.forEachIndexed { index, option ->
            val card = MaterialCardView(context).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                useCompatPadding = true
                isClickable = true
                isFocusable = true
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
            }

            val icon = ImageView(context).apply {
                setImageResource(option.iconRes)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(accent))
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }

            val title = TextView(context).apply {
                text = context.getString(option.titleRes)
                setTextColor(onSurface)
                textSize = 15f
            }
            val summary = TextView(context).apply {
                text = context.getString(option.summaryRes)
                setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xB0))
                textSize = 13f
                setPadding(0, dp(3), 0, 0)
            }
            textColumn.addView(title)
            textColumn.addView(summary)
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val check = CheckBox(context).apply {
                isChecked = checked[index]
                CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(accent))
                contentDescription = context.getString(option.titleRes)
            }
            row.addView(check)

            card.setOnClickListener {
                checked[index] = !checked[index]
                check.isChecked = checked[index]
                updateRowState(index)
            }
            check.setOnClickListener {
                checked[index] = check.isChecked
                updateRowState(index)
            }

            card.addView(row)
            cards += card
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            updateRowState(index)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pref_home_layout_custom_title)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                sp.edit {
                    options.forEachIndexed { index, option -> putBoolean(option.key, checked[index]) }
                    putString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, ToggleOptionsActivity.HOME_MODE_CUSTOM)
                    putBoolean(ToggleOptionsActivity.KEY_HOME_LAYOUT_DETAILED, false)
                }
                onChanged?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(accent)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(accent)
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.98f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
