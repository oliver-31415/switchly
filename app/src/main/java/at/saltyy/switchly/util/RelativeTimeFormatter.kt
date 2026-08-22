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

import android.content.Context
import at.saltyy.switchly.R
import java.text.DateFormat
import java.util.Date

object RelativeTimeFormatter {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS
    private const val ABSOLUTE_AFTER_DAYS = 7L

    fun format(context: Context, timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs <= 0L) return ""
        val delta = (nowMs - timestampMs).coerceAtLeast(0L)
        return when {
            delta < MINUTE_MS -> context.getString(R.string.relative_time_just_now)
            delta < HOUR_MS -> {
                val minutes = (delta / MINUTE_MS).coerceAtLeast(1L).toInt()
                context.resources.getQuantityString(R.plurals.relative_time_minutes_ago, minutes, minutes)
            }
            delta < DAY_MS -> {
                val hours = (delta / HOUR_MS).coerceAtLeast(1L).toInt()
                context.resources.getQuantityString(R.plurals.relative_time_hours_ago, hours, hours)
            }
            delta < 2L * DAY_MS -> context.getString(R.string.relative_time_yesterday)
            delta < ABSOLUTE_AFTER_DAYS * DAY_MS -> {
                val days = (delta / DAY_MS).coerceAtLeast(2L).toInt()
                context.resources.getQuantityString(R.plurals.relative_time_days_ago, days, days)
            }
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestampMs))
        }
    }
}
