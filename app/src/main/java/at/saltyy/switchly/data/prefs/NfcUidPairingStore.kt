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
 *
 * NOTE: Legacy single/csv keys + "wrong-type" fallbacks were intentionally removed.
 * This store now only reads/writes the canonical StringSet key.
 */
object NfcUidPairingStore {

    data class TagMeta(
        val uid: String,
        val name: String?,
        val note: String?,
        val pairedAtMillis: Long
    )

    private const val PREFS = "switchly_prefs"
    private const val KEY_PAIRED_UID_HEX_SET = "nfc_paired_uid_hex_set"

    private const val KEY_TAG_NAME_PREFIX = "nfc_tag_name_"
    private const val KEY_TAG_NOTE_PREFIX = "nfc_tag_note_"
    private const val KEY_TAG_PAIRED_AT_PREFIX = "nfc_tag_paired_at_"

    private fun normalize(uid: String?): String = NfcTagUid.normalizeUidHex(uid)

    @Synchronized
    fun getPairedUidsHex(ctx: Context): Set<String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawSet = runCatching {
            sp.getStringSet(KEY_PAIRED_UID_HEX_SET, emptySet())?.toSet()
        }.getOrNull()

        val migrated = rawSet ?: migrateLegacyOrInvalidUidStorage(ctx)
        return migrated.map(::normalize).filter { it.isNotBlank() }.toSet()
    }

    private fun migrateLegacyOrInvalidUidStorage(ctx: Context): Set<String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = runCatching { sp.all }.getOrDefault(emptyMap())
        val raw = all[KEY_PAIRED_UID_HEX_SET]

        val migrated = when (raw) {
            is Set<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.split(',', ';', '\n')
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            else -> emptyList()
        }.map(::normalize).filter { it.isNotBlank() }.toSet()

        sp.edit(commit = true) {
            if (migrated.isEmpty()) {
                remove(KEY_PAIRED_UID_HEX_SET)
            } else {
                putStringSet(KEY_PAIRED_UID_HEX_SET, migrated)
            }
        }
        return migrated
    }

    fun isPaired(ctx: Context): Boolean = getPairedUidsHex(ctx).isNotEmpty()

    @Synchronized
    /**
     * @return true if this UID was newly added (was not previously paired)
     */
    fun addPairedUidHex(ctx: Context, uidHex: String): Boolean {
        val clean = normalize(uidHex)
        if (clean.isBlank()) return false

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getPairedUidsHex(ctx).toMutableSet()
        val isNew = !current.contains(clean)
        current.add(clean)

        // commit=true: avoid write-loss if user force-stops right after pairing.
        sp.edit(commit = true) {
            putStringSet(KEY_PAIRED_UID_HEX_SET, current)

            // Track pairing time for sorting (best-effort; legacy tags may not have it).
            if (isNew && !sp.contains(KEY_TAG_PAIRED_AT_PREFIX + clean)) {
                putLong(KEY_TAG_PAIRED_AT_PREFIX + clean, System.currentTimeMillis())
            }
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
        return TagMeta(uid = clean, name = name, note = note, pairedAtMillis = pairedAt)
    }

    @Synchronized
    fun setTagMeta(ctx: Context, uidHex: String, name: String?, note: String?) {
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
