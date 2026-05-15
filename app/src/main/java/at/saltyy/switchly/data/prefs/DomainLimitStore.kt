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

/**
 * Daily per-domain limit (minutes), scoped to the active profile.
 * Older builds stored website limits globally by domain. 
 * We still read those profileless keys once so existing user data is migrated into the active profile.
 */
object DomainLimitStore {

    private const val PREFIX = "domain_limit_min_"
    private const val PROFILE_SEGMENT = "__p__"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun sanitizeProfile(profile: String): String =
        profile.trim().ifBlank { "default" }
            .replace("_", "__")
            .replace(":", "_")

    private fun currentProfile(ctx: Context): String =
        sanitizeProfile(ProfileStore.getCurrent(ctx) ?: "default")

    private fun profilelessKey(domain: String): String = PREFIX + domain

    private fun scopedKey(profile: String, domain: String): String =
        PREFIX + PROFILE_SEGMENT + profile + "__" + domain

    private fun readMinutesRaw(ctx: Context, key: String): Int {
        val p = prefs(ctx)
        val minutes = try {
            p.getInt(key, 0)
        } catch (_: ClassCastException) {
            val v = runCatching { p.getLong(key, 0L).toInt() }.getOrDefault(0)
            p.edit { putInt(key, v.coerceAtLeast(0)) }
            v
        }
        return minutes.coerceAtLeast(0)
    }

    fun getLimitMinutes(ctx: Context, domain: String): Int {
        val d = DomainBlockStore.normalize(domain) ?: return 0
        val profile = currentProfile(ctx)
        val scoped = scopedKey(profile, d)
        val scopedMinutes = readMinutesRaw(ctx, scoped)
        if (scopedMinutes > 0) return scopedMinutes

        val profileless = readMinutesRaw(ctx, profilelessKey(d))
        if (profileless > 0) {
            // One-time lazy migration for the active profile.
            prefs(ctx).edit {
                putInt(scoped, profileless)
                remove(profilelessKey(d))
            }
        }
        return profileless
    }

    fun setLimitMinutes(ctx: Context, domain: String, minutes: Int) {
        val d = DomainBlockStore.normalize(domain) ?: return
        val m = minutes.coerceAtLeast(0)
        val scoped = scopedKey(currentProfile(ctx), d)
        prefs(ctx).edit {
            if (m <= 0) remove(scoped) else putInt(scoped, m)
            // Clear profileless storage once this domain is touched in a profile-scoped build.
            remove(profilelessKey(d))
        }
    }

    fun clear(ctx: Context, domain: String) {
        val d = DomainBlockStore.normalize(domain) ?: return
        prefs(ctx).edit {
            remove(scopedKey(currentProfile(ctx), d))
            remove(profilelessKey(d))
        }
    }

    /**
     * Returns all domains that currently have a stored limit key for the active profile.
     * Profileless global keys are included and lazily migrated when they are read.
     */
    fun getDomainsWithLimit(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val profilePrefix = PREFIX + PROFILE_SEGMENT + currentProfile(ctx) + "__"
        val scoped = p.all.keys
            .asSequence()
            .filter { it.startsWith(profilePrefix) }
            .map { it.removePrefix(profilePrefix) }
            .filter { it.isNotBlank() }
            .toMutableSet()

        val profileless = p.all.keys
            .asSequence()
            .filter { it.startsWith(PREFIX) && !it.startsWith(PREFIX + PROFILE_SEGMENT) }
            .map { it.removePrefix(PREFIX) }
            .filter { it.isNotBlank() }
            .toSet()

        scoped.addAll(profileless)
        return scoped
    }

    fun onProfileRenamed(ctx: Context, oldProfile: String, newProfile: String) {
        val p = prefs(ctx)
        val oldPrefix = PREFIX + PROFILE_SEGMENT + sanitizeProfile(oldProfile) + "__"
        val newPrefix = PREFIX + PROFILE_SEGMENT + sanitizeProfile(newProfile) + "__"
        val entries = p.all
            .filterKeys { it.startsWith(oldPrefix) }
            .mapNotNull { (key, value) ->
                val domain = key.removePrefix(oldPrefix)
                if (domain.isBlank()) null else domain to when (value) {
                    is Int -> value
                    is Long -> value.toInt()
                    else -> 0
                }
            }

        if (entries.isEmpty()) return

        p.edit {
            for ((domain, minutes) in entries) {
                if (minutes > 0) putInt(newPrefix + domain, minutes)
                remove(oldPrefix + domain)
            }
        }
    }

    fun onProfileRemoved(ctx: Context, profile: String) {
        val p = prefs(ctx)
        val prefix = PREFIX + PROFILE_SEGMENT + sanitizeProfile(profile) + "__"
        val keys = p.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        p.edit {
            for (key in keys) remove(key)
        }
    }
}
