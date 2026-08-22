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

object InAppDetectionStore {
    private const val PREFS = "switchly_in_app_detection"
    private const val PREFIX_LAST = "last_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, surfaceKey: String, timestamp: Long = System.currentTimeMillis()) {
        if (surfaceKey.isBlank()) return
        prefs(context).edit { putLong(PREFIX_LAST + surfaceKey, timestamp) }
    }

    fun lastForAny(context: Context, surfaceKeys: Collection<String>): Long =
        surfaceKeys.maxOfOrNull { key -> prefs(context).getLong(PREFIX_LAST + key, 0L) } ?: 0L
}
