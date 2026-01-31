package at.saltyy.switchly.nfc

import android.animation.ObjectAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout

class NfcWriterActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null

    private lateinit var ddProfile: AutoCompleteTextView
    private lateinit var ddAction: AutoCompleteTextView
    private lateinit var ddTime: AutoCompleteTextView
    private lateinit var tilProfile: TextInputLayout
    private lateinit var tilAction: TextInputLayout
    private lateinit var tilTime: TextInputLayout
    private lateinit var tvTempHint: TextView
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

    private val actionLabels by lazy {
        listOf(
            getString(R.string.nfc_action_enable),
            getString(R.string.nfc_action_disable),
            getString(R.string.nfc_action_toggle),
            getString(R.string.nfc_action_temp_disable),
            getString(R.string.nfc_action_temp_enable),
            getString(R.string.nfc_action_pair_uid)
        )
    }

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
        btnArmWrite = findViewById(R.id.btnArmWrite)
        statusRow = findViewById(R.id.statusRow)
        tvStatus = findViewById(R.id.tvStatus)
        statusProgress = findViewById(R.id.statusProgress)

        // capture whatever text is set in XML as default
        defaultTempHintText = tvTempHint.text

        // Button tinted with the accent color
        btnArmWrite.backgroundTintList = AccentColor.getActiveColor(this)

        // Text fields (dropdown outlines) accent tint
        tintTextFieldsWithAccent()

        setupDropdowns()
        setupTimeDropdown()

        btnArmWrite.setOnClickListener {
            if (!armed) buildUriForSelected() else disarm(hideRow = true)
        }

        // start hidden (KTX)
        statusRow.isVisible = false
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
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
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

        ddAction.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, actionLabels)
        )
        ddAction.setText(actionLabels[0], false)

        ddAction.setOnItemClickListener { _, _, position, _ ->
            updateTimeVisibilityForAction(actionLabels[position])
        }

        // keep hint state consistent when profile changes
        ddProfile.setOnItemClickListener { _, _, _, _ ->
            updateTimeVisibilityForAction(ddAction.text?.toString().orEmpty())
        }

        updateTimeVisibilityForAction(actionLabels[0])
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
            setPadding(padding, padding / 2, padding, 0)
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
                }
            }
            .show()
    }

    private fun updateTimeVisibilityForAction(selectedActionLabel: String) {
        val isTempDisable = selectedActionLabel == getString(R.string.nfc_action_temp_disable)
        val isTempEnable = selectedActionLabel == getString(R.string.nfc_action_temp_enable)
        val isTemp = isTempDisable || isTempEnable

        // KTX
        tilTime.isVisible = isTemp
        tvTempHint.isVisible = isTemp

        if (!isTemp) return

        val noneLabel = getString(R.string.nfc_profile_none)
        val profile = ddProfile.text?.toString()?.trim().orEmpty()
        val isProfileSelected = profile.isNotEmpty() && profile != noneLabel

        tvTempHint.text = if (isProfileSelected) {
            if (isTempDisable) {
                "Note: Profile temp-disable only applies if that profile is currently active."
            } else {
                "Note: Profile temp-enable will activate this profile temporarily."
            }
        } else {
            // restore whatever hint was originally in XML
            defaultTempHintText ?: ""
        }
    }

    private fun buildUriForSelected() {
        val selectedActionLabel = ddAction.text?.toString()?.trim().orEmpty()

        // UID-only pairing mode (supports read-only / non-NDEF tags)
        if (selectedActionLabel == getString(R.string.nfc_action_pair_uid)) {
            pendingUidPairing = true
            pendingUriToWrite = null
            arm()
            // Override default status text for pairing
            tvStatus.text = getString(R.string.nfc_pair_waiting)
            return
        }
        
        val selectedProfile = ddProfile.text?.toString()?.trim().orEmpty()
        val noneLabel = getString(R.string.nfc_profile_none)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val tempMinutes: Int? =
            if (
                selectedActionLabel == getString(R.string.nfc_action_temp_disable) ||
                selectedActionLabel == getString(R.string.nfc_action_temp_enable)
            ) {
                prefs.getString("pref_nfc_unlock_minutes", "10")?.toIntOrNull()
            } else null

        val actionVerb = when (selectedActionLabel) {
            getString(R.string.nfc_action_enable) -> "enable"
            getString(R.string.nfc_action_disable) -> "disable"
            getString(R.string.nfc_action_toggle) -> "toggle"
            getString(R.string.nfc_action_temp_disable) -> "temp_disable${(tempMinutes ?: 10).coerceIn(1, 120)}"
            getString(R.string.nfc_action_temp_enable) -> "temp_enable${(tempMinutes ?: 10).coerceIn(1, 120)}"
            else -> "toggle"
        }

        val isProfile = selectedProfile.isNotEmpty() && selectedProfile != noneLabel
        val isTempAction =
            selectedActionLabel == getString(R.string.nfc_action_temp_disable) ||
                selectedActionLabel == getString(R.string.nfc_action_temp_enable)

        if (isProfile && isTempAction) {
            val msg =
                if (selectedActionLabel == getString(R.string.nfc_action_temp_disable)) {
                    "Heads-up: Profile temp-disable only stops Switchly if that profile is currently active."
                } else {
                    "Heads-up: Profile temp-enable will activate this profile temporarily."
                }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        val uri = if (isProfile) {
            NfcSchema.uriForProfileAction(selectedProfile, actionVerb)
        } else {
            NfcSchema.uriForGlobalAction(actionVerb)
        }

        pendingUriToWrite = uri
        arm()
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

            at.saltyy.switchly.data.prefs.NfcUidPairingStore.setPairedUidHex(this, uid)

            statusRow.isVisible = true
            statusProgress.isVisible = false
            tvStatus.text = getString(R.string.nfc_pair_ok_with_uid, uid)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ok))

            Toast.makeText(this, getString(R.string.nfc_pair_ok), Toast.LENGTH_SHORT).show()
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
            val ok = writeUriToTag(uriToWrite, tag)

            val okColor = ContextCompat.getColor(this, R.color.status_ok)
            val errorColor = ContextCompat.getColor(this, R.color.status_error)
            val neutralColor = ContextCompat.getColor(this, R.color.status_neutral)

            statusProgress.isVisible = false

            if (ok) {
                tvStatus.text = getString(R.string.nfc_write_ok)
                tvStatus.setTextColor(okColor)
                Toast.makeText(this, getString(R.string.nfc_write_ok), Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = getString(R.string.nfc_write_error_generic)
                tvStatus.setTextColor(errorColor)
                Toast.makeText(this, getString(R.string.nfc_write_error_generic), Toast.LENGTH_SHORT).show()
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

    private fun writeUriToTag(uriString: String, tag: Tag): Boolean {
        return try {
            val uriRecord = NdefRecord.createUri(uriString)
            val message = NdefMessage(arrayOf(uriRecord))

            val ndef = Ndef.get(tag) ?: return false
            ndef.connect()

            if (!ndef.isWritable) {
                ndef.close()
                return false
            }

            if (message.toByteArray().size > ndef.maxSize) {
                ndef.close()
                return false
            }

            ndef.writeNdefMessage(message)
            ndef.close()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
