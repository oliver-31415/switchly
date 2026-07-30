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

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Uses the typed PackageManager flag APIs on Android 13+ while keeping older
 * Android versions isolated from deprecated overloads.
 */
object PackageManagerApiCompat {
    fun getApplicationInfo(
        packageManager: PackageManager,
        packageName: String,
        flags: Long = 0L,
    ): ApplicationInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.getApplicationInfo(packageManager, packageName, flags)
        } else {
            Legacy.getApplicationInfo(packageManager, packageName, flags)
        }
    }

    fun queryIntentActivities(
        packageManager: PackageManager,
        intent: Intent,
        flags: Long = 0L,
    ): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.queryIntentActivities(packageManager, intent, flags)
        } else {
            Legacy.queryIntentActivities(packageManager, intent, flags)
        }
    }

    fun resolveActivity(
        packageManager: PackageManager,
        intent: Intent,
        flags: Long = 0L,
    ): ResolveInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.resolveActivity(packageManager, intent, flags)
        } else {
            Legacy.resolveActivity(packageManager, intent, flags)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun getApplicationInfo(
            packageManager: PackageManager,
            packageName: String,
            flags: Long,
        ): ApplicationInfo {
            return packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(flags),
            )
        }

        fun queryIntentActivities(
            packageManager: PackageManager,
            intent: Intent,
            flags: Long,
        ): List<ResolveInfo> {
            return packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(flags),
            )
        }

        fun resolveActivity(
            packageManager: PackageManager,
            intent: Intent,
            flags: Long,
        ): ResolveInfo? {
            return packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(flags),
            )
        }
    }

    private object Legacy {
        private val getApplicationInfoMethod: Method by lazy {
            PackageManager::class.java.getMethod(
                "getApplicationInfo",
                String::class.java,
                Integer.TYPE,
            )
        }
        private val queryIntentActivitiesMethod: Method by lazy {
            PackageManager::class.java.getMethod(
                "queryIntentActivities",
                Intent::class.java,
                Integer.TYPE,
            )
        }
        private val resolveActivityMethod: Method by lazy {
            PackageManager::class.java.getMethod(
                "resolveActivity",
                Intent::class.java,
                Integer.TYPE,
            )
        }

        fun getApplicationInfo(
            packageManager: PackageManager,
            packageName: String,
            flags: Long,
        ): ApplicationInfo {
            return invoke(getApplicationInfoMethod, packageManager, packageName, flags.toInt())
                as ApplicationInfo
        }

        fun queryIntentActivities(
            packageManager: PackageManager,
            intent: Intent,
            flags: Long,
        ): List<ResolveInfo> {
            val result = invoke(queryIntentActivitiesMethod, packageManager, intent, flags.toInt())
            return (result as? List<*>).orEmpty().filterIsInstance<ResolveInfo>()
        }

        fun resolveActivity(
            packageManager: PackageManager,
            intent: Intent,
            flags: Long,
        ): ResolveInfo? {
            return invoke(resolveActivityMethod, packageManager, intent, flags.toInt()) as? ResolveInfo
        }

        private fun invoke(method: Method, receiver: Any, vararg arguments: Any): Any? {
            return try {
                method.invoke(receiver, *arguments)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }
}
