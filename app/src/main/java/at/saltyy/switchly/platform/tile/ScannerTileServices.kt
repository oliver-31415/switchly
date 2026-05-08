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

package at.saltyy.switchly.platform.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.entry.QuickActionIconFactory
import at.saltyy.switchly.feature.entry.ScanLauncherActivity

abstract class BaseQuickActionTileService : TileService() {

    protected abstract val labelRes: Int
    protected abstract val drawableRes: Int
    protected abstract val launchAction: String
    protected abstract val requestCode: Int

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchAndCollapse() }
        } else {
            launchAndCollapse()
        }
    }

    private fun launchAndCollapse() {
        val intent = Intent(this, ScanLauncherActivity::class.java)
            .setAction(launchAction)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            launchTileIntentCompat(intent)
        }
    }

    private fun launchTileIntentCompat(intent: Intent) {
        runCatching {
            TileService::class.java
                .getMethod("startActivityAndCollapse", Intent::class.java)
                .invoke(this, intent)
        }.getOrElse {
            startActivity(intent)
        }
    }

    private fun refreshTile() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(labelRes)
            icon = QuickActionIconFactory.createTileIcon(this@BaseQuickActionTileService, drawableRes)
            updateTile()
        }
    }
}

class QrScanTileService : BaseQuickActionTileService() {
    override val labelRes: Int = R.string.qr_scan_title
    override val drawableRes: Int = R.drawable.qr_code_24
    override val launchAction: String = ScanLauncherActivity.ACTION_OPEN_QR_SCAN
    override val requestCode: Int = 1001
}

class BarcodeScanTileService : BaseQuickActionTileService() {
    override val labelRes: Int = R.string.barcode_scan_title
    override val drawableRes: Int = R.drawable.barcode_24
    override val launchAction: String = ScanLauncherActivity.ACTION_OPEN_BARCODE_SCAN
    override val requestCode: Int = 1002
}

