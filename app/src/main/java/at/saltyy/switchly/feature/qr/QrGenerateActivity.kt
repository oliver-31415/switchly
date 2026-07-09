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

package at.saltyy.switchly.feature.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.databinding.ActivityQrGenerateBinding
import at.saltyy.switchly.nfc.NfcSchema
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.LocaleHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class QrGenerateActivity : AppCompatActivity() {

    private lateinit var b: ActivityQrGenerateBinding
    private var currentQrBitmap: Bitmap? = null

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

    private fun defaultAction(): Action =
        actions.firstOrNull { it.mode == Mode.GLOBAL && it.id == "toggle" } ?: actions.first()

    private fun minutePresetsFor(action: Action): List<String> {
        val presets = mutableListOf(
            getString(R.string.qr_minutes_5),
            getString(R.string.qr_minutes_10),
            getString(R.string.qr_minutes_30),
            getString(R.string.qr_minutes_60),
            getString(R.string.qr_minutes_120),
        )
        presets += getString(R.string.qr_minutes_custom)
        if (action.id == "temp_enable" || action.id == "temp_disable") {
            presets += getString(R.string.qr_minutes_ask_when_scanned)
        }
        return presets
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
        val defaultAction = defaultAction()
        b.actionDropdown.setText(getString(defaultAction.labelRes), false)

        // Profile dropdown
        refreshProfiles()

        // Minutes dropdown
        b.minutesDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, minutePresetsFor(defaultAction))
        )
        b.minutesDropdown.setText(getString(R.string.qr_minutes_default), false)

        // Listeners
        b.actionDropdown.setOnItemClickListener { _, _, pos, _ ->
            val act = actions.getOrNull(pos) ?: defaultAction()
            applyActionUi(act)
            regenerate()
        }

        b.profileDropdown.setOnItemClickListener { _, _, _, _ ->
            regenerate()
        }

        b.minutesDropdown.setOnItemClickListener { _, _, pos, _ ->
            val actionLabel = b.actionDropdown.text?.toString().orEmpty()
            val action = actions.firstOrNull { getString(it.labelRes) == actionLabel } ?: defaultAction()
            val v = minutePresetsFor(action).getOrNull(pos) ?: return@setOnItemClickListener
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
            shareQrAsPng()
        }

        // Initial render
        applyActionUi(defaultAction())
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_qr_generate, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_info -> {
                showQrInfoDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showQrInfoDialog() {
        Dialogs.builder(this)
            .setTitle(R.string.qr_info_title)
            .setMessage(R.string.qr_info_body)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showCustomMinutesDialog() {
        val accent = AccentColor.getAccentColorInt(this)
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.qr_minutes_hint)
            isSingleLine = true
            backgroundTintList = ColorStateList.valueOf(accent)
        }

        fun presetChip(minutes: Long): TextView {
            return TextView(this).apply {
                text = getString(R.string.temp_duration_preset_minutes, minutes)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
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
        }
        listOf(5L, 10L, 15L, 30L, 60L).forEachIndexed { index, minutes ->
            presetRow.addView(
                presetChip(minutes),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(4)
                }
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(TextView(this@QrGenerateActivity).apply {
                text = getString(R.string.temp_duration_quick_presets)
                textSize = 12.5f
                alpha = 0.74f
                setPadding(0, 0, 0, dp(4))
            })
            addView(presetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        Dialogs.builder(this)
            .setTitle(getString(R.string.qr_minutes_custom_title))
            .setView(content)
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

        if (action.supportsMinutes) {
            val presets = minutePresetsFor(action)
            b.minutesDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, presets))
            val current = b.minutesDropdown.text?.toString().orEmpty()
            if (current == getString(R.string.qr_minutes_ask_when_scanned) && action.id !in setOf("temp_enable", "temp_disable")) {
                b.minutesDropdown.setText(getString(R.string.qr_minutes_default), false)
            }
        }
    }

    private fun regenerate() {
        val actionLabel = b.actionDropdown.text?.toString().orEmpty()
        val action = actions.firstOrNull { getString(it.labelRes) == actionLabel } ?: defaultAction()

        val uri = buildSwitchlyUri(action)
        b.tvUri.text = uri

        val bmp = generateQrBitmap(uri, 900)
        currentQrBitmap = bmp
        b.ivQr.setImageBitmap(bmp)
    }

    private fun buildSwitchlyUri(action: Action): String {
        return if (action.mode == Mode.GLOBAL) {
            val minutesText = b.minutesDropdown.text?.toString().orEmpty()
            val minutesSuffix = if (action.supportsMinutes) {
                if ((action.id == "temp_enable" || action.id == "temp_disable") && minutesText == getString(R.string.qr_minutes_ask_when_scanned)) {
                    ""
                } else {
                    val minutes = parseMinutes(minutesText)
                    val clamped = (minutes ?: 10L).coerceIn(1L, 1440L)
                    clamped.toString()
                }
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

    private fun shareQrAsPng() {
        val bitmap = currentQrBitmap ?: run {
            val uriText = b.tvUri.text?.toString().orEmpty()
            if (uriText.isBlank()) return
            generateQrBitmap(uriText, 900)
        }
        val streamUri = writeQrBitmapToCache(bitmap) ?: run {
            Toast.makeText(this, R.string.qr_share_failed, Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, streamUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, getString(R.string.share), streamUri)
                },
                getString(R.string.share)
            )
        )
    }

    private fun writeQrBitmapToCache(bitmap: Bitmap): Uri? {
        return runCatching {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "switchly_qr_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.qr_clipboard_label), text))
    }
}
