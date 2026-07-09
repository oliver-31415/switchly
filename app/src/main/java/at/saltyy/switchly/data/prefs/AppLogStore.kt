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

object AppLogStore {
    const val PREFS_NAME = "switchly_app_logs"
    const val KEY_LINES = "lines"
    private const val MAX_LINES = 1000
    private val SEP = '\u001E'

    fun append(context: Context, tag: String, message: String, error: Throwable? = null) {
        val entry = buildString {
            append(timestamp())
            append(" [")
            append(tag)
            append("] ")
            append(message.trim())
            error?.let {
                val summary = it.javaClass.simpleName + (it.message?.takeIf { msg -> msg.isNotBlank() }?.let { msg -> ": $msg" } ?: "")
                append(" | ")
                append(summary)
            }
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_LINES, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: mutableListOf()

        current += entry
        while (current.size > MAX_LINES) current.removeAt(0)

        prefs.edit {
            putString(KEY_LINES, current.joinToString(separator = SEP.toString()))
        }
    }

    fun latestLines(context: Context, limit: Int = MAX_LINES): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lines = prefs.getString(KEY_LINES, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return lines.takeLast(limit.coerceAtLeast(1))
    }

    fun latestPlainText(context: Context, limit: Int = MAX_LINES): String {
        val lines = latestLines(context, limit)
        if (lines.isEmpty()) return "No recent app logs."
        return lines.joinToString(separator = "\n")
    }

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

    fun clear(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(KEY_LINES) }
    }

    fun export(context: Context): String {
        val lines = latestLines(context)
        if (lines.isEmpty()) return "No recent app logs."

        return buildString {
            append("-----\n")
            append("Latest app logs\n")
            append("-----\n")
            lines.forEach { append(it).append('\n') }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
