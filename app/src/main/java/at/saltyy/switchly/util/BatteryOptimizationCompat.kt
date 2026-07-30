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

package at.saltyy.switchly.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

object BatteryOptimizationCompat {
    private const val PREFS_SCHEDULE_HEALTH = "switchly_schedule_health"
    private const val KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE = "battery_optimization_confirmed_max_available"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching { am.isBackgroundRestricted }.getOrDefault(false)
    }

    fun isUserConfirmedMaxAvailable(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_SCHEDULE_HEALTH, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE, false)
    }

    fun isEffectivelyOk(context: Context): Boolean {
        // Be strict: Android "Optimized" can still delay schedules, geofences, receivers and Accessibility-related reliability.
        // Treat the setup as OK only when the app is truly excluded from battery optimization, or when the user manually confirmed the best available OEM/autostart setup.
        return isIgnoringBatteryOptimizations(context) || isUserConfirmedMaxAvailable(context)
    }

    fun isLikelyStillRestricted(context: Context): Boolean = !isEffectivelyOk(context)
}
