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

// Persists and retrieves blocked inbox state.
private const val PREFS = "switchly_prefs"
private const val KEY_BLOCKED_EVENTS = "blocked_inbox_events"
private const val KEY_BLOCKED_EVENTS_UPDATED_AT = "blocked_inbox_events_updated_at"

private const val FS = "\u001F" // field separator
private const val RS = "\n"     // record separator
private const val MAX_EVENTS = 300

data class BlockedNotificationEvent(
    val timeMillis: Long,
    val pkg: String,
    val profile: String,
    val reason: String,
    val title: String,
    val text: String,
    val bigText: String = "",
    val subText: String = "",
    val summaryText: String = "",
    val groupKey: String = "",
    val category: String = "",
    val channelId: String = "",
)

object BlockedInboxStore {

    fun add(ctx: Context, event: BlockedNotificationEvent) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = getAll(ctx).toMutableList()
        all.add(event)

        val clipped = all
            .sortedByDescending { it.timeMillis }
            .take(MAX_EVENTS)

        sp.edit {
            putString(KEY_BLOCKED_EVENTS, serialize(clipped))
            putLong(KEY_BLOCKED_EVENTS_UPDATED_AT, System.currentTimeMillis())
        }
    }

    fun getAll(ctx: Context): List<BlockedNotificationEvent> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_BLOCKED_EVENTS, "").orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }

        return raw.split(RS)
            .asSequence()
            .mapNotNull { deserializeLine(it) }
            .sortedByDescending { it.timeMillis }
            .toList()
    }

    fun clear(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            remove(KEY_BLOCKED_EVENTS)
            putLong(KEY_BLOCKED_EVENTS_UPDATED_AT, System.currentTimeMillis())
        }
    }

    /**
     * Removes a single event
     * We intentionally match on multiple fields to reduce accidental deletions.
     */
    fun remove(ctx: Context, event: BlockedNotificationEvent) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = getAll(ctx)
        if (all.isEmpty()) {
            return
        }

        val remaining = all.filterNot { e ->
            e.timeMillis == event.timeMillis &&
                e.pkg == event.pkg &&
                e.reason == event.reason &&
                e.title == event.title &&
                e.text == event.text
        }

        sp.edit {
            putString(KEY_BLOCKED_EVENTS, serialize(remaining))
            putLong(KEY_BLOCKED_EVENTS_UPDATED_AT, System.currentTimeMillis())
        }
    }

    private fun serialize(list: List<BlockedNotificationEvent>): String {
        return list.joinToString(RS) { e ->
            listOf(
                e.timeMillis.toString(),
                norm(e.pkg),
                norm(e.profile),
                norm(e.reason),
                norm(e.title),
                norm(e.text),
                norm(e.bigText),
                norm(e.subText),
                norm(e.summaryText),
                norm(e.groupKey),
                norm(e.category),
                norm(e.channelId)
            ).joinToString(FS)
        }
    }

    private fun deserializeLine(line: String): BlockedNotificationEvent? {
        val parts = line.split(FS)
        if (parts.size < 2) {
            return null
        }

        val t = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val pkg = denorm(parts.getOrNull(1))
        if (pkg.isBlank()) {
            return null
        }

        return BlockedNotificationEvent(
            timeMillis = t,
            pkg = pkg,
            profile = denorm(parts.getOrNull(2)),
            reason = denorm(parts.getOrNull(3)),
            title = denorm(parts.getOrNull(4)),
            text = denorm(parts.getOrNull(5)),
            bigText = denorm(parts.getOrNull(6)),
            subText = denorm(parts.getOrNull(7)),
            summaryText = denorm(parts.getOrNull(8)),
            groupKey = denorm(parts.getOrNull(9)),
            category = denorm(parts.getOrNull(10)),
            channelId = denorm(parts.getOrNull(11)),
        )
    }

    private fun norm(s: String?): String {
        return (s ?: "")
            .replace(FS, " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
    }

    private fun denorm(s: String?): String = s?.trim().orEmpty()
}
