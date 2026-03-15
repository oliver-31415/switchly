package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.net.IDN
import java.util.Locale

object DomainBlockStore {

    private const val KEY_ENABLED = "domain_block_enabled"
    private const val KEY_DOMAINS = "domain_block_domains"

    fun isEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun getDomains(ctx: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(ctx).getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet()

    fun addDomain(ctx: Context, raw: String): Boolean {
        val d = normalize(raw) ?: return false
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val cur = sp.getStringSet(KEY_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = cur.add(d)
        if (added) sp.edit { putStringSet(KEY_DOMAINS, cur) }
        return added
    }

    fun removeDomain(ctx: Context, domain: String) {
        val d = normalize(domain) ?: return
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val cur = sp.getStringSet(KEY_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (cur.remove(d)) sp.edit { putStringSet(KEY_DOMAINS, cur) }
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