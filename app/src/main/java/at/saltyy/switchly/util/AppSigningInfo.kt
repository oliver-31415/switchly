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
import android.content.pm.Signature
import androidx.core.content.pm.PackageInfoCompat
import java.security.MessageDigest
import java.util.Locale

object AppSigningInfo {

    /**
     * Returns the currently active signing certificate where Android exposes a rotation lineage.
     * PackageInfoCompat handles the pre-Android-9 compatibility path without direct use of the deprecated PackageInfo.signatures/GET_SIGNATURES APIs in Switchly code.
     */
    fun sha1(context: Context): String? = signatures(context)
        .lastOrNull()
        ?.let(::sha1)

    /**
     * Returns the signing certificate lineage known to Android.
     * For a rotated single signer, Android reports the lineage from the original certificate through the current certificate.
     */
    fun sha1History(context: Context): List<String> = signatures(context)
        .map(::sha1)
        .distinct()

    private fun signatures(context: Context): List<Signature> = runCatching {
        PackageInfoCompat.getSignatures(
            context.packageManager,
            context.packageName,
        ).toList()
    }.getOrDefault(emptyList())

    private fun sha1(signature: Signature): String = MessageDigest.getInstance("SHA-1")
        .digest(signature.toByteArray())
        .joinToString(":") { byte ->
            String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
        }
}
