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
import java.net.IDN
import java.util.Locale

object DomainBlockStore {

    private const val KEY_ENABLED = "domain_block_enabled"
    private const val KEY_DOMAINS = "domain_block_domains"
    private const val KEY_PROFILE_MIGRATION_DONE = "domain_block_domains_profile_migration_done"
    private const val PREFIX_PROFILE_DOMAINS = "domain_block_domains__p__"
    private const val PREFIX_PROFILE_ALLOWED_DOMAINS = "domain_allowed_domains__p__"
    private const val PREFIX_PROFILE_DISABLED_RULES = "domain_rule_disabled__p__"

    fun isEnabled(ctx: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!sp.getBoolean(KEY_ENABLED, true)) {
            sp.edit { putBoolean(KEY_ENABLED, true) }
        }
        return true
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun sanitizeProfile(profile: String): String =
        profile.trim().ifBlank { "default" }
            .replace("_", "__")
            .replace(":", "_")

    private fun currentProfile(ctx: Context): String =
        sanitizeProfile(ProfileStore.getCurrent(ctx) ?: "default")

    private fun keyForProfile(profile: String): String =
        PREFIX_PROFILE_DOMAINS + sanitizeProfile(profile)

    private fun keyAllowedForProfile(profile: String): String =
        PREFIX_PROFILE_ALLOWED_DOMAINS + sanitizeProfile(profile)

    private fun keyDisabledForProfile(profile: String): String =
        PREFIX_PROFILE_DISABLED_RULES + sanitizeProfile(profile)

    fun migrateLegacyDomainsIntoCurrentProfileIfNeeded(ctx: Context) {
        migrateGlobalDomainsIfNeeded(ctx)
    }

    private fun migrateGlobalDomainsIfNeeded(ctx: Context, profile: String = currentProfile(ctx)) {
        val sp = prefs(ctx)
        val hasLegacyDomains = sp.contains(KEY_DOMAINS)
        if (sp.getBoolean(KEY_PROFILE_MIGRATION_DONE, false) && !hasLegacyDomains) return

        val legacy = try {
            sp.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }
            .mapNotNull { normalize(it) }
            .toSet()

        sp.edit {
            if (legacy.isNotEmpty()) {
                putStringSet(keyForProfile(profile), legacy)
            }
            remove(KEY_DOMAINS)
            putBoolean(KEY_PROFILE_MIGRATION_DONE, true)
        }
    }

    fun getDomains(ctx: Context): Set<String> {
        val profile = ProfileStore.getCurrent(ctx) ?: "default"
        return if (ProfileRuleModeStore.isAllowMode(ctx, profile)) {
            getAllowedDomainsForProfile(ctx, profile)
        } else {
            getDomainsForProfile(ctx, profile)
        }
    }

