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

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import java.util.concurrent.Executors

object QuickShortcutRegistrar {

    private const val PREFS = "switchly_shortcuts"
    private const val KEY_REGISTERED_SPEC = "registered_spec"
    private const val SHORTCUT_SPEC = "focus_qr_barcode"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SwitchlyShortcuts").apply { isDaemon = true }
    }

    fun refreshAsync(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!force && isRegisteredForCurrentSpec(appContext)) {
            return
        }
        executor.execute {
            if (!force && isRegisteredForCurrentSpec(appContext)) return@execute
            if (refresh(appContext)) {
                markRegisteredForCurrentSpec(appContext)
            }
        }
    }

    fun refresh(context: Context): Boolean {
        return runCatching {
            // Avoid removeAllDynamicShortcuts on startup.
            // On Samsung/Android 14-16 this binder call has caused ANRs when invoked from Application.onCreate().
            // Adding the same IDs updates/replaces the dynamic shortcut definitions.
            ShortcutManagerCompat.addDynamicShortcuts(context, buildShortcuts(context))
        }.getOrDefault(false)
    }

    private fun buildShortcuts(context: Context): List<ShortcutInfoCompat> {
        return listOf(
            shortcut(
                context = context,
                id = "quick_focus_now",
                shortLabelRes = R.string.shortcut_focus_now_short,
                longLabelRes = R.string.shortcut_focus_now_long,
                drawableRes = R.drawable.play_arrow_24,
                action = ScanLauncherActivity.ACTION_FOCUS_NOW,
            ),
            shortcut(
                context = context,
                id = "quick_qr_scan",
                shortLabelRes = R.string.shortcut_qr_short,
                longLabelRes = R.string.shortcut_qr_long,
                drawableRes = R.drawable.qr_code_24,
                action = ScanLauncherActivity.ACTION_OPEN_QR_SCAN,
            ),
            shortcut(
                context = context,
                id = "quick_barcode_scan",
                shortLabelRes = R.string.shortcut_barcode_short,
                longLabelRes = R.string.shortcut_barcode_long,
                drawableRes = R.drawable.barcode_24,
                action = ScanLauncherActivity.ACTION_OPEN_BARCODE_SCAN,
            )
        )
    }

    private fun currentSpec(): String = "${BuildConfig.VERSION_CODE}:$SHORTCUT_SPEC"

    private fun isRegisteredForCurrentSpec(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REGISTERED_SPEC, null) == currentSpec()
    }

    private fun markRegisteredForCurrentSpec(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_REGISTERED_SPEC, currentSpec())
        }
    }

    private fun shortcut(
        context: Context,
        id: String,
        shortLabelRes: Int,
        longLabelRes: Int,
        drawableRes: Int,
        action: String,
    ): ShortcutInfoCompat {
        val intent = Intent(context, ScanLauncherActivity::class.java)
            .setAction(action)
            .setData("switchly://shortcut/$id".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(shortLabelRes))
            .setLongLabel(context.getString(longLabelRes))
            .setIcon(QuickActionIconFactory.createShortcutIcon(context, drawableRes))
            .setIntent(intent)
            .build()
    }
}
