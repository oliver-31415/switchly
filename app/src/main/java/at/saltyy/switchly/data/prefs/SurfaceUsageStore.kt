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
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily usage tracking for in-app "surfaces" like:
 *  - yt:shorts
 *  - ig:reels
 *  - ig:explore
 */
object SurfaceUsageStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX_DAY = "surf_usage_day_" // + yyyymmdd + "_" + key
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_PENDING_KEYS = 32

    private val lock = Any()
    private val pending = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastFlushAt: Long = 0L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "%04d%02d%02d".format(year, month, day)
    }

    private fun prefKey(surfaceKey: String): String = PREFIX_DAY + dayKey() + "_" + surfaceKey

    fun addUsageMsToday(context: Context, surfaceKey: String, deltaMs: Long) {
        if (surfaceKey.isBlank() || deltaMs <= 0L) {
            return
        }

        val key = prefKey(surfaceKey)
        pending.merge(key, deltaMs) { current, extra -> current + extra }
        maybeFlush(context, force = false)
    }

    fun getUsageMsToday(context: Context, surfaceKey: String): Long {
        if (surfaceKey.isBlank()) {
            return 0L
        }

        val key = prefKey(surfaceKey)
        val sharedPreferences = prefs(context)
        val persisted = sharedPreferences.getLong(key, 0L)
        val pendingValue = pending[key] ?: 0L
        return (persisted + pendingValue).coerceAtLeast(0L)
    }

    fun flush(context: Context) {
        maybeFlush(context, force = true)
    }

    private fun maybeFlush(context: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        val shouldFlush =
            force ||
                (now - lastFlushAt) >= FLUSH_INTERVAL_MS ||
                pending.size >= MAX_PENDING_KEYS
        if (!shouldFlush) {
            return
        }

        synchronized(lock) {
            val stillTooEarly =
                !force &&
                    (now - lastFlushAt) < FLUSH_INTERVAL_MS &&
                    pending.size < MAX_PENDING_KEYS
            if (stillTooEarly) {
                return
            }

            lastFlushAt = now
            val snapshot = HashMap(pending)
            pending.clear()
            if (snapshot.isEmpty()) {
                return
            }

            val sharedPreferences = prefs(context)
            sharedPreferences.edit {
                for ((key, delta) in snapshot) {
                    val current = sharedPreferences.getLong(key, 0L)
                    putLong(key, current + delta)
                }
            }
        }
    }
}