    fun getDomainsForProfile(ctx: Context, profile: String): Set<String> {
        val scopedProfile = sanitizeProfile(profile)
        migrateGlobalDomainsIfNeeded(ctx, scopedProfile)
        return try {
            prefs(ctx).getStringSet(keyForProfile(scopedProfile), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            prefs(ctx).edit { remove(keyForProfile(scopedProfile)) }
            emptySet()
        }
            .mapNotNull { normalize(it) }
            .toCollection(linkedSetOf())
    }

    fun setDomainsForProfile(ctx: Context, profile: String, domains: Set<String>) {
        val clean = domains.mapNotNull { normalize(it) }.toCollection(linkedSetOf())
        prefs(ctx).edit { putStringSet(keyForProfile(profile), clean) }
    }

    fun getAllowedDomainsForProfile(ctx: Context, profile: String): Set<String> {
        val scopedProfile = sanitizeProfile(profile)
        migrateGlobalDomainsIfNeeded(ctx, scopedProfile)
        return try {
            prefs(ctx).getStringSet(keyAllowedForProfile(scopedProfile), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            prefs(ctx).edit { remove(keyAllowedForProfile(scopedProfile)) }
            emptySet()
        }
            .mapNotNull { normalize(it) }
            .toCollection(linkedSetOf())
    }

    fun setAllowedDomainsForProfile(ctx: Context, profile: String, domains: Set<String>) {
        val clean = domains.mapNotNull { normalize(it) }.toCollection(linkedSetOf())
        prefs(ctx).edit { putStringSet(keyAllowedForProfile(profile), clean) }
    }

    fun getDisabledDomainsForProfile(ctx: Context, profile: String): Set<String> {
        val scopedProfile = sanitizeProfile(profile)
        return try {
            prefs(ctx).getStringSet(keyDisabledForProfile(scopedProfile), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            prefs(ctx).edit { remove(keyDisabledForProfile(scopedProfile)) }
            emptySet()
        }
            .mapNotNull { normalize(it) }
            .toCollection(linkedSetOf())
    }

    fun setDisabledDomainsForProfile(ctx: Context, profile: String, domains: Set<String>) {
        val clean = domains.mapNotNull { normalize(it) }.toCollection(linkedSetOf())
        prefs(ctx).edit { putStringSet(keyDisabledForProfile(profile), clean) }
    }

    fun isDomainEnabled(ctx: Context, domain: String): Boolean =
        isDomainEnabledForProfile(ctx, ProfileStore.getCurrent(ctx) ?: "default", domain)

    fun isDomainEnabledForProfile(ctx: Context, profile: String, domain: String): Boolean {
        val d = normalize(domain) ?: return true
        return d !in getDisabledDomainsForProfile(ctx, profile)
    }

    fun setDomainEnabled(ctx: Context, domain: String, enabled: Boolean) {
        setDomainEnabledForProfile(ctx, ProfileStore.getCurrent(ctx) ?: "default", domain, enabled)
    }

    fun setDomainEnabledForProfile(ctx: Context, profile: String, domain: String, enabled: Boolean) {
        val d = normalize(domain) ?: return
        val disabled = getDisabledDomainsForProfile(ctx, profile).toMutableSet()
        if (enabled) disabled.remove(d) else disabled.add(d)
        setDisabledDomainsForProfile(ctx, profile, disabled)
    }

    fun isRuleEnabledForHost(ctx: Context, host: String): Boolean {
        val profile = ProfileStore.getCurrent(ctx) ?: "default"
        val disabled = getDisabledDomainsForProfile(ctx, profile)
        if (disabled.isEmpty()) return true
        return disabled.none { matches(host, it) }
    }

    fun getEnabledDomains(ctx: Context): Set<String> {
        val profile = ProfileStore.getCurrent(ctx) ?: "default"
        val disabled = getDisabledDomainsForProfile(ctx, profile)
        if (disabled.isEmpty()) return getDomains(ctx)
        return getDomains(ctx).filterNot { d -> disabled.any { matches(d, it) } }.toCollection(linkedSetOf())
    }

    private fun selectedDomainsForMode(ctx: Context, profile: String): Set<String> {
        return if (ProfileRuleModeStore.isAllowMode(ctx, profile)) {
            getAllowedDomainsForProfile(ctx, profile)
        } else {
            getDomainsForProfile(ctx, profile)
        }
    }

    private fun setSelectedDomainsForMode(ctx: Context, profile: String, domains: Set<String>) {
        if (ProfileRuleModeStore.isAllowMode(ctx, profile)) {
            setAllowedDomainsForProfile(ctx, profile, domains)
        } else {
            setDomainsForProfile(ctx, profile, domains)
        }
    }

    fun addDomain(ctx: Context, raw: String): Boolean =
        addDomainForProfile(ctx, ProfileStore.getCurrent(ctx) ?: "default", raw)

    fun addDomainForProfile(ctx: Context, profile: String, raw: String): Boolean {
        val d = normalize(raw) ?: return false
        val cur = selectedDomainsForMode(ctx, profile).toMutableSet()
        val added = cur.add(d)
        if (added) setSelectedDomainsForMode(ctx, profile, cur)
        setDomainEnabledForProfile(ctx, profile, d, true)
        return added
    }

    fun removeDomain(ctx: Context, domain: String) {
        removeDomainForProfile(ctx, ProfileStore.getCurrent(ctx) ?: "default", domain)
    }

    fun removeDomainForProfile(ctx: Context, profile: String, domain: String) {
        val d = normalize(domain) ?: return
        val cur = selectedDomainsForMode(ctx, profile).toMutableSet()
        if (cur.remove(d)) setSelectedDomainsForMode(ctx, profile, cur)
        val disabled = getDisabledDomainsForProfile(ctx, profile).toMutableSet()
        if (disabled.remove(d)) setDisabledDomainsForProfile(ctx, profile, disabled)
    }

    fun shouldBlockHost(ctx: Context, host: String): Boolean {
        val profile = ProfileStore.getCurrent(ctx) ?: "default"
        val allowMode = ProfileRuleModeStore.isAllowMode(ctx, profile)
        val disabled = getDisabledDomainsForProfile(ctx, profile)
        val selectedRaw = if (allowMode) {
            getAllowedDomainsForProfile(ctx, profile)
        } else {
            getDomainsForProfile(ctx, profile)
        }
        val selected = selectedRaw.filterNot { domain -> disabled.any { matches(domain, it) } }
        val matched = selected.any { matches(host, it) }
        return if (allowMode) !matched else matched
    }

    fun isHostSelected(ctx: Context, host: String): Boolean {
        val profile = ProfileStore.getCurrent(ctx) ?: "default"
        return selectedDomainsForMode(ctx, profile).any { matches(host, it) }
    }

    fun onProfileRenamed(ctx: Context, oldProfile: String, newProfile: String) {
        val sp = prefs(ctx)
        val oldKey = keyForProfile(oldProfile)
        val newKey = keyForProfile(newProfile)
        val oldAllowedKey = keyAllowedForProfile(oldProfile)
        val newAllowedKey = keyAllowedForProfile(newProfile)
        val oldDisabledKey = keyDisabledForProfile(oldProfile)
        val newDisabledKey = keyDisabledForProfile(newProfile)
        val oldDomains = try {
            sp.getStringSet(oldKey, emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }
        val oldAllowedDomains = try {
            sp.getStringSet(oldAllowedKey, emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }
        val oldDisabledDomains = try {
            sp.getStringSet(oldDisabledKey, emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }
        sp.edit {
            if (oldDomains.isNotEmpty()) putStringSet(newKey, oldDomains)
            if (oldAllowedDomains.isNotEmpty()) putStringSet(newAllowedKey, oldAllowedDomains)
            if (oldDisabledDomains.isNotEmpty()) putStringSet(newDisabledKey, oldDisabledDomains)
            remove(oldKey)
            remove(oldAllowedKey)
            remove(oldDisabledKey)
        }
    }

    fun onProfileRemoved(ctx: Context, profile: String) {
        prefs(ctx).edit {
            remove(keyForProfile(profile))
            remove(keyAllowedForProfile(profile))
            remove(keyDisabledForProfile(profile))
        }
    }

    fun copyProfile(ctx: Context, fromProfile: String, toProfile: String) {
        setDomainsForProfile(ctx, toProfile, getDomainsForProfile(ctx, fromProfile))
        setAllowedDomainsForProfile(ctx, toProfile, getAllowedDomainsForProfile(ctx, fromProfile))
        setDisabledDomainsForProfile(ctx, toProfile, getDisabledDomainsForProfile(ctx, fromProfile))
    }

    fun normalize(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isBlank()) return null

        s = s.lowercase(Locale.ROOT)

        // Accept wildcard inputs like "*.youtube.com" and treat them as "youtube.com".
        if (s.startsWith("*.")) s = s.removePrefix("*.")

        // Strip scheme if present (http/https/custom schemes).
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)

        // Strip any path/query/fragment/whitespace tail.
        val endIdx = listOf(
            s.indexOf('/'),
            s.indexOf('?'),
            s.indexOf('#'),
            s.indexOf(' ')
        ).filter { it >= 0 }.minOrNull() ?: -1
        if (endIdx >= 0) s = s.substring(0, endIdx)

        // Strip potential user-info (user:pass@host).
        val at = s.lastIndexOf('@')
        if (at >= 0 && at < s.length - 1) s = s.substring(at + 1)

        s = s.trim().trimEnd('.')
        if (s.startsWith("www.")) s = s.removePrefix("www.")

        // Strip :port (keep IPv6 out of scope for now).
        val colon = s.lastIndexOf(':')
        if (colon > 0) {
            val tail = s.substring(colon + 1)
            if (tail.all { it.isDigit() }) s = s.substring(0, colon)
        }

        // Collapse accidental duplicate dots.
        while (".." in s) s = s.replace("..", ".")

        if (s.isBlank() || s.startsWith(".") || s.endsWith(".")) return null

        val ascii = runCatching { IDN.toASCII(s, IDN.ALLOW_UNASSIGNED) }.getOrNull()
            ?.lowercase(Locale.ROOT)
            ?: return null

        if (!ascii.contains('.')) return null
        if (ascii.length !in 3..253) return null
        if (!ascii.matches(Regex("^[a-z0-9][a-z0-9.-]*[a-z0-9]$"))) return null

        return ascii
    }

    fun matches(host: String, domain: String): Boolean {
        val h = normalize(host) ?: return false
        val d = normalize(domain) ?: return false
        return h == d || h.endsWith("." + d)
    }
}
