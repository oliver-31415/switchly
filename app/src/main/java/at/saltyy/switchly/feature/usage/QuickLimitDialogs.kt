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

import android.content.res.ColorStateList
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageLimitResetStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.applySwitchlyDialogWidth
import at.saltyy.switchly.util.AppBlockSafety
import at.saltyy.switchly.util.EditingLockGuard
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Quick-edit dialogs for app/website limits.
 * Goal: one consistent entry point (icon) to set either a time limit or an open-attempt limit.
 */
object QuickLimitDialogs {

    private const val MODE_TIME = 0
    private const val MODE_ATTEMPTS = 1
    private const val MODE_ALWAYS_BLOCK = 2

    fun showForApp(
        activity: AppCompatActivity,
        pkg: String,
        label: String,
        startOnAttempts: Boolean? = null,
        onChanged: (() -> Unit)? = null
    ) {
        val safety = AppBlockSafety.resolve(activity, pkg)
        when (safety.level) {
            AppBlockSafety.Level.HARD_EXCLUDED -> {
                Toast.makeText(
                    activity,
                    safety.hint ?: activity.getString(R.string.app_picker_protected_generic_hint),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            AppBlockSafety.Level.SOFT_WARNING -> {
                AlertDialog.Builder(activity)
                    .setTitle(safety.warningTitle ?: activity.getString(R.string.app_picker_protected_caution_title))
                    .setMessage(safety.warningMessage ?: safety.hint ?: activity.getString(R.string.app_picker_protected_generic_hint))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.continue_label) { _, _ ->
                        showForAppInternal(activity, pkg, label, startOnAttempts, onChanged)
                    }
                    .showAccented()
            }
            else -> showForAppInternal(activity, pkg, label, startOnAttempts, onChanged)
        }
    }

    private fun showForAppInternal(
        activity: AppCompatActivity,
        pkg: String,
        label: String,
        startOnAttempts: Boolean? = null,
        onChanged: (() -> Unit)? = null
    ) {
        if (EditingLockGuard.isLocked(activity)) {
            EditingLockGuard.showLockedDialog(activity, R.string.toast_disable_switchly_to_edit_app_limits)
            return
        }

        val profile = ProfileStore.getCurrent(activity)
        if (profile.isNullOrBlank()) {
            Toast.makeText(activity, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val currentTime = UsageLimitStore.getLimitMinutes(activity, profile, pkg)
        val currentAttempts = AttemptLimitStore.getLimitAttempts(activity, profile, pkg)
        val currentResetMode = UsageLimitResetStore.getMode(activity, profile, pkg)

        val initialMode = when (startOnAttempts) {
            true -> MODE_ATTEMPTS
            false -> MODE_TIME
            else -> if (currentAttempts > 0 && currentTime == 0) MODE_ATTEMPTS else MODE_TIME
        }

        showCompactLimitDialog(
            activity = activity,
            title = activity.getString(R.string.edit_limits),
            subtitle = "$label\n${activity.getString(R.string.profile_active_fmt, profile)}",
            supportedModes = intArrayOf(MODE_TIME, MODE_ATTEMPTS),
            initialMode = initialMode,
            initialValueProvider = { mode ->
                when (mode) {
                    MODE_ATTEMPTS -> currentAttempts
                    else -> currentTime
                }
            },
            showTimeResetMode = true,
            initialResetMode = currentResetMode
        ) { mode, value, resetMode ->
            when (mode) {
                MODE_TIME -> {
                    val m = value.coerceAtLeast(0)
                    UsageLimitStore.setLimitMinutes(activity, profile, pkg, m)
                    LimitReachedStore.clearToday(activity, pkg)
                    if (m > 0) {
                        UsageLimitResetStore.setMode(activity, profile, pkg, resetMode ?: UsageLimitResetStore.MODE_DAY)
                        ensureManaged(activity, profile, pkg)
                    } else {
                        UsageLimitResetStore.clearMode(activity, profile, pkg)
                        UsageStore.setUsageMsToday(activity, pkg, 0L)
                    }
                }
                MODE_ATTEMPTS -> {
                    val n = value.coerceAtLeast(0)
                    AttemptLimitStore.setLimitAttempts(activity, profile, pkg, n)
                    if (n > 0) ensureManaged(activity, profile, pkg)
                    if (n == 0) OpenCountStore.setToday(activity, profile, pkg, 0)
                }
            }
            BlockingRuntime.ensureRunning(activity)
            onChanged?.invoke()
        }
    }

    fun showForWebsite(activity: AppCompatActivity, domain: String, label: String, onChanged: (() -> Unit)? = null) {
        if (EditingLockGuard.isLocked(activity)) {
            EditingLockGuard.showLockedDialog(activity, R.string.toast_disable_switchly_to_edit_websites)
            return
        }

        // Websites support: time limit OR always-block rule.
        val normalized = DomainBlockStore.normalize(domain) ?: domain
        val isAllowMode = ProfileStore.getCurrent(activity)?.let { ProfileRuleModeStore.isAllowMode(activity, it) } == true
        val isAlways = DomainBlockStore.getDomains(activity).contains(normalized)
        val current = DomainLimitStore.getLimitMinutes(activity, normalized)

        showCompactLimitDialog(
            activity = activity,
            title = activity.getString(R.string.edit_limits),
            subtitle = label,
            supportedModes = intArrayOf(MODE_TIME, MODE_ALWAYS_BLOCK),
            initialMode = if (isAlways) MODE_ALWAYS_BLOCK else MODE_TIME,
            initialValueProvider = { current }
        ) { mode, value, _ ->
            when (mode) {
                MODE_ALWAYS_BLOCK -> {
                    if (value > 0) {
                        DomainLimitStore.clear(activity, normalized)
                        DomainBlockStore.addDomain(activity, normalized)
                    } else {
                        // Clear always-block
                        DomainBlockStore.removeDomain(activity, normalized)
                    }
                }

                else -> {
                    val m = value.coerceAtLeast(0)
                    if (m <= 0) {
                        DomainLimitStore.clear(activity, normalized)
                        DomainBlockStore.removeDomain(activity, normalized)
                    } else {
                        if (isAllowMode) {
                            DomainBlockStore.addDomain(activity, normalized)
                        } else {
                            DomainBlockStore.removeDomain(activity, normalized)
                        }
                        DomainLimitStore.setLimitMinutes(activity, normalized, m)
                    }
                }
            }

            BlockingRuntime.ensureRunning(activity)
            onChanged?.invoke()
        }
    }

    private fun ensureManaged(activity: AppCompatActivity, profile: String, pkg: String) {
        if (AppBlockSafety.isHardExcluded(activity, pkg)) return
        val selected = ProfileStore.getSelectedForProfileMode(activity, profile).toMutableSet()
        if (!selected.contains(pkg)) {
            selected.add(pkg)
            ProfileStore.setSelectedForProfileMode(activity, profile, selected)
        }
    }

    private fun showCompactLimitDialog(
        activity: AppCompatActivity,
        title: String,
        subtitle: CharSequence? = null,
        supportedModes: IntArray,
        initialMode: Int,
        initialValueProvider: (mode: Int) -> Int,
        showTimeResetMode: Boolean = false,
        initialResetMode: String = UsageLimitResetStore.MODE_DAY,
        onApply: (mode: Int, value: Int, resetMode: String?) -> Unit
    ) {
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_quick_limit_compact, null)
        val tilType = v.findViewById<TextInputLayout>(R.id.tilType)
        val tvSubtitle = v.findViewById<TextView>(R.id.tvLimitSubtitle)
        val tvModeSummary = v.findViewById<TextView>(R.id.tvLimitModeSummary)
        val etType = v.findViewById<MaterialAutoCompleteTextView>(R.id.etType)
        val tilValue = v.findViewById<TextInputLayout>(R.id.tilValue)
        val etValue = v.findViewById<TextInputEditText>(R.id.etValue)
        val tilResetMode = v.findViewById<TextInputLayout>(R.id.tilResetMode)
        val etResetMode = v.findViewById<MaterialAutoCompleteTextView>(R.id.etResetMode)

        // Ensure the dialog matches the currently selected accent (including custom colors).
        val accent = AccentColor.getAccentColorInt(activity)
        val accentList = ColorStateList.valueOf(accent)
        fun accentInputs() {
            // Outlined box stroke + hints + dropdown icon
            tilValue.boxStrokeColor = accent
            tilValue.hintTextColor = accentList
            tilValue.defaultHintTextColor = accentList

            tilType.boxStrokeColor = accent
            tilType.hintTextColor = accentList
            tilType.defaultHintTextColor = accentList
            tilType.setEndIconTintList(accentList)

            tilResetMode.boxStrokeColor = accent
            tilResetMode.hintTextColor = accentList
            tilResetMode.defaultHintTextColor = accentList
            tilResetMode.setEndIconTintList(accentList)
        }
        accentInputs()
        tvSubtitle.text = subtitle?.toString() ?: ""
        tvSubtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE

        val modeLabels = supportedModes.map { mode ->
            when (mode) {
                MODE_ATTEMPTS -> activity.getString(R.string.limit_attempts_action)
                MODE_ALWAYS_BLOCK -> activity.getString(R.string.rule_block_always)
                else -> activity.getString(R.string.limit_time_action)
            }
        }

        val resetModes = listOf(UsageLimitResetStore.MODE_DAY, UsageLimitResetStore.MODE_SESSION)
        val resetLabels = listOf(
            activity.getString(R.string.limit_reset_per_day),
            activity.getString(R.string.limit_reset_per_session)
        )
        val activeResetMode = arrayOf(
            when (initialResetMode) {
                UsageLimitResetStore.MODE_SESSION -> UsageLimitResetStore.MODE_SESSION
                else -> UsageLimitResetStore.MODE_DAY
            }
        )
        etResetMode.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, resetLabels))
        etResetMode.setText(resetLabels[resetModes.indexOf(activeResetMode[0]).coerceAtLeast(0)], false)
        etResetMode.setOnItemClickListener { _, _, position, _ ->
            activeResetMode[0] = resetModes.getOrNull(position) ?: UsageLimitResetStore.MODE_DAY
        }

        etType.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, modeLabels))
        if (supportedModes.size <= 1) tilType.visibility = View.GONE

        fun applyMode(mode: Int, keepTypedValue: Boolean) {
            val typed = etValue.text?.toString()?.trim().orEmpty()
            val fromStore = initialValueProvider(mode).let { if (it == 0) "" else it.toString() }
            val nextText = if (keepTypedValue && typed.isNotBlank()) typed else fromStore

            if (mode == MODE_ALWAYS_BLOCK) {
                tilValue.visibility = View.GONE
                tilResetMode.visibility = View.GONE
                etValue.setText("")
                tvModeSummary.setText(R.string.limit_always_block_action_summary)
                return
            }

            tilValue.visibility = View.VISIBLE
            tilResetMode.visibility = if (showTimeResetMode && mode == MODE_TIME) View.VISIBLE else View.GONE
            tvModeSummary.setText(
                when (mode) {
                    MODE_ATTEMPTS -> R.string.limit_attempts_action_summary
                    else -> R.string.limit_time_action_summary
                }
            )
            tilValue.hint = when (mode) {
                MODE_ATTEMPTS -> activity.getString(R.string.opens_hint)
                else -> activity.getString(R.string.minutes_hint_day)
            }
            etValue.inputType = InputType.TYPE_CLASS_NUMBER
            etValue.setText(nextText)
            etValue.setSelection(etValue.text?.length ?: 0)
        }

        val initialIdx = supportedModes.indexOf(initialMode).takeIf { it >= 0 } ?: 0
        val activeMode = intArrayOf(supportedModes[initialIdx])
        etType.setText(modeLabels[initialIdx], false)
        applyMode(activeMode[0], keepTypedValue = false)

        etType.setOnItemClickListener { _, _, position, _ ->
            activeMode[0] = supportedModes[position]
            applyMode(activeMode[0], keepTypedValue = true)
        }

        // Use custom in-view MaterialButtons so dialogs match Switchly's button styling.
        val dlg = Dialogs.builder(activity)
            .setTitle(title)
            .setView(v)
            .create()

        val btnClear = v.findViewById<MaterialButton>(R.id.btnClear)
        val btnCancel = v.findViewById<MaterialButton>(R.id.btnCancel)
        val btnOk = v.findViewById<MaterialButton>(R.id.btnOk)

        // Match the global dialog button design:
        // - OK = filled accent
        // - Cancel/Clear = text-only accent
        val onAccent = if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE

        btnCancel.setTextColor(accent)
        btnCancel.isAllCaps = false
        btnCancel.backgroundTintList = null
        runCatching { btnCancel.setBackgroundColor(android.graphics.Color.TRANSPARENT) }

        val error = android.graphics.Color.rgb(186, 26, 26)
        btnClear.setTextColor(error)
        btnClear.isAllCaps = false
        btnClear.backgroundTintList = null
        runCatching { btnClear.setBackgroundColor(android.graphics.Color.TRANSPARENT) }

        btnOk.setTextColor(onAccent)
        btnOk.isAllCaps = false
        // Prefer active accent tint for proper state handling.
        btnOk.backgroundTintList = AccentColor.getActiveColor(activity)

        btnClear.setOnClickListener {
            onApply(activeMode[0], 0, activeResetMode[0])
            dlg.dismiss()
        }
        btnCancel.setOnClickListener { dlg.dismiss() }
        btnOk.setOnClickListener {
            if (activeMode[0] == MODE_ALWAYS_BLOCK) {
                // Pass a non-zero sentinel to mean "enable always block".
                onApply(activeMode[0], 1, activeResetMode[0])
                dlg.dismiss()
                return@setOnClickListener
            }

            val raw = etValue.text?.toString()?.trim().orEmpty()
            val n = raw.toIntOrNull() ?: 0
            val max = when (activeMode[0]) {
                MODE_ATTEMPTS -> 200
                else -> 24 * 60
            }
            if (n < 0 || n > max) {
                Toast.makeText(activity, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onApply(activeMode[0], n, activeResetMode[0])
            dlg.dismiss()
        }

        dlg.setOnShowListener {
            dlg.applySwitchlyDialogWidth(0.94f)
            // Retint in CUSTOM accent mode so the dialog matches the rest of the app.
            runCatching { CustomAccentApplier.applyToDialog(dlg) }
        }
        dlg.show()
    }
}
