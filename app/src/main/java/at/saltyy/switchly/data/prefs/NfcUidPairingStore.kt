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
import at.saltyy.switchly.nfc.NfcTagUid

/**
 * Stores paired NFC tag UIDs (serial numbers).
 * Supports multiple tags + optional user metadata (name, note) per tag.
 * This store only reads/writes the canonical StringSet key.
 */
object NfcUidPairingStore {

    enum class TagKind(val id: String) {
        UNKNOWN("unknown"),
        WRITABLE("writable"),
        READ_ONLY("read_only");

        companion object {
            fun fromId(id: String?): TagKind {
                return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UNKNOWN
            }
        }
    }

    enum class ReadOnlyAction(val id: String, vararg val legacyIds: String) {
        TOGGLE("toggle"),
        DISABLE("disable", "disable_only", "unlock_only"),
        ENABLE("enable", "enable_only", "lock_only"),
        TEMP_DISABLE("temp_disable"),
        TEMP_ENABLE("temp_enable");

        companion object {
            fun fromId(id: String?): ReadOnlyAction {
                return entries.firstOrNull { action ->
                    action.id.equals(id, ignoreCase = true) ||
                        action.legacyIds.any { it.equals(id, ignoreCase = true) }
                } ?: TOGGLE
            }
        }
    }

    data class TagMeta(
        val uid: String,
        val name: String?,
        val note: String?,
        val pairedAtMillis: Long,
        val tagKind: TagKind,
        val readOnlyAction: ReadOnlyAction,
        val readOnlyDurationMinutes: Int,
        val enabled: Boolean
    ) {
        val supportsUidOnlyAction: Boolean
            get() = tagKind != TagKind.WRITABLE

        val usesTemporaryReadOnlyAction: Boolean
            get() = readOnlyAction == ReadOnlyAction.TEMP_DISABLE ||
                readOnlyAction == ReadOnlyAction.TEMP_ENABLE
    }

    private const val PREFS = "switchly_prefs"
    private const val KEY_PAIRED_UID_HEX_SET = "nfc_paired_uid_hex_set"

    private const val KEY_TAG_NAME_PREFIX = "nfc_tag_name_"
    private const val KEY_TAG_NOTE_PREFIX = "nfc_tag_note_"
    private const val KEY_TAG_PAIRED_AT_PREFIX = "nfc_tag_paired_at_"
    private const val KEY_TAG_KIND_PREFIX = "nfc_tag_kind_"
    private const val KEY_TAG_ENABLED_PREFIX = "nfc_tag_enabled_"
    private const val KEY_TAG_READ_ONLY_ACTION_PREFIX = "nfc_tag_read_only_action_"
    private const val KEY_TAG_READ_ONLY_DURATION_PREFIX = "nfc_tag_read_only_duration_"
    const val DEFAULT_READ_ONLY_TEMP_DURATION_MINUTES = 1

    private fun normalize(uid: String?): String = NfcTagUid.normalizeUidHex(uid)

    @Synchronized
    fun getPairedUidsHex(ctx: Context): Set<String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawSet = try {
            sp.getStringSet(KEY_PAIRED_UID_HEX_SET, emptySet())?.toSet().orEmpty()
        } catch (_: ClassCastException) {
            sp.edit(commit = true) { remove(KEY_PAIRED_UID_HEX_SET) }
            emptySet()
        }

