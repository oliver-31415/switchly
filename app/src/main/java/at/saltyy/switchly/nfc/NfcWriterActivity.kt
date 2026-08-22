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

package at.saltyy.switchly.nfc

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.SwitchlyDropdownAdapter
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlinx.coroutines.launch

class NfcWriterActivity : AppCompatActivity() {

    private val writeFlowLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Show a small status row on return (success or failure)
            val data = result.data
            val resultStr = data?.getStringExtra(NfcWriteWaitingActivity.EXTRA_RESULT)
            val uid = data?.getStringExtra(NfcWriteWaitingActivity.EXTRA_UID)

            // Always show the row for feedback
            statusRow.isVisible = true
            statusRow.alpha = 1f
            statusProgress.isVisible = false

            val okColor = ContextCompat.getColor(this, R.color.status_ok)
            val errorColor = ContextCompat.getColor(this, R.color.status_error)
            val neutralColor = ContextCompat.getColor(this, R.color.status_neutral)

            if (result.resultCode == RESULT_OK && resultStr == NfcWriteWaitingActivity.RESULT_OK_STR) {
                tvStatus.text = if (uid != null) {
                    getString(R.string.nfc_pair_ok_with_uid_explained, uid)
                } else {
                    getString(R.string.nfc_write_ok)
                }
                tvStatus.setTextColor(okColor)
            } else {
                // Map error types to existing messages
                when (resultStr) {
                    NfcWriteWaitingActivity.RESULT_TOO_SMALL_STR ->
                        tvStatus.text = getString(R.string.nfc_write_error_too_small)
                    NfcWriteWaitingActivity.RESULT_NOT_WRITABLE_STR ->
                        tvStatus.text = getString(R.string.nfc_write_error_not_writable)
                    NfcWriteWaitingActivity.RESULT_UNSUPPORTED_STR ->
                        tvStatus.text = getString(R.string.nfc_write_error_unsupported)
                    NfcWriteWaitingActivity.RESULT_TRANSIENT_STR ->
                        tvStatus.text = getString(R.string.nfc_write_transient_retry)
                    else ->
                        tvStatus.text = getString(R.string.nfc_write_error_generic)
                }
                tvStatus.setTextColor(errorColor)
            }

