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
import java.util.Calendar

/**
 * Per-profile usage counter used for enforcing profile-specific app limits.
 *
 * The general usage dashboard can keep using [UsageStore], but blocking decisions need to be scoped to the active profile.
 * Otherwise an app that was hard-blocked in one profile can look as if it already consumed time in another profile after an NFC/schedule profile switch.
 */
object ProfileUsageStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX_DAY = "profile_usage_day_" // profile_usage_day_yyyymmdd_profile_pkg
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_PENDING_KEYS = 32

    private val lock = Any()
    private val pending = HashMap<String, Long>()
    @Volatile private var lastFlushAtMs: Long = 0L

    fun addUsageMsToday(ctx: Context, profile: String, pkg: String, deltaMs: Long) {
        if (profile.isBlank() || pkg.isBlank() || deltaMs <= 0L) return
        val key = dayKey(todayYmdInt(), profile, pkg)
        val now = System.currentTimeMillis()
        var shouldFlush = false

        synchronized(lock) {
            pending[key] = (pending[key] ?: 0L) + deltaMs
            shouldFlush = (now - lastFlushAtMs) >= FLUSH_INTERVAL_MS || pending.size >= MAX_PENDING_KEYS
        }

        if (shouldFlush) flush(ctx)
    }

    fun getUsageMsToday(ctx: Context, profile: String, pkg: String): Long {
        if (profile.isBlank() || pkg.isBlank()) return 0L
        val key = dayKey(todayYmdInt(), profile, pkg)
        val persisted = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        val buffered = synchronized(lock) { pending[key] ?: 0L }
        return persisted + buffered
    }

    fun setUsageMsToday(ctx: Context, profile: String, pkg: String, ms: Long) {
        if (profile.isBlank() || pkg.isBlank()) return
        flush(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(dayKey(todayYmdInt(), profile, pkg), ms.coerceAtLeast(0L))
        }
    }

    fun flush(ctx: Context) {
        val snapshot: Map<String, Long>
        synchronized(lock) {
            if (pending.isEmpty()) {
                lastFlushAtMs = System.currentTimeMillis()
                return
            }
            snapshot = HashMap(pending)
            pending.clear()
            lastFlushAtMs = System.currentTimeMillis()
        }

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            snapshot.forEach { (key, delta) ->
                val current = sp.getLong(key, 0L).coerceAtLeast(0L)
                putLong(key, current + delta.coerceAtLeast(0L))
            }
        }
    }

    private fun dayKey(ymd: Int, profile: String, pkg: String): String {
        return PREFIX_DAY + ymd + "_" + profile.safeKeyPart() + "_" + pkg.safeKeyPart()
    }

    private fun String.safeKeyPart(): String = replace("_", "__")

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(calendar: Calendar): Int {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return (year * 10000) + (month * 100) + day
    }
}
