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

package at.saltyy.switchly.feature.usage

import android.content.Context
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ActivityHistoryLogStore
import at.saltyy.switchly.data.prefs.AppLogStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ActivityHistoryRepository {
    enum class Source {
        SCHEDULE,
        LOCATION,
        WIFI,
        BLUETOOTH,
        NFC,
        QR,
        BARCODE,
        PROFILE,
        MANUAL,
        EMERGENCY,
        OTHER
    }

    enum class Action {
        ENABLE,
        DISABLE,
        TOGGLE,
        TEMPORARY,
        BLOCKED,
        RESTORE,
        OTHER
    }

    data class Entry(
        val timeMillis: Long,
        val title: String,
        val summary: String,
        val iconRes: Int,
        val source: Source = Source.OTHER,
        val action: Action = Action.OTHER,
        val rawMessage: String = ""
    )

    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun entriesForDay(context: Context, dayMillis: Long, limit: Int = 40): List<Entry> {
        val start = Calendar.getInstance().apply {
            timeInMillis = dayMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        return historyLines(context)
            .mapNotNull { parseLine(context, it) }
            .let { dedupeGenericTemporaryEntries(it) }
            .filter { it.timeMillis in start until end }
            .sortedBy { it.timeMillis }
            .takeLast(limit.coerceAtLeast(1))
    }
    fun recentEntries(context: Context, days: Int = 14, limit: Int = 120): List<Entry> {
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -days.coerceAtLeast(1))
        }.timeInMillis

        return historyLines(context)
            .mapNotNull { parseLine(context, it) }
            .let { dedupeGenericTemporaryEntries(it) }
            .filter { it.timeMillis >= start }
            .sortedByDescending { it.timeMillis }
            .take(limit.coerceAtLeast(1))
    }

    private fun historyLines(context: Context): List<String> {
        ActivityHistoryLogStore.ensureMigrated(context, AppLogStore.latestLines(context, 1000))
        return ActivityHistoryLogStore.latestLines(context)
    }

    private fun dedupeGenericTemporaryEntries(entries: List<Entry>): List<Entry> {
        val specificTempSeconds = entries
            .filter { entry ->
                entry.action == Action.TEMPORARY && entry.source in setOf(
                    Source.MANUAL,
                    Source.NFC,
                    Source.QR,
                    Source.BARCODE,
                    Source.EMERGENCY
                )
            }
            .map { it.timeMillis / 1000L }

        if (specificTempSeconds.isEmpty()) {
            return entries
        }

        return entries.filterNot { entry ->
            entry.source == Source.PROFILE && entry.action == Action.TEMPORARY &&
                specificTempSeconds.any { sec -> kotlin.math.abs(sec - entry.timeMillis / 1000L) <= 2L }
        }
    }

    private fun parseLine(context: Context, line: String): Entry? {
        if (line.length < 22) {
            return null
        }
        val timeMillis = runCatching {
            logDateFormat.parse(line.substring(0, 19))?.time
        }.getOrNull() ?: return null

        val tagStart = line.indexOf('[', startIndex = 19)
        val tagEnd = line.indexOf(']', startIndex = tagStart + 1)
        if (tagStart < 0 || tagEnd <= tagStart) {
            return null
        }

        val tag = line.substring(tagStart + 1, tagEnd).trim()
        val message = line.substring(tagEnd + 1).trim()
        if (message.isBlank()) {
            return null
        }

        val lowerTag = tag.lowercase(Locale.US)
        val lowerMessage = message.lowercase(Locale.US)

        return when (lowerTag) {
            "schedule" -> scheduleEntry(context, timeMillis, message, lowerMessage)
            "location" -> locationEntry(context, timeMillis, message, lowerMessage)
            "wifi" -> simpleEntry(timeMillis, context.getString(R.string.activity_history_wifi_title), message, R.drawable.wifi_24, Source.WIFI)
            "bluetooth" -> simpleEntry(timeMillis, context.getString(R.string.activity_history_bluetooth_title), message, R.drawable.bluetooth_24, Source.BLUETOOTH)
            "nfc" -> scanEntry(context, timeMillis, context.getString(R.string.activity_history_nfc_title), message, lowerMessage, R.drawable.nfc_24, Source.NFC)
            "qr" -> scanEntry(context, timeMillis, context.getString(R.string.activity_history_qr_title), message, lowerMessage, R.drawable.qr_code_24, Source.QR)
            "barcode" -> scanEntry(context, timeMillis, context.getString(R.string.activity_history_barcode_title), message, lowerMessage, R.drawable.barcode_24, Source.BARCODE)
            "blocking" -> blockingEntry(context, timeMillis, message, lowerMessage)
            "profiles" -> profileEntry(context, timeMillis, message, lowerMessage)
            "emergency" -> emergencyEntry(context, timeMillis, message, lowerMessage)
            else -> null
        }
    }

    private fun blockingEntry(context: Context, timeMillis: Long, message: String, lowerMessage: String): Entry? {
        if ("app_block" !in lowerMessage && "website_block" !in lowerMessage && "surface_block" !in lowerMessage && "multiwindow_block" !in lowerMessage) {
            return null
        }
        val pkg = valueAfter(message, "pkg=")?.takeIf { it != "-" }
        val title = when {
            "website_block" in lowerMessage -> context.getString(R.string.activity_history_blocked_website_title)
            "surface_block" in lowerMessage -> context.getString(R.string.activity_history_blocked_surface_title)
            else -> context.getString(R.string.activity_history_blocked_app_title)
        }
        val matched = valueAfter(message, "host=") ?: valueAfter(message, "title=") ?: valueAfter(message, "label=") ?: pkg
        val summary = buildList {
            add(context.getString(R.string.activity_history_action_line, context.getString(R.string.activity_history_action_blocked)))
            if (!pkg.isNullOrBlank()) add(context.getString(R.string.activity_history_package_line, pkg))
            if (!matched.isNullOrBlank()) add(context.getString(R.string.activity_history_matched_line, matched))
        }.joinToString("\n")
        return Entry(
            timeMillis = timeMillis,
            title = title,
            summary = summary,
            iconRes = R.drawable.lock_24,
            source = Source.OTHER,
            action = Action.BLOCKED,
            rawMessage = message
        )
    }

    private fun scheduleEntry(context: Context, timeMillis: Long, message: String, lowerMessage: String): Entry? {
        if ("schedule_apply" in lowerMessage) {
            val source = valueAfter(message, "source=")?.lowercase(Locale.US).orEmpty()
            val action = valueAfter(message, "action=").orEmpty()
            val title = when (source) {
                "location" -> context.getString(R.string.activity_history_location_schedule_title)
                "wifi" -> context.getString(R.string.activity_history_wifi_schedule_title)
                "bluetooth", "bt" -> context.getString(R.string.activity_history_bluetooth_schedule_title)
                else -> context.getString(R.string.activity_history_schedule_title)
            }
            return simpleEntry(
                timeMillis,
                title,
                readableScheduleSummary(context, action, message),
                iconForSource(source),
                sourceForSchedule(source),
                actionFromValue(action)
            )
        }

        if ("applied location schedule" in lowerMessage) {
            return simpleEntry(
                timeMillis,
                context.getString(R.string.activity_history_location_schedule_title),
                readableMessage(message),
                R.drawable.location_on_24,
                Source.LOCATION
            )
        }

        if ("disable_blocked_by_nfc" in lowerMessage) {
            return simpleEntry(
                timeMillis,
                context.getString(R.string.activity_history_schedule_blocked_title),
                context.getString(R.string.activity_history_schedule_blocked_by_nfc),
                R.drawable.lock_24,
                Source.SCHEDULE,
                Action.BLOCKED
            )
        }

        return null
    }

    private fun locationEntry(context: Context, timeMillis: Long, message: String, lowerMessage: String): Entry? {
        if ("geofence" !in lowerMessage && "location" !in lowerMessage) {
            return null
        }
        if ("registered" in lowerMessage || "failed" in lowerMessage) {
            return null
        }
        return simpleEntry(
            timeMillis,
            context.getString(R.string.activity_history_location_title),
            readableMessage(message),
            R.drawable.location_on_24,
            Source.LOCATION
        )
    }

    private fun scanEntry(
        context: Context,
        timeMillis: Long,
        sourceTitle: String,
        message: String,
        lowerMessage: String,
        iconRes: Int,
        source: Source
    ): Entry? {
        if ("action applied" !in lowerMessage) {
            return null
        }
        val action = valueAfter(message, "action=").orEmpty()
        val profile = valueAfter(message, "profile=")?.takeIf { it != "-" }
        return Entry(
            timeMillis = timeMillis,
            title = context.getString(R.string.activity_history_source_input_changed_title, sourceTitle),
            summary = structuredActionSummary(context, action, profile, message),
            iconRes = iconRes,
            source = source,
            action = actionFromValue(action)
        )
    }

    private fun emergencyEntry(context: Context, timeMillis: Long, message: String, lowerMessage: String): Entry {
        val action = when {
            "started" in lowerMessage || "resumed" in lowerMessage -> "enable"
            "ended" in lowerMessage || "cancel" in lowerMessage || "paused" in lowerMessage -> "disable"
            else -> "emergency"
        }
        return Entry(
            timeMillis = timeMillis,
            title = context.getString(R.string.activity_history_emergency_changed_title),
            summary = structuredActionSummary(context, action, profile = null, originalMessage = message),
            iconRes = R.drawable.lock_open_24,
            source = Source.EMERGENCY,
            action = Action.TEMPORARY
        )
    }

    private fun profileEntry(context: Context, timeMillis: Long, message: String, lowerMessage: String): Entry? {
        if ("manual toggle" in lowerMessage) {
            val action = valueAfter(message, "action=").orEmpty()
            val profile = valueAfter(message, "profile=")?.takeIf { it != "-" }
            return Entry(
                timeMillis = timeMillis,
                title = context.getString(R.string.activity_history_manual_changed_title),
                summary = structuredActionSummary(context, action, profile, message),
                iconRes = R.drawable.play_arrow_24,
                source = Source.MANUAL,
                action = actionFromValue(action)
            )
        }

        if ("temp disable started" in lowerMessage) {
            return Entry(
                timeMillis = timeMillis,
                title = context.getString(R.string.activity_history_temporary_changed_title),
                summary = structuredActionSummary(context, "temp_disable", profile = null, originalMessage = message),
                iconRes = R.drawable.lock_open_24,
                source = Source.PROFILE,
                action = Action.TEMPORARY
            )
        }
        if ("temp enable started" in lowerMessage) {
            return Entry(
                timeMillis = timeMillis,
                title = context.getString(R.string.activity_history_temporary_changed_title),
                summary = structuredActionSummary(context, "temp_enable", profile = null, originalMessage = message),
                iconRes = R.drawable.play_arrow_24,
                source = Source.PROFILE,
                action = Action.TEMPORARY
            )
        }
        if ("temp disable expired" in lowerMessage || "temp enable expired" in lowerMessage) {
            return simpleEntry(timeMillis, context.getString(R.string.activity_history_temp_expired_title), readableMessage(message), R.drawable.schedule_24, Source.PROFILE, Action.TEMPORARY)
        }
        if ("restored previous profile" in lowerMessage) {
            val profile = valueAfter(message, "id=")?.takeIf { it != "-" }
            return Entry(
                timeMillis = timeMillis,
                title = context.getString(R.string.activity_history_profile_restored_title),
                summary = structuredActionSummary(context, "restore", profile, message),
                iconRes = R.drawable.apps_24,
                source = Source.PROFILE,
                action = Action.RESTORE
            )
        }
        return null
    }

    private fun simpleEntry(
        timeMillis: Long,
        title: String,
        summary: String,
        iconRes: Int,
        source: Source = Source.OTHER,
        action: Action = Action.OTHER
    ): Entry {
        return Entry(timeMillis, title, readableMessage(summary), iconRes, source, action, summary)
    }

    private fun readableScheduleSummary(context: Context, action: String, message: String): String {
        val profile = valueAfter(message, "profile=")?.takeIf { it != "-" }
        return structuredActionSummary(context, action, profile, message)
    }

    private fun structuredActionSummary(
        context: Context,
        action: String,
        profile: String?,
        originalMessage: String
    ): String {
        val lines = mutableListOf<String>()
        lines += context.getString(R.string.activity_history_action_line, readableActionLabel(context, action))
        if (!profile.isNullOrBlank()) {
            lines += context.getString(R.string.activity_history_profile_line, profile)
        }
        valueAfter(originalMessage, "duration=")?.let { duration ->
            lines += context.getString(R.string.activity_history_duration_line, readableDuration(duration))
        }
        return lines.joinToString("\n")
    }

    private fun readableActionLabel(context: Context, action: String): String {
        return when (normalizedActionValue(action)) {
            "enable" -> context.getString(R.string.activity_history_action_enable)
            "disable" -> context.getString(R.string.activity_history_action_disable)
            "toggle" -> context.getString(R.string.activity_history_action_toggle)
            "temp_enable" -> context.getString(R.string.activity_history_action_temp_enable)
            "temp_disable" -> context.getString(R.string.activity_history_action_temp_disable)
            "emergency" -> context.getString(R.string.activity_history_action_emergency)
            "restore" -> context.getString(R.string.activity_history_action_restore)
            "blocked" -> context.getString(R.string.activity_history_action_blocked)
            else -> fallbackActionLabel(context, action)
        }
    }

    private fun fallbackActionLabel(context: Context, action: String): String {
        val clean = action.replace('_', ' ').trim()
        if (clean.isBlank()) {
            return context.getString(R.string.activity_history_action_unknown)
        }
        return clean.substring(0, 1).uppercase(Locale.getDefault()) + clean.substring(1)
    }

    private fun readableDuration(raw: String): String {
        if (raw.equals("ask", ignoreCase = true)) {
            return raw
        }
        val millis = raw.removeSuffix("ms").toLongOrNull() ?: return raw
        val minutes = (millis / 60_000L).coerceAtLeast(1L)
        return "${minutes}m"
    }

    private fun normalizedActionValue(action: String): String {
        val lower = action.lowercase(Locale.US)
        return when {
            lower in setOf("start", "enable", "on", "activate", "enabled") -> "enable"
            lower in setOf("stop", "disable", "off", "disabled") -> "disable"
            lower == "toggle" -> "toggle"
            lower.startsWith("temp_enable") -> "temp_enable"
            lower.startsWith("temp_disable") || lower.startsWith("reentry") -> "temp_disable"
            lower.startsWith("emergency") -> "emergency"
            lower == "restore" || lower == "restored" -> "restore"
            lower == "blocked" -> "blocked"
            else -> lower
        }
    }

    private fun sourceForSchedule(source: String): Source {
        return when (source) {
            "location" -> Source.LOCATION
            "wifi" -> Source.WIFI
            "bluetooth", "bt" -> Source.BLUETOOTH
            else -> Source.SCHEDULE
        }
    }

    private fun actionFromValue(action: String): Action {
        return when (normalizedActionValue(action)) {
            "enable" -> Action.ENABLE
            "disable" -> Action.DISABLE
            "toggle" -> Action.TOGGLE
            "temp_enable", "temp_disable", "emergency" -> Action.TEMPORARY
            "restore" -> Action.RESTORE
            "blocked" -> Action.BLOCKED
            else -> Action.OTHER
        }
    }

    private fun iconForSource(source: String): Int {
        return when (source) {
            "location" -> R.drawable.location_on_24
            "wifi" -> R.drawable.wifi_24
            "bluetooth", "bt" -> R.drawable.bluetooth_24
            else -> R.drawable.schedule_24
        }
    }

    private fun valueAfter(message: String, key: String): String? {
        val start = message.indexOf(key)
        if (start < 0) {
            return null
        }
        val raw = message.substring(start + key.length)
        val nextKeyIndex = listOf(
            " pkg=", " label=", " host=", " title=", " action=", " profile=", " duration=", " request=", " source=", " uid=", " id=", " reason=",
            " hardBlocked=", " limitReached=", " redirected=", " immediate=", " countAttempt=", " countAsBlock=", " backCount=", " defer=", " returnToPkg="
        )
            .map { raw.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        val value = if (nextKeyIndex != null) raw.substring(0, nextKeyIndex) else raw
        return value.trim().trim(',').takeIf { it.isNotBlank() }
    }

    private fun readableMessage(message: String): String {
        return message
            .replace('_', ' ')
            .replace("schedule apply", "Schedule applied")
            .replace("schedule match", "Schedule matched")
            .replace("duration=", "duration=")
            .trim()
    }
}
