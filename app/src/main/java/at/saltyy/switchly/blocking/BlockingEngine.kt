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

import android.content.Context
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.TempAllowStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.util.AppBlockSafety

class BlockingEngine {

    data class PolicySnapshot(
        val enabled: Boolean,
        val emergencyBypass: Boolean,
        val blockedPrefixes: Set<String>,
        val tempAllowedPkgs: Set<String>
    )

    fun shouldBlock(context: Context, topPackage: String?): Boolean {
        if (topPackage.isNullOrBlank()) return false
        if (AppBlockSafety.isHardExcluded(context, topPackage)) return false
        if (EmergencyBypassStore.isActive(context)) return false
        if (!SwitchModeStore.isEnabled(context)) return false
        if (TempAllowStore.isAllowed(context, topPackage)) return false

        val profile = ProfileStore.getCurrent(context) ?: return false
        val blocked = ProfileStore.getBlockedForProfile(context, profile)
        return blocked.any { it.isNotBlank() && topPackage.startsWith(it) }
    }

}
