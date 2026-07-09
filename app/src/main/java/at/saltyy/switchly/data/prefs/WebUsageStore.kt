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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily usage tracking per domain (normalized).
 * Used for the Web Usage tab and for website daily limits.
 * Storage key format: web_usage_day_YYYYMMDD_<domain>
 */
object WebUsageStore {
    private const val PREFIX_DAY = "web_usage_day_" // + yyyymmdd + "_" + domain
    private const val PREFIX_SESSION_DAY = "web_usage_sessions_" // + yyyymmdd

    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_PENDING_KEYS = 64
    private const val MAX_SESSIONS_PER_DAY = 600
    private const val SESSION_MERGE_GAP_MS = 30_000L

    private val lock = Any()
    private val pending = ConcurrentHashMap<String, Long>()
    private val pendingSessions = ArrayList<WebsiteSession>()
    @Volatile private var lastFlushAt: Long = 0L

    data class WebsiteSession(
        val domain: String,
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    private fun dayKey(offsetDays: Int = 0): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return "%04d%02d%02d".format(y, m, d)
    }

    private fun dayKeyForMillis(timeMillis: Long): String {
        val c = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return "%04d%02d%02d".format(y, m, d)
    }

    private fun prefKeyForDay(domain: String, day: String): String {
        val norm = DomainBlockStore.normalize(domain) ?: ""
        return PREFIX_DAY + day + "_" + norm
    }

    fun addUsageMsToday(ctx: Context, domain: String, deltaMs: Long) {
        val norm = DomainBlockStore.normalize(domain) ?: return
        if (norm.isBlank() || deltaMs <= 0L) return
        val now = System.currentTimeMillis()
        val start = (now - deltaMs).coerceAtMost(now)
        val k = prefKeyForDay(norm, dayKey(0))
        pending.merge(k, deltaMs) { a, b -> a + b }
        synchronized(lock) {
            pendingSessions += WebsiteSession(norm, start, now)
        }
        maybeFlush(ctx, force = false)
    }

    fun getUsageMsToday(ctx: Context, domain: String): Long {
        val norm = DomainBlockStore.normalize(domain) ?: return 0L
        if (norm.isBlank()) return 0L
        val k = prefKeyForDay(norm, dayKey(0))
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val base = prefs.getLong(k, 0L)
        val extra = pending[k] ?: 0L
        return (base + extra).coerceAtLeast(0L)
    }

