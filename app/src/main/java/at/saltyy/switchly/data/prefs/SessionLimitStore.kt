package at.saltyy.switchly.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Per-app session limits (in minutes).
 * Session = continuous time inside the app. When the user leaves and comes back, the session counter resets.
 */
object SessionLimitStore {
    private const val PREFS = "switchly_prefs"

    private fun isInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private const val PREFIX_LIMIT_MIN = "session_limit_min__" // + profile + "__" + pkg
    private const val PREFIX_EVER_LIMIT = "session_limit_ever__" // + pkg

    private fun key(profile: String, pkg: String) = PREFIX_LIMIT_MIN + profile + "__" + pkg

    fun setLimitMinutes(ctx: Context, profile: String, pkg: String, minutes: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)

        if (minutes > 0) {
            prefs.edit { putBoolean(PREFIX_EVER_LIMIT + pkg, true) }
        }

        if (minutes <= 0) {
            prefs.edit { remove(k) }
            return
        }

        prefs.edit { putInt(k, minutes.coerceAtLeast(0)) }
    }

    fun getLimitMinutes(ctx: Context, profile: String, pkg: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)
        if (!prefs.contains(k)) return 0
        return readIntOrLongAndMigrate(prefs, k).coerceAtLeast(0)
    }

    fun getAllLimitedPackages(ctx: Context, profile: String): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = PREFIX_LIMIT_MIN + profile + "__"

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { pkg -> getLimitMinutes(ctx, profile, pkg) > 0 }
            .distinct()
            .sorted()
            .toList()
    }

    fun getAllEverLimitedPackages(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.asSequence()
            .filter { it.startsWith(PREFIX_EVER_LIMIT) }
            .map { it.removePrefix(PREFIX_EVER_LIMIT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .filter { isInstalled(ctx, it) }.toList()
    }

    private fun readIntOrLongAndMigrate(prefs: SharedPreferences, key: String): Int {
        if (!prefs.contains(key)) return 0

        return try {
            prefs.getInt(key, 0)
        } catch (_: ClassCastException) {
            val v = try {
                prefs.getLong(key, 0L).toInt()
            } catch (_: Exception) {
                0
            }
            prefs.edit { putInt(key, v) }
            v
        }
    }
}
