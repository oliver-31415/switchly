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

package at.saltyy.switchly.feature.usage

import android.content.Context
import at.saltyy.switchly.data.prefs.IgnoredUsageAppsStore
import at.saltyy.switchly.data.statistics.UsageInsightsAppCatalog

/**
 * Applies one exclusion policy to every app-based Usage & Insights surface.
 * Blocking rules, profile membership and stored raw counters remain untouched.
 */
object UsageInsightsAppFilter {
    fun shouldHide(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim()
        if (shouldAlwaysHide(normalized)) return true
        return IgnoredUsageAppsStore.isIgnored(context, normalized)
    }

    fun shouldAlwaysHide(packageName: String): Boolean {
        return UsageInsightsAppCatalog.shouldAlwaysHide(packageName)
    }

    fun <T> filterMap(context: Context, values: Map<String, T>): Map<String, T> {
        return values.filterKeys { packageName -> !shouldHide(context, packageName) }
    }

}
