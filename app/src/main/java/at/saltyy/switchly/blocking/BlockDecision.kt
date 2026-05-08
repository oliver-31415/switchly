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

internal data class AppBlockDecision(
    val shouldBlock: Boolean,
    val immediate: Boolean
) {
    companion object {
        val Allow = AppBlockDecision(shouldBlock = false, immediate = false)
    }
}

internal fun resolveAppBlockDecision(
    pkg: String,
    blockedPackages: Set<String>,
    limitMinutes: Int,
    attemptLimit: Int,
    opensExceeded: Boolean,
    effectiveUsageMsToday: Long,
    lockActive: Boolean,
    highRisk: Boolean,
    force: Boolean
): AppBlockDecision {
    // Same precedence rule as open counting: only hard-block when no limits are configured.
    val hardBlocked = isManagedPackage(pkg, blockedPackages) && limitMinutes <= 0 && attemptLimit <= 0

    // Enforcement should apply either when the app is hard-blocked OR when it has a daily/attempt limit.
    // Users can set a limit without adding the app to the hard-block list.
    val managed = isManagedPackage(pkg, blockedPackages) || limitMinutes > 0 || attemptLimit > 0
    if (!managed) return AppBlockDecision.Allow

    if (lockActive && highRisk) {
        return AppBlockDecision(shouldBlock = true, immediate = true)
    }

    val timeLimitReached = limitMinutes > 0 && (force || effectiveUsageMsToday >= limitMinutes * 60_000L)
    val shouldBlockNow = hardBlocked || opensExceeded || timeLimitReached
    if (!shouldBlockNow) return AppBlockDecision.Allow

    return AppBlockDecision(
        shouldBlock = true,
        immediate = hardBlocked || opensExceeded || force
    )
}
