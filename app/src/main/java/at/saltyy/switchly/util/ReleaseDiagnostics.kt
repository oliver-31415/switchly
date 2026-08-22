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

import android.content.Context
import android.os.Build
import at.saltyy.switchly.BuildConfig

object ReleaseDiagnostics {

    enum class Status { OK, WARNING, ERROR, NOT_APPLICABLE }

    data class Snapshot(
        val status: Status,
        val signingMessage: String,
        val currentSha1: String?,
        val signingHistory: List<String>,
        val firstInstallTime: Long,
        val lastUpdateTime: Long,
        val upgradeObserved: Boolean,
        val installerPackage: String?,
    )

    fun snapshot(context: Context): Snapshot {
        val currentSha1 = AppSigningInfo.sha1(context)
        val signingHistory = AppSigningInfo.sha1History(context)
        val status = when {
            BuildConfig.DEBUG -> Status.NOT_APPLICABLE
            !BuildConfig.SWITCHLY_RELEASE_SIGNING_CONFIGURED -> Status.WARNING
            currentSha1.isNullOrBlank() -> Status.ERROR
            else -> Status.OK
        }
        val signingMessage = when (status) {
            Status.OK -> "Official release signing was configured and an installed certificate is present."
            Status.WARNING -> "This build was produced without Switchly's configured official release signing inputs."
            Status.ERROR -> "Android did not report an installed signing certificate for this package."
            Status.NOT_APPLICABLE -> "Debug build; official release-signing check is not applicable."
        }

        val packageInfo = runCatching {
            PackageManagerApiCompat.getPackageInfo(
                context.packageManager,
                context.packageName,
            )
        }.getOrNull()
        val firstInstall = packageInfo?.firstInstallTime ?: 0L
        val lastUpdate = packageInfo?.lastUpdateTime ?: 0L
        val upgradeObserved = firstInstall > 0L && lastUpdate > firstInstall + 1_000L

        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            }.getOrNull()
        } else {
            null
        }

        return Snapshot(
            status = status,
            signingMessage = signingMessage,
            currentSha1 = currentSha1,
            signingHistory = signingHistory,
            firstInstallTime = firstInstall,
            lastUpdateTime = lastUpdate,
            upgradeObserved = upgradeObserved,
            installerPackage = installer,
        )
    }
}
