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
