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

package at.saltyy.switchly.data.sync

import android.content.Context
import android.net.Uri
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.data.prefs.AppLogStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import org.json.JSONArray
import org.json.JSONObject

object FileBackupRuntime {

    private const val TAG = "FileBackupRuntime"
    private const val FIELD_APP_VERSION_CODE = "app_version_code"
    private const val FIELD_APP_VERSION_NAME = "app_version_name"
    private const val FIELD_EXPORTED_AT = "exported_at"
    private const val FIELD_PAYLOAD = "payload"
    private const val FIELD_SCHEMA = "schema"
    private const val SCHEMA = "switchly_backup_v1"

    fun writeLocalBackupToUri(ctx: Context, uri: Uri): Result<Unit> {
        return runCatching {
            val root = JSONObject()
                .put(FIELD_SCHEMA, SCHEMA)
                .put(FIELD_EXPORTED_AT, System.currentTimeMillis())
                .put(FIELD_APP_VERSION_NAME, BuildConfig.VERSION_NAME)
                .put(FIELD_APP_VERSION_CODE, BuildConfig.VERSION_CODE)
                .put(FIELD_PAYLOAD, toJsonValue(CloudSyncRuntime.createLocalBackupPayload(ctx)))

            ctx.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(root.toString(2))
                }
            } ?: error("Could not open backup file")
        }.onFailure { AppLogStore.append(ctx, TAG, "File backup failed", it) }
    }

    fun restoreBackupFromUri(ctx: Context, uri: Uri): Result<Unit> {
        return runCatching {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } ?: error("Could not open backup file")

            val root = JSONObject(raw)
            val payload = when {
                root.has(FIELD_PAYLOAD) -> fromJsonValue(root.get(FIELD_PAYLOAD)) as? Map<*, *>
                else -> fromJsonValue(root) as? Map<*, *>
            } ?: error("Invalid backup file")

            CloudSyncRuntime.applyBackupPayload(ctx, payload)
        }.onFailure { AppLogStore.append(ctx, TAG, "File restore failed", it) }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Boolean -> value
            is Number -> value
            is String -> value
            is Map<*, *> -> JSONObject().apply {
                for ((rawKey, rawValue) in value) {
                    val key = rawKey as? String ?: continue
                    put(key, toJsonValue(rawValue))
                }
            }
            is Iterable<*> -> JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }
            is Array<*> -> JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }
            else -> value.toString()
        }
    }

    private fun fromJsonValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> buildMap<String, Any?> {
                value.keys().forEach { key ->
                    put(key, fromJsonValue(value.get(key)))
                }
            }
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    add(fromJsonValue(value.get(i)))
                }
            }
            else -> value
        }
    }
}
