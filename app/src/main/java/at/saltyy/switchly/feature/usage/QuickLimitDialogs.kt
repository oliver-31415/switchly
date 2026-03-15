package at.saltyy.switchly.feature.usage

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.DomainLimitStore
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.blocking.BlockingRuntime
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.content.res.ColorStateList

/**
 * Quick-edit dialogs for app/website limits.
 *
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
        val profile = ProfileStore.getCurrent(activity)
        if (profile.isNullOrBlank()) {
            Toast.makeText(activity, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            return
        }

        // Single compact dialog: dropdown (type) + value.
        val currentTime = UsageLimitStore.getLimitMinutes(activity, profile, pkg)
        val currentAttempts = AttemptLimitStore.getLimitAttempts(activity, profile, pkg)

        val initialMode = when (startOnAttempts) {
            true -> MODE_ATTEMPTS
            false -> MODE_TIME
            else -> if (currentAttempts > 0 && currentTime == 0) MODE_ATTEMPTS else MODE_TIME
        }

        val titled = "$label · ${activity.getString(R.string.profile_active_fmt, profile)}"

        showCompactLimitDialog(
            activity = activity,
            title = titled,
            supportedModes = intArrayOf(MODE_TIME, MODE_ATTEMPTS),
            initialMode = initialMode,
            initialValueProvider = { mode ->
                when (mode) {
                    MODE_ATTEMPTS -> currentAttempts
                    else -> currentTime
                }
            }
        ) { mode, value ->
            when (mode) {
                MODE_TIME -> {
                    val m = value.coerceAtLeast(0)
                    UsageLimitStore.setLimitMinutes(activity, profile, pkg, m)
                    LimitReachedStore.clearToday(activity, pkg)
                    if (m > 0) ensureManaged(activity, profile, pkg)
                    if (m == 0) UsageStore.setUsageMsToday(activity, pkg, 0L)
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
        if (SwitchModeStore.isEnabled(activity)) {
            Toast.makeText(activity, R.string.toast_disable_switchly_to_edit_websites, Toast.LENGTH_SHORT).show()
            return
        }

        // Websites support: time limit OR always-block rule.
        val normalized = DomainBlockStore.normalize(domain) ?: domain
        val isAlways = DomainBlockStore.getDomains(activity).contains(normalized)
        val current = DomainLimitStore.getLimitMinutes(activity, normalized)

        showCompactLimitDialog(
            activity = activity,
            title = label,
            supportedModes = intArrayOf(MODE_TIME, MODE_ALWAYS_BLOCK),
            initialMode = if (isAlways) MODE_ALWAYS_BLOCK else MODE_TIME,
            initialValueProvider = { current }
        ) { mode, value ->
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
                        DomainBlockStore.removeDomain(activity, normalized)
                        DomainLimitStore.setLimitMinutes(activity, normalized, m)
                    }
                }
            }

            BlockingRuntime.ensureRunning(activity)
            onChanged?.invoke()
        }
    }

    private fun ensureManaged(activity: AppCompatActivity, profile: String, pkg: String) {
        val blocked = ProfileStore.getBlockedForProfile(activity, profile).toMutableSet()
        if (!blocked.contains(pkg)) {
            blocked.add(pkg)
            ProfileStore.setBlockedForProfile(activity, profile, blocked)
        }
    }

    private fun showCompactLimitDialog(
        activity: AppCompatActivity,
        title: String,
        supportedModes: IntArray,
        initialMode: Int,
        initialValueProvider: (mode: Int) -> Int,
        onApply: (mode: Int, value: Int) -> Unit
    ) {
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_quick_limit_compact, null)
        val tilType = v.findViewById<TextInputLayout>(R.id.tilType)
        val etType = v.findViewById<MaterialAutoCompleteTextView>(R.id.etType)
        val tilValue = v.findViewById<TextInputLayout>(R.id.tilValue)
        val etValue = v.findViewById<TextInputEditText>(R.id.etValue)

        // Ensure the dialog matches the currently selected accent (including custom colors).
        val accent = AccentColor.getAccentColorInt(activity)
        val accentList = ColorStateList.valueOf(accent)
        fun accentInputs() {
            // Outlined box stroke + hints + dropdown icon
            tilValue.boxStrokeColor = accent
            tilValue.setHintTextColor(accentList)
            tilValue.defaultHintTextColor = accentList

            tilType.boxStrokeColor = accent
            tilType.setHintTextColor(accentList)
            tilType.defaultHintTextColor = accentList
            tilType.setEndIconTintList(accentList)
        }
        accentInputs()

        val modeLabels = supportedModes.map { mode ->
            when (mode) {
                MODE_ATTEMPTS -> activity.getString(R.string.limit_attempts_action)
                MODE_ALWAYS_BLOCK -> activity.getString(R.string.rule_block_always)
                else -> activity.getString(R.string.limit_time_action)
            }
        }

        etType.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, modeLabels))
        if (supportedModes.size <= 1) tilType.visibility = View.GONE

        fun applyMode(mode: Int, keepTypedValue: Boolean) {
            val typed = etValue.text?.toString()?.trim().orEmpty()
            val fromStore = initialValueProvider(mode).let { if (it == 0) "" else it.toString() }
            val nextText = if (keepTypedValue && typed.isNotBlank()) typed else fromStore

            if (mode == MODE_ALWAYS_BLOCK) {
                tilValue.visibility = View.GONE
                etValue.setText("")
                return
            }

            tilValue.visibility = View.VISIBLE
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

        listOf(btnClear, btnCancel).forEach {
            it.setTextColor(accent)
            it.isAllCaps = false
            it.backgroundTintList = null
            runCatching { it.setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        }

        btnOk.setTextColor(onAccent)
        btnOk.isAllCaps = false
        // Prefer active accent tint for proper state handling.
        btnOk.backgroundTintList = AccentColor.getActiveColor(activity)

        btnClear.setOnClickListener {
            onApply(activeMode[0], 0)
            dlg.dismiss()
        }
        btnCancel.setOnClickListener { dlg.dismiss() }
        btnOk.setOnClickListener {
            if (activeMode[0] == MODE_ALWAYS_BLOCK) {
                // Pass a non-zero sentinel to mean "enable always block".
                onApply(activeMode[0], 1)
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
            onApply(activeMode[0], n)
            dlg.dismiss()
        }

        dlg.setOnShowListener {
            // Retint in CUSTOM accent mode so the dialog matches the rest of the app.
            runCatching { CustomAccentApplier.applyToDialog(dlg) }
        }
        dlg.show()
    }
}
