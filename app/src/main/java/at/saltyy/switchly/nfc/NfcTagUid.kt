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

import android.nfc.Tag

/**
 * Helper for getting a stable human-readable UID (tag.id) string.
 * Note: Not every NFC technology guarantees a stable UID across all devices, but for most common tags it works fine.
 */
object NfcTagUid {

    fun uidHex(tag: Tag?): String? = uidHex(tag?.id)

    fun uidHex(id: ByteArray?): String? {
        if (id == null || id.isEmpty()) {
            return null
        }
        return id.joinToString(separator = "") { b ->
            "%02X".format(b)
        }.let { normalizeUidHex(it) }
    }

    /**
     * Normalizes UID text to uppercase HEX without separators.
     * Accepts formats like "04:AB-11 22" and converts to "04AB1122".
     */
    fun normalizeUidHex(value: String?): String {
        if (value == null) {
            return ""
        }
        return value
            .trim()
            .uppercase()
            .replace(Regex("[^0-9A-F]"), "")
    }
}
