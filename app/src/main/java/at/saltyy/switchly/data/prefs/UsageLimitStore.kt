package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object UsageLimitStore {
    private const val PREFS = "switchly_prefs"

    // per profile
    private const val PREFIX_LIMIT_MIN = "usage_limit_min__" // + profile + "__" + pkg

    // permanent marker: package has had a limit at least once
    private const val PREFIX_EVER_LIMIT = "usage_limit_ever__" // + pkg

    private fun key(profile: String, pkg: String) = PREFIX_LIMIT_MIN + profile + "__" + pkg

    fun setLimitMinutes(ctx: Context, profile: String, pkg: String, minutes: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)

        // If a user ever set a limit for this package, keep a permanent marker so
        // stats can still show it even after the limit is removed later.
        if (minutes > 0) {
            prefs.edit { putBoolean(PREFIX_EVER_LIMIT + pkg, true) }
        }

        if (minutes <= 0) {
            prefs.edit { remove(k) }
            return
        }

        prefs.edit {
            putInt(k, minutes.coerceAtLeast(0))
        }
    }

    /**
     * Returns all packages that had a limit set at least once (even if it's removed now).
     */
    fun getAllEverLimitedPackages(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(PREFIX_EVER_LIMIT) }
            .map { it.removePrefix(PREFIX_EVER_LIMIT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    fun getLimitMinutes(ctx: Context, profile: String, pkg: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, pkg)

        if (!prefs.contains(k)) return 0

        // Strict: we store limits as Int. If the stored type is wrong, drop it.
        return try {
            prefs.getInt(k, 0).coerceAtLeast(0)
        } catch (_: ClassCastException) {
            prefs.edit { remove(k) }
            0
        }
    }

    fun getAllLimitedPackages(ctx: Context, profile: String): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = PREFIX_LIMIT_MIN + profile + "__"

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            // values are stored as Int, so checking existence is enough, but keep correctness:
            .filter { pkg -> getLimitMinutes(ctx, profile, pkg) > 0 }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Returns all packages that currently have a limit set in **any** profile.
     *
     * Limits are stored with keys like:
     *   usage_limit_min__<profile>__<pkg>
     */
    fun getAllLimitedPackagesAnyProfile(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return prefs.all.keys.asSequence()
            .filter { it.startsWith(PREFIX_LIMIT_MIN) }
            .mapNotNull { key ->
                // Remove prefix, then split at "__" to drop profile part.
                val rest = key.removePrefix(PREFIX_LIMIT_MIN) // <profile>__<pkg>
                val pkg = rest.substringAfter("__", missingDelimiterValue = "")
                pkg.takeIf { it.isNotBlank() }
            }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Best-effort limit for a package across ALL profiles.
     *
     * This fixes cases where TODAY shows usage (bar) but no "Limit / %" because
     * the current profile has no limit, but another profile does (e.g. YouTube).
     *
     * Strategy: take the MAX minutes found for that package across profiles.
     */
    fun getBestLimitMinutesAcrossProfiles(ctx: Context, pkg: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var best = 0

        for ((k, vAny) in prefs.all) {
            if (!k.startsWith(PREFIX_LIMIT_MIN)) continue
            // Key format: usage_limit_min__<profile>__<pkg>
            if (!k.endsWith("__$pkg")) continue

            val minutes = (vAny as? Int) ?: continue
            if (minutes > best) best = minutes
        }

        return best.coerceAtLeast(0)
    }

    fun hasAnyLimits(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.any { it.startsWith(PREFIX_LIMIT_MIN) }
    }
}
