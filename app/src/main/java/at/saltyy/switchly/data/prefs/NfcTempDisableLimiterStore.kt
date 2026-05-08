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
import androidx.preference.PreferenceManager
import java.time.LocalDate

/**
 * Scope: per tag UID (fallback bucket "global" if UID unavailable).
 * Defaults (intentionally conservative):
 * - max [DEFAULT_DAILY_USES_PER_TAG] uses per day and tag
 * - [DEFAULT_COOLDOWN_MINUTES] minutes cooldown between uses for the same tag
 */
object NfcTempDisableLimiterStore {

    const val DEFAULT_DAILY_USES_PER_TAG = 3
    const val DEFAULT_COOLDOWN_MINUTES = 60

    private const val KEY_LAST_PREFIX = "nfc_td_last_" // + uidBucket -> epoch millis
    private const val KEY_COUNT_PREFIX = "nfc_td_count_" // + uidBucket + _ + epochDay -> int

    // Optional per-tag overrides (configured from Paired tags -> edit UID)
    private const val KEY_CFG_DAILY_PREFIX = "nfc_td_cfg_daily_" // + uidBucket -> int
    private const val KEY_CFG_COOLDOWN_PREFIX = "nfc_td_cfg_cooldown_" // + uidBucket -> int

    data class TagConfig(
        val dailyLimit: Int,
        val cooldownMinutes: Int,
        val hasDailyOverride: Boolean,
        val hasCooldownOverride: Boolean,
    )

    sealed interface CheckResult {
        object Allowed : CheckResult
        data class Cooldown(val minutesRemaining: Int) : CheckResult
        data class DailyLimitReached(val usedToday: Int, val limit: Int) : CheckResult
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun isEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getBoolean(BlockingToggleKeys.KEY_LIMIT_TEMP_DISABLE_TAGS, false)) return true
        return p.all.keys.any { key ->
            key.startsWith(KEY_CFG_DAILY_PREFIX) || key.startsWith(KEY_CFG_COOLDOWN_PREFIX)
        }
    }

    fun getTagConfig(uidBucket: String, ctx: Context): TagConfig {
        val bucket = bucketForUid(uidBucket)
        val p = prefs(ctx)

        val dailyKey = "$KEY_CFG_DAILY_PREFIX$bucket"
        val cooldownKey = "$KEY_CFG_COOLDOWN_PREFIX$bucket"

        val hasDaily = p.contains(dailyKey)
        val hasCooldown = p.contains(cooldownKey)

        val daily = p.getInt(dailyKey, DEFAULT_DAILY_USES_PER_TAG)
            .coerceAtLeast(1)
        val cooldown = p.getInt(cooldownKey, DEFAULT_COOLDOWN_MINUTES)
            .coerceAtLeast(1)

        return TagConfig(
            dailyLimit = daily,
            cooldownMinutes = cooldown,
            hasDailyOverride = hasDaily,
            hasCooldownOverride = hasCooldown,
        )
    }

    fun getDailyLimitOverride(uidBucket: String, ctx: Context): Int? {
        val bucket = bucketForUid(uidBucket)
        val p = prefs(ctx)
        val key = "$KEY_CFG_DAILY_PREFIX$bucket"
        if (!p.contains(key)) return null
        return p.getInt(key, DEFAULT_DAILY_USES_PER_TAG).coerceAtLeast(1)
    }

    fun getCooldownOverrideMinutes(uidBucket: String, ctx: Context): Int? {
        val bucket = bucketForUid(uidBucket)
        val p = prefs(ctx)
        val key = "$KEY_CFG_COOLDOWN_PREFIX$bucket"
        if (!p.contains(key)) return null
        return p.getInt(key, DEFAULT_COOLDOWN_MINUTES).coerceAtLeast(1)
    }

    // Saves optional per-tag limits. Pass null to remove an override and fall back to defaults.
    fun setTagConfig(
        uidBucket: String,
        ctx: Context,
        dailyLimit: Int?,
        cooldownMinutes: Int?,
    ) {
        val bucket = bucketForUid(uidBucket)
        val p = prefs(ctx)

        val sanitizedDaily = dailyLimit
            ?.coerceIn(1, 50)

        val sanitizedCooldown = cooldownMinutes
            ?.coerceIn(1, 24 * 60)

        p.edit {
            if (sanitizedDaily == null) {
                remove("$KEY_CFG_DAILY_PREFIX$bucket")
            } else {
                putInt("$KEY_CFG_DAILY_PREFIX$bucket", sanitizedDaily)
            }

            if (sanitizedCooldown == null) {
                remove("$KEY_CFG_COOLDOWN_PREFIX$bucket")
            } else {
                putInt("$KEY_CFG_COOLDOWN_PREFIX$bucket", sanitizedCooldown)
            }
        }
    }

    fun clearTagConfig(uidBucket: String, ctx: Context) {
        val bucket = bucketForUid(uidBucket)
        prefs(ctx).edit {
            remove("$KEY_CFG_DAILY_PREFIX$bucket")
            remove("$KEY_CFG_COOLDOWN_PREFIX$bucket")
        }
    }

    fun check(uidBucket: String, ctx: Context): CheckResult {
        val bucket = bucketForUid(uidBucket)
        val p = prefs(ctx)
        val config = getTagConfig(bucket, ctx)

        val now = System.currentTimeMillis()
        val cooldownMs = config.cooldownMinutes * 60_000L
        val last = p.getLong("$KEY_LAST_PREFIX$bucket", 0L)
        if (last > 0L) {
            val elapsed = now - last
            if (elapsed in 0 until cooldownMs) {
                val remainingMs = cooldownMs - elapsed
                val mins = ((remainingMs + 59_999L)/60_000L).toInt().coerceAtLeast(1)
                return CheckResult.Cooldown(mins)
            }
        }

        val day = LocalDate.now().toEpochDay()
        val todayCount = p.getInt("$KEY_COUNT_PREFIX${bucket}_$day", 0)
        if (todayCount >= config.dailyLimit) {
            return CheckResult.DailyLimitReached(todayCount, config.dailyLimit)
        }

        return CheckResult.Allowed
    }

    fun consume(uidBucket: String, ctx: Context) {
        val bucket = bucketForUid(uidBucket)
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val day = LocalDate.now().toEpochDay()
        val countKey = "$KEY_COUNT_PREFIX${bucket}_$day"

        val current = p.getInt(countKey, 0)
        p.edit {
            putLong("$KEY_LAST_PREFIX$bucket", now)
            putInt(countKey, current + 1)
        }
    }

    fun bucketForUid(uidHex: String?): String =
        uidHex
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
            ?: "global"
}
