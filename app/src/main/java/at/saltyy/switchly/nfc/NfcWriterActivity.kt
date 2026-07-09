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
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.FrameLayout
import android.widget.ImageButton
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
import androidx.core.view.ViewCompat
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
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyStoreLinks
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlinx.coroutines.launch

class NfcWriterActivity : AppCompatActivity() {

    private enum class WriteResult {
        OK,
        TOO_SMALL,
        NOT_WRITABLE,
        FAILED
    }

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
    private lateinit var statusRow: android.view.View
    private lateinit var tvStatus: TextView
    private lateinit var statusProgress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        const val TEMP_ASK_WHEN_SCANNED_VALUE = "ask"
    }

    private fun buildActionLabels(): List<String> {
        val labels = mutableListOf(
            getString(R.string.nfc_action_enable),
            getString(R.string.nfc_action_disable),
            getString(R.string.nfc_action_toggle),
            getString(R.string.nfc_action_temp_disable),
            getString(R.string.nfc_action_temp_enable),
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Re-entry is meaningful only when Switchly settings access is locked while protection is active.
        val showReentry = prefs.getBoolean(BlockingToggleKeys.KEY_LOCK_SWITCHLY_APP_ACCESS, false)
        if (showReentry) {
            labels += getString(R.string.nfc_action_reentry)
        }

        return labels
    }

    private val actionLabels: List<String>
        get() = buildActionLabels()

    private fun defaultActionLabel(): String = getString(R.string.nfc_action_toggle)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun isNfcTagWritingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this) &&
            !AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(this)
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

        ddProfile.isEnabled = !locked
        ddAction.isEnabled = !locked
        ddTime.isEnabled = !locked
        tilProfile.isEnabled = !locked
        tilAction.isEnabled = !locked
        tilTime.isEnabled = !locked
        btnArmWrite.isEnabled = !locked
        btnArmWrite.alpha = if (locked) 0.6f else 1f
        btnArmWrite.text = if (locked) {
            getString(R.string.nfc_write_locked_button)
        } else {
            getString(R.string.nfc_arm_write)
        }

        if (locked) {
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
            if (!statusProgress.isVisible && tvStatus.text?.toString() == getString(R.string.nfc_write_locked_status)) {
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

        setupDropdowns()
        setupTimeDropdown()

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
        val isReentry = selectedActionLabel == getString(R.string.nfc_action_reentry)
        val isTemp = isTempDisable || isTempEnable || isReentry

        if (isReentry && isAskWhenScannedSelected()) {
            PreferenceManager.getDefaultSharedPreferences(this).edit {
                putString("pref_nfc_unlock_minutes", "10")
            }
            ddTime.setText(getString(R.string.nfc_time_10_min), false)
        }

        tilTime.isVisible = isTemp
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
            getString(R.string.nfc_action_reentry) ->
                resources.getQuantityString(
                    R.plurals.nfc_action_desc_reentry,
                    tempMinutes,
                    tempMinutes
                )
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
            .setNeutralButton(R.string.store_open) { _, _ -> SwitchlyStoreLinks.openStore(this) }
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    private fun buildActionInfoBody(): CharSequence {
        val sb = SpannableStringBuilder()

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

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val showReentry = prefs.getBoolean(BlockingToggleKeys.KEY_LOCK_SWITCHLY_APP_ACCESS, false)
        if (showReentry) {
            addItem(
                getString(R.string.nfc_action_reentry),
                resources.getQuantityString(
                    R.plurals.nfc_action_desc_reentry,
                    sampleMinutes,
                    sampleMinutes,
                ),
            )
        }

        addItem(
            getString(R.string.store_card_title),
            getString(R.string.store_card_summary),
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
                selectedActionLabel == getString(R.string.nfc_action_temp_enable) ||
                selectedActionLabel == getString(R.string.nfc_action_reentry)
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
            selectedActionLabel == getString(R.string.nfc_action_reentry) -> "reentry${(tempMinutes ?: 10).coerceIn(1, 1440)}"
            else -> "toggle"
        }

        val forceGlobalAction = false
        val isProfile = !forceGlobalAction && selectedProfile.isNotEmpty() && selectedProfile != noneLabel
        val isTempAction =
            selectedActionLabel == getString(R.string.nfc_action_temp_disable) ||
                selectedActionLabel == getString(R.string.nfc_action_temp_enable) ||
                selectedActionLabel == getString(R.string.nfc_action_reentry)

        if (isProfile && isTempAction) {
            val msgRes =
                if (
                    selectedActionLabel == getString(R.string.nfc_action_temp_disable) ||
                    selectedActionLabel == getString(R.string.nfc_action_reentry)
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

        val i = Intent(this, NfcWriteWaitingActivity::class.java).apply {
            putExtra(NfcWriteWaitingActivity.EXTRA_MODE, NfcWriteWaitingActivity.MODE_WRITE_URI)
            putExtra(NfcWriteWaitingActivity.EXTRA_URI_TO_WRITE, uri)
        }
        writeFlowLauncher.launch(i)
    }

    private fun showPairMetaPrompt(uid: String) {
        val v = layoutInflater.inflate(R.layout.dialog_paired_tag_pair_meta, FrameLayout(this), false)
        v.findViewById<TextView>(R.id.tvUid).text = uid

        val etName = v.findViewById<TextInputEditText>(R.id.etTagName)
        val etNote = v.findViewById<TextInputEditText>(R.id.etTagNote)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.paired_tag_pair_prompt_title))
            .setMessage(getString(R.string.paired_tag_pair_prompt_message))
            .setView(v)
            .setPositiveButton(getString(R.string.paired_tag_pair_prompt_save)) { _, _ ->
                at.saltyy.switchly.data.prefs.NfcUidPairingStore.setTagMeta(
                    this,
                    uid,
                    etName.text?.toString(),
                    etNote.text?.toString()
                )
            }
            .setNegativeButton(getString(R.string.paired_tag_pair_prompt_skip), null)
            .showAccented()
    }

    private fun writeUriToTag(uriString: String, tag: Tag): WriteResult {
        var ndef: Ndef? = null
        var formatable: NdefFormatable? = null
        return try {
            val uriRecord = NdefRecord.createUri(uriString)
            val appRecord = NdefRecord.createApplicationRecord(packageName)
            val uriOnlyMessage = NdefMessage(arrayOf(uriRecord))
            val preferredMessage = NdefMessage(arrayOf(uriRecord, appRecord))

            val ndefTech = Ndef.get(tag)
            if (ndefTech != null) {
                ndef = ndefTech
                ndef.connect()

                if (!ndef.isWritable) {
                    return WriteResult.NOT_WRITABLE
                }

                val message = when {
                    preferredMessage.toByteArray().size <= ndef.maxSize -> preferredMessage
                    uriOnlyMessage.toByteArray().size <= ndef.maxSize -> uriOnlyMessage
                    else -> return WriteResult.TOO_SMALL
                }

                ndef.writeNdefMessage(message)
                WriteResult.OK
            } else {
                val formatableTech = NdefFormatable.get(tag) ?: return WriteResult.FAILED
                formatable = formatableTech
                formatable.connect()

                runCatching {
                    formatable.format(preferredMessage)
                }.recoverCatching {
                    formatable.format(uriOnlyMessage)
                }.getOrThrow()

                WriteResult.OK
            }
        } catch (_: Throwable) {
            WriteResult.FAILED
        } finally {
            runCatching { ndef?.close() }
            runCatching { formatable?.close() }
        }
    }
}
