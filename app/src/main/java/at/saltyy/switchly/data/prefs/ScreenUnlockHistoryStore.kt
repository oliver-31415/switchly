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
import at.saltyy.switchly.data.statistics.StatsPersistence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Persistent screen-unlock sessions archived from Android UsageEvents for long-range stats.
object ScreenUnlockHistoryStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX_DAY = "screen_unlock_sessions_"
    private const val MAX_SESSIONS_PER_DAY = 600
    private const val ENTRY_SEPARATOR = ';'
    private const val VALUE_SEPARATOR = ':'

    data class Session(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    private val dayFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.US)
    }

    @Synchronized
    fun mergeSessions(context: Context, sessions: List<Session>) {
        if (sessions.isEmpty()) {
            return
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val grouped = sessions
            .filter { it.startMs > 0L && it.endMs >= it.startMs }
            .groupBy { dayKey(it.startMs) }
        if (grouped.isEmpty()) {
            return
        }

        prefs.edit {
            grouped.forEach { (day, incoming) ->
                val key = PREFIX_DAY + day
                val byStart = linkedMapOf<Long, Session>()
                parse(prefs.getString(key, null)).forEach { existing ->
                    byStart[existing.startMs] = existing
                }
                incoming.forEach { candidate ->
                    val existing = byStart[candidate.startMs]
                    if (existing == null || candidate.endMs > existing.endMs) {
                        byStart[candidate.startMs] = candidate
                    }
                }
                val merged = byStart.values.sortedBy { it.startMs }.takeLast(MAX_SESSIONS_PER_DAY)
                if (merged.isEmpty()) {
                    remove(key)
                } else {
                    putString(key, encode(merged))
                }
            }
        }
    }

    fun sessionsForRange(context: Context, startMs: Long, endMs: Long): List<Session> {
        if (endMs <= startMs) {
            return emptyList()
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.all.keys
            .asSequence()
            .filter { it.startsWith(PREFIX_DAY) }
            .sorted()
            .flatMap { key -> parse(prefs.getString(key, null)).asSequence() }
            .filter { it.endMs > startMs && it.startMs < endMs }
            .map { it.copy(startMs = maxOf(it.startMs, startMs), endMs = minOf(it.endMs, endMs)) }
            .toList()
        val archived = StatsPersistence.screenUnlockSessionsForRange(context, startMs, endMs).map { session ->
            Session(session.startMs, session.endMs)
        }
        return (cached + archived)
            .groupBy(Session::startMs)
            .values
            .map { matches -> matches.maxBy(Session::endMs) }
            .sortedBy(Session::startMs)
    }

    private fun encode(sessions: List<Session>): String = sessions.joinToString(ENTRY_SEPARATOR.toString()) {
        "${it.startMs}$VALUE_SEPARATOR${it.endMs}"
    }

    private fun parse(raw: String?): List<Session> = raw.orEmpty()
        .split(ENTRY_SEPARATOR)
        .mapNotNull { entry ->
            val parts = entry.split(VALUE_SEPARATOR, limit = 2)
            val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (start <= 0L || end < start) {
                null
            } else {
                Session(start, end)
            }
        }

    private fun dayKey(timeMs: Long): String =
        (dayFormat.get() ?: SimpleDateFormat("yyyyMMdd", Locale.US)).format(Date(timeMs))
}
