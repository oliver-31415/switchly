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

package at.saltyy.switchly.data.statistics

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object StatsBackupCodec {
    const val FIELD_VERSION = "version"
    const val FIELD_ENCODING = "encoding"
    const val FIELD_CHUNKS = "chunks"
    const val FIELD_VALUE_COUNT = "value_count"
    const val FIELD_SESSION_COUNT = "session_count"
    const val FIELD_EVENT_COUNT = "event_count"

    private const val VERSION = 1
    private const val ENCODING = "gzip-base64"
    private const val CHUNK_SIZE = 240_000

    fun encode(snapshot: StatsPersistence.Snapshot): Map<String, Any?> {
        val root = JSONObject()
        root.put("v", JSONArray().apply {
            snapshot.values.forEach { value ->
                put(JSONArray().apply {
                    put(value.id)
                    put(value.prefsName)
                    put(value.prefKey)
                    put(value.valueType)
                    put(value.longValue ?: JSONObject.NULL)
                    put(value.realValue ?: JSONObject.NULL)
                    put(value.textValue ?: JSONObject.NULL)
                    put(value.updatedAtMs)
                })
            }
        })
        root.put("s", JSONArray().apply {
            snapshot.sessions.forEach { session ->
                put(JSONArray().apply {
                    put(session.id)
                    put(session.category)
                    put(session.day)
                    put(session.subject)
                    put(session.startMs)
                    put(session.endMs)
                    put(session.metadata ?: JSONObject.NULL)
                    put(session.updatedAtMs)
                })
            }
        })
        root.put("e", JSONArray().apply {
            snapshot.events.forEach { event ->
                put(JSONArray().apply {
                    put(event.id)
                    put(event.day)
                    put(event.timestampMs)
                    put(event.category)
                    put(event.tag)
                    put(event.message)
                    put(event.rawLine)
                })
            }
        })
        root.put("m", JSONArray().apply {
            snapshot.metadata.forEach { metadata ->
                put(JSONArray().put(metadata.key).put(metadata.value))
            }
        })

        val compressed = gzip(root.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(compressed)
        val chunks = encoded.chunked(CHUNK_SIZE)

        return mapOf(
            FIELD_VERSION to VERSION,
            FIELD_ENCODING to ENCODING,
            FIELD_CHUNKS to chunks,
            FIELD_VALUE_COUNT to snapshot.values.size,
            FIELD_SESSION_COUNT to snapshot.sessions.size,
            FIELD_EVENT_COUNT to snapshot.events.size,
        )
    }

    fun decode(payload: Map<*, *>): StatsPersistence.Snapshot {
        val version = (payload[FIELD_VERSION] as? Number)?.toInt() ?: 0
        require(version == VERSION) { "Unsupported statistics database backup version: $version" }
        require(payload[FIELD_ENCODING] == ENCODING) { "Unsupported statistics database backup encoding" }
        val chunks = (payload[FIELD_CHUNKS] as? List<*>)
            ?.filterIsInstance<String>()
            .orEmpty()
        require(chunks.isNotEmpty()) { "Statistics database backup is incomplete" }

        val compressed = Base64.getDecoder().decode(chunks.joinToString(separator = ""))
        val json = ungzip(compressed).toString(Charsets.UTF_8)
        val root = JSONObject(json)

        val values = root.optJSONArray("v").toRows { row ->
            StatValueEntity(
                id = row.getString(0),
                prefsName = row.getString(1),
                prefKey = row.getString(2),
                valueType = row.getString(3),
                longValue = row.optNullableLong(4),
                realValue = row.optNullableDouble(5),
                textValue = row.optNullableString(6),
                updatedAtMs = row.getLong(7),
            )
        }
        val sessions = root.optJSONArray("s").toRows { row ->
            StatSessionEntity(
                id = row.getString(0),
                category = row.getString(1),
                day = row.getInt(2),
                subject = row.getString(3),
                startMs = row.getLong(4),
                endMs = row.getLong(5),
                metadata = row.optNullableString(6),
                updatedAtMs = row.getLong(7),
            )
        }
        val events = root.optJSONArray("e").toRows { row ->
            StatEventEntity(
                id = row.getString(0),
                day = row.getInt(1),
                timestampMs = row.getLong(2),
                category = row.getString(3),
                tag = row.getString(4),
                message = row.getString(5),
                rawLine = row.getString(6),
            )
        }
        val metadata = root.optJSONArray("m").toRows { row ->
            StatMetadataEntity(row.getString(0), row.getString(1))
        }
        requireCount(payload, FIELD_VALUE_COUNT, values.size)
        requireCount(payload, FIELD_SESSION_COUNT, sessions.size)
        requireCount(payload, FIELD_EVENT_COUNT, events.size)
        return StatsPersistence.Snapshot(values, sessions, events, metadata)
    }

    private fun requireCount(payload: Map<*, *>, field: String, actual: Int) {
        val expected = (payload[field] as? Number)?.toInt() ?: return
        require(expected == actual) { "Statistics database backup count mismatch for $field" }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { stream -> stream.write(bytes) }
        return output.toByteArray()
    }

    private fun ungzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { stream -> stream.readBytes() }
    }

    private fun <T> JSONArray?.toRows(transform: (JSONArray) -> T): List<T> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                val row = optJSONArray(index) ?: continue
                add(transform(row))
            }
        }
    }

    private fun JSONArray.optNullableLong(index: Int): Long? {
        if (isNull(index)) {
            return null
        }
        return optLong(index)
    }

    private fun JSONArray.optNullableDouble(index: Int): Double? {
        if (isNull(index)) {
            return null
        }
        return optDouble(index)
    }

    private fun JSONArray.optNullableString(index: Int): String? {
        if (isNull(index)) {
            return null
        }
        return optString(index)
    }
}
