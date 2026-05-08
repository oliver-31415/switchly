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

package at.saltyy.switchly.blocking

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

internal class UsageEventsForegroundResolver(
    private val context: Context
) {
    fun resolveTopPackage(start: Long, end: Long): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()
        var lastPkg: String? = null
        var lastTs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.isActivityResumed()) {
                val pkg = event.packageName
                if (event.timeStamp >= lastTs && !pkg.isNullOrBlank()) {
                    lastTs = event.timeStamp
                    lastPkg = pkg
                }
            }
        }
        return lastPkg
    }

    fun queryForegroundEvents(start: Long, end: Long): List<Pair<String, Long>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val events = try {
            usm.queryEvents(start, end)
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }

        val out = ArrayList<Pair<String, Long>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.isActivityResumed()) {
                val pkg = event.packageName?.trim().orEmpty()
                if (pkg.isNotBlank() && pkg != context.packageName) {
                    out += pkg to event.timeStamp
                }
            }
        }
        return out
    }

    private fun UsageEvents.Event.isActivityResumed(): Boolean {
        // Foreground/resume usage events use value 1 across supported Android versions.
        // Keep this local constant to support minSdk 27 without referencing newer SDK fields directly.
        return eventType == EVENT_ACTIVITY_RESUMED
    }

    private companion object {
        private const val EVENT_ACTIVITY_RESUMED = 1
    }

}
