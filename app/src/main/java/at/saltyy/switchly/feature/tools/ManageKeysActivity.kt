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

package at.saltyy.switchly.feature.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.feature.qr.QrGenerateActivity
import at.saltyy.switchly.feature.settings.ManageBarcodesActivity
import at.saltyy.switchly.feature.settings.ManagePairedTagsActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.LockedUi
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar

class ManageKeysActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_keys)

        toolbar = findViewById(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = null)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        setupCards()
        applyOnboardingFilterIfNeeded()
        syncLockedCardState()
    }

    override fun onResume() {
        super.onResume()
        syncLockedCardState()
    }

    private fun applyOnboardingFilterIfNeeded() {
        if (!intent.getBooleanExtra(EXTRA_FILTER_FROM_ONBOARDING, false)) {
            return
        }

        val showNfc = intent.getBooleanExtra(EXTRA_SHOW_NFC, false)
        val showQr = intent.getBooleanExtra(EXTRA_SHOW_QR, false)
        val showBarcode = intent.getBooleanExtra(EXTRA_SHOW_BARCODE, false)

        // In onboarding we only show the key types the user selected in Blocking Controls.
        findViewById<View>(R.id.cardWriteNfc).visibility = if (showNfc) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardPairedTags).visibility = if (showNfc) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardGenerateQr).visibility = if (showQr) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardManageBarcodes).visibility = if (showBarcode) View.VISIBLE else View.GONE
    }

    private fun setupCards() {
        findViewById<View>(R.id.cardWriteNfc).setOnClickListener {
            when {
                !AutomationModeStore.isNfcAllowed(this) -> showFeatureDisabledToast(R.string.toast_write_nfc_requires_enabled)
                isNfcTagWritingLocked() -> EditingLockGuard.showLockedDialog(this, R.string.edit_locked_write_nfc_tags)
                else -> startActivity(Intent(this, NfcWriterActivity::class.java))
            }
        }

        findViewById<View>(R.id.cardPairedTags).setOnClickListener {
            val locked = EditingLockGuard.isLocked(this)
            val pairedTagsEnabled = arePairedTagsEnabled()
            AppLogStore.append(
                this,
                "NFC",
                "Manage Paired Tags clicked from Manage Keys locked=$locked pairedTagsEnabled=$pairedTagsEnabled"
            )
            when {
                !AutomationModeStore.isNfcAllowed(this) -> showFeatureDisabledToast(R.string.toast_write_nfc_requires_enabled)
                !pairedTagsEnabled -> showFeatureDisabledToast(R.string.toast_manage_paired_tags_requires_enabled)
                locked -> EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_paired_tags)
                else -> openManagePairedTags()
            }
        }

        findViewById<View>(R.id.cardGenerateQr).setOnClickListener {
            val qrEnabled = AutomationModeStore.shouldShowQrTools(this)
            AppLogStore.append(
                this,
                "QR",
                "Manage QR clicked from Manage Keys qrEnabled=$qrEnabled"
            )
            when {
                !qrEnabled -> showFeatureDisabledToast(R.string.toast_manage_qr_requires_enabled)
                EditingLockGuard.isLocked(this) ->
                    EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_qr_codes)
                else -> startActivity(Intent(this, QrGenerateActivity::class.java))
            }
        }

        findViewById<View>(R.id.cardManageBarcodes).setOnClickListener {
            val barcodeEnabled = AutomationModeStore.shouldShowBarcodeTools(this)
            val locked = EditingLockGuard.isLocked(this)
            AppLogStore.append(
                this,
                "Barcode",
                "Manage Barcodes clicked from Manage Keys barcodeEnabled=$barcodeEnabled locked=$locked"
            )
            when {
                !barcodeEnabled -> showFeatureDisabledToast(R.string.toast_manage_barcodes_requires_enabled)
                locked -> EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_barcodes)
                else -> startActivity(Intent(this, ManageBarcodesActivity::class.java))
            }
        }

    }

    private fun arePairedTagsEnabled(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
    }

    private fun showFeatureDisabledToast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    private fun openManagePairedTags() {
        AppLogStore.append(this, "NFC", "Opening Manage Paired Tags")
        runCatching {
            startActivity(Intent(this, ManagePairedTagsActivity::class.java))
        }.onFailure { error ->
            AppLogStore.append(this, "NFC", "Failed to open Manage Paired Tags", error)
            Toast.makeText(this, R.string.error_open_manage_paired_tags, Toast.LENGTH_LONG).show()
        }
    }

    private fun syncLockedCardState() {
        val editLocked = EditingLockGuard.isLocked(this)
        val nfcAllowed = AutomationModeStore.isNfcAllowed(this)
        applyLockedCardState(
            findViewById(R.id.cardWriteNfc), 
            !nfcAllowed || isNfcTagWritingLocked())
        applyLockedCardState(
            findViewById(R.id.cardPairedTags), 
            !nfcAllowed || editLocked || !arePairedTagsEnabled())
        applyLockedCardState(
            findViewById(R.id.cardGenerateQr),
            !AutomationModeStore.shouldShowQrTools(this) || editLocked
        )
        applyLockedCardState(
            findViewById(R.id.cardManageBarcodes),
            !AutomationModeStore.shouldShowBarcodeTools(this) || editLocked
        )
    }

    private fun applyLockedCardState(view: View, locked: Boolean) {
        view.alpha = if (locked) lockedCardAlpha() else 1f
        // Keep clicks enabled so locked cards can explain why they cannot be opened.
        view.isEnabled = true
    }

    private fun lockedCardAlpha(): Float = LockedUi.cardAlpha(this)

    private fun isNfcTagWritingLocked(): Boolean {
        return EditingLockGuard.isLocked(this)
    }

    companion object {
        const val EXTRA_FILTER_FROM_ONBOARDING = "extra_filter_from_onboarding"
        const val EXTRA_SHOW_NFC = "extra_show_nfc"
        const val EXTRA_SHOW_QR = "extra_show_qr"
        const val EXTRA_SHOW_BARCODE = "extra_show_barcode"
    }

}
