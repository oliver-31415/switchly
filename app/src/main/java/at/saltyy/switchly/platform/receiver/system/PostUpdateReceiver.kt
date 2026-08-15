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

package at.saltyy.switchly.platform.receiver.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.saltyy.switchly.platform.receiver.location.LocationTriggerMonitor
import at.saltyy.switchly.util.PersistentStatusNotifier
import at.saltyy.switchly.util.ProtectionStatusNotifier

/**
 * Triggered after an app update (ACTION_MY_PACKAGE_REPLACED).
 * Android may need a moment to reconnect Accessibility after replacing the package, so use the shared verified/grace-period warning flow instead of showing an immediate notification.
 */
class PostUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val appContext = ctx.applicationContext
        runCatching { LocationTriggerMonitor.ensureStarted(appContext) }
        runCatching { ProtectionStatusNotifier.refresh(appContext) }
        runCatching { PersistentStatusNotifier.refresh(appContext) }
    }
}
