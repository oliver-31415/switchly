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

package at.saltyy.switchly.premium

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Workaround for a rare Play Billing crash where ProxyBillingActivity can be started without the required PendingIntent extras.
 * Strategy:
 *  - Keep ProxyBillingActivity disabled by default.
 *  - Enable it only right before starting a purchase flow.
 *  - Disable it again after the flow completes (or times out).
 */
object BillingProxyActivityGate {

    private const val TAG = "BillingProxyGate"
    private const val PROXY_ACTIVITY = "com.android.billingclient.api.ProxyBillingActivity"

    fun enable(context: Context) = setEnabled(context, true)

    fun disable(context: Context) = setEnabled(context, false)

    private fun setEnabled(context: Context, enabled: Boolean) {
        val cn = ComponentName(context.packageName, PROXY_ACTIVITY)
        val pm = context.packageManager

        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        runCatching {
            pm.setComponentEnabledSetting(cn, newState, PackageManager.DONT_KILL_APP)
        }.onFailure { t ->
            // If the component is missing (e.g. stripped builds) or PM rejects the call, don't crash the app.
            Log.w(TAG, "Failed to set ProxyBillingActivity enabled=$enabled", t)
        }
    }
}
