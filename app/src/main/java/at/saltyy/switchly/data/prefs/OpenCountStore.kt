package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

/**
 * Tracks how often an app has been opened today.
 * New key format (per profile):
 *   open_count_<yyyymmdd>__<profile>__<pkg> = Int
 *
 * The date is computed in the device's local timezone (Calendar.getInstance()).
 */
object OpenCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "open_count_" // open_count_yyyymmdd__profile__pkg

    private fun key(ymd: Int, profile: String, pkg: String): String =
        PREFIX + ymd.toString() + "__" + profile + "__" + pkg

    private fun legacyKey(ymd: Int, pkg: String): String =
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

        // Fallback to legacy key and migrate
        val legacy = legacyKey(ymd, pkg)
        if (sp.contains(legacy)) {
            val v = readIntWithLongMigration(sp, legacy)
            sp.edit {
                if (v <= 0) remove(k) else putInt(k, v)
                remove(legacy)
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
        val legacy = legacyKey(ymd, pkg)

        sp.edit {
            if (count <= 0) {
                remove(k)
            } else {
                putInt(k, count.coerceAtLeast(0))
            }
            // Clean legacy key to avoid mixed behaviour.
            remove(legacy)
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

    // Backwards-compatible overloads (use current profile)
    fun getToday(ctx: Context, pkg: String): Int = getToday(ctx, ProfileStore.getCurrent(ctx) ?: "default", pkg)
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
