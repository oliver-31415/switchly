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
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Persists and retrieves app log state.
object AppLogStore {
    const val PREFS_NAME = "switchly_app_logs"
    const val KEY_LINES = "lines"
    private const val MAX_LINES = 1000
    private const val KEY_RATE_LIMIT_PREFIX = "rate_limit_"
    private val SEP = '\u001E'

    @Synchronized
    fun append(context: Context, tag: String, message: String, error: Throwable? = null) {
        appendInternal(context, tag, message, error)
    }

    @Synchronized
    fun appendRateLimited(
        context: Context,
        tag: String,
        message: String,
        error: Throwable? = null,
        windowMs: Long = 15 * 60_000L,
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val signature = entryBody(tag, message, error)
        val rateLimitKey = KEY_RATE_LIMIT_PREFIX + Integer.toHexString(signature.hashCode())
        val now = System.currentTimeMillis()
        val lastLoggedAt = prefs.getLong(rateLimitKey, 0L)
        if (lastLoggedAt > 0L && now - lastLoggedAt < windowMs.coerceAtLeast(0L)) {
            return
        }

        prefs.edit { putLong(rateLimitKey, now) }
        appendInternal(appContext, tag, message, error)
    }

    private fun appendInternal(context: Context, tag: String, message: String, error: Throwable?) {
        val body = entryBody(tag, message, error)
        val entry = "${timestamp()} $body"
        ActivityHistoryLogStore.append(context, entry, tag, message)

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_LINES, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: mutableListOf()

        // Consecutive identical entries add no diagnostic value and can quickly hide useful logs.
        if (current.lastOrNull()?.let(::withoutTimestamp) == body) {
            return
        }

        current += entry
        while (current.size > MAX_LINES) current.removeAt(0)

        prefs.edit {
            putString(KEY_LINES, current.joinToString(separator = SEP.toString()))
        }
    }

    private fun entryBody(tag: String, message: String, error: Throwable?): String = buildString {
        append("[")
        append(tag)
        append("] ")
        append(message.trim())
        error?.let {
            val summary = it.javaClass.simpleName +
                (it.message?.takeIf { msg -> msg.isNotBlank() }?.let { msg -> ": $msg" } ?: "")
            append(" | ")
            append(summary)
        }
    }

    private fun withoutTimestamp(line: String): String {
        return if (line.length > 20 && line[4] == '-' && line[7] == '-' && line[10] == ' ') {
            line.substring(20)
        } else {
            line
        }
    }

    fun latestLines(context: Context, limit: Int = MAX_LINES): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lines = prefs.getString(KEY_LINES, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return collapseConsecutive(lines).takeLast(limit.coerceAtLeast(1))
    }

    private fun collapseConsecutive(lines: List<String>): List<String> {
        if (lines.size < 2) {
            return lines
        }

        val collapsed = mutableListOf<String>()
        var lastLine = lines.first()
        var lastBody = withoutTimestamp(lastLine)
        var repeated = 1

        fun flush() {
            collapsed += if (repeated > 1) {
                "$lastLine (repeated ${repeated}×)"
            } else {
                lastLine
            }
        }

        for (line in lines.drop(1)) {
            val body = withoutTimestamp(line)
            if (body == lastBody) {
                lastLine = line
                repeated += 1
            } else {
                flush()
                lastLine = line
                lastBody = body
                repeated = 1
            }
        }
        flush()
        return collapsed
    }

    fun latestPlainText(context: Context, limit: Int = MAX_LINES): String {
        val lines = latestLines(context, limit)
        if (lines.isEmpty()) {
            return "No recent app logs."
        }
        return lines.joinToString(separator = "\n")
    }

    @Synchronized
    fun replaceLines(context: Context, lines: List<String>) {
        val clean = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeLast(MAX_LINES)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            if (clean.isEmpty()) {
                remove(KEY_LINES)
            } else {
                putString(KEY_LINES, clean.joinToString(separator = SEP.toString()))
            }
        }
    }

    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Synchronized
    fun clear(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { clear() }
    }

    fun export(context: Context): String = latestPlainText(context)

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
