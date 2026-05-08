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

package at.saltyy.switchly.platform.receiver.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.ProfileStore

class PackageAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        if (pkg == context.packageName) return

        val changedProfiles = ProfileStore.addBlockedAppToAutoBlockProfiles(context, pkg)
        if (changedProfiles > 0) {
            AppLogStore.append(
                context,
                "Blocking",
                "Newly installed app auto-added package=$pkg profiles=$changedProfiles"
            )
            BlockingRuntime.ensureRunning(context)
        }
    }
}
