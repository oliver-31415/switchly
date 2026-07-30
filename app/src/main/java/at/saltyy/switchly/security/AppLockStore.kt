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

package at.saltyy.switchly.security

import android.content.Context
import androidx.core.content.edit
import at.saltyy.switchly.util.ManagedDevicePolicyHelper

// Persists and retrieves app lock state.
object AppLockStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_ENABLED = "pref_app_lock_enabled"
    private const val KEY_PIN = "pref_app_lock_pin"
    private const val KEY_BIOMETRIC = "pref_app_lock_biometric"
    private const val KEY_STRICT_PROTECTION = "pref_app_lock_strict_protection"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPin(ctx: Context): Boolean = !prefs(ctx).getString(KEY_PIN, null).isNullOrBlank()

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false) && hasPin(ctx)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit(commit = true) {
            putBoolean(KEY_ENABLED, enabled && hasPin(ctx))
        }
    }

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit(commit = true) {
            putString(KEY_PIN, pin.trim())
        }
    }

    fun matchesPin(ctx: Context, enteredPin: String): Boolean {
        val expected = prefs(ctx).getString(KEY_PIN, null)?.trim().orEmpty()
        return expected.isNotBlank() && expected == enteredPin.trim()
    }

    fun isBiometricEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit(commit = true) { putBoolean(KEY_BIOMETRIC, enabled) }
    }

    fun isStrictProtectionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_STRICT_PROTECTION, false)

    fun setStrictProtectionEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit(commit = true) { putBoolean(KEY_STRICT_PROTECTION, enabled) }
        ManagedDevicePolicyHelper.syncSelfUninstallBlock(ctx)
    }
}
