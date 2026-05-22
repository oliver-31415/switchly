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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.nfc.NfcEntryActivity
import at.saltyy.switchly.nfc.NfcSchema
import at.saltyy.switchly.ui.ThemeUtils

/**
 * Public, browsable entry point for switchly:// QR links opened by external scanners.
 *
 * The actual action execution stays in NfcEntryActivity, but external scanners do not get the internal QR scanner extra. 
 * This small trampoline validates the URI, asks for an explicit user confirmation, then forwards it as a QR-sourced action.
 */
class ExternalQrActionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (!isSupportedSwitchlyUri(uri)) {
            Toast.makeText(this, R.string.invalid_qr_code, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!AutomationModeStore.isQrAllowed(this)) {
            Toast.makeText(this, R.string.mode_blocked_qr_action, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.external_qr_confirm_title)
            .setMessage(R.string.external_qr_confirm_message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.external_qr_confirm_action) { _, _ ->
                startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setClass(this, NfcEntryActivity::class.java)
                        .putExtra(QrScanActivity.EXTRA_SCAN_SOURCE, "qr")
                )
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun isSupportedSwitchlyUri(uri: Uri?): Boolean {
        return uri != null &&
            uri.scheme.equals("switchly", ignoreCase = true) &&
            NfcSchema.isKnownHost(uri.host)
    }
}
