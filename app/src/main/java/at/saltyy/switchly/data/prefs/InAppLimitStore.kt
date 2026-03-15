package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

/**
 * Profile-based global in-app daily limit (minutes).
 * This is the "overall budget" for timed in-app sections.
 * If set and reached, all timed in-app sections will be blocked (even if they have their own per-surface limit).
 */
object InAppLimitStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "inapp_limit_min__" // + profile 

    private fun sanitizeProfile(profile: String): String {
        return profile.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }

    private fun key(profile: String): String = PREFIX + sanitizeProfile(profile)

    private fun readMinutesCompat(prefs: android.content.SharedPreferences, key: String): Int {
        val any = prefs.all[key]
        return when (any) {
            is Int -> any
            is Long -> any.toInt()
            is String -> any.toIntOrNull() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }

    fun getLimitMinutes(ctx: Context, profile: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile)
        if (!prefs.contains(k)) return 0
        return readMinutesCompat(prefs, k)
    }

    fun setLimitMinutes(ctx: Context, profile: String, minutes: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile)
        val m = minutes.coerceAtLeast(0)
        if (m <= 0) {
            prefs.edit { remove(k) }
        } else {
            prefs.edit { putInt(k, m) }
        }
    }
}
