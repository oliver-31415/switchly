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

package at.saltyy.switchly.feature.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.pm.ShortcutManagerCompat
import at.saltyy.switchly.feature.barcode.BarcodeScanActivity
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.feature.qr.QrScanActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.util.ActivityTransitionCompat
import at.saltyy.switchly.widget.QuickActionReceiver

/**
 * Lightweight exported trampoline for launcher shortcuts, widgets and Quick Settings tiles.
 * Keeps internal activities non-exported while still allowing the launcher and system UI to trigger selected quick actions.
 */
class ScanLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun reportShortcutUsage(intent: Intent?) {
        val shortcutId = shortcutIdFrom(intent) ?: return
        ShortcutManagerCompat.reportShortcutUsed(this, shortcutId)
    }

    private fun shortcutIdFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != SHORTCUT_SCHEME || data.host != SHORTCUT_HOST) return null
        return data.lastPathSegment?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isTrustedFocusNowIntent(intent: Intent?): Boolean {
        // Only allow Focus Now from Switchly-created launcher shortcuts/widgets.
        // The activity is exported as a trampoline for system surfaces, so do not let arbitrary explicit external intents trigger focus mode by action string alone.
        return shortcutIdFrom(intent) == SHORTCUT_FOCUS_NOW_ID
    }

    private fun handleIntent(intent: Intent?) {
        reportShortcutUsage(intent)

        when (intent?.action) {
            ACTION_FOCUS_NOW -> {
                if (isTrustedFocusNowIntent(intent)) {
                    QuickActionReceiver.handleFocusNow(this)
                }
                finishAndNoAnim()
                return
            }
        }

        val launchIntent = when (intent?.action) {
            ACTION_OPEN_QR_SCAN -> Intent(this, QrScanActivity::class.java)
                .putExtra(QrScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true)

            ACTION_OPEN_BARCODE_SCAN -> Intent(this, BarcodeScanActivity::class.java)
                .putExtra(BarcodeScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true)

            ACTION_OPEN_NFC_WRITE -> Intent(this, NfcWriterActivity::class.java)
            ACTION_OPEN_BLOCKED_NOTIFICATIONS -> Intent(this, BlockedInboxActivity::class.java)
            else -> Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        finishAndNoAnim()
    }

    private fun finishAndNoAnim() {
        finish()
        ActivityTransitionCompat.finishWithoutAnimation(this)
    }

    companion object {
        private const val SHORTCUT_SCHEME = "switchly"
        private const val SHORTCUT_HOST = "shortcut"
        private const val SHORTCUT_FOCUS_NOW_ID = "quick_focus_now"

        const val ACTION_OPEN_QR_SCAN = "at.saltyy.switchly.action.OPEN_QR_SCAN"
        const val ACTION_OPEN_BARCODE_SCAN = "at.saltyy.switchly.action.OPEN_BARCODE_SCAN"
        const val ACTION_OPEN_NFC_WRITE = "at.saltyy.switchly.action.OPEN_NFC_WRITE"
        const val ACTION_OPEN_BLOCKED_NOTIFICATIONS = "at.saltyy.switchly.action.OPEN_BLOCKED_NOTIFICATIONS"
        const val ACTION_FOCUS_NOW = "at.saltyy.switchly.action.FOCUS_NOW"
    }
}
