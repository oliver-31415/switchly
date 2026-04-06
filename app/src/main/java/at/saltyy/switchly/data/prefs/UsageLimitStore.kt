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

object UsageLimitStore {
    private const val PREFS = "switchly_prefs"

    private fun isInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // per profile
    private const val PREFIX_LIMIT_MIN = "usage_limit_min__" // + profile + "__" + pkg

    // permanent marker: package has had a limit at least once
    private const val PREFIX_EVER_LIMIT = "usage_limit_ever__" // + pkg

    private fun key(profile: String, pkg: String) = PREFIX_LIMIT_MIN + profile + "__" + pkg

    fun setLimitMinutes(ctx: Context, profile: String, pkg: String, minutes: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)

        // If a user ever set a limit for this package, keep a permanent marker so stats can still show it even after the limit is removed later.
        if (minutes > 0) {
            prefs.edit { putBoolean(PREFIX_EVER_LIMIT + pkg, true) }
        }

        if (minutes <= 0) {
            prefs.edit { remove(k) }
            return
        }

        prefs.edit {
            putInt(k, minutes.coerceAtLeast(0))
        }
    }

    // Returns all packages that had a limit set at least once (even if it's removed now).
    fun getAllEverLimitedPackages(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(PREFIX_EVER_LIMIT) }
            .map { it.removePrefix(PREFIX_EVER_LIMIT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    fun getLimitMinutes(ctx: Context, profile: String, pkg: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)

        if (!prefs.contains(k)) return 0
        return readIntOrLongAndMigrate(prefs, k).coerceAtLeast(0)
    }

    fun getAllLimitedPackages(ctx: Context, profile: String): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = PREFIX_LIMIT_MIN + profile + "__"

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { pkg -> getLimitMinutes(ctx, profile, pkg) > 0 }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Returns all packages that currently have a limit set in **any** profile.
     * Limits are stored with keys like: usage_limit_min__<profile>__<pkg>
     */
    fun getAllLimitedPackagesAnyProfile(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(PREFIX_LIMIT_MIN) }
            .mapNotNull { key ->
                // Remove prefix, then split at "__" to drop profile part.
                val rest = key.removePrefix(PREFIX_LIMIT_MIN) // <profile>__<pkg>
                val pkg = rest.substringAfter("__", missingDelimiterValue = "")
                pkg.takeIf { it.isNotBlank() }
            }
            .distinct()
            .sorted()
            .filter { isInstalled(ctx, it) }.toList()
    }

    /**
     * Best-effort limit for a package across ALL profiles.
     *
     * This fixes cases where TODAY shows usage (bar) but no "Limit/%" because
     * the current profile has no limit, but another profile does (e.g. YouTube).
     *
     * Strategy: take the MAX minutes found for that package across profiles.
     */
    fun getBestLimitMinutesAcrossProfiles(ctx: Context, pkg: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var best = 0

        for ((k, vAny) in prefs.all) {
            if (!k.startsWith(PREFIX_LIMIT_MIN)) continue

            // Key format: usage_limit_min__<profile>__<pkg>
            // We only want exact pkg match at the end:
            if (!k.endsWith("__$pkg")) continue

            val minutes = when (vAny) {
                is Int -> vAny
                is Long -> vAny.toInt()
                is String -> vAny.toIntOrNull() ?: 0
                else -> 0
            }

            if (minutes > best) best = minutes
        }

        return best.coerceAtLeast(0)
    }

    fun hasAnyLimits(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.any { it.startsWith(PREFIX_LIMIT_MIN) }
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
