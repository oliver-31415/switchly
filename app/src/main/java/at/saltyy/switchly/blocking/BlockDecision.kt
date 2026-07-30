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
    force: Boolean,
    allowMode: Boolean = false,
    essentialAllowed: Boolean = false,
    allowModeListed: Boolean = false
): AppBlockDecision {
    val selected = isManagedPackage(pkg, blockedPackages)
    val hasLimit = limitMinutes > 0 || attemptLimit > 0

    // In blocklist mode, selected apps are hard-blocked unless they have a limit.
    // In allow-selected mode, the picker list is the boundary:
    // - listed + selected => allowed/exempted
    // - listed + unselected => blocked
    // - not listed in the picker => allowed, because the user cannot select it
    val hardBlocked = if (allowMode) {
        allowModeListed && !selected && !hasLimit && !essentialAllowed
    } else {
        selected && !hasLimit
    }

    // Enforcement should apply either when the app is hard-blocked OR when it has a daily/attempt limit.
    // In allow-selected mode, apps outside the picker list are unmanaged/allowed unless another limit applies.
    val managed = if (allowMode) {
        hardBlocked || selected || hasLimit
    } else {
        hardBlocked || selected || hasLimit
    }
    if (!managed) {
        return AppBlockDecision.Allow
    }

    if (lockActive && highRisk) {
        return AppBlockDecision(shouldBlock = true, immediate = true)
    }

    // Force is used for immediate re-checks after foreground corrections, schedule ticks, or other reliability probes.
    // It must not turn a limited app into a block before the configured daily usage limit is actually reached.
    val timeLimitReached = limitMinutes > 0 && effectiveUsageMsToday >= limitMinutes * 60_000L
    val shouldBlockNow = hardBlocked || opensExceeded || timeLimitReached
    if (!shouldBlockNow) {
        return AppBlockDecision.Allow
    }

    return AppBlockDecision(
        shouldBlock = true,
        // Once a daily time limit is reached, treat it like an immediate block too.
        // This keeps the time-limit path aligned with direct/attempt blocking and improves  blocker Activity launch reliability on OEM devices that are sensitive to delayed launches.
        immediate = hardBlocked || opensExceeded || timeLimitReached || force
    )
}
