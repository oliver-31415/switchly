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
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import at.saltyy.switchly.nfc.NfcSchema
import at.saltyy.switchly.R
import java.time.LocalDate

/**
 * Stores managed scan codes (QR or barcode) that map a scanned raw value to a Switchly action URI.
 * This is primarily used for physical barcodes, but QR entries can also be stored for stricter
 * QR-only workflows (similar to paired NFC tags).
 */
object ScanCodeStore {

    enum class Kind(val raw: String) {
        QR("qr"),
        BARCODE("barcode");

        companion object {
            fun fromRaw(raw: String?): Kind = entries.firstOrNull { it.raw == raw } ?: BARCODE
        }
    }

    data class Entry(
        val kind: Kind,
        val rawValue: String,
        val actionUri: String,
        val name: String?,
        val note: String?,
        val dailyLimit: Int?,
        val cooldownMinutes: Int?,
        val addedAtMillis: Long,
    ) {
        val id: String get() = buildId(kind, rawValue)
    }

    private const val PREFS = "switchly_prefs"
    private const val KEY_ENTRY_IDS = "scan_code_entry_ids"
    private const val KEY_KIND_PREFIX = "scan_code_kind_"
    private const val KEY_RAW_PREFIX = "scan_code_raw_"
    private const val KEY_ACTION_PREFIX = "scan_code_action_"
    private const val KEY_NAME_PREFIX = "scan_code_name_"
    private const val KEY_NOTE_PREFIX = "scan_code_note_"
    private const val KEY_DAILY_LIMIT_PREFIX = "scan_code_daily_limit_"
    private const val KEY_COOLDOWN_PREFIX = "scan_code_cooldown_"
    private const val KEY_LAST_USED_PREFIX = "scan_code_last_used_"
    private const val KEY_COUNT_PREFIX = "scan_code_count_"
    private const val KEY_ADDED_AT_PREFIX = "scan_code_added_at_"

    private fun normalizeRaw(raw: String?): String = raw?.trim().orEmpty()

    private fun encodeForKey(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun buildId(kind: Kind, rawValue: String): String =
        kind.raw + "|" + encodeForKey(normalizeRaw(rawValue))

    private fun readIds(sp: android.content.SharedPreferences): Set<String> {
        return runCatching { sp.getStringSet(KEY_ENTRY_IDS, emptySet())?.toSet() }.getOrNull() ?: emptySet()
    }

    private fun getIntCompat(sp: android.content.SharedPreferences, key: String, defaultValue: Int): Int {
        return when (val raw = sp.all[key]) {
            is Int -> raw
            is Long -> raw.toInt()
            is Float -> raw.toInt()
            is String -> raw.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getLongCompat(sp: android.content.SharedPreferences, key: String, defaultValue: Long): Long {
        return when (val raw = sp.all[key]) {
            is Long -> raw
            is Int -> raw.toLong()
            is Float -> raw.toLong()
            is String -> raw.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    fun getEntries(ctx: Context): List<Entry> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return readIds(sp)
            .mapNotNull { id -> readEntry(sp, id) }
            .sortedWith(compareByDescending<Entry> { it.addedAtMillis }.thenBy { it.name?.lowercase() ?: "~" }.thenBy { it.rawValue })
    }

    fun hasEntries(ctx: Context, kind: Kind): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return readIds(sp).any { id -> readEntry(sp, id)?.kind == kind }
    }

    fun findEntry(ctx: Context, kind: Kind, rawValue: String): Entry? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return readEntry(sp, buildId(kind, rawValue))
    }

    fun upsert(ctx: Context, entry: Entry) {
        val cleanRaw = normalizeRaw(entry.rawValue)
        val cleanAction = entry.actionUri.trim()
        if (cleanRaw.isBlank() || cleanAction.isBlank()) {
            return
        }

        val id = buildId(entry.kind, cleanRaw)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = readIds(sp).toMutableSet().apply { add(id) }
        val safeName = entry.name?.trim()?.take(80)?.takeIf { it.isNotBlank() }
        val safeNote = entry.note?.trim()?.take(240)?.takeIf { it.isNotBlank() }
        val addedAt = if (entry.addedAtMillis > 0L) entry.addedAtMillis else System.currentTimeMillis()
        val safeDaily = entry.dailyLimit?.coerceIn(1, 50)
        val safeCooldown = entry.cooldownMinutes?.coerceIn(1, 24 * 60)

        sp.edit(commit = true) {
            putStringSet(KEY_ENTRY_IDS, ids)
            putString(KEY_KIND_PREFIX + id, entry.kind.raw)
            putString(KEY_RAW_PREFIX + id, cleanRaw)
            putString(KEY_ACTION_PREFIX + id, cleanAction)
            if (safeName == null) remove(KEY_NAME_PREFIX + id) else putString(KEY_NAME_PREFIX + id, safeName)
            if (safeNote == null) remove(KEY_NOTE_PREFIX + id) else putString(KEY_NOTE_PREFIX + id, safeNote)
            if (safeDaily == null) remove(KEY_DAILY_LIMIT_PREFIX + id) else putInt(KEY_DAILY_LIMIT_PREFIX + id, safeDaily)
            if (safeCooldown == null) remove(KEY_COOLDOWN_PREFIX + id) else putInt(KEY_COOLDOWN_PREFIX + id, safeCooldown)
            putLong(KEY_ADDED_AT_PREFIX + id, addedAt)
        }
    }

    fun remove(ctx: Context, kind: Kind, rawValue: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = buildId(kind, rawValue)
        val ids = readIds(sp).toMutableSet().apply { remove(id) }
        sp.edit(commit = true) {
            putStringSet(KEY_ENTRY_IDS, ids)
            remove(KEY_KIND_PREFIX + id)
            remove(KEY_RAW_PREFIX + id)
            remove(KEY_ACTION_PREFIX + id)
            remove(KEY_NAME_PREFIX + id)
            remove(KEY_NOTE_PREFIX + id)
            remove(KEY_DAILY_LIMIT_PREFIX + id)
            remove(KEY_COOLDOWN_PREFIX + id)
            remove(KEY_LAST_USED_PREFIX + id)
            remove(KEY_ADDED_AT_PREFIX + id)
        }
        clearTodayCounters(ctx, id)
    }

    fun clear(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = readIds(sp)
        sp.edit(commit = true) {
            remove(KEY_ENTRY_IDS)
            ids.forEach { id ->
                remove(KEY_KIND_PREFIX + id)
                remove(KEY_RAW_PREFIX + id)
                remove(KEY_ACTION_PREFIX + id)
                remove(KEY_NAME_PREFIX + id)
                remove(KEY_NOTE_PREFIX + id)
                remove(KEY_DAILY_LIMIT_PREFIX + id)
                remove(KEY_COOLDOWN_PREFIX + id)
                remove(KEY_LAST_USED_PREFIX + id)
                remove(KEY_ADDED_AT_PREFIX + id)
            }
        }
        ids.forEach { id -> clearTodayCounters(ctx, id) }
    }

    fun checkLimits(ctx: Context, entry: Entry): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = entry.id
        val now = System.currentTimeMillis()
        entry.cooldownMinutes?.coerceAtLeast(1)?.let { minutes ->
            val lastUsed = getLongCompat(sp, KEY_LAST_USED_PREFIX + id, 0L)
            val cooldownMs = minutes * 60_000L
            if (lastUsed > 0L && now - lastUsed in 0 until cooldownMs) {
                val remainingMs = cooldownMs - (now - lastUsed)
                val minsRemaining = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                return ctx.getString(R.string.manage_barcodes_limit_cooldown_toast, minsRemaining)
            }
        }

        entry.dailyLimit?.coerceAtLeast(1)?.let { limit ->
            val day = LocalDate.now().toEpochDay()
            val used = getIntCompat(sp, KEY_COUNT_PREFIX + id + "_" + day, 0)
            if (used >= limit) {
                return ctx.getString(R.string.manage_barcodes_limit_daily_toast, limit)
            }
        }

        return null
    }

    fun consume(ctx: Context, entry: Entry) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = entry.id
        val day = LocalDate.now().toEpochDay()
        val countKey = KEY_COUNT_PREFIX + id + "_" + day
        val current = getIntCompat(sp, countKey, 0)
        sp.edit(commit = true) {
            putLong(KEY_LAST_USED_PREFIX + id, System.currentTimeMillis())
            putInt(countKey, current + 1)
        }
    }

