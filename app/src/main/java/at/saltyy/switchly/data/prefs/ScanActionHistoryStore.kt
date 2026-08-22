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

package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object ScanActionHistoryStore {
    enum class Source(val key: String) {
        NFC("nfc"),
        QR("qr"),
        BARCODE("barcode");

        companion object {
            fun fromLogTag(tag: String): Source? = when (tag.lowercase()) {
                "nfc" -> NFC
                "qr" -> QR
                "barcode" -> BARCODE
                else -> null
            }
        }
    }

    data class Entry(
        val source: Source,
        val action: String,
        val profile: String?,
        val durationMs: Long?,
        val atMillis: Long,
        val resultingEnabled: Boolean,
    )

    private const val PREFS = "switchly_scan_action_history"

    private fun prefix(source: Source) = "scan_history_${source.key}_"

    fun record(
        context: Context,
        source: Source,
        action: String,
        profile: String? = null,
        durationMs: Long? = null,
        resultingEnabled: Boolean,
    ) {
        val p = prefix(source)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(p + "action", action)
            putString(p + "profile", profile.orEmpty())
            if (durationMs != null) putLong(p + "duration", durationMs) else remove(p + "duration")
            putLong(p + "at", System.currentTimeMillis())
            putBoolean(p + "enabled", resultingEnabled)
        }
    }

    fun get(context: Context, source: Source): Entry? {
        val p = prefix(source)
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = when (val raw = sp.all[p + "at"]) {
            is Long -> raw
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
        if (at <= 0L) return null
        val action = sp.getString(p + "action", null).orEmpty()
        if (action.isBlank()) return null
        val duration = when (val raw = sp.all[p + "duration"]) {
            is Long -> raw
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
        return Entry(
            source = source,
            action = action,
            profile = sp.getString(p + "profile", null)?.takeIf { it.isNotBlank() },
            durationMs = duration,
            atMillis = at,
            resultingEnabled = sp.getBoolean(p + "enabled", false),
        )
    }
}
