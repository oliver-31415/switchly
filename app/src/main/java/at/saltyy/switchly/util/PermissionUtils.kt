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

package at.saltyy.switchly.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

object PermissionUtils {

    // Returns true if the given AccessibilityService is enabled for this app.
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val expected = ComponentName(context, serviceClass)
            val expectedLong = expected.flattenToString()
            val expectedShort = expected.flattenToShortString()

            // Real-time system view (most reliable during runtime).
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledByManager = am
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val id = info.id.orEmpty()
                    id.equals(expectedLong, ignoreCase = true) ||
                        id.equals(expectedShort, ignoreCase = true)
                } == true
            if (enabledByManager) return true

            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            if (!enabled) return false

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            // Enabled services are stored as colon separated component names
            TextUtils.SimpleStringSplitter(':').apply {
                setString(enabledServices)
                for (s in this) {
                    if (s.equals(expectedLong, ignoreCase = true) || s.equals(expectedShort, ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}
