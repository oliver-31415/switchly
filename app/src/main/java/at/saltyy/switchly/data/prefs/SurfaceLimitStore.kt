package at.saltyy.switchly.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

/**
 * Per-profile rules for in-app "surfaces" (Shorts/Reels/Explore/Stories/etc).
 *
 * Stored as an Int per (profile, surfaceKey).
 *
 * Values:
 *  -1  => always block this surface (immediate)
 *   0  => no specific rule (falls back to global in-app limit if set)
 *  >0  => daily limit in minutes
 */
object SurfaceLimitStore {
    private const val PREFS = "switchly_prefs"

    // surf_rule__<profile>__<surfaceKey>
    private const val PREFIX_RULE = "surf_rule__"

    private fun sanitizeProfile(profile: String): String {
        return profile.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }

    private fun key(profile: String, surfaceKey: String): String {
        return PREFIX_RULE + sanitizeProfile(profile) + "__" + surfaceKey
    }

    fun hasRule(ctx: Context, profile: String, surfaceKey: String): Boolean {
        if (surfaceKey.isBlank()) return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.contains(key(profile, surfaceKey))
    }

    /**
     * Returns the raw rule value for (profile, surfaceKey).
     * See [SurfaceLimitStore] doc for meaning.
     */
    fun getRule(ctx: Context, profile: String, surfaceKey: String): Int {
        if (surfaceKey.isBlank()) return 0
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, surfaceKey)
        return readIntOrMigrate(prefs, k)
    }

    /**
     * Sets the raw rule value for (profile, surfaceKey).
     * Use -1 for always block, 0 to clear, >0 for minutes/day.
     */
    fun setRule(ctx: Context, profile: String, surfaceKey: String, rule: Int) {
        if (surfaceKey.isBlank()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, surfaceKey)

        // Store 0 explicitly so we can distinguish "no saved choice" vs "user chose to use global".
        prefs.edit { putInt(k, rule) }
    }

    fun clear(ctx: Context, profile: String, surfaceKey: String) {
        if (surfaceKey.isBlank()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { remove(key(profile, surfaceKey)) }
    }

    /**
     * Convenience: returns only the positive daily limit minutes (0 if none).
     */
    fun getLimitMinutes(ctx: Context, profile: String, surfaceKey: String): Int {
        return getRule(ctx, profile, surfaceKey).coerceAtLeast(0)
    }

    fun setLimitMinutes(ctx: Context, profile: String, surfaceKey: String, minutes: Int) {
        val m = minutes.coerceAtLeast(0)
        setRule(ctx, profile, surfaceKey, m)
    }

    private fun readIntOrMigrate(prefs: SharedPreferences, key: String): Int {
        // Fast path
        try {
            return prefs.getInt(key, 0)
        } catch (_: ClassCastException) {
            // Fall through to migration path.
        }

        val any = prefs.all[key]
        val value = when (any) {
            is Int -> any
            is Long -> any.toInt()
            is Number -> any.toInt()
            is String -> any.toIntOrNull() ?: any.toLongOrNull()?.toInt() ?: 0
            else -> 0
        }

        // Migrate to stable Int storage to prevent repeated crashes.
        prefs.edit { putInt(key, value) }
        return value
    }
}
