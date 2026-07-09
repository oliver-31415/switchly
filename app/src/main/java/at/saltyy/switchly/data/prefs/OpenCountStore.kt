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
import java.util.Calendar

/**
 * Tracks how often an app has been opened today.
 * New key format (per profile):
 *   open_count_<yyyymmdd>__<profile>__<pkg> = Int
 * The date is computed in the device's local timezone (Calendar.getInstance()).
 */
object OpenCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "open_count_" // open_count_yyyymmdd__profile__pkg

    private fun key(ymd: Int, profile: String, pkg: String): String =
        PREFIX + ymd.toString() + "__" + profile + "__" + pkg

    private fun profilelessKey(ymd: Int, pkg: String): String =
        PREFIX + ymd.toString() + "_" + pkg

    /**
     * Returns today's open count for [pkg] in [profile].
     */
    fun getToday(ctx: Context, profile: String, pkg: String): Int {
        if (pkg.isBlank()) return 0
        val safeProfile = profile.ifBlank { ProfileStore.getCurrent(ctx) ?: "default" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ymd = todayYmdInt()
        val k = key(ymd, safeProfile, pkg)

        // Prefer new key
        if (sp.contains(k)) {
            return readIntWithLongMigration(sp, k)
        }

        // Fallback to the older profileless key and migrate
        val profileless = profilelessKey(ymd, pkg)
        if (sp.contains(profileless)) {
            val v = readIntWithLongMigration(sp, profileless)
            sp.edit {
                if (v <= 0) remove(k) else putInt(k, v)
                remove(profileless)
            }
            return v
        }

        return 0
    }

    fun setToday(ctx: Context, profile: String, pkg: String, count: Int) {
        if (pkg.isBlank()) return
        val safeProfile = profile.ifBlank { ProfileStore.getCurrent(ctx) ?: "default" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ymd = todayYmdInt()
        val k = key(ymd, safeProfile, pkg)
        val profileless = profilelessKey(ymd, pkg)

        sp.edit {
            if (count <= 0) {
                remove(k)
            } else {
                putInt(k, count.coerceAtLeast(0))
            }
            // Clean profileless key to avoid mixed behaviour.
            remove(profileless)
        }
    }

    fun incrementToday(ctx: Context, profile: String, pkg: String): Int {
        if (pkg.isBlank()) return 0
        val safeProfile = profile.ifBlank { ProfileStore.getCurrent(ctx) ?: "default" }
        val current = getToday(ctx, safeProfile, pkg)
        val next = (current + 1).coerceAtLeast(0)
        setToday(ctx, safeProfile, pkg, next)
        return next
    }

    fun getForLastNDays(ctx: Context, profile: String, pkg: String, days: Int): Int {
        if (pkg.isBlank() || days <= 0) return 0
        val safeProfile = profile.ifBlank { ProfileStore.getCurrent(ctx) ?: "default" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        var sum = 0
        repeat(days) {
            val k = key(ymdInt(cal), safeProfile, pkg)
            sum += if (sp.contains(k)) readIntWithLongMigration(sp, k) else 0
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getForMonth(ctx: Context, profile: String, pkg: String, year: Int, month1Based: Int): Int {
        if (pkg.isBlank()) return 0
        val safeProfile = profile.ifBlank { ProfileStore.getCurrent(ctx) ?: "default" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetMonth = cal.get(Calendar.MONTH)
        var sum = 0
        while (cal.get(Calendar.MONTH) == targetMonth) {
            val k = key(ymdInt(cal), safeProfile, pkg)
            sum += if (sp.contains(k)) readIntWithLongMigration(sp, k) else 0
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return sum
    }

    fun getForYear(ctx: Context, profile: String, pkg: String, year: Int): Int {
        if (pkg.isBlank()) return 0
        val safeProfile = profile.ifBlank { ProfileStore.getCurrent(ctx) ?: "default" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var sum = 0
        while (cal.get(Calendar.YEAR) == year) {
            val k = key(ymdInt(cal), safeProfile, pkg)
            sum += if (sp.contains(k)) readIntWithLongMigration(sp, k) else 0
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum
    }

    fun getOverall(ctx: Context, profile: String, pkg: String): Int {
        if (pkg.isBlank()) return 0
        val safeProfile = profile.ifBlank { ProfileStore.getCurrent(ctx) ?: "default" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0
        val suffix = "__${safeProfile}__${pkg}"
        for ((k, _v) in sp.all) {
            if (!k.startsWith(PREFIX) || !k.endsWith(suffix)) continue
            sum += readIntWithLongMigration(sp, k)
        }
        return sum
    }

    fun getForCurrentWeek(ctx: Context, profile: String, pkg: String): Int {
        val now = Calendar.getInstance()
        val currentDow = now.get(Calendar.DAY_OF_WEEK)
        val firstDow = now.firstDayOfWeek
        val diff = (7 + (currentDow - firstDow)) % 7
        return getForLastNDays(ctx, profile, pkg, diff + 1)
    }

    fun getForCurrentMonth(ctx: Context, profile: String, pkg: String): Int {
        val now = Calendar.getInstance()
        return getForMonth(ctx, profile, pkg, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
    }

    fun getForCurrentYear(ctx: Context, profile: String, pkg: String): Int {
        val now = Calendar.getInstance()
        return getForYear(ctx, profile, pkg, now.get(Calendar.YEAR))
    }

    fun getTodayAllProfiles(ctx: Context, pkg: String): Int {
        return getForDayAllProfiles(ctx, todayYmdInt(), pkg)
    }

    fun getForLastNDaysAllProfiles(ctx: Context, pkg: String, days: Int): Int {
        if (pkg.isBlank() || days <= 0) return 0
        val cal = Calendar.getInstance()
        var sum = 0
        repeat(days) {
            sum += getForDayAllProfiles(ctx, ymdInt(cal), pkg)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getForCurrentWeekAllProfiles(ctx: Context, pkg: String): Int {
        val now = Calendar.getInstance()
        val diff = (7 + (now.get(Calendar.DAY_OF_WEEK) - now.firstDayOfWeek)) % 7
        return getForLastNDaysAllProfiles(ctx, pkg, diff + 1)
    }

    fun getForCurrentMonthAllProfiles(ctx: Context, pkg: String): Int {
        val now = Calendar.getInstance()
        return getForMonthAllProfiles(ctx, pkg, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
    }

    fun getForCurrentYearAllProfiles(ctx: Context, pkg: String): Int {
        val now = Calendar.getInstance()
        return getForYearAllProfiles(ctx, pkg, now.get(Calendar.YEAR))
    }

    fun getForMonthAllProfiles(ctx: Context, pkg: String, year: Int, month1Based: Int): Int {
        if (pkg.isBlank()) return 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetMonth = cal.get(Calendar.MONTH)
        var sum = 0
        while (cal.get(Calendar.MONTH) == targetMonth) {
            sum += getForDayAllProfiles(ctx, ymdInt(cal), pkg)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return sum
    }

    fun getForYearAllProfiles(ctx: Context, pkg: String, year: Int): Int {
        if (pkg.isBlank()) return 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var sum = 0
        while (cal.get(Calendar.YEAR) == year) {
            sum += getForDayAllProfiles(ctx, ymdInt(cal), pkg)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum
    }

    fun getOverallAllProfiles(ctx: Context, pkg: String): Int {
        if (pkg.isBlank()) return 0
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0
        val profilelessSuffix = '_' + pkg
        val newSuffix = "__" + pkg
        for ((k, _v) in sp.all) {
            if (!k.startsWith(PREFIX)) continue
            when {
                k.endsWith(newSuffix) -> sum += readIntWithLongMigration(sp, k)
                k.endsWith(profilelessSuffix) && !k.contains("__") -> sum += readIntWithLongMigration(sp, k)
            }
        }
        return sum
    }

    private fun getForDayAllProfiles(ctx: Context, ymd: Int, pkg: String): Int {
        if (pkg.isBlank()) return 0
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0
        var foundNew = false
        val dayPrefix = PREFIX + ymd.toString() + "__"
        val newSuffix = "__" + pkg
        for ((k, _v) in sp.all) {
            if (!k.startsWith(dayPrefix) || !k.endsWith(newSuffix)) continue
            sum += readIntWithLongMigration(sp, k)
            foundNew = true
        }
        if (!foundNew) {
            val profileless = profilelessKey(ymd, pkg)
            if (sp.contains(profileless)) sum += readIntWithLongMigration(sp, profileless)
        }
        return sum
    }

    fun mergeProfilelessForDay(ctx: Context, ymd: Int, pkg: String, count: Int) {
        if (pkg.isBlank() || count <= 0) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = profilelessKey(ymd, pkg)
        val cur = if (sp.contains(k)) readIntWithLongMigration(sp, k) else 0
        val merged = maxOf(cur, count.coerceAtLeast(0))
        if (merged == cur) return
        sp.edit { putInt(k, merged) }
    }

    // Backwards-compatible overloads (use current profile)
    fun getToday(ctx: Context, pkg: String): Int = getToday(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg)
    fun getForLastNDays(ctx: Context, pkg: String, days: Int): Int = getForLastNDays(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg, days)
    fun getForMonth(ctx: Context, pkg: String, year: Int, month1Based: Int): Int = getForMonth(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg, year, month1Based)
    fun getForYear(ctx: Context, pkg: String, year: Int): Int = getForYear(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg, year)
    fun getOverall(ctx: Context, pkg: String): Int = getOverall(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg)
    fun getForCurrentWeek(ctx: Context, pkg: String): Int = getForCurrentWeek(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg)
    fun getForCurrentMonth(ctx: Context, pkg: String): Int = getForCurrentMonth(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg)
    fun getForCurrentYear(ctx: Context, pkg: String): Int = getForCurrentYear(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg)
    fun setToday(ctx: Context, pkg: String, count: Int) = setToday(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg, count)
    fun incrementToday(ctx: Context, pkg: String): Int = incrementToday(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg)

    private fun readIntWithLongMigration(sp: android.content.SharedPreferences, k: String): Int {
        return try {
            sp.getInt(k, 0)
        } catch (_: ClassCastException) {
            val v = runCatching { sp.getLong(k, 0L).toInt() }.getOrDefault(0)
            sp.edit { putInt(k, v) }
            v
        }
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
