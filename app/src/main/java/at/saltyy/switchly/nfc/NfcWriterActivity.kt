package at.saltyy.switchly.nfc

import android.animation.ObjectAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.textfield.TextInputEditText
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputLayout

class NfcWriterActivity : AppCompatActivity() {

    companion object {
        private const val MENU_INFO_ACTIONS = 1001
    }

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
                    getString(R.string.nfc_pair_ok_with_uid, uid)
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
    private lateinit var tvTempHint: TextView
    private lateinit var tvActionHint: TextView
    private lateinit var btnActionInfo: ImageButton
    private lateinit var btnArmWrite: Button
    private lateinit var statusRow: android.view.View
    private lateinit var tvStatus: TextView
    private lateinit var statusProgress: ProgressBar

    // keep the default hint text from XML so we can restore it (no missing resources)
    private var defaultTempHintText: CharSequence? = null

    private var pendingUriToWrite: String? = null
    private var pendingUidPairing: Boolean = false
    private var armed = false

    private val handler = Handler(Looper.getMainLooper())

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

        labels += getString(R.string.nfc_action_pair_uid)
        return labels
    }

    private val actionLabels: List<String>
        get() = buildActionLabels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
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
        setupInfoMenu(toolbar)

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
        tvTempHint = findViewById(R.id.tvTempHint)
        tvActionHint = findViewById(R.id.tvActionHint)
        btnActionInfo = findViewById(R.id.btnActionInfo)
        btnArmWrite = findViewById(R.id.btnArmWrite)
        statusRow = findViewById(R.id.statusRow)
        tvStatus = findViewById(R.id.tvStatus)
        statusProgress = findViewById(R.id.statusProgress)

        // Give the status row a "card" look without adding any background/stroke drawable resources.
        applyStatusRowChrome()

        // capture whatever text is set in XML as default
        defaultTempHintText = tvTempHint.text

        // Button tinted with the accent color
        btnArmWrite.backgroundTintList = AccentColor.getActiveColor(this)

        // Always-visible info button for action explanations
        btnActionInfo.imageTintList = AccentColor.getActiveColor(this)
        btnActionInfo.setOnClickListener { showActionInfoDialog() }

        // Text fields (dropdown outlines) accent tint
        tintTextFieldsWithAccent()

        setupDropdowns()
        setupTimeDropdown()

        btnArmWrite.setOnClickListener {
            buildUriForSelected()
        }

        // Always "Write" (flow is on its own screen now)
        btnArmWrite.text = getString(R.string.nfc_arm_write)
        // start hidden (KTX)
        statusRow.isVisible = false
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
            fillColor = android.content.res.ColorStateList.valueOf(fill)
            setStroke(strokeWidth, stroke)
            elevation = 2f * density
        }

        ViewCompat.setBackground(statusRow, bg)
        statusRow.clipToOutline = true
    }

    override fun onResume() {
        super.onResume()

        val adapter = nfcAdapter ?: return
        val intent = Intent(this, this::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        refreshActionDropdown(keepCurrentSelection = true)
    }

    override fun onPause() {
        // Call disableForegroundDispatch() before super to avoid IllegalStateException on
        // newer Android versions when the activity is already past RESUMED.
        runCatching {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                nfcAdapter?.disableForegroundDispatch(this)
            }
        }
        super.onPause()
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
            ArrayAdapter(this, android.R.layout.simple_list_item_1, profileEntries)
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
            ArrayAdapter(this, android.R.layout.simple_list_item_1, availableActions)
        )

        val selectedToApply = if (keepCurrentSelection && availableActions.contains(selectedNow)) {
            selectedNow
        } else {
            availableActions.firstOrNull().orEmpty()
        }

        ddAction.setText(selectedToApply, false)
        updateTimeVisibilityForAction(selectedToApply)
        updateActionHintForSelection(selectedToApply)
    }

    private fun setupTimeDropdown() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isPremium = PremiumManager.isPremium(this)

        val entries = mutableListOf<String>()
        val values = mutableListOf<Int>()

        if (isPremium) {
            entries += listOf(
                getString(R.string.nfc_time_5_min),
                getString(R.string.nfc_time_10_min),
                getString(R.string.nfc_time_15_min),
                getString(R.string.nfc_time_20_min),
                getString(R.string.nfc_time_25_min),
                getString(R.string.nfc_time_30_min),
                getString(R.string.nfc_time_custom)
            )
            values += listOf(5, 10, 15, 20, 25, 30, -1)
        } else {
            entries += listOf(
                getString(R.string.nfc_time_5_min),
                getString(R.string.nfc_time_10_min),
                getString(R.string.nfc_time_15_min),
                getString(R.string.nfc_time_20_min),
                getString(R.string.nfc_time_25_min),
                getString(R.string.nfc_time_30_min),
            )
            values += listOf(5, 10, 15, 20, 25, 30)
        }

        ddTime.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, entries))

        val saved = prefs.getString("pref_nfc_unlock_minutes", "10")?.toIntOrNull() ?: 10
        val idx = values.indexOf(saved)
        if (idx >= 0) {
            ddTime.setText(entries[idx], false)
        } else {
            ddTime.setText(
                resources.getQuantityString(R.plurals.nfc_time_custom_label, saved, saved),
                false
            )
        }

        ddTime.setOnItemClickListener { _, _, position, _ ->
            if (isPremium && position == entries.lastIndex) {
                showCustomTimeDialog()
            } else {
                val mins = values[position]
                prefs.edit { putString("pref_nfc_unlock_minutes", mins.toString()) }
                updateActionHintForSelection(ddAction.text?.toString().orEmpty())
            }
        }
    }

    private fun showCustomTimeDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.nfc_time_custom_placeholder)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding/2, padding, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.nfc_time_custom_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val mins = input.text?.toString()?.trim()?.toIntOrNull()
                if (mins == null || mins < 1 || mins > 120) {
                    Toast.makeText(this, R.string.nfc_time_custom_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit { putString("pref_nfc_unlock_minutes", mins.toString()) }
                    ddTime.setText(
                        resources.getQuantityString(R.plurals.nfc_time_custom_label, mins, mins),
                        false
                    )
                    updateActionHintForSelection(ddAction.text?.toString().orEmpty())
                }
            }
            .showAccented()
    }

    private fun updateTimeVisibilityForAction(selectedActionLabel: String) {
        val isTempDisable = selectedActionLabel == getString(R.string.nfc_action_temp_disable)
        val isTempEnable = selectedActionLabel == getString(R.string.nfc_action_temp_enable)
        val isReentry = selectedActionLabel == getString(R.string.nfc_action_reentry)
        val isTemp = isTempDisable || isTempEnable || isReentry

        // KTX
        tilTime.isVisible = isTemp
        tvTempHint.isVisible = isTemp

        if (!isTemp) return

        val noneLabel = getString(R.string.nfc_profile_none)
        val profile = ddProfile.text?.toString()?.trim().orEmpty()
        val isProfileSelected = profile.isNotEmpty() && profile != noneLabel

        tvTempHint.text = when {
            isProfileSelected -> {
                if (isTempDisable || isReentry) {
                    getString(R.string.nfc_temp_hint_profile_disable)
                } else {
                    getString(R.string.nfc_temp_hint_profile_enable)
                }
            }
            else -> {
                // restore whatever hint was originally in XML
                defaultTempHintText ?: ""
            }
        }
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
                resources.getQuantityString(
                    R.plurals.nfc_action_desc_temp_disable,
                    tempMinutes,
                    tempMinutes
                )
            getString(R.string.nfc_action_temp_enable) ->
                resources.getQuantityString(
                    R.plurals.nfc_action_desc_temp_enable,
                    tempMinutes,
                    tempMinutes
                )
            getString(R.string.nfc_action_reentry) ->
                resources.getQuantityString(
                    R.plurals.nfc_action_desc_reentry,
                    tempMinutes,
                    tempMinutes
                )
            getString(R.string.nfc_action_pair_uid) -> {
                val enabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(at.saltyy.switchly.data.prefs.BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
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

    private fun selectedTempMinutes(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString("pref_nfc_unlock_minutes", "10")?.toIntOrNull()?.coerceIn(1, 120) ?: 10
    }

    private fun setupInfoMenu(toolbar: MaterialToolbar) {
        val menu = toolbar.menu
        menu.add(Menu.NONE, MENU_INFO_ACTIONS, Menu.NONE, R.string.nfc_action_info_title)
            .setIcon(R.drawable.info_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_INFO_ACTIONS) {
                showActionInfoDialog()
                true
            } else {
                false
            }
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
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
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
            ),
        )
        addItem(
            getString(R.string.nfc_action_temp_enable),
            resources.getQuantityString(
                R.plurals.nfc_action_desc_temp_enable,
                sampleMinutes,
                sampleMinutes,
            ),
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
            getString(R.string.nfc_action_pair_uid),
            getString(R.string.nfc_action_desc_pair_uid),
        )

        sb.append(getString(R.string.pref_enable_reentry_in_write_summary)).append("\n")
        sb.append(getString(R.string.pref_limit_temp_disable_tags_summary))

        return sb
    }


    private fun buildUriForSelected() {
        val selectedActionLabel = ddAction.text?.toString()?.trim().orEmpty()

        // UID-only pairing mode (supports read-only/non-NDEF tags)
        if (selectedActionLabel == getString(R.string.nfc_action_pair_uid)) {
            val i = Intent(this, NfcWriteWaitingActivity::class.java).apply {
                putExtra(NfcWriteWaitingActivity.EXTRA_MODE, NfcWriteWaitingActivity.MODE_PAIR_UID)
            }
            writeFlowLauncher.launch(i)
            return
        }
        
        val selectedProfile = ddProfile.text?.toString()?.trim().orEmpty()
        val noneLabel = getString(R.string.nfc_profile_none)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val tempMinutes: Int? =
            if (
                selectedActionLabel == getString(R.string.nfc_action_temp_disable) ||
                selectedActionLabel == getString(R.string.nfc_action_temp_enable) ||
                selectedActionLabel == getString(R.string.nfc_action_reentry)
            ) {
                prefs.getString("pref_nfc_unlock_minutes", "10")?.toIntOrNull()
            } else null

        val actionVerb = when {
            selectedActionLabel == getString(R.string.nfc_action_enable) -> "enable"
            selectedActionLabel == getString(R.string.nfc_action_disable) -> "disable"
            selectedActionLabel == getString(R.string.nfc_action_toggle) -> "toggle"
            selectedActionLabel == getString(R.string.nfc_action_temp_disable) -> "temp_disable${(tempMinutes ?: 10).coerceIn(1, 120)}"
            selectedActionLabel == getString(R.string.nfc_action_temp_enable) -> "temp_enable${(tempMinutes ?: 10).coerceIn(1, 120)}"
            selectedActionLabel == getString(R.string.nfc_action_reentry) -> "reentry${(tempMinutes ?: 10).coerceIn(1, 120)}"
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

    private fun arm() {
        armed = true
        statusRow.isVisible = true
        statusRow.alpha = 1f

        statusProgress.isIndeterminate = true
        statusProgress.isVisible = true

        tvStatus.text = getString(R.string.nfc_waiting_tag)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))

        btnArmWrite.text = getString(R.string.nfc_cancel_write)
    }

    private fun disarm(hideRow: Boolean) {
        armed = false
        pendingUriToWrite = null
        pendingUidPairing = false

        if (hideRow) statusRow.isVisible = false

        statusProgress.isVisible = false
        btnArmWrite.text = getString(R.string.nfc_arm_write)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!armed || intent == null) return

        val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tag == null) {
            Toast.makeText(this, getString(R.string.nfc_tag_error), Toast.LENGTH_SHORT).show()
            return
        }

        // UID-only pairing: just store the scanned tag UID (no writing).
        if (pendingUidPairing) {
            val uid = NfcTagUid.uidHex(tag)
            if (uid == null) {
                Toast.makeText(this, getString(R.string.nfc_pair_error), Toast.LENGTH_SHORT).show()
                return
            }

            val isNew = at.saltyy.switchly.data.prefs.NfcUidPairingStore.addPairedUidHex(this, uid)

            statusRow.isVisible = true
            statusProgress.isVisible = false
            tvStatus.text = getString(R.string.nfc_pair_ok_with_uid, uid)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ok))

            Toast.makeText(this, getString(R.string.nfc_pair_ok), Toast.LENGTH_SHORT).show()

            // Prompt for a friendly name/note only for newly paired tags.
            if (isNew) {
                showPairMetaPrompt(uid)
            }
            disarm(hideRow = false)
            return
        }

        val uriToWrite = pendingUriToWrite
        if (uriToWrite == null) {
            Toast.makeText(this, getString(R.string.nfc_no_data), Toast.LENGTH_SHORT).show()
            return
        }

        statusRow.isVisible = true
        statusProgress.isIndeterminate = true
        statusProgress.isVisible = true
        tvStatus.text = getString(R.string.nfc_writing)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))

        handler.post {
            val result = writeUriToTag(uriToWrite, tag)

            val okColor = ContextCompat.getColor(this, R.color.status_ok)
            val errorColor = ContextCompat.getColor(this, R.color.status_error)
            val neutralColor = ContextCompat.getColor(this, R.color.status_neutral)

            statusProgress.isVisible = false

            when (result) {
                WriteResult.OK -> {
                    tvStatus.text = getString(R.string.nfc_write_ok)
                    tvStatus.setTextColor(okColor)
                    Toast.makeText(this, getString(R.string.nfc_write_ok), Toast.LENGTH_SHORT).show()
                }
                WriteResult.TOO_SMALL -> {
                    tvStatus.text = getString(R.string.nfc_write_error_too_small)
                    tvStatus.setTextColor(errorColor)
                    Toast.makeText(this, getString(R.string.nfc_write_error_too_small), Toast.LENGTH_LONG).show()
                }
                WriteResult.NOT_WRITABLE,
                WriteResult.FAILED -> {
                    tvStatus.text = getString(R.string.nfc_write_error_generic)
                    tvStatus.setTextColor(errorColor)
                    Toast.makeText(this, getString(R.string.nfc_write_error_generic), Toast.LENGTH_SHORT).show()
                }
            }

            disarm(hideRow = false)

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
    }

    private fun showPairMetaPrompt(uid: String) {
        val v = layoutInflater.inflate(R.layout.dialog_paired_tag_pair_meta, null)
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
        return try {
            val uriRecord = NdefRecord.createUri(uriString)
            val message = NdefMessage(arrayOf(uriRecord))

            ndef = Ndef.get(tag) ?: return WriteResult.FAILED
            ndef.connect()

            if (!ndef.isWritable) {
                return WriteResult.NOT_WRITABLE
            }

            if (message.toByteArray().size > ndef.maxSize) {
                return WriteResult.TOO_SMALL
            }

            ndef.writeNdefMessage(message)
            WriteResult.OK
        } catch (_: Throwable) {
            WriteResult.FAILED
        } finally {
            runCatching { ndef?.close() }
        }
    }
}
