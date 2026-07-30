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

// Persists and retrieves emergency pin state.
object EmergencyPinStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_EMERGENCY_PIN = "pref_emergency_pin"

    private val legacyKeys = listOf(
        "emergency_pin",
        "pref_emergency_unlock_pin",
        "emergency_unlock_pin"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPin(ctx: Context): String? {
        val sp = prefs(ctx)
        val candidates = buildList {
            add(sp.getString(KEY_EMERGENCY_PIN, null))
            legacyKeys.forEach { key -> add(sp.getString(key, null)) }
        }

        val resolved = candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
        if (!resolved.isNullOrBlank() && sp.getString(KEY_EMERGENCY_PIN, null) != resolved) {
            sp.edit { putString(KEY_EMERGENCY_PIN, resolved) }
        }
        return resolved
    }

    fun hasPin(ctx: Context): Boolean = !getPin(ctx).isNullOrBlank()

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit(commit = true) { putString(KEY_EMERGENCY_PIN, pin.trim()) }
    }

    fun matchesPin(ctx: Context, enteredPin: String): Boolean {
        val expected = getPin(ctx).orEmpty()
        return expected.isNotBlank() && expected == enteredPin.trim()
    }
}
