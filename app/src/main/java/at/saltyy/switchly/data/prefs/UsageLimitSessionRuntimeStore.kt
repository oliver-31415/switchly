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

/**
 * Lightweight runtime mirror for per-active-session app limits.
 * Enforcement stays in the Accessibility service, but Home/Statistics screens need a user-facing source of truth so they don't show stale daily "limit reached" state after a new session starts.
 */
object UsageLimitSessionRuntimeStore {
    data class State(
        val profile: String,
        val pkg: String,
        val generation: Long,
        val startedAt: Long,
        val usedMs: Long,
        val limitMs: Long,
        val reached: Boolean
    ) {
        val remainingMs: Long get() = (limitMs - usedMs).coerceAtLeast(0L)
    }

    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "usage_limit_session_runtime__"
    private const val SEP = "__"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun safe(part: String): String = part.replace("_", "__")
    private fun prefix(profile: String, pkg: String): String = PREFIX + safe(profile) + SEP + safe(pkg)

    fun update(
        context: Context,
        profile: String,
        pkg: String,
        generation: Long,
        startedAt: Long,
        usedMs: Long,
        limitMs: Long,
        reached: Boolean
    ) {
        if (profile.isBlank() || pkg.isBlank() || limitMs <= 0L) {
            return
        }
        val p = prefix(profile, pkg)
        prefs(context).edit {
            putLong("${p}_generation", generation)
            putLong("${p}_started_at", startedAt)
            putLong("${p}_used_ms", usedMs.coerceAtLeast(0L))
            putLong("${p}_limit_ms", limitMs.coerceAtLeast(0L))
            putBoolean("${p}_reached", reached)
        }
    }

    fun get(context: Context, profile: String, pkg: String): State? {
        if (profile.isBlank() || pkg.isBlank()) {
            return null
        }
        val p = prefix(profile, pkg)
        val sp = prefs(context)
        val generation = sp.getLong("${p}_generation", -1L)
        if (generation < 0L) {
            return null
        }
        val currentGeneration = SwitchModeStore.getLimitSessionGeneration(context)
        if (generation != currentGeneration) {
            return null
        }
        val limitMs = sp.getLong("${p}_limit_ms", 0L)
        if (limitMs <= 0L) {
            return null
        }
        return State(
            profile = profile,
            pkg = pkg,
            generation = generation,
            startedAt = sp.getLong("${p}_started_at", 0L),
            usedMs = sp.getLong("${p}_used_ms", 0L).coerceAtLeast(0L),
            limitMs = limitMs,
            reached = sp.getBoolean("${p}_reached", false)
        )
    }

    fun clearAll(context: Context) {
        val sp = prefs(context)
        val keys = sp.all.keys.filter { it.startsWith(PREFIX) }
        if (keys.isEmpty()) {
            return
        }
        sp.edit { keys.forEach { remove(it) } }
    }
}
