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

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Guards the exported NFC entry activity from accepting forged in-app QR/barcode dispatches.
 * NfcEntryActivity must stay exported for Android's NFC dispatch.
 * QR/barcode scans are internal handoffs, so they carry a short-lived one-time token that external apps cannot know.
 */
object InternalScanDispatchGuard {

    const val EXTRA_TOKEN = "at.saltyy.switchly.extra.INTERNAL_SCAN_TOKEN"

    private const val PREFS = "switchly_internal_scan_dispatch"
    private const val KEY_SOURCE_PREFIX = "source_"
    private const val KEY_EXPIRES_PREFIX = "expires_"
    private const val TTL_MS = 30_000L

    fun issue(context: Context, source: String): String {
        val token = UUID.randomUUID().toString()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_SOURCE_PREFIX + token, source)
                putLong(KEY_EXPIRES_PREFIX + token, System.currentTimeMillis() + TTL_MS)
            }
        return token
    }

    fun consume(context: Context, expectedSource: String?, token: String?): Boolean {
        if (expectedSource.isNullOrBlank() || token.isNullOrBlank()) return false

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sourceKey = KEY_SOURCE_PREFIX + token
        val expiresKey = KEY_EXPIRES_PREFIX + token
        val source = prefs.getString(sourceKey, null)
        val expiresAt = prefs.getLong(expiresKey, 0L)

        prefs.edit {
            remove(sourceKey)
            remove(expiresKey)
        }

        return source == expectedSource && expiresAt >= System.currentTimeMillis()
    }
}
