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
import android.nfc.Tag
import androidx.core.content.edit
import at.saltyy.switchly.util.getLongCompat

// Prevents a tag that was just written or paired from immediately executing while it is still held near the phone.
object NfcRecentWriteGuard {

    private const val PREFS = "switchly_nfc_recent_write"
    private const val KEY_UID = "uid"
    private const val KEY_WRITTEN_AT_MS = "written_at_ms"
    private const val REBOUND_WINDOW_MS = 3_000L

    fun markUid(context: Context, uidHex: String?) {
        val normalized = NfcTagUid.normalizeUidHex(uidHex)
        if (normalized.isBlank()) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_UID, normalized)
            putLong(KEY_WRITTEN_AT_MS, System.currentTimeMillis())
        }
    }

    fun shouldIgnore(context: Context, tag: Tag?): Boolean {
        val normalized = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
        if (normalized.isBlank()) {
            return false
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUid = NfcTagUid.normalizeUidHex(prefs.getString(KEY_UID, null))
        val writtenAt = prefs.getLongCompat(KEY_WRITTEN_AT_MS, 0L)
        val age = System.currentTimeMillis() - writtenAt
        return normalized == lastUid && age in 0..REBOUND_WINDOW_MS
    }
}
