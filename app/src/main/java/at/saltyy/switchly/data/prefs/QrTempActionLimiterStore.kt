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

package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import at.saltyy.switchly.nfc.NfcSchema
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Limits temporary QR actions by scanned QR payload.
 *
 * NFC temporary limits can use a physical tag UID. 
 * QR codes do not have a UID, so the limiter uses a stable hash of the scanned Switchly URI instead.
 */
object QrTempActionLimiterStore {

    const val DEFAULT_DAILY_USES_PER_CODE = 3
    const val DEFAULT_COOLDOWN_MINUTES = 60

    private const val KEY_LAST_PREFIX = "qr_temp_last_"
    private const val KEY_COUNT_PREFIX = "qr_temp_count_"

    sealed interface CheckResult {
        object Allowed : CheckResult
        data class Cooldown(val minutesRemaining: Int) : CheckResult
        data class DailyLimitReached(val usedToday: Int, val limit: Int) : CheckResult
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(BlockingToggleKeys.KEY_LIMIT_TEMP_QR_CODES, false)

    fun isLimitedTemporaryAction(rawUri: String): Boolean {
        val uri = runCatching { rawUri.trim().toUri() }.getOrNull() ?: return false
        if (!uri.scheme.equals("switchly", ignoreCase = true)) return false
        if (!NfcSchema.isKnownHost(uri.host)) return false

        val action = when (uri.host?.lowercase()) {
            NfcSchema.HOST_SWITCH -> uri.pathSegments.getOrNull(0)
            NfcSchema.HOST_PROFILE -> uri.pathSegments.getOrNull(1)
            else -> null
        }?.trim()?.lowercase().orEmpty()

        return action.startsWith("temp_disable") || action.startsWith("reentry")
    }

    fun check(rawUri: String, ctx: Context): CheckResult {
        val bucket = bucketForRaw(rawUri)
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val cooldownMs = DEFAULT_COOLDOWN_MINUTES * 60_000L

        val last = p.getLong("$KEY_LAST_PREFIX$bucket", 0L)
        if (last > 0L) {
            val elapsed = now - last
            if (elapsed in 0 until cooldownMs) {
                val remainingMs = cooldownMs - elapsed
                val mins = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                return CheckResult.Cooldown(mins)
            }
        }

        val day = LocalDate.now().toEpochDay()
        val countKey = "$KEY_COUNT_PREFIX${bucket}_$day"
        val todayCount = p.getInt(countKey, 0)
        if (todayCount >= DEFAULT_DAILY_USES_PER_CODE) {
            return CheckResult.DailyLimitReached(todayCount, DEFAULT_DAILY_USES_PER_CODE)
        }

        return CheckResult.Allowed
    }

    fun consume(rawUri: String, ctx: Context) {
        val bucket = bucketForRaw(rawUri)
        val p = prefs(ctx)
        val day = LocalDate.now().toEpochDay()
        val countKey = "$KEY_COUNT_PREFIX${bucket}_$day"
        val current = p.getInt(countKey, 0)

        p.edit {
            putLong("$KEY_LAST_PREFIX$bucket", System.currentTimeMillis())
            putInt(countKey, current + 1)
        }
    }

    private fun bucketForRaw(rawUri: String): String {
        val normalized = rawUri.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(32)
    }
}
