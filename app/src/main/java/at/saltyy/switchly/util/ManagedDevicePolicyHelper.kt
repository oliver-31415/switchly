/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package at.saltyy.switchly.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.receiver.DPMReceiver

object ManagedDevicePolicyHelper {
    fun syncSelfUninstallBlock(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DPMReceiver::class.java)
        val hasManagedOwnership = dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)
        if (!hasManagedOwnership) return

        val shouldBlock = AutomationModeStore.isUninstallFrictionEnabled(context) && SwitchModeStore.isEnabled(context)
        runCatching {
            dpm.setUninstallBlocked(admin, context.packageName, shouldBlock)
            AppLogStore.append(context, "ManagedPolicy", "setUninstallBlocked self=$shouldBlock")
        }.onFailure {
            AppLogStore.append(context, "ManagedPolicy", "setUninstallBlocked failed: ${it.message}")
        }
    }
}
