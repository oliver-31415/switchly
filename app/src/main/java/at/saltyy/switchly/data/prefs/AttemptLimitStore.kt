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
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stores a per-app, per-profile "open/attempt" limit.
 *
 * If a limit is set to N, the app will be blocked once it has been opened more than N times
 * on the current day (local device timezone).
 */
object AttemptLimitStore {
    private const val PREFS = "switchly_prefs"

    private fun isInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // attempt_limit__<profile>__<pkg> = Int
    private const val PREFIX_LIMIT = "attempt_limit__"

    // Permanent marker: package has had an attempt limit at least once.
    // Used so UI can keep showing it even after removal (similar to UsageLimitStore).
    private const val PREFIX_EVER = "attempt_limit_ever__" // + pkg

    private fun key(profile: String, pkg: String) = PREFIX_LIMIT + profile + "__" + pkg

    fun setLimitAttempts(ctx: Context, profile: String, pkg: String, attempts: Int) {
        if (profile.isBlank() || pkg.isBlank()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)

        if (attempts > 0) {
            prefs.edit { putBoolean(PREFIX_EVER + pkg, true) }
        }

        if (attempts <= 0) {
            prefs.edit { remove(k) }
            return
        }

        prefs.edit { putInt(k, attempts.coerceAtLeast(0)) }
    }

    fun getLimitAttempts(ctx: Context, profile: String, pkg: String): Int {
        if (profile.isBlank() || pkg.isBlank()) return 0
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)
        if (!prefs.contains(k)) return 0
        return readIntOrLongAndMigrate(prefs, k).coerceAtLeast(0)
    }

    fun getAllLimitedPackages(ctx: Context, profile: String): List<String> {
        if (profile.isBlank()) return emptyList()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = PREFIX_LIMIT + profile + "__"

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { pkg -> getLimitAttempts(ctx, profile, pkg) > 0 }
            .distinct()
            .sorted()
            .toList()
    }

    fun hasAnyLimits(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.any { it.startsWith(PREFIX_LIMIT) }
    }

    /**
     * Returns all packages that had an attempt limit set at least once (even if removed now).
     */
    fun getAllEverLimitedPackages(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.asSequence()
            .filter { it.startsWith(PREFIX_EVER) }
            .map { it.removePrefix(PREFIX_EVER) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .filter { isInstalled(ctx, it) }.toList()
    }

    /**
     * Reads Int if stored as Int, otherwise reads Long and migrates to Int.
     * If key doesn't exist or type is something else -> returns 0.
     */
    private fun readIntOrLongAndMigrate(prefs: SharedPreferences, key: String): Int {
        if (!prefs.contains(key)) return 0

        return try {
            prefs.getInt(key, 0)
        } catch (_: ClassCastException) {
            val v = try {
                prefs.getLong(key, 0L).toInt()
            } catch (_: Exception) {
                0
            }
            prefs.edit { putInt(key, v) }
            v
        }
    }
}