    fun setUsageMsToday(ctx: Context, domain: String, valueMs: Long) {
        val norm = DomainBlockStore.normalize(domain) ?: return
        if (norm.isBlank()) return
        val k = prefKeyForDay(norm, dayKey(0))

        // Drop any pending increments for today so the value is stable.
        synchronized(lock) {
            pending.remove(k)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit { putLong(k, valueMs.coerceAtLeast(0L)) }
    }

    /**
     * Returns per-day usage (ms) for the last [days] days including today.
     * List order: oldest -> newest.
     */
    fun getUsageMsForLastNDays(ctx: Context, domain: String, days: Int): List<Long> {
        val norm = DomainBlockStore.normalize(domain) ?: return emptyList()
        // We keep more than 30 days of data. Reading a full year can be useful for the statistics screen.
        val n = days.coerceAtLeast(1).coerceAtMost(366)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val out = ArrayList<Long>(n)
        for (i in (-(n - 1))..0) {
            val day = dayKey(i)
            val k = prefKeyForDay(norm, day)
            val base = prefs.getLong(k, 0L)
            val extra = if (i == 0) (pending[k] ?: 0L) else 0L
            out.add((base + extra).coerceAtLeast(0L))
        }
        return out
    }

    fun getUsageMsMapForDateRange(ctx: Context, startMs: Long, endMs: Long): Map<String, Long> {
        if (endMs <= startMs) return emptyMap()
        flush(ctx)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val wanted = HashSet<String>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= endMs) {
            wanted += dayKeyForMillis(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val totals = linkedMapOf<String, Long>()
        for ((key, raw) in prefs.all) {
            if (!key.startsWith(PREFIX_DAY)) continue
            val rest = key.removePrefix(PREFIX_DAY)
            if (rest.length <= 9) continue
            val day = rest.substring(0, 8)
            if (day !in wanted) continue
            val domain = rest.substring(9).trim()
            val ms = (raw as? Long)?.coerceAtLeast(0L) ?: 0L
            if (domain.isNotBlank() && ms > 0L) totals[domain] = (totals[domain] ?: 0L) + ms
        }
        return totals
    }

    fun getSessionsForDateRange(ctx: Context, startMs: Long, endMs: Long): List<WebsiteSession> {
        if (endMs <= startMs) return emptyList()
        flush(ctx)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val out = ArrayList<WebsiteSession>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= endMs) {
            out += parseSessions(prefs.getString(sessionKeyForDay(dayKeyForMillis(cal.timeInMillis)), null))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
            .filter { it.endMs > startMs && it.startMs < endMs && it.durationMs > 0L }
            .map { it.copy(startMs = maxOf(it.startMs, startMs), endMs = minOf(it.endMs, endMs)) }
            .mergeWebsiteSessions()
    }

    fun getUsageMsForDateRange(ctx: Context, domain: String, startMs: Long, endMs: Long): List<Long> {
        val norm = DomainBlockStore.normalize(domain) ?: return emptyList()
        if (norm.isBlank() || endMs <= startMs) return emptyList()
        flush(ctx)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val out = ArrayList<Long>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= endMs) {
            out += prefs.getLong(prefKeyForDay(norm, dayKeyForMillis(cal.timeInMillis)), 0L).coerceAtLeast(0L)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    /**
     * Best-effort list of domains known to the app:
     * - domains in the block list
     * - domains that have recorded usage keys
     * - domains that have a limit stored
     */
    fun getDomains(ctx: Context): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val out = LinkedHashSet<String>()

        // 1) Block list
        runCatching { out.addAll(DomainBlockStore.getDomains(ctx)) }

        // 2) Keys recorded for usage
        for (k in prefs.all.keys) {
            if (!k.startsWith(PREFIX_DAY)) continue
            // PREFIX_DAY + YYYYMMDD + "_" + domain
            val rest = k.removePrefix(PREFIX_DAY)
            if (rest.length <= 9) continue
            val domain = rest.substring(9) // skip YYYYMMDD_
            if (domain.isNotBlank()) out.add(domain)
        }

        // 3) Limit keys (DomainLimitStore stores domain_limit_min_<domain>)
        val limitPrefix = "domain_limit_min_"
        for (k in prefs.all.keys) {
            if (!k.startsWith(limitPrefix)) continue
            val domain = k.removePrefix(limitPrefix)
            if (domain.isNotBlank()) out.add(domain)
        }

        return out
    }

    /**
     * Deletes all stored usage history for [domain] (all days).
     * Note: this does NOT remove the domain from the block list or clear its daily limit.
     * Use [DomainBlockStore.removeDomain]/[DomainLimitStore.clear] separately if needed.
     */
    fun clearAllUsage(ctx: Context, domain: String) {
        val norm = DomainBlockStore.normalize(domain) ?: return
        if (norm.isBlank()) return

        // Clear any buffered increments first to avoid re-adding after deletion.
        synchronized(lock) {
            val suffix = "_" + norm
            pending.keys
                .filter { it.startsWith(PREFIX_DAY) && it.endsWith(suffix) }
                .forEach { pending.remove(it) }
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val suffix = "_" + norm
        val keysToRemove = prefs.all.keys
            .filter { it.startsWith(PREFIX_DAY) && it.endsWith(suffix) }
        val sessionUpdates = prefs.all.keys
            .filter { it.startsWith(PREFIX_SESSION_DAY) }
            .mapNotNull { key ->
                val sessions = parseSessions(prefs.getString(key, null))
                val kept = sessions.filterNot { it.domain == norm }
                if (kept.size == sessions.size) null else key to kept
            }

        if (keysToRemove.isEmpty() && sessionUpdates.isEmpty()) return
        prefs.edit {
            for (k in keysToRemove) remove(k)
            for ((k, kept) in sessionUpdates) {
                if (kept.isEmpty()) remove(k) else putString(k, encodeSessions(kept))
            }
        }
    }

    /** Total recorded usage for [domain] across all stored days. */
    fun getUsageMsAllTime(ctx: Context, domain: String): Long {
        val norm = DomainBlockStore.normalize(domain) ?: return 0L
        if (norm.isBlank()) return 0L

        // Ensure buffered increments are persisted so totals are correct.
        flush(ctx)

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val suffix = "_" + norm
        var total = 0L
        for ((k, v) in prefs.all) {
            if (!k.startsWith(PREFIX_DAY) || !k.endsWith(suffix)) continue
            total += (v as? Long) ?: 0L
        }
        return total.coerceAtLeast(0L)
    }

    /**
     * Returns all-time usage per month for [domain].
     * Keys are YYYYMM (e.g., 202402).
     */
    fun getUsageMsPerMonthAllTime(ctx: Context, domain: String): Map<Int, Long> {
        val norm = DomainBlockStore.normalize(domain) ?: return emptyMap()
        if (norm.isBlank()) return emptyMap()

        flush(ctx)

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val suffix = "_" + norm
        val sums = HashMap<Int, Long>()

        for ((k, v) in prefs.all) {
            if (!k.startsWith(PREFIX_DAY) || !k.endsWith(suffix)) continue
            val rest = k.removePrefix(PREFIX_DAY)
            if (rest.length < 8) continue
            val yyyymm = rest.substring(0, 6).toIntOrNull() ?: continue
            val ms = (v as? Long) ?: 0L
            if (ms <= 0L) continue
            sums[yyyymm] = (sums[yyyymm] ?: 0L) + ms
        }

        // Return sorted for stable charting.
        return sums.toSortedMap()
    }

    fun flush(ctx: Context) = maybeFlush(ctx, force = true)

    private fun maybeFlush(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        val should = force || (now - lastFlushAt) >= FLUSH_INTERVAL_MS || pending.size >= MAX_PENDING_KEYS || pendingSessions.size >= MAX_PENDING_KEYS
        if (!should) return
        synchronized(lock) {
            if (!force && (now - lastFlushAt) < FLUSH_INTERVAL_MS && pending.size < MAX_PENDING_KEYS && pendingSessions.size < MAX_PENDING_KEYS) return
            lastFlushAt = now
            val snapshot = HashMap(pending)
            val sessionSnapshot = ArrayList(pendingSessions)
            pending.clear()
            pendingSessions.clear()
            if (snapshot.isEmpty() && sessionSnapshot.isEmpty()) return

            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            prefs.edit {
                for ((k, add) in snapshot) {
                    val cur = prefs.getLong(k, 0L)
                    putLong(k, cur + add)
                }
                val byDay = sessionSnapshot
                    .filter { it.domain.isNotBlank() && it.endMs > it.startMs }
                    .groupBy { dayKeyForMillis(it.startMs) }
                for ((day, sessions) in byDay) {
                    val key = sessionKeyForDay(day)
                    val merged = (parseSessions(prefs.getString(key, null)) + sessions)
                        .mergeWebsiteSessions()
                        .takeLast(MAX_SESSIONS_PER_DAY)
                    putString(key, encodeSessions(merged))
                }
            }
        }
    }

    private fun sessionKeyForDay(day: String): String = PREFIX_SESSION_DAY + day

    private fun parseSessions(raw: String?): List<WebsiteSession> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val domain = item.optString("d").takeIf { it.isNotBlank() } ?: continue
                    val start = item.optLong("s", 0L)
                    val end = item.optLong("e", 0L)
                    if (end > start) add(WebsiteSession(domain, start, end))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeSessions(sessions: List<WebsiteSession>): String {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(JSONObject().apply {
                put("d", session.domain)
                put("s", session.startMs)
                put("e", session.endMs)
            })
        }
        return array.toString()
    }

    private fun List<WebsiteSession>.mergeWebsiteSessions(): List<WebsiteSession> {
        if (isEmpty()) return emptyList()
        val merged = ArrayList<WebsiteSession>()
        for (session in sortedBy { it.startMs }) {
            val last = merged.lastOrNull()
            if (last != null && last.domain == session.domain && session.startMs - last.endMs in 0..SESSION_MERGE_GAP_MS) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, session.endMs))
            } else {
                merged += session
            }
        }
        return merged
    }
}
