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
import at.saltyy.switchly.data.prefs.UsageStore

/**
 * Single source of truth for app usage "today" when user-facing timers and limits are involved.
 *
 * We intentionally use Switchly's internal per-day counter here. On some devices/OEMs,
 * UsageStats for the current day can be delayed or include stale data from the previous day,
 * which causes apps to be blocked before the real daily limit is actually reached.
 */
object AppUsageToday {
    fun getUsageMsToday(
        ctx: Context,
        pkg: String,
        now: Long = System.currentTimeMillis()
    ): Long {
        return runCatching { UsageStore.getUsageMsToday(ctx, pkg) }.getOrDefault(0L)
    }
}
