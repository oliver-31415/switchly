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

import java.util.Locale

internal fun formatDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return when {
        hours > 0L && mins > 0L -> "${hours}h ${mins}m"
        hours > 0L -> "${hours}h"
        else -> "${mins}m"
    }
}

internal fun sanitizeProfile(profile: String): String {
    return profile
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_.-]"), "_")
        .ifBlank { "default" }
}

internal fun scopedKey(profile: String, baseKey: String): String {
    return "p_${sanitizeProfile(profile)}_$baseKey"
}

internal fun isManagedPackage(pkg: String, blocked: Set<String>): Boolean {
    // Profiles store exact package names. Using startsWith() can cause accidental matches
    // (especially with OEM/system containers), resulting in random false blocks.
    //
    // Optional: allow prefix entries when they end with ".*".
    return blocked.any { raw ->
        val e = raw.trim()
        when {
            e.isBlank() -> false
            e.endsWith(".*") -> pkg.startsWith(e.removeSuffix(".*"))
            else -> pkg == e
        }
    }
}
