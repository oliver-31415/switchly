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
import at.saltyy.switchly.feature.usage.UsageStatsRepo

/**
 * Small helper to read system-reported foreground time for "today".
 * Note: Some devices only update totals when the app is backgrounded.
 * Use this as a best-effort signal (e.g., for UI and as a fallback for enforcement), not as the only real-time source.
 */
object SystemUsageToday {
    fun getUsageMsToday(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): Long {
        return try {
            UsageStatsRepo.getTodayMsForPackage(ctx, pkg, now)
        } catch (_: Throwable) {
            0L
        }
    }
}