            handler.postDelayed({
                val anim = ObjectAnimator.ofFloat(statusRow, "alpha", 1f, 0f)
                anim.duration = 300
                anim.start()
                anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        statusRow.isVisible = false
                        statusRow.alpha = 1f
                        tvStatus.setTextColor(neutralColor)
                    }
                })
            }, 1800)
        }

    private var nfcAdapter: NfcAdapter? = null

    private lateinit var ddProfile: AutoCompleteTextView
    private lateinit var ddAction: AutoCompleteTextView
    private lateinit var ddTime: AutoCompleteTextView
    private lateinit var tilProfile: TextInputLayout
    private lateinit var tilAction: TextInputLayout
    private lateinit var tilTime: TextInputLayout
    private lateinit var tvActionHint: TextView
    private lateinit var btnArmWrite: Button
    private lateinit var statusRow: View
    private lateinit var tvStatus: TextView
    private lateinit var statusProgress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_PRESELECT_PROFILE = "preselect_profile"
        const val EXTRA_PRESELECT_ACTION = "preselect_action"
        const val EXTRA_PRESELECT_DURATION_MINUTES = "preselect_duration_minutes"
        const val EXTRA_PRESELECT_ASK_DURATION = "preselect_ask_duration"
        const val EXTRA_REWRITE_UID = "rewrite_uid"
        private const val TEMP_ASK_WHEN_SCANNED_VALUE = "ask"
    }

    private var rewriteUid: String = ""

    private fun buildActionLabels(): List<String> {
        val labels = mutableListOf(
            getString(R.string.nfc_action_enable),
            getString(R.string.nfc_action_disable),
            getString(R.string.nfc_action_toggle),
            getString(R.string.nfc_action_temp_disable),
            getString(R.string.nfc_action_temp_enable),
        )

        return labels
    }

    private val actionLabels: List<String>
        get() = buildActionLabels()

    private fun defaultActionLabel(): String = getString(R.string.nfc_action_toggle)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun isNfcTagWritingLocked(): Boolean {
        return EditingLockGuard.isLocked(this) &&
            !AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(this)
    }

    private fun openSystemNfcSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_NFC_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) {
                return
            }
        }
    }

    private fun openProtectionControls() {
        startActivity(
            Intent(this, ToggleOptionsActivity::class.java).apply {
                putExtra(
                    ToggleOptionsActivity.EXTRA_SCROLL_TO_SECTION,
                    ToggleOptionsActivity.SECTION_BLOCKING
                )
            }
        )
    }

    private fun updateWriteLockState() {
        val locked = isNfcTagWritingLocked()
        val nfcSystemEnabled = nfcAdapter?.isEnabled == true
        val controlsEnabled = !locked && nfcSystemEnabled

        ddProfile.isEnabled = controlsEnabled
        ddAction.isEnabled = controlsEnabled
        ddTime.isEnabled = controlsEnabled
        tilProfile.isEnabled = controlsEnabled
        tilAction.isEnabled = controlsEnabled
        tilTime.isEnabled = controlsEnabled
        btnArmWrite.isEnabled = true
        btnArmWrite.alpha = if (controlsEnabled) 1f else 0.72f
        btnArmWrite.text = when {
            locked -> getString(R.string.nfc_write_locked_button)
            !nfcSystemEnabled -> getString(R.string.nfc_system_disabled_button)
            else -> getString(R.string.nfc_arm_write)
        }

        if (!nfcSystemEnabled) {
            statusRow.isVisible = true
            statusRow.alpha = 1f
            statusProgress.isVisible = false
            tvStatus.text = getString(R.string.nfc_system_disabled_status)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            statusRow.setOnClickListener { openSystemNfcSettings() }
            btnArmWrite.setOnClickListener { openSystemNfcSettings() }
        } else if (locked) {
            statusRow.isVisible = true
            statusRow.alpha = 1f
            statusProgress.isVisible = false
            tvStatus.text = getString(R.string.nfc_write_locked_status)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            statusRow.setOnClickListener { openProtectionControls() }
            btnArmWrite.setOnClickListener {
                Toast.makeText(this, R.string.nfc_write_locked_while_enabled, Toast.LENGTH_SHORT).show()
                openProtectionControls()
            }
        } else {
            statusRow.setOnClickListener(null)
            btnArmWrite.setOnClickListener { buildUriForSelected() }
            val statusText = tvStatus.text?.toString().orEmpty()
            val staleSystemOrLockWarning = statusText == getString(R.string.nfc_write_locked_status) ||
                statusText == getString(R.string.nfc_system_disabled_status)
            if (!statusProgress.isVisible && staleSystemOrLockWarning) {
                statusRow.isVisible = false
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_write)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_available), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        tilProfile = findViewById(R.id.tilProfile)
        tilAction = findViewById(R.id.tilAction)
        tilTime = findViewById(R.id.tilTime)
        ddProfile = findViewById(R.id.ddProfile)
        ddAction = findViewById(R.id.ddAction)
        ddTime = findViewById(R.id.ddTime)
        tvActionHint = findViewById(R.id.tvActionHint)
        btnArmWrite = findViewById(R.id.btnArmWrite)
        statusRow = findViewById(R.id.statusRow)
        tvStatus = findViewById(R.id.tvStatus)
        statusProgress = findViewById(R.id.statusProgress)

        // Give the status row a "card" look without adding any background/stroke drawable resources.
        applyStatusRowChrome()

        // Button tinted with the accent color
        btnArmWrite.backgroundTintList = AccentColor.getActiveColor(this)

        // Text fields (dropdown outlines) accent tint
        tintTextFieldsWithAccent()

        rewriteUid = NfcTagUid.normalizeUidHex(intent.getStringExtra(EXTRA_REWRITE_UID))
        applyPreselectedDurationPreference()
        setupDropdowns()
        setupTimeDropdown()
        applyIntentSelection()

        // Always "Write" (flow is on its own screen now)
        btnArmWrite.text = getString(R.string.nfc_arm_write)
        // start hidden (KTX)
        statusRow.isVisible = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread { updateWriteLockState() }
                }
            }
        }

        updateWriteLockState()
    }

    private fun applyPreselectedDurationPreference() {
        val action = intent.getStringExtra(EXTRA_PRESELECT_ACTION).orEmpty()
        if (action != "temp_disable" && action != "temp_enable") {
            return
        }

        val value = if (intent.getBooleanExtra(EXTRA_PRESELECT_ASK_DURATION, false)) {
            TEMP_ASK_WHEN_SCANNED_VALUE
        } else {
            intent.getIntExtra(EXTRA_PRESELECT_DURATION_MINUTES, 10)
                .coerceIn(1, 1440)
                .toString()
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putString("pref_nfc_unlock_minutes", value)
        }
    }

    private fun applyIntentSelection() {
        val requestedProfile = intent.getStringExtra(EXTRA_PRESELECT_PROFILE)?.trim().orEmpty()
        if (requestedProfile.isNotBlank() && ProfileStore.getProfiles(this).contains(requestedProfile)) {
            ddProfile.setText(requestedProfile, false)
        }

        val requestedAction = intent.getStringExtra(EXTRA_PRESELECT_ACTION).orEmpty()
        val actionLabel = when (requestedAction) {
            "enable" -> getString(R.string.nfc_action_enable)
            "disable" -> getString(R.string.nfc_action_disable)
            "toggle" -> getString(R.string.nfc_action_toggle)
            "temp_disable" -> getString(R.string.nfc_action_temp_disable)
            "temp_enable" -> getString(R.string.nfc_action_temp_enable)
            else -> null
        }
        if (actionLabel != null && actionLabels.contains(actionLabel)) {
            ddAction.setText(actionLabel, false)
            updateTimeVisibilityForAction(actionLabel)
            updateActionHintForSelection(actionLabel)
        }
    }

    private fun applyStatusRowChrome() {
        // Rounded container + subtle stroke, via runtime MaterialShapeDrawable (no XML drawable resources).
        val density = resources.displayMetrics.density
        val radius = 16f * density
        val strokeWidth = 1f * density

        val shapeModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(radius)
            .build()

        val fill = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0)
        )

        val stroke = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutlineVariant,
            ContextCompat.getColor(this, R.color.status_border)
        )

        val bg = MaterialShapeDrawable(shapeModel).apply {
            fillColor = ColorStateList.valueOf(fill)
            setStroke(strokeWidth, stroke)
            elevation = 2f * density
        }

        statusRow.background = bg
        statusRow.clipToOutline = true
    }

    override fun onResume() {
        super.onResume()
        refreshActionDropdown(keepCurrentSelection = true)
        updateWriteLockState()
    }

    private fun tintTextFieldsWithAccent() {
        val accentInt = AccentColor.getAccentColorInt(this)
        val accentTint = AccentColor.getActiveColor(this)
        listOf(tilProfile, tilAction, tilTime).forEach { til ->
            til.boxStrokeColor = accentInt
            til.hintTextColor = accentTint
            til.setEndIconTintList(accentTint)
        }
    }

    private fun setupDropdowns() {
        val profilesFromStore = ProfileStore.getProfiles(this).toList().sorted()
        val noneLabel = getString(R.string.nfc_profile_none)

        val profileEntries = mutableListOf<String>().apply {
            add(noneLabel)
            addAll(profilesFromStore)
        }

        ddProfile.setAdapter(
            SwitchlyDropdownAdapter(this, profileEntries)
        )
        ddProfile.setText(noneLabel, false)

        refreshActionDropdown(keepCurrentSelection = false)

        ddAction.setOnItemClickListener { _, _, position, _ ->
            val adapter = ddAction.adapter
            val selected = when {
                adapter != null && position in 0 until adapter.count ->
                    adapter.getItem(position)?.toString().orEmpty()
                else -> ddAction.text?.toString().orEmpty()
            }
            updateTimeVisibilityForAction(selected)
            updateActionHintForSelection(selected)
        }

        // keep hint state consistent when profile changes
        ddProfile.setOnItemClickListener { _, _, _, _ ->
            val selected = ddAction.text?.toString().orEmpty()
            updateTimeVisibilityForAction(selected)
            updateActionHintForSelection(selected)
        }
    }

    private fun refreshActionDropdown(keepCurrentSelection: Boolean = true) {
        val selectedNow = ddAction.text?.toString()?.trim().orEmpty()
        val availableActions = actionLabels

        ddAction.setAdapter(
            SwitchlyDropdownAdapter(this, availableActions)
        )

        val selectedToApply = if (keepCurrentSelection && availableActions.contains(selectedNow)) {
            selectedNow
        } else {
            defaultActionLabel().takeIf { availableActions.contains(it) }
                ?: availableActions.firstOrNull().orEmpty()
        }

        ddAction.setText(selectedToApply, false)
        updateTimeVisibilityForAction(selectedToApply)
        updateActionHintForSelection(selectedToApply)
    }

    private fun setupTimeDropdown() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isPremium = PremiumManager.isPremium(this)

        val entries = mutableListOf<String>()
        val values = mutableListOf<String>()

        entries += listOf(
            getString(R.string.nfc_time_5_min),
            getString(R.string.nfc_time_10_min),
            getString(R.string.nfc_time_15_min),
            getString(R.string.nfc_time_20_min),
            getString(R.string.nfc_time_25_min),
            getString(R.string.nfc_time_30_min),
        )
        values += listOf("5", "10", "15", "20", "25", "30")

        if (isPremium) {
            entries += getString(R.string.nfc_time_custom)
            values += "custom"
            entries += getString(R.string.nfc_time_ask_when_scanned)
            values += TEMP_ASK_WHEN_SCANNED_VALUE
        }

        ddTime.setAdapter(SwitchlyDropdownAdapter(this, entries))

        val savedRaw = prefs.getString("pref_nfc_unlock_minutes", "10").orEmpty()
        val idx = values.indexOf(savedRaw)
        if (idx >= 0) {
            ddTime.setText(entries[idx], false)
        } else {
            val saved = savedRaw.toIntOrNull() ?: 10
            ddTime.setText(
                resources.getQuantityString(R.plurals.nfc_time_custom_label, saved, saved),
                false
            )
        }

        ddTime.setOnItemClickListener { _, _, position, _ ->
            val selected = values.getOrNull(position) ?: return@setOnItemClickListener
            if (isPremium && selected == "custom") {
                showCustomTimeDialog()
            } else {
                prefs.edit { putString("pref_nfc_unlock_minutes", selected) }
                val selectedAction = ddAction.text?.toString().orEmpty()
                updateTimeVisibilityForAction(selectedAction)
                updateActionHintForSelection(selectedAction)
            }
        }
    }

    private fun showCustomTimeDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedMinutes = prefs.getString("pref_nfc_unlock_minutes", "10")
            ?.toIntOrNull()
            ?.takeIf { it in 1..1440 }

        val accent = AccentColor.getAccentColorInt(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.temp_enable_duration_custom_hint)
            isSingleLine = true
            savedMinutes?.let { setText(String.format(Locale.ROOT, "%d", it)) }
            setSelectAllOnFocus(true)
            backgroundTintList = ColorStateList.valueOf(accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun presetPill(minutes: Int): TextView {
            return TextView(this).apply {
                text = getString(R.string.temp_duration_preset_minutes, minutes)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(accent)
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), accent)
                }
                setOnClickListener {
                    input.setText(String.format(Locale.getDefault(), "%d", minutes))
                    input.setSelection(input.text?.length ?: 0)
                }
            }
        }

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(5, 10, 15, 30, 60).forEachIndexed { index, minutes ->
            presetRow.addView(
                presetPill(minutes),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(4)
                }
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(TextView(this@NfcWriterActivity).apply {
                text = getString(R.string.nfc_time_custom_message)
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            })
            addView(TextView(this@NfcWriterActivity).apply {
                text = getString(R.string.temp_duration_quick_presets)
                textSize = 12.5f
                alpha = 0.74f
                setPadding(0, dp(9), 0, dp(4))
            })
            addView(presetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nfc_time_custom_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .showAccented()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mins = input.text?.toString()?.trim()?.toIntOrNull()
            if (mins == null || mins !in 1..1440) {
                Toast.makeText(this, R.string.nfc_time_custom_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit { putString("pref_nfc_unlock_minutes", mins.toString()) }
            ddTime.setText(
                resources.getQuantityString(R.plurals.nfc_time_custom_label, mins, mins),
                false
            )
            val selectedAction = ddAction.text?.toString().orEmpty()
            updateTimeVisibilityForAction(selectedAction)
            updateActionHintForSelection(selectedAction)
            dialog.dismiss()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun updateTimeVisibilityForAction(selectedActionLabel: String) {
        val isTempDisable = selectedActionLabel == getString(R.string.nfc_action_temp_disable)
        val isTempEnable = selectedActionLabel == getString(R.string.nfc_action_temp_enable)
        tilTime.isVisible = isTempDisable || isTempEnable
    }

    private fun updateActionHintForSelection(selectedActionLabel: String) {
        val noneLabel = getString(R.string.nfc_profile_none)
        val selectedProfile = ddProfile.text?.toString()?.trim().orEmpty()
        val isProfileSelected = selectedProfile.isNotEmpty() && selectedProfile != noneLabel
        val tempMinutes = selectedTempMinutes()

        val base = when (selectedActionLabel) {
            getString(R.string.nfc_action_enable) -> getString(R.string.nfc_action_desc_enable)
            getString(R.string.nfc_action_disable) -> getString(R.string.nfc_action_desc_disable)
            getString(R.string.nfc_action_toggle) -> getString(R.string.nfc_action_desc_toggle)
            getString(R.string.nfc_action_temp_disable) ->
                if (isAskWhenScannedSelected()) {
                    getString(R.string.nfc_action_desc_temp_disable_ask)
                } else {
                    resources.getQuantityString(
                        R.plurals.nfc_action_desc_temp_disable,
                        tempMinutes,
                        tempMinutes
                    )
                }
            getString(R.string.nfc_action_temp_enable) ->
                if (isAskWhenScannedSelected()) {
                    getString(R.string.nfc_action_desc_temp_enable_ask)
                } else {
                    resources.getQuantityString(
                        R.plurals.nfc_action_desc_temp_enable,
                        tempMinutes,
                        tempMinutes
                    )
                }
            getString(R.string.nfc_action_pair_uid) -> {
                val enabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
                if (enabled) getString(R.string.nfc_action_desc_pair_uid) else getString(R.string.nfc_action_desc_pair_uid_disabled)
            }
            else -> getString(R.string.nfc_action_hint_default)
        }

        val profileLine = if (isProfileSelected) {
            getString(R.string.nfc_action_desc_profile_selected, selectedProfile)
        } else {
            getString(R.string.nfc_action_desc_profile_none)
        }
        tvActionHint.text = getString(R.string.nfc_action_hint_with_profile, base, profileLine)
    }

    private fun isAskWhenScannedSelected(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString("pref_nfc_unlock_minutes", "10") == TEMP_ASK_WHEN_SCANNED_VALUE
    }

    private fun selectedTempMinutes(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString("pref_nfc_unlock_minutes", "10")?.toIntOrNull()?.coerceIn(1, 1440) ?: 10
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_nfc_writer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_info -> {
                showActionInfoDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showActionInfoDialog() {
        val bodyView = TextView(this).apply {
            text = buildActionInfoBody()
            textSize = 14f
            setLineSpacing(0f, 1.18f)
        }

        val scroll = ScrollView(this).apply {
            val padH = (20 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(
                bodyView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.nfc_action_info_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    private fun buildActionInfoBody(): CharSequence {
        val sb = SpannableStringBuilder()

        val introTitleStart = sb.length
        sb.append(getString(R.string.nfc_write_intro_title)).append("\n")
        sb.setSpan(
            StyleSpan(Typeface.BOLD),
            introTitleStart,
            sb.length - 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.append(getString(R.string.nfc_write_onboarding_message)).append("\n\n")

        fun addItem(title: String, desc: String) {
            val titleStart = sb.length
            sb.append("• ").append(title).append("\n")
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                titleStart + 2,
                titleStart + 2 + title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.append("  ").append(desc.trim()).append("\n\n")
        }

        val sampleMinutes = 10

        addItem(
            getString(R.string.nfc_action_enable),
            getString(R.string.nfc_action_desc_enable),
        )
        addItem(
            getString(R.string.nfc_action_disable),
            getString(R.string.nfc_action_desc_disable),
        )
        addItem(
            getString(R.string.nfc_action_toggle),
            getString(R.string.nfc_action_desc_toggle),
        )
        addItem(
            getString(R.string.nfc_action_temp_disable),
            resources.getQuantityString(
                R.plurals.nfc_action_desc_temp_disable,
                sampleMinutes,
                sampleMinutes,
            ) + "\n" + getString(R.string.nfc_action_desc_temp_disable_ask),
        )
        addItem(
            getString(R.string.nfc_action_temp_enable),
            resources.getQuantityString(
                R.plurals.nfc_action_desc_temp_enable,
                sampleMinutes,
                sampleMinutes,
            ) + "\n" + getString(R.string.nfc_action_desc_temp_enable_ask),
        )

        sb.append(getString(R.string.pref_limit_temp_disable_tags_summary))

        return sb
    }

    private fun buildUriForSelected() {
        if (isNfcTagWritingLocked()) {
            Toast.makeText(this, R.string.nfc_write_locked_while_enabled, Toast.LENGTH_SHORT).show()
            openProtectionControls()
            return
        }

        val selectedActionLabel = ddAction.text?.toString()?.trim().orEmpty()

        // UID-only pairing mode (supports read-only/non-NDEF tags)
        if (selectedActionLabel == getString(R.string.nfc_action_pair_uid)) {
            val pairedTagsEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
            if (!pairedTagsEnabled) {
                Toast.makeText(this, R.string.nfc_action_desc_pair_uid_disabled, Toast.LENGTH_LONG).show()
                openProtectionControls()
                return
            }
            val i = Intent(this, NfcWriteWaitingActivity::class.java).apply {
                putExtra(NfcWriteWaitingActivity.EXTRA_MODE, NfcWriteWaitingActivity.MODE_PAIR_UID_READONLY)
            }
            writeFlowLauncher.launch(i)
            return
        }

        val selectedProfile = ddProfile.text?.toString()?.trim().orEmpty()
        val noneLabel = getString(R.string.nfc_profile_none)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val tempMinutesRaw = prefs.getString("pref_nfc_unlock_minutes", "10").orEmpty()
        val askWhenScanned = tempMinutesRaw == TEMP_ASK_WHEN_SCANNED_VALUE
        val tempMinutes: Int? =
            if (
                selectedActionLabel == getString(R.string.nfc_action_temp_disable) ||
                selectedActionLabel == getString(R.string.nfc_action_temp_enable)
            ) {
                tempMinutesRaw.toIntOrNull()
            } else null

        val actionVerb = when {
            selectedActionLabel == getString(R.string.nfc_action_enable) -> "enable"
            selectedActionLabel == getString(R.string.nfc_action_disable) -> "disable"
            selectedActionLabel == getString(R.string.nfc_action_toggle) -> "toggle"
            selectedActionLabel == getString(R.string.nfc_action_temp_disable) && askWhenScanned -> "temp_disable"
            selectedActionLabel == getString(R.string.nfc_action_temp_disable) -> "temp_disable${(tempMinutes ?: 10).coerceIn(1, 1440)}"
            selectedActionLabel == getString(R.string.nfc_action_temp_enable) && askWhenScanned -> "temp_enable"
            selectedActionLabel == getString(R.string.nfc_action_temp_enable) -> "temp_enable${(tempMinutes ?: 10).coerceIn(1, 1440)}"
            else -> "toggle"
        }

        val forceGlobalAction = false
        val isProfile = !forceGlobalAction && selectedProfile.isNotEmpty() && selectedProfile != noneLabel
        val isTempAction =
            selectedActionLabel == getString(R.string.nfc_action_temp_disable) ||
                selectedActionLabel == getString(R.string.nfc_action_temp_enable)

        if (isProfile && isTempAction) {
            val msgRes =
                if (
                    selectedActionLabel == getString(R.string.nfc_action_temp_disable)
                ) {
                    R.string.nfc_temp_hint_profile_disable_toast
                } else {
                    R.string.nfc_temp_hint_profile_enable_toast
                }
            Toast.makeText(this, msgRes, Toast.LENGTH_LONG).show()
        }

        val uri = if (isProfile) {
            NfcSchema.uriForProfileAction(selectedProfile, actionVerb)
        } else {
            NfcSchema.uriForGlobalAction(actionVerb)
        }
        val fallbackAction = when (selectedActionLabel) {
            getString(R.string.nfc_action_enable) -> "enable"
            getString(R.string.nfc_action_disable) -> "disable"
            getString(R.string.nfc_action_temp_disable) -> "temp_disable"
            getString(R.string.nfc_action_temp_enable) -> "temp_enable"
            else -> "toggle"
        }

        val i = Intent(this, NfcWriteWaitingActivity::class.java).apply {
            putExtra(NfcWriteWaitingActivity.EXTRA_MODE, NfcWriteWaitingActivity.MODE_WRITE_URI)
            putExtra(NfcWriteWaitingActivity.EXTRA_URI_TO_WRITE, uri)
            putExtra(NfcWriteWaitingActivity.EXTRA_FALLBACK_ACTION, fallbackAction)
            if (isProfile) {
                putExtra(NfcWriteWaitingActivity.EXTRA_FALLBACK_PROFILE, selectedProfile)
            }
            if (isTempAction) {
                putExtra(
                    NfcWriteWaitingActivity.EXTRA_FALLBACK_DURATION_MINUTES,
                    (tempMinutes ?: 10).coerceIn(1, 1440),
                )
                putExtra(NfcWriteWaitingActivity.EXTRA_FALLBACK_ASK_DURATION, askWhenScanned)
            }
            if (rewriteUid.isNotBlank()) {
                putExtra(NfcWriteWaitingActivity.EXTRA_EXPECTED_UID, rewriteUid)
            }
        }
        writeFlowLauncher.launch(i)
    }
}