        return rawSet.map(::normalize).filter { it.isNotBlank() }.toSet()
    }

    fun isPaired(ctx: Context): Boolean = getPairedUidsHex(ctx).isNotEmpty()

    fun getEnabledPairedUidsHex(ctx: Context): Set<String> =
        getPairedUidsHex(ctx).filter { uid -> isTagEnabled(ctx, uid) }.toSet()

    fun isTagEnabled(ctx: Context, uidHex: String): Boolean {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return false
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_TAG_ENABLED_PREFIX + clean, true)
    }

    @Synchronized
    fun setTagEnabled(ctx: Context, uidHex: String, enabled: Boolean) {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit(commit = true) { putBoolean(KEY_TAG_ENABLED_PREFIX + clean, enabled) }
    }

    /**
     * @return true if this UID was newly added (was not previously paired)
     */
    @Synchronized
    fun addPairedUidHex(ctx: Context, uidHex: String, tagKind: TagKind? = null): Boolean {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return false

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getPairedUidsHex(ctx).toMutableSet()
        val isNew = !current.contains(clean)
        current.add(clean)

        // commit=true: avoid write-loss if user force-stops right after pairing.
        sp.edit(commit = true) {
            putStringSet(KEY_PAIRED_UID_HEX_SET, current)

            // Track pairing time for sorting (best-effort; existing tags may not have it).
            if (isNew && !sp.contains(KEY_TAG_PAIRED_AT_PREFIX + clean)) {
                putLong(KEY_TAG_PAIRED_AT_PREFIX + clean, System.currentTimeMillis())
            }

            // Store the pairing type when the user explicitly adds the tag as writable/read-only.
            // Existing tags can be re-paired through the correct flow to update their type.
            tagKind?.let { putString(KEY_TAG_KIND_PREFIX + clean, it.id) }
        }

        return isNew
    }

    @Synchronized
    fun removePairedUidHex(ctx: Context, uidHex: String) {
        val clean = normalize(uidHex)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getPairedUidsHex(ctx).toMutableSet()
        current.remove(clean)

        sp.edit(commit = true) {
            putStringSet(KEY_PAIRED_UID_HEX_SET, current)
            remove(KEY_TAG_NAME_PREFIX + clean)
            remove(KEY_TAG_NOTE_PREFIX + clean)
            remove(KEY_TAG_PAIRED_AT_PREFIX + clean)
            remove(KEY_TAG_KIND_PREFIX + clean)
            remove(KEY_TAG_ENABLED_PREFIX + clean)
            remove(KEY_TAG_READ_ONLY_ACTION_PREFIX + clean)
            remove(KEY_TAG_READ_ONLY_DURATION_PREFIX + clean)
        }

        // Also remove optional per-tag limiter overrides.
        NfcTempDisableLimiterStore.clearTagConfig(
            uidBucket = NfcTempDisableLimiterStore.bucketForUid(clean),
            ctx = ctx
        )
    }

    fun getTagMeta(ctx: Context, uidHex: String): TagMeta {
        val clean = normalize(uidHex)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_TAG_NAME_PREFIX + clean, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val note = sp.getString(KEY_TAG_NOTE_PREFIX + clean, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val pairedAt = sp.getLong(KEY_TAG_PAIRED_AT_PREFIX + clean, 0L)
        val tagKind = TagKind.fromId(sp.getString(KEY_TAG_KIND_PREFIX + clean, null))
        val readOnlyAction = ReadOnlyAction.fromId(sp.getString(KEY_TAG_READ_ONLY_ACTION_PREFIX + clean, null))
        val enabled = sp.getBoolean(KEY_TAG_ENABLED_PREFIX + clean, true)
        val readOnlyDurationMinutes = sp.getInt(
            KEY_TAG_READ_ONLY_DURATION_PREFIX + clean,
            DEFAULT_READ_ONLY_TEMP_DURATION_MINUTES
        ).coerceIn(1, 1440)
        return TagMeta(
            uid = clean,
            name = name,
            note = note,
            pairedAtMillis = pairedAt,
            tagKind = tagKind,
            readOnlyAction = readOnlyAction,
            readOnlyDurationMinutes = readOnlyDurationMinutes,
            enabled = enabled
        )
    }

    fun supportsUidOnlyAction(ctx: Context, uidHex: String): Boolean {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return false
        return getTagMeta(ctx, clean).supportsUidOnlyAction
    }

    fun getReadOnlyAction(ctx: Context, uidHex: String): ReadOnlyAction {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return ReadOnlyAction.TOGGLE
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReadOnlyAction.fromId(sp.getString(KEY_TAG_READ_ONLY_ACTION_PREFIX + clean, null))
    }

    @Synchronized
    fun setReadOnlyAction(ctx: Context, uidHex: String, action: ReadOnlyAction) {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit(commit = true) { putString(KEY_TAG_READ_ONLY_ACTION_PREFIX + clean, action.id) }
    }

    @Synchronized
    fun setTagMeta(
        ctx: Context,
        uidHex: String,
        name: String?,
        note: String?,
        readOnlyAction: ReadOnlyAction? = null,
        readOnlyDurationMinutes: Int? = null,
        tagKind: TagKind? = null,
    ) {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return

        fun sanitize(v: String?, maxLen: Int): String? {
            val s = v
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.trim()
                ?.take(maxLen)
            return s?.takeIf { it.isNotBlank() }
        }

        val n = sanitize(name, 80)
        val no = sanitize(note, 240)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit(commit = true) {
            if (n == null) remove(KEY_TAG_NAME_PREFIX + clean) else putString(KEY_TAG_NAME_PREFIX + clean, n)
            if (no == null) remove(KEY_TAG_NOTE_PREFIX + clean) else putString(KEY_TAG_NOTE_PREFIX + clean, no)
            readOnlyAction?.let { putString(KEY_TAG_READ_ONLY_ACTION_PREFIX + clean, it.id) }
            readOnlyDurationMinutes
                ?.coerceIn(1, 1440)
                ?.let { putInt(KEY_TAG_READ_ONLY_DURATION_PREFIX + clean, it) }
            tagKind?.let { putString(KEY_TAG_KIND_PREFIX + clean, it.id) }
        }
    }

    fun getPairedTags(ctx: Context): List<TagMeta> {
        return getPairedUidsHex(ctx)
            .map { uid -> getTagMeta(ctx, uid) }
            .sortedWith(compareBy<TagMeta>({ it.name?.lowercase() ?: "~" }, { it.uid }))
    }

    @Synchronized
    fun clear(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uids = getPairedUidsHex(ctx)
        sp.edit(commit = true) {
            remove(KEY_PAIRED_UID_HEX_SET)
            uids.forEach { uid ->
                remove(KEY_TAG_NAME_PREFIX + uid)
                remove(KEY_TAG_NOTE_PREFIX + uid)
                remove(KEY_TAG_PAIRED_AT_PREFIX + uid)
                remove(KEY_TAG_KIND_PREFIX + uid)
                remove(KEY_TAG_ENABLED_PREFIX + uid)
                remove(KEY_TAG_READ_ONLY_ACTION_PREFIX + uid)
                remove(KEY_TAG_READ_ONLY_DURATION_PREFIX + uid)
            }
        }
        uids.forEach { uid ->
            NfcTempDisableLimiterStore.clearTagConfig(
                uidBucket = NfcTempDisableLimiterStore.bucketForUid(uid),
                ctx = ctx
            )
        }
    }

    // Convenience helper (returns first UID if any)
    fun getPairedUidHex(ctx: Context): String? = getPairedUidsHex(ctx).sorted().firstOrNull()
}
