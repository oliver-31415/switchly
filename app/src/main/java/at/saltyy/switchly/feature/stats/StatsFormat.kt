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

package at.saltyy.switchly.feature.stats

// Small formatting helpers shared across stats screens/adapters.
object StatsFormat {
    fun prettyMsWithSeconds(ms: Long): String {
        if (ms <= 0L) return "0m 0s"
        val totalSec = (ms/1000L).toInt()
        val h = totalSec/3600
        val m = (totalSec % 3600)/60
        val s = totalSec % 60
        return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
    }

    fun prettyPercent(p: Float): String {
        val v = (p * 100f)
        return if (v.isNaN()) "0.0%" else String.format(java.util.Locale.getDefault(), "%.1f%%", v)
    }

    fun prettyMs(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalSec = (ms/1000L).toInt()
        val h = totalSec/3600
        val m = (totalSec % 3600)/60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
    }
}
