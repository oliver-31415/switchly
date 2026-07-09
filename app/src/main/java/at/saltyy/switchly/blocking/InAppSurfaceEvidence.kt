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

internal class InAppSurfaceEvidence {
    private val recentSurfaceHintAtByPkg = HashMap<String, Long>()
    private val recentSurfaceHintKeyByPkg = HashMap<String, String>()
    private val surfaceEvidenceAt = HashMap<String, Long>()
    private val surfaceEvidenceCount = HashMap<String, Int>()

    fun rememberSurfaceHint(pkg: String, key: String, now: Long) {
        recentSurfaceHintKeyByPkg[pkg] = key
        recentSurfaceHintAtByPkg[pkg] = now
    }

    fun recentSurfaceHintMatches(pkg: String, key: String, ttlMs: Long, now: Long): Boolean {
        val hintKey = recentSurfaceHintKeyByPkg[pkg] ?: return false
        val hintAt = recentSurfaceHintAtByPkg[pkg] ?: return false
        return hintKey == key && (now - hintAt) <= ttlMs
    }

    fun clearSurfaceHintForPackage(pkg: String) {
        recentSurfaceHintKeyByPkg.remove(pkg)
        recentSurfaceHintAtByPkg.remove(pkg)
    }

    fun clearSurfaceEvidence(vararg keys: String) {
        for (key in keys) {
            surfaceEvidenceCount.remove(key)
            surfaceEvidenceAt.remove(key)
        }
    }

    fun surfaceKeysForPackage(pkg: String): Array<String> = when (pkg) {
        "com.google.android.youtube" -> arrayOf("yt:home", "yt:shorts", "yt:subscriptions", "yt:you")
        "com.instagram.android" -> arrayOf("ig:reels", "ig:explore", "ig:search", "ig:stories")
        "com.twitter.android" -> arrayOf("x:foryou", "x:search", "x:grok", "x:notifications")
        "com.snapchat.android" -> arrayOf("snap:map", "snap:stories", "snap:spotlight", "snap:following")
        else -> emptyArray()
    }

    fun surfaceConfirmed(key: String, required: Int, confirmMs: Long, now: Long): Boolean {
        val lastAt = surfaceEvidenceAt[key] ?: 0L
        val count = if (now - lastAt <= confirmMs) (surfaceEvidenceCount[key] ?: 0) + 1 else 1
        surfaceEvidenceAt[key] = now
        surfaceEvidenceCount[key] = count
        return count >= required.coerceAtLeast(1)
    }
}