    fun isStrictSwitchlyUri(rawValue: String): Boolean {
        val uri = runCatching { rawValue.trim().toUri() }.getOrNull() ?: return false
        return NfcSchema.isSupportedCommandUri(uri)
    }

    private fun clearTodayCounters(ctx: Context, id: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = LocalDate.now().toEpochDay()
        sp.edit(commit = true) {
            remove(KEY_COUNT_PREFIX + id + "_" + day)
        }
    }

    private fun readEntry(sp: android.content.SharedPreferences, id: String): Entry? {
        val kind = Kind.fromRaw(sp.getString(KEY_KIND_PREFIX + id, null))
        val rawValue = normalizeRaw(sp.getString(KEY_RAW_PREFIX + id, null))
        val actionUri = sp.getString(KEY_ACTION_PREFIX + id, null)?.trim().orEmpty()
        if (rawValue.isBlank() || actionUri.isBlank()) {
            return null
        }
        val name = sp.getString(KEY_NAME_PREFIX + id, null)?.trim()?.takeIf { it.isNotBlank() }
        val note = sp.getString(KEY_NOTE_PREFIX + id, null)?.trim()?.takeIf { it.isNotBlank() }
        val dailyLimit = if (sp.contains(KEY_DAILY_LIMIT_PREFIX + id)) getIntCompat(sp, KEY_DAILY_LIMIT_PREFIX + id, 1).coerceAtLeast(1) else null
        val cooldownMinutes = if (sp.contains(KEY_COOLDOWN_PREFIX + id)) getIntCompat(sp, KEY_COOLDOWN_PREFIX + id, 1).coerceAtLeast(1) else null
        val addedAt = getLongCompat(sp, KEY_ADDED_AT_PREFIX + id, 0L)
        return Entry(
            kind = kind,
            rawValue = rawValue,
            actionUri = actionUri,
            name = name,
            note = note,
            dailyLimit = dailyLimit,
            cooldownMinutes = cooldownMinutes,
            addedAtMillis = addedAt,
        )
    }
}
