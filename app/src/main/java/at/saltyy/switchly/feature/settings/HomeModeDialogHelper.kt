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

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object HomeModeDialogHelper {

    private const val ORDER_SEPARATOR = "|"

    data class HomeModuleChoice(
        val key: String,
        val title: String,
        val summary: String,
        val iconRes: Int,
        val enabled: Boolean
    )

    data class HomeLayoutModeChoice(
        val mode: String,
        val title: String,
        val summary: String,
        val iconRes: Int,
        val selected: Boolean,
    )

    private data class CustomHomeChild(
        val key: String,
        val titleRes: Int,
        val summaryRes: Int,
        val defaultValue: Boolean
    )

    private data class CustomHomeModule(
        val key: String,
        val titleRes: Int,
        val summaryRes: Int,
        val iconRes: Int,
        val defaultValue: Boolean,
        val children: List<CustomHomeChild> = emptyList()
    )

    private data class HomeDragPayload(
        val key: String,
        val parentKey: String? = null,
    ) {
        val isChild: Boolean get() = parentKey != null
    }

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

    private fun customHomeModules(): List<CustomHomeModule> = listOf(
        CustomHomeModule(
            key = ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL,
            titleRes = R.string.home_custom_protection_control,
            summaryRes = R.string.home_custom_protection_control_summary,
            iconRes = R.drawable.security_24,
            defaultValue = true,
            children = listOf(
                CustomHomeChild(
                    ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_TIMER,
                    R.string.home_custom_active_timer,
                    R.string.home_custom_active_timer_summary,
                    true
                ),
                CustomHomeChild(
                    ToggleOptionsActivity.KEY_HOME_CUSTOM_MAIN_BUTTON,
                    R.string.home_custom_main_button,
                    R.string.home_custom_main_button_summary,
                    true
                ),
                CustomHomeChild(
                    ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_PROFILE,
                    R.string.home_custom_active_profile,
                    R.string.home_custom_active_profile_summary,
                    true
                ),
                CustomHomeChild(
                    ToggleOptionsActivity.KEY_HOME_CUSTOM_CONTROL_MODE,
                    R.string.home_custom_control_mode,
                    R.string.home_custom_control_mode_summary,
                    false
                ),
                CustomHomeChild(
                    ToggleOptionsActivity.KEY_HOME_CUSTOM_TEMPORARY,
                    R.string.home_custom_temporary,
                    R.string.home_custom_temporary_summary,
                    true
                ),
                CustomHomeChild(
                    ToggleOptionsActivity.KEY_HOME_CUSTOM_EMERGENCY,
                    R.string.home_custom_emergency,
                    R.string.home_custom_emergency_summary,
                    true
                ),
                CustomHomeChild(
                    ToggleOptionsActivity.KEY_HOME_CUSTOM_PROFILE_DROPDOWN,
                    R.string.home_custom_profile_dropdown,
                    R.string.home_custom_profile_dropdown_summary,
                    false
                )
            )
        ),
        CustomHomeModule(
            ToggleOptionsActivity.KEY_HOME_CUSTOM_QUICK_ACTIONS,
            R.string.home_custom_quick_actions,
            R.string.home_custom_quick_actions_summary,
            R.drawable.dashboard_24,
            false
        ),
        CustomHomeModule(
            ToggleOptionsActivity.KEY_HOME_CUSTOM_NEXT_SCHEDULE,
            R.string.home_custom_next_schedule,
            R.string.home_custom_next_schedule_summary,
            R.drawable.schedule_24,
            false
        ),
        CustomHomeModule(
            ToggleOptionsActivity.KEY_HOME_CUSTOM_BLOCKED_APPS,
            R.string.home_custom_blocked_apps,
            R.string.home_custom_blocked_apps_summary,
            R.drawable.apps_24,
            true
        )
    )

    private fun allCustomKeys(): Set<String> {
        val keys = linkedSetOf<String>()
        customHomeModules().forEach { module ->
            keys.add(module.key)
            module.children.forEach { child -> keys.add(child.key) }
        }
        return keys
    }

    private fun protectionChildKeys(): Set<String> = customHomeModules()
        .first { it.key == ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL }
        .children
        .mapTo(linkedSetOf()) { it.key }

    fun customHomeOrderKeys(context: Context): List<String> {
        val defaultKeys = customHomeModules().map { it.key }
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(ToggleOptionsActivity.KEY_HOME_CUSTOM_ORDER, null)
            .orEmpty()
        val protectionKeys = protectionChildKeys()
        val stored = raw.split(ORDER_SEPARATOR)
            .map { it.trim() }
            .mapNotNull { key ->
                when {
                    key == ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL -> key
                    key in protectionKeys -> ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL
                    key in defaultKeys -> key
                    else -> null
                }
            }
            .distinct()
        return stored + defaultKeys.filterNot { it in stored }
    }

    fun defaultProtectionChildOrderKeys(): List<String> = customHomeModules()
        .first { it.key == ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL }
        .children
        .map { it.key }

    fun customProtectionChildOrderKeys(context: Context): List<String> {
        val defaults = defaultProtectionChildOrderKeys()
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_ORDER, null)
            .orEmpty()
        val stored = raw.split(ORDER_SEPARATOR)
            .map { it.trim() }
            .filter { it in defaults }
            .distinct()
        return stored + defaults.filterNot { it in stored }
    }

    private fun orderedCustomHomeModules(context: Context): MutableList<CustomHomeModule> {
        val childOrder = customProtectionChildOrderKeys(context)
        val byKey = customHomeModules().associateBy { it.key }
        return customHomeOrderKeys(context).mapNotNull { key ->
            byKey[key]?.let { module ->
                if (module.key == ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL) {
                    val childrenByKey = module.children.associateBy { it.key }
                    module.copy(children = childOrder.mapNotNull { childrenByKey[it] })
                } else {
                    module
                }
            }
        }.toMutableList()
    }

    fun homeModuleChoices(context: Context): List<HomeModuleChoice> {
        return orderedCustomHomeModules(context).map { module ->
            HomeModuleChoice(
                key = module.key,
                title = context.getString(module.titleRes),
                summary = context.getString(module.summaryRes),
                iconRes = module.iconRes,
                enabled = isModuleEffectivelyEnabled(context, module)
            )
        }
    }

    fun setHomeModuleEnabled(context: Context, key: String, enabled: Boolean) {
        ensureCustomLayoutInitialized(context)
        val validKeys = customHomeModules().mapTo(hashSetOf()) { it.key }
        if (key !in validKeys) return

        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(key, enabled)
            putString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, ToggleOptionsActivity.HOME_MODE_CUSTOM)
            putBoolean(ToggleOptionsActivity.KEY_HOME_LAYOUT_DETAILED, false)
        }
    }

    private fun isModuleEffectivelyEnabled(context: Context, module: CustomHomeModule): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return if (currentHomeLayoutMode(context) == ToggleOptionsActivity.HOME_MODE_CUSTOM) {
            sp.getBoolean(module.key, module.defaultValue)
        } else {
            moduleEnabledForMode(module.key, currentHomeLayoutMode(context))
        }
    }

    private fun moduleEnabledForMode(key: String, mode: String): Boolean = when (key) {
        ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL -> true
        ToggleOptionsActivity.KEY_HOME_CUSTOM_QUICK_ACTIONS -> mode == ToggleOptionsActivity.HOME_MODE_ADVANCED
        ToggleOptionsActivity.KEY_HOME_CUSTOM_NEXT_SCHEDULE -> mode == ToggleOptionsActivity.HOME_MODE_ADVANCED
        ToggleOptionsActivity.KEY_HOME_CUSTOM_BLOCKED_APPS ->
            mode == ToggleOptionsActivity.HOME_MODE_DEFAULT || mode == ToggleOptionsActivity.HOME_MODE_ADVANCED
        else -> false
    }

    private fun childEnabledForMode(key: String, mode: String): Boolean = when (key) {
        ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_TIMER -> true
        ToggleOptionsActivity.KEY_HOME_CUSTOM_MAIN_BUTTON -> true
        ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_PROFILE -> true
        ToggleOptionsActivity.KEY_HOME_CUSTOM_CONTROL_MODE -> mode != ToggleOptionsActivity.HOME_MODE_DEFAULT
        ToggleOptionsActivity.KEY_HOME_CUSTOM_TEMPORARY ->
            mode == ToggleOptionsActivity.HOME_MODE_DEFAULT || mode == ToggleOptionsActivity.HOME_MODE_ADVANCED
        ToggleOptionsActivity.KEY_HOME_CUSTOM_EMERGENCY -> true
        ToggleOptionsActivity.KEY_HOME_CUSTOM_PROFILE_DROPDOWN -> mode == ToggleOptionsActivity.HOME_MODE_ADVANCED
        else -> false
    }

    private fun hasSavedCustomLayout(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.contains(ToggleOptionsActivity.KEY_HOME_CUSTOM_ORDER) ||
            sp.contains(ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_ORDER) ||
            allCustomKeys().any { key -> sp.contains(key) }
    }

    private fun ensureCustomLayoutInitialized(context: Context) {
        if (hasSavedCustomLayout(context)) return

        val mode = currentHomeLayoutMode(context)
        val modules = customHomeModules()
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            modules.forEach { module ->
                putBoolean(module.key, moduleEnabledForMode(module.key, mode))
                module.children.forEach { child ->
                    putBoolean(child.key, childEnabledForMode(child.key, mode))
                }
            }
            putString(
                ToggleOptionsActivity.KEY_HOME_CUSTOM_ORDER,
                modules.joinToString(ORDER_SEPARATOR) { it.key }
            )
            putString(
                ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_ORDER,
                modules.first { it.key == ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL }
                    .children
                    .joinToString(ORDER_SEPARATOR) { it.key }
            )
        }
    }

    fun homeLayoutModeChoices(context: Context): List<HomeLayoutModeChoice> {
        val currentMode = currentHomeLayoutMode(context)
        return listOf(
            ToggleOptionsActivity.HOME_MODE_DEFAULT,
            ToggleOptionsActivity.HOME_MODE_CUSTOM,
            ToggleOptionsActivity.HOME_MODE_SIMPLE,
            ToggleOptionsActivity.HOME_MODE_ADVANCED,
        ).map { mode ->
            HomeLayoutModeChoice(
                mode = mode,
                title = homeLayoutModeLabel(context, mode),
                summary = context.getString(
                    when (mode) {
                        ToggleOptionsActivity.HOME_MODE_DEFAULT -> R.string.home_mode_default_card_summary
                        ToggleOptionsActivity.HOME_MODE_SIMPLE -> R.string.home_mode_simple_card_summary
                        ToggleOptionsActivity.HOME_MODE_ADVANCED -> R.string.home_mode_advanced_card_summary
                        ToggleOptionsActivity.HOME_MODE_CUSTOM -> R.string.home_mode_custom_card_summary
                        else -> R.string.pref_home_layout_mode_summary
                    }
                ),
                iconRes = when (mode) {
                    ToggleOptionsActivity.HOME_MODE_DEFAULT -> R.drawable.dashboard_24
                    ToggleOptionsActivity.HOME_MODE_SIMPLE -> R.drawable.toggle_on_24
                    ToggleOptionsActivity.HOME_MODE_ADVANCED -> R.drawable.tune_24
                    ToggleOptionsActivity.HOME_MODE_CUSTOM -> R.drawable.palette_24
                    else -> R.drawable.dashboard_24
                },
                selected = mode == currentMode,
            )
        }
    }

    fun setHomeLayoutMode(
        context: Context,
        mode: String,
        onChanged: (() -> Unit)? = null,
    ) {
        val validMode = mode.takeIf { candidate ->
            candidate == ToggleOptionsActivity.HOME_MODE_DEFAULT ||
                candidate == ToggleOptionsActivity.HOME_MODE_SIMPLE ||
                candidate == ToggleOptionsActivity.HOME_MODE_ADVANCED ||
                candidate == ToggleOptionsActivity.HOME_MODE_CUSTOM
        } ?: return

        if (validMode == ToggleOptionsActivity.HOME_MODE_CUSTOM) {
            ensureCustomLayoutInitialized(context)
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, validMode)
            putBoolean(
                ToggleOptionsActivity.KEY_HOME_LAYOUT_DETAILED,
                validMode == ToggleOptionsActivity.HOME_MODE_ADVANCED
            )
        }
        onChanged?.invoke()
    }

    fun showHomeLayoutModeDialog(context: Context, onChanged: (() -> Unit)? = null) {
        val modes = listOf(
            ToggleOptionsActivity.HOME_MODE_DEFAULT,
            ToggleOptionsActivity.HOME_MODE_CUSTOM,
            ToggleOptionsActivity.HOME_MODE_SIMPLE,
            ToggleOptionsActivity.HOME_MODE_ADVANCED
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
            options = options,
            confirmSelection = true
        ) { index ->
            val mode = modes.getOrNull(index) ?: return@showSwitchlyOptionDialog
            setHomeLayoutMode(context, mode, onChanged)
            if (mode == ToggleOptionsActivity.HOME_MODE_CUSTOM) {
                showCustomizeHomeDialog(context, onChanged)
            }
        }
    }

    fun showCustomizeHomeDialog(context: Context, onChanged: (() -> Unit)? = null) {
        ensureCustomLayoutInitialized(context)

        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val modules = orderedCustomHomeModules(context)
        val childrenByModule = modules.associate { module ->
            module.key to module.children.toMutableList()
        }.toMutableMap()
        val accent = AccentColor.getAccentColorInt(context)
        val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT)
        val surfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, surface)
        val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val outline = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutline,
            ColorUtils.setAlphaComponent(onSurface, 0x44)
        )
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

        val enabledByKey = mutableMapOf<String, Boolean>()
        val expandedByKey = modules.associate { it.key to false }.toMutableMap()
        modules.forEach { module ->
            enabledByKey[module.key] = sp.getBoolean(module.key, module.defaultValue)
            childrenByModule[module.key].orEmpty().forEach { child ->
                enabledByKey[child.key] = sp.getBoolean(child.key, child.defaultValue)
            }
        }

        lateinit var countText: TextView
        lateinit var rowsContainer: LinearLayout
        var draggedPayload: HomeDragPayload? = null
        lateinit var renderRows: () -> Unit

        fun updateCountText() {
            val checkedCount = modules.count { enabledByKey[it.key] == true }
            countText.text = context.resources.getQuantityString(
                R.plurals.pref_home_layout_custom_selected_count,
                checkedCount,
                checkedCount,
                modules.size
            )
        }

        fun rerenderRows() {
            rowsContainer.removeAllViews()
            renderRows()
        }

        fun moveModule(fromIndex: Int, toIndex: Int) {
            if (fromIndex !in modules.indices || toIndex !in modules.indices || fromIndex == toIndex) return
            val moved = modules.removeAt(fromIndex)
            modules.add(toIndex, moved)
            rerenderRows()
        }

        fun moveModuleByKey(fromKey: String, toKey: String) {
            moveModule(
                modules.indexOfFirst { it.key == fromKey },
                modules.indexOfFirst { it.key == toKey }
            )
        }

        fun moveChild(parentKey: String, fromKey: String, toKey: String) {
            val children = childrenByModule[parentKey] ?: return
            val fromIndex = children.indexOfFirst { it.key == fromKey }
            val toIndex = children.indexOfFirst { it.key == toKey }
            if (fromIndex !in children.indices || toIndex !in children.indices || fromIndex == toIndex) return
            val moved = children.removeAt(fromIndex)
            children.add(toIndex, moved)
            rerenderRows()
        }

        fun attachModuleDragReorder(card: MaterialCardView, dragHandle: View, key: String) {
            dragHandle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    view.performClick()
                    return@setOnTouchListener true
                }
                if (event.actionMasked != MotionEvent.ACTION_DOWN) {
                    return@setOnTouchListener false
                }
                val payload = HomeDragPayload(key = key)
                draggedPayload = payload
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val data = ClipData.newPlainText("custom_home_module", key)
                val started = view.startDragAndDrop(data, View.DragShadowBuilder(card), payload, 0)
                if (!started) draggedPayload = null
                started
            }
            card.setOnDragListener { view, event ->
                val payload = draggedPayload ?: event.localState as? HomeDragPayload
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> payload?.isChild == false
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        if (payload?.key != key) view.alpha = 0.72f
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        view.alpha = 1f
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        view.alpha = 1f
                        if (payload != null && !payload.isChild && payload.key != key) {
                            moveModuleByKey(payload.key, key)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        view.alpha = 1f
                        draggedPayload = null
                        true
                    }
                    else -> true
                }
            }
        }

        fun attachChildDragReorder(
            row: View,
            dragHandle: View,
            parentKey: String,
            childKey: String,
        ) {
            dragHandle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    view.performClick()
                    return@setOnTouchListener true
                }
                if (event.actionMasked != MotionEvent.ACTION_DOWN) {
                    return@setOnTouchListener false
                }
                val payload = HomeDragPayload(key = childKey, parentKey = parentKey)
                draggedPayload = payload
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val data = ClipData.newPlainText("custom_home_child", childKey)
                val started = view.startDragAndDrop(data, View.DragShadowBuilder(row), payload, 0)
                if (!started) draggedPayload = null
                started
            }
            row.setOnDragListener { view, event ->
                val payload = draggedPayload ?: event.localState as? HomeDragPayload
                val accepts = payload?.isChild == true && payload.parentKey == parentKey
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> accepts
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        if (accepts && payload?.key != childKey) view.alpha = 0.72f
                        accepts
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        view.alpha = 1f
                        accepts
                    }
                    DragEvent.ACTION_DROP -> {
                        view.alpha = 1f
                        if (accepts && payload.key != childKey) {
                            moveChild(parentKey, payload.key, childKey)
                        }
                        accepts
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        view.alpha = 1f
                        draggedPayload = null
                        true
                    }
                    else -> accepts
                }
            }
        }

        fun resetToDefaults() {
            modules.clear()
            modules.addAll(customHomeModules())
            childrenByModule.clear()
            enabledByKey.clear()
            expandedByKey.clear()
            modules.forEach { module ->
                childrenByModule[module.key] = module.children.toMutableList()
                enabledByKey[module.key] = module.defaultValue
                expandedByKey[module.key] = false
                module.children.forEach { child -> enabledByKey[child.key] = child.defaultValue }
            }
            rerenderRows()
        }

        val content = ScrollView(context).apply {
            isFillViewport = false
            setPadding(dp(8), dp(10), dp(8), dp(2))
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list.addView(TextView(context).apply {
            text = context.getString(R.string.pref_home_layout_custom_builder_intro)
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xCC))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(6))
        })
        list.addView(TextView(context).apply {
            text = context.getString(R.string.pref_home_layout_custom_reorder_hint)
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xAA))
            textSize = 12.5f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(10))
        })

        countText = TextView(context).apply {
            setTextColor(accent)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        list.addView(countText)

        rowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.addView(rowsContainer)

        renderRows = {
            modules.forEach { module ->
                val card = MaterialCardView(context).apply {
                    radius = dp(16).toFloat()
                    cardElevation = 0f
                    useCompatPadding = false
                }
                val cardContent = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val parentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(68)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    isClickable = true
                    isFocusable = true
                }

                val dragHandle = ImageView(context).apply {
                    setImageResource(R.drawable.drag_handle_24)
                    contentDescription = context.getString(R.string.pref_home_layout_custom_drag_handle)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    ImageViewCompat.setImageTintList(
                        this,
                        ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x99))
                    )
                }
                parentRow.addView(dragHandle, LinearLayout.LayoutParams(dp(40), dp(48)))

                parentRow.addView(ImageView(context).apply {
                    setImageResource(module.iconRes)
                    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(accent))
                }, LinearLayout.LayoutParams(dp(24), dp(24)))

                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(4), 0)
                }
                textColumn.addView(TextView(context).apply {
                    text = context.getString(module.titleRes)
                    setTextColor(onSurface)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                })
                textColumn.addView(TextView(context).apply {
                    text = context.getString(module.summaryRes)
                    setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xA8))
                    textSize = 12.5f
                    maxLines = 2
                    setPadding(0, dp(2), 0, 0)
                })
                parentRow.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val expandIcon = ImageView(context).apply {
                    setImageResource(R.drawable.keyboard_arrow_down_24)
                    contentDescription = context.getString(R.string.pref_home_layout_custom_expand_details)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    isVisible = module.children.isNotEmpty()
                    ImageViewCompat.setImageTintList(
                        this,
                        ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0xB8))
                    )
                }
                parentRow.addView(expandIcon, LinearLayout.LayoutParams(dp(36), dp(36)))

                val parentCheck = MaterialCheckBox(context).apply {
                    isChecked = enabledByKey[module.key] ?: module.defaultValue
                    CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(accent))
                    contentDescription = context.getString(module.titleRes)
                }
                parentRow.addView(parentCheck, LinearLayout.LayoutParams(dp(48), dp(48)))
                cardContent.addView(parentRow)

                val divider = View(context).apply {
                    setBackgroundColor(ColorUtils.setAlphaComponent(outline, 0x70))
                }
                cardContent.addView(
                    divider,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        marginStart = dp(48)
                        marginEnd = dp(12)
                    }
                )

                val childrenContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(4), dp(8), dp(8))
                    isVisible = module.children.isNotEmpty() && expandedByKey[module.key] == true
                }

                fun refreshExpandedState() {
                    val expanded = expandedByKey[module.key] == true
                    childrenContainer.isVisible = module.children.isNotEmpty() && expanded
                    divider.isVisible = childrenContainer.isVisible
                    expandIcon.rotation = if (expanded) 180f else 0f
                    expandIcon.contentDescription = context.getString(
                        if (expanded) {
                            R.string.pref_home_layout_custom_collapse_details
                        } else {
                            R.string.pref_home_layout_custom_expand_details
                        }
                    )
                }

                fun refreshModuleState() {
                    val parentEnabled = enabledByKey[module.key] ?: module.defaultValue
                    parentCheck.isChecked = parentEnabled
                    card.strokeWidth = dp(if (parentEnabled) 2 else 1)
                    card.strokeColor = if (parentEnabled) accent else outline
                    card.setCardBackgroundColor(
                        if (parentEnabled) ColorUtils.setAlphaComponent(accent, 0x12) else surfaceVariant
                    )
                    childrenContainer.alpha = if (parentEnabled) 1f else 0.48f
                    for (childIndex in 0 until childrenContainer.childCount) {
                        val childRow = childrenContainer.getChildAt(childIndex)
                        childRow.isEnabled = parentEnabled
                        if (childRow is ViewGroup) {
                            for (nestedIndex in 0 until childRow.childCount) {
                                childRow.getChildAt(nestedIndex).isEnabled = parentEnabled
                            }
                        }
                    }
                    updateCountText()
                }

                childrenByModule[module.key].orEmpty().forEach { child ->
                    val childRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(50)
                        setPadding(0, dp(3), 0, dp(3))
                        isClickable = true
                        isFocusable = true
                    }
                    val childDragHandle = ImageView(context).apply {
                        setImageResource(R.drawable.drag_handle_24)
                        contentDescription = context.getString(R.string.pref_home_layout_custom_drag_handle)
                        setPadding(dp(6), dp(8), dp(6), dp(8))
                        ImageViewCompat.setImageTintList(
                            this,
                            ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x88))
                        )
                    }
                    childRow.addView(childDragHandle, LinearLayout.LayoutParams(dp(36), dp(44)))

                    val childText = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    childText.addView(TextView(context).apply {
                        text = context.getString(child.titleRes)
                        setTextColor(onSurface)
                        textSize = 13.5f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    childText.addView(TextView(context).apply {
                        text = context.getString(child.summaryRes)
                        setTextColor(ColorUtils.setAlphaComponent(onSurface, 0x98))
                        textSize = 12f
                        maxLines = 2
                        setPadding(0, dp(1), 0, 0)
                    })
                    childRow.addView(childText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    val childCheck = MaterialCheckBox(context).apply {
                        isChecked = enabledByKey[child.key] ?: child.defaultValue
                        CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(accent))
                        contentDescription = context.getString(child.titleRes)
                    }
                    childRow.addView(childCheck, LinearLayout.LayoutParams(dp(48), dp(48)))
                    childRow.setOnClickListener {
                        if (enabledByKey[module.key] != true) return@setOnClickListener
                        val newValue = !(enabledByKey[child.key] ?: child.defaultValue)
                        enabledByKey[child.key] = newValue
                        childCheck.isChecked = newValue
                    }
                    childCheck.setOnClickListener {
                        if (enabledByKey[module.key] == true) {
                            enabledByKey[child.key] = childCheck.isChecked
                        } else {
                            childCheck.isChecked = enabledByKey[child.key] ?: child.defaultValue
                        }
                    }
                    attachChildDragReorder(
                        row = childRow,
                        dragHandle = childDragHandle,
                        parentKey = module.key,
                        childKey = child.key,
                    )
                    childrenContainer.addView(childRow)
                }
                cardContent.addView(childrenContainer)

                parentRow.setOnClickListener {
                    if (module.children.isNotEmpty()) {
                        expandedByKey[module.key] = expandedByKey[module.key] != true
                        refreshExpandedState()
                    } else {
                        enabledByKey[module.key] = !(enabledByKey[module.key] ?: module.defaultValue)
                        refreshModuleState()
                    }
                }
                parentCheck.setOnClickListener {
                    enabledByKey[module.key] = parentCheck.isChecked
                    refreshModuleState()
                }

                attachModuleDragReorder(card, dragHandle, module.key)
                card.addView(cardContent)
                rowsContainer.addView(
                    card,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8)
                    }
                )
                refreshExpandedState()
                refreshModuleState()
            }
        }
        renderRows()

        lateinit var dialog: AlertDialog
        val titleView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(16), dp(4))
        }
        titleView.addView(TextView(context).apply {
            text = context.getString(R.string.pref_home_layout_custom_title)
            setTextColor(onSurface)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleView.addView(ImageView(context).apply {
            setImageResource(R.drawable.sync_24)
            contentDescription = context.getString(R.string.pref_home_layout_custom_restore_default)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(accent))
            setOnClickListener { resetToDefaults() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        dialog = MaterialAlertDialogBuilder(context)
            .setCustomTitle(titleView)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                sp.edit {
                    modules.forEach { module ->
                        putBoolean(module.key, enabledByKey[module.key] ?: module.defaultValue)
                        childrenByModule[module.key].orEmpty().forEach { child ->
                            putBoolean(child.key, enabledByKey[child.key] ?: child.defaultValue)
                        }
                    }
                    putString(
                        ToggleOptionsActivity.KEY_HOME_CUSTOM_ORDER,
                        modules.joinToString(separator = ORDER_SEPARATOR) { it.key }
                    )
                    putString(
                        ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_ORDER,
                        childrenByModule[ToggleOptionsActivity.KEY_HOME_CUSTOM_PROTECTION_CONTROL]
                            .orEmpty()
                            .joinToString(separator = ORDER_SEPARATOR) { it.key }
                    )
                    putString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, ToggleOptionsActivity.HOME_MODE_CUSTOM)
                    putBoolean(ToggleOptionsActivity.KEY_HOME_LAYOUT_DETAILED, false)
                }
                onChanged?.invoke()
            }
            .create()
        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.98f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }
}
