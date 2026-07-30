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

// Persists and retrieves nfc diagnostics state.
object NfcDiagnosticsStore {
    data class Snapshot(
        val lastIntentAtMillis: Long,
        val lastIntentAction: String,
        val lastUidHex: String,
        val lastTechList: String,
        val lastUri: String,
        val lastResolvedAction: String,
        val lastResolvedProfile: String,
        val lastFailureReason: String,
        val lastWriteAtMillis: Long,
        val lastWriteResult: String,
    )

    private const val PREFS = "switchly_prefs"
    private const val KEY_LAST_INTENT_AT = "nfc_diag_last_intent_at"
    private const val KEY_LAST_INTENT_ACTION = "nfc_diag_last_intent_action"
    private const val KEY_LAST_UID = "nfc_diag_last_uid"
    private const val KEY_LAST_TECHS = "nfc_diag_last_techs"
    private const val KEY_LAST_URI = "nfc_diag_last_uri"
    private const val KEY_LAST_RESOLVED_ACTION = "nfc_diag_last_resolved_action"
    private const val KEY_LAST_RESOLVED_PROFILE = "nfc_diag_last_resolved_profile"
    private const val KEY_LAST_FAILURE = "nfc_diag_last_failure"
    private const val KEY_LAST_WRITE_AT = "nfc_diag_last_write_at"
    private const val KEY_LAST_WRITE_RESULT = "nfc_diag_last_write_result"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordIntentReceived(
        context: Context,
        intentAction: String?,
        uidHex: String?,
        techList: List<String>,
        uri: String?,
    ) {
        prefs(context).edit {
            putLong(KEY_LAST_INTENT_AT, System.currentTimeMillis())
            putString(KEY_LAST_INTENT_ACTION, intentAction.orEmpty())
            putString(KEY_LAST_UID, uidHex.orEmpty())
            putString(KEY_LAST_TECHS, techList.joinToString(",") { it.substringAfterLast('.') })
            putString(KEY_LAST_URI, uri.orEmpty())
            remove(KEY_LAST_FAILURE)
        }
    }

    fun recordResolvedUri(context: Context, uri: String?) {
        prefs(context).edit {
            putString(KEY_LAST_URI, uri.orEmpty())
        }
    }

    fun recordResolvedAction(
        context: Context,
        action: String,
        profile: String? = null,
    ) {
        prefs(context).edit {
            putString(KEY_LAST_RESOLVED_ACTION, action)
            putString(KEY_LAST_RESOLVED_PROFILE, profile.orEmpty())
            remove(KEY_LAST_FAILURE)
        }
    }

    fun recordFailure(
        context: Context,
        reason: String,
    ) {
        prefs(context).edit {
            putString(KEY_LAST_FAILURE, reason)
        }
    }

    fun recordWriteResult(
        context: Context,
        result: String,
    ) {
        prefs(context).edit {
            putLong(KEY_LAST_WRITE_AT, System.currentTimeMillis())
            putString(KEY_LAST_WRITE_RESULT, result)
        }
    }

    fun snapshot(context: Context): Snapshot {
        val sp = prefs(context)
        return Snapshot(
            lastIntentAtMillis = readLongCompat(sp.all[KEY_LAST_INTENT_AT]),
            lastIntentAction = sp.getString(KEY_LAST_INTENT_ACTION, null).orEmpty(),
            lastUidHex = sp.getString(KEY_LAST_UID, null).orEmpty(),
            lastTechList = sp.getString(KEY_LAST_TECHS, null).orEmpty(),
            lastUri = sp.getString(KEY_LAST_URI, null).orEmpty(),
            lastResolvedAction = sp.getString(KEY_LAST_RESOLVED_ACTION, null).orEmpty(),
            lastResolvedProfile = sp.getString(KEY_LAST_RESOLVED_PROFILE, null).orEmpty(),
            lastFailureReason = sp.getString(KEY_LAST_FAILURE, null).orEmpty(),
            lastWriteAtMillis = readLongCompat(sp.all[KEY_LAST_WRITE_AT]),
            lastWriteResult = sp.getString(KEY_LAST_WRITE_RESULT, null).orEmpty(),
        )
    }

    private fun readLongCompat(value: Any?): Long {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
