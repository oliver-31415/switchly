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

/**
 * Dedicated persistent event source for Activity History.
 * Events are stored in per-day buckets instead of one small rolling diagnostic buffer.
 * This keeps old history available for long-range statistics and backup/restore without letting one noisy day evict the complete timeline. 
 * A generous per-day safety cap still protects against accidental log loops.
 */
object ActivityHistoryLogStore {
    const val PREFS_NAME = "switchly_activity_history_logs"
    const val KEY_LINES = "lines" // Legacy v1 storage key.
    const val KEY_CHANGE_VERSION = "change_version"

    private const val PREFIX_DAY = "day_"
    private const val MAX_LINES_PER_DAY = 500
    private const val MIGRATION_VERSION = 2
    private const val DUPLICATE_WINDOW_MS = 30_000L
    private const val KEY_MIGRATION_VERSION = "migration_version"
    private const val KEY_LAST_SIGNATURE = "last_signature"
    private const val KEY_LAST_APPENDED_AT = "last_appended_at"
    private val SEP = '\u001E'
    private val fallbackDayFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.US)
    }

    private val alwaysRelevantTags = setOf("wifi", "bluetooth", "emergency")
    private val actionAppliedTags = setOf("nfc", "qr", "barcode")
    private val locationMarkers = arrayOf("geofence", "location")
    private val ignoredLocationMarkers = arrayOf("registered", "failed")
    private val relevantMessageMarkers = mapOf(
        "schedule" to arrayOf(
            "schedule_apply",
            "applied location schedule",
            "disable_blocked_by_nfc"
        ),
        "blocking" to arrayOf(
            "app_block",
            "website_block",
            "surface_block",
            "multiwindow_block"
        ),
        "profiles" to arrayOf(
            "manual toggle",
            "temp disable started",
            "temp enable started",
            "temp disable expired",
            "temp enable expired",
            "restored previous profile"
        )
    )

    @Synchronized
    fun append(
        context: Context,
        fullEntry: String,
        tag: String,
        message: String,
    ) {
        if (!isRelevant(tag, message)) {
            return
        }

        val prefs = prefs(context)
        migrateLegacyStorage(prefs, emptyList())

        val now = System.currentTimeMillis()
        val signature = Integer.toHexString("$tag\n${message.trim()}".hashCode())
        val duplicateWithinWindow = prefs.getString(KEY_LAST_SIGNATURE, null) == signature &&
            now - prefs.getLong(KEY_LAST_APPENDED_AT, 0L) < DUPLICATE_WINDOW_MS
        if (duplicateWithinWindow) {
            return
        }

        val key = PREFIX_DAY + dayKeyForLine(fullEntry)
        val current = storedLinesForKey(prefs, key).toMutableList()
        current += fullEntry
        while (current.size > MAX_LINES_PER_DAY) current.removeAt(0)

        prefs.edit {
            putString(key, current.joinToString(SEP.toString()))
            putString(KEY_LAST_SIGNATURE, signature)
            putLong(KEY_LAST_APPENDED_AT, now)
            putLong(KEY_CHANGE_VERSION, prefs.getLong(KEY_CHANGE_VERSION, 0L) + 1L)
        }
    }

    @Synchronized
    fun ensureMigrated(context: Context, legacyLines: List<String>) {
        migrateLegacyStorage(prefs(context), legacyLines)
    }

    // Returns newest history entries while reading only the newest day buckets first.
    fun latestLines(context: Context, limit: Int = 20_000): List<String> {
        val prefs = prefs(context)
        migrateLegacyStorage(prefs, emptyList())
        val wanted = limit.coerceAtLeast(1)
        val out = ArrayList<String>(minOf(wanted, 2_000))
        dayKeys(prefs).asReversed().forEach { key ->
            val lines = storedLinesForKey(prefs, key)
            for (index in lines.indices.reversed()) {
                out += lines[index]
                if (out.size >= wanted) {
                    return out.asReversed()
                }
            }
        }
        return out.asReversed()
    }

    // Full day-bucket export used by backup/restore.
    fun exportDays(context: Context): Map<String, String> {
        val prefs = prefs(context)
        migrateLegacyStorage(prefs, emptyList())
        return dayKeys(prefs).associate { key ->
            key.removePrefix(PREFIX_DAY) to prefs.getString(key, "").orEmpty()
        }
    }

    @Synchronized
    fun replaceDays(context: Context, days: Map<String, String>) {
        val prefs = prefs(context)
        prefs.edit {
            prefs.all.keys.filter { it.startsWith(PREFIX_DAY) }.forEach { remove(it) }
            days.toSortedMap().forEach { (day, encoded) ->
                val cleanDay = day.filter(Char::isDigit).take(8)
                val cleanLines = encoded.split(SEP)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .filter(::isRelevantLine)
                    .distinct()
                    .sorted()
                    .takeLast(MAX_LINES_PER_DAY)
                if (cleanDay.length == 8 && cleanLines.isNotEmpty()) {
                    putString(PREFIX_DAY + cleanDay, cleanLines.joinToString(SEP.toString()))
                }
            }
            remove(KEY_LINES)
            putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION)
            remove(KEY_LAST_SIGNATURE)
            remove(KEY_LAST_APPENDED_AT)
            putLong(KEY_CHANGE_VERSION, prefs.getLong(KEY_CHANGE_VERSION, 0L) + 1L)
        }
    }

    // Legacy restore compatibility for backups produced before day buckets existed.
    @Synchronized
    fun replaceLines(context: Context, lines: List<String>) {
        val grouped = lines
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(::isRelevantLine)
            .distinct()
            .sorted()
            .groupBy(::dayKeyForLine)
            .mapValues { (_, dayLines) -> dayLines.takeLast(MAX_LINES_PER_DAY).joinToString(SEP.toString()) }
        replaceDays(context, grouped)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun isHistoryChangeKey(key: String?): Boolean =
        key == KEY_CHANGE_VERSION || key == KEY_LINES || key?.startsWith(PREFIX_DAY) == true

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun dayKeys(prefs: SharedPreferences): List<String> =
        prefs.all.keys.filter { it.startsWith(PREFIX_DAY) }.sorted()

    private fun storedLinesForKey(prefs: SharedPreferences, key: String): List<String> =
        prefs.getString(key, null)?.split(SEP)?.filter(String::isNotBlank).orEmpty()

    @Synchronized
    private fun migrateLegacyStorage(prefs: SharedPreferences, externalLegacyLines: List<String>) {
        if (prefs.getInt(KEY_MIGRATION_VERSION, 0) >= MIGRATION_VERSION) {
            return
        }

        val legacyStored = prefs.getString(KEY_LINES, null)
            ?.split(SEP)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val grouped = (legacyStored + externalLegacyLines)
            .filter(::isRelevantLine)
            .distinct()
            .sorted()
            .groupBy(::dayKeyForLine)

        prefs.edit {
            grouped.forEach { (day, lines) ->
                val key = PREFIX_DAY + day
                val merged = (storedLinesForKey(prefs, key) + lines)
                    .distinct()
                    .sorted()
                    .takeLast(MAX_LINES_PER_DAY)
                if (merged.isNotEmpty()) {
                    putString(key, merged.joinToString(SEP.toString()))
                }
            }
            remove(KEY_LINES)
            putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION)
            putLong(KEY_CHANGE_VERSION, prefs.getLong(KEY_CHANGE_VERSION, 0L) + 1L)
        }
    }

    private fun dayKeyForLine(line: String): String {
        if (line.length >= 10 && line[4] == '-' && line[7] == '-') {
            val y = line.substring(0, 4)
            val m = line.substring(5, 7)
            val d = line.substring(8, 10)
            if ((y + m + d).all(Char::isDigit)) {
                return y + m + d
            }
        }
        return (fallbackDayFormat.get() ?: SimpleDateFormat("yyyyMMdd", Locale.US)).format(Date())
    }

    private fun isRelevantLine(line: String): Boolean {
        val tagStart = line.indexOf('[')
        val tagEnd = line.indexOf(']', startIndex = tagStart + 1)
        if (tagStart < 0 || tagEnd <= tagStart) {
            return false
        }
        val tag = line.substring(tagStart + 1, tagEnd).trim()
        val message = line.substring(tagEnd + 1).trim()
        return isRelevant(tag, message)
    }

    // Returns whether a diagnostic log entry represents a user-visible activity event
    private fun isRelevant(tag: String, message: String): Boolean {
        val normalizedTag = tag.lowercase(Locale.US)
        val normalizedMessage = message.lowercase(Locale.US)

        return when {
            normalizedTag in alwaysRelevantTags -> true
            normalizedTag in actionAppliedTags -> "action applied" in normalizedMessage
            normalizedTag == "location" ->
                containsAnyMarker(normalizedMessage, locationMarkers) &&
                    containsNoMarker(normalizedMessage, ignoredLocationMarkers)
            else -> relevantMessageMarkers[normalizedTag]
                ?.let { markers -> containsAnyMarker(normalizedMessage, markers) }
                ?: false
        }
    }

    private fun containsAnyMarker(value: String, markers: Array<String>): Boolean =
        markers.any { marker -> value.contains(marker) }

    private fun containsNoMarker(value: String, markers: Array<String>): Boolean =
        markers.none { marker -> value.contains(marker) }
}
