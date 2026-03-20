package at.saltyy.switchly.feature.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.ui.dialog.showAccented
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.databinding.ActivityQrGenerateBinding
import at.saltyy.switchly.nfc.NfcSchema
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.util.Locale

class QrGenerateActivity : AppCompatActivity() {

    private lateinit var b: ActivityQrGenerateBinding

    private enum class Mode { GLOBAL, PROFILE }

    private data class Action(
        val id: String,
        val labelRes: Int,
        val mode: Mode,
        val supportsMinutes: Boolean
    )

    private val actions by lazy {
        listOf(
            Action("enable", R.string.qr_action_enable, Mode.GLOBAL, false),
            Action("disable", R.string.qr_action_disable, Mode.GLOBAL, false),
            Action("toggle", R.string.qr_action_toggle, Mode.GLOBAL, false),

            Action("temp_disable", R.string.qr_action_temp_disable, Mode.GLOBAL, true),
            Action("temp_enable", R.string.qr_action_temp_enable, Mode.GLOBAL, true),

            Action("enable", R.string.qr_action_profile_enable, Mode.PROFILE, false),
            Action("disable", R.string.qr_action_profile_disable, Mode.PROFILE, false),
            Action("toggle", R.string.qr_action_profile_toggle, Mode.PROFILE, false),
        )
    }

    private val minutePresets by lazy {
        listOf(
            getString(R.string.qr_minutes_5),
            getString(R.string.qr_minutes_10),
            getString(R.string.qr_minutes_30),
            getString(R.string.qr_minutes_60),
            getString(R.string.qr_minutes_120),
            getString(R.string.qr_minutes_custom)
        )
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        if (!AutomationModeStore.shouldShowQrTools(this)) {
            Toast.makeText(this, R.string.mode_blocked_qr_action, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        b = ActivityQrGenerateBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupToolbar()
        applyAccent()

        // Action dropdown
        val actionLabels = actions.map { getString(it.labelRes) }
        b.actionDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, actionLabels)
        )
        b.actionDropdown.setText(actionLabels.first(), false)

        // Profile dropdown
        refreshProfiles()

        // Minutes dropdown
        b.minutesDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, minutePresets)
        )
        b.minutesDropdown.setText(getString(R.string.qr_minutes_default), false)

        // Listeners
        b.actionDropdown.setOnItemClickListener { _, _, pos, _ ->
            val act = actions.getOrNull(pos) ?: actions.first()
            applyActionUi(act)
            regenerate()
        }

        b.profileDropdown.setOnItemClickListener { _, _, _, _ ->
            regenerate()
        }

        b.minutesDropdown.setOnItemClickListener { _, _, pos, _ ->
            val v = minutePresets.getOrNull(pos) ?: return@setOnItemClickListener
            if (v == getString(R.string.qr_minutes_custom)) {
                showCustomMinutesDialog()
            } else {
                regenerate()
            }
        }

        b.btnCopy.setOnClickListener {
            val uri = b.tvUri.text?.toString().orEmpty()
            if (uri.isBlank()) return@setOnClickListener
            copyToClipboard(uri)
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        b.btnShare.setOnClickListener {
            val uri = b.tvUri.text?.toString().orEmpty()
            if (uri.isBlank()) return@setOnClickListener
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, uri)
                    },
                    getString(R.string.share)
                )
            )
        }

        // Initial render
        applyActionUi(actions.first())
        regenerate()
    }

    override fun onResume() {
        super.onResume()
        setupToolbar()
        applyAccent()
    }

    private fun applyAccent() {
        val tint = AccentColor.getActiveColor(this)
        b.btnCopy.backgroundTintList = tint
        b.btnShare.backgroundTintList = tint
    }

    private fun setupToolbar() {
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        b.toolbar.setNavigationIcon(R.drawable.arrow_back_ios_24)
        b.toolbar.navigationIcon?.mutate()?.setTint(
            ContextCompat.getColor(this, R.color.font_white)
        )
    }

    private fun refreshProfiles() {
        val profiles = ProfileStore.getProfiles(this).toList().sorted()
        b.profileDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, profiles)
        )

        val current = ProfileStore.getCurrent(this)
        val pick = current?.takeIf { profiles.contains(it) } ?: profiles.firstOrNull().orEmpty()
        b.profileDropdown.setText(pick, false)
    }

    private fun showCustomMinutesDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.qr_minutes_hint)
        }

        Dialogs.builder(this)
            .setTitle(getString(R.string.qr_minutes_custom_title))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val m = input.text?.toString()?.trim()?.toLongOrNull()
                if (m == null || m <= 0) {
                    Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val locale = Locale.getDefault()
                b.minutesDropdown.setText(String.format(locale, "%d", m), false)
                regenerate()
            }
            .showAccented()
    }

    private fun applyActionUi(action: Action) {
        val isProfile = action.mode == Mode.PROFILE
        b.tilProfile.visibility = if (isProfile) View.VISIBLE else View.GONE
        b.tilMinutes.visibility = if (action.supportsMinutes) View.VISIBLE else View.GONE
    }

    private fun regenerate() {
        val actionLabel = b.actionDropdown.text?.toString().orEmpty()
        val action = actions.firstOrNull { getString(it.labelRes) == actionLabel } ?: actions.first()

        val uri = buildSwitchlyUri(action)
        b.tvUri.text = uri

        val bmp = generateQrBitmap(uri, 900)
        b.ivQr.setImageBitmap(bmp)
    }

    private fun buildSwitchlyUri(action: Action): String {
        return if (action.mode == Mode.GLOBAL) {
            val minutesSuffix = if (action.supportsMinutes) {
                val minutes = parseMinutes(b.minutesDropdown.text?.toString())
                val clamped = (minutes ?: 0L).coerceIn(1L, 120L)
                clamped.toString()
            } else ""

            val finalAction = if (action.supportsMinutes) (action.id + minutesSuffix) else action.id
            NfcSchema.uriForGlobalAction(finalAction)
        } else {
            val profile = b.profileDropdown.text?.toString()?.trim()
                .orEmpty()
                .ifBlank { getString(R.string.qr_profile_fallback) }

            NfcSchema.uriForProfileAction(profile, action.id)
        }
    }

    private fun parseMinutes(text: String?): Long? {
        val s = text?.trim().orEmpty()
        if (s.isBlank()) return null
        val match = Regex("""\d+""").find(s) ?: return null
        return match.value.toLongOrNull()
    }

    private fun generateQrBitmap(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter()
            .encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        return matrixToBitmap(matrix)
    }

    private fun matrixToBitmap(matrix: BitMatrix): Bitmap {
        val w = matrix.width
        val h = matrix.height

        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()

        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp[x, y] = if (matrix[x, y]) black else white
            }
        }
        return bmp
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.qr_clipboard_label), text))
    }
}
