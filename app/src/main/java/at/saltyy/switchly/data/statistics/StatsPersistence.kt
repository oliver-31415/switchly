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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import at.saltyy.switchly.data.prefs.ActivityHistoryLogStore
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Durable statistics archive.
 * Existing stores keep their synchronous SharedPreferences API so blocking/accessibility code does not gain database latency.
 * Every statistics key is mirrored to Room, Room restores missing cache keys on startup, and exact sessions/events are stored in structured tables.
 * This makes Room the durable archive while preserving compatibility with every existing statistics screen.
 */
object StatsPersistence {
    private const val TAG = "StatsPersistence"
    private const val DEFAULT_PREFS_SOURCE = "__default_preferences__"
    private const val INTERNAL_PREFS_SOURCE = "switchly_prefs"
    private const val UI_HINTS_PREFS_SOURCE = "switchly_ui_hints"
    private const val MIGRATION_VERSION_KEY = "shared_preferences_migration_version"
    private const val MIGRATION_VERSION = "1"
    private const val SESSION_APP = "app"
    private const val SESSION_SCREEN_UNLOCK = "screen_unlock"
    private const val SESSION_WEBSITE = "website"
    private const val IO_TIMEOUT_SECONDS = 120L

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SwitchlyStatsRoom").apply { isDaemon = true }
    }
    private val pauseDepth = AtomicInteger(0)
    private val initLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var ioThread: Thread? = null

    private lateinit var appContext: Context
    private lateinit var internalPrefs: SharedPreferences
    private lateinit var defaultPrefs: SharedPreferences
    private lateinit var historyPrefs: SharedPreferences
    private lateinit var uiHintsPrefs: SharedPreferences

    private val internalStatsPrefixes = listOf(
        "usage_day_",
        "blocked_ms_",
        "blocked_count_",
        "blocked_attempt_",
        "profile_usage_day_",
        "surf_usage_day_",
        "surface_usage_",
        "open_count_",
        "app_launch_count_",
        "screen_unlock_",
        "limit_hit_count_",
        "switchly_runtime_ms_",
        "switchly_active_",
        "switch_action_count_",
        "schedule_exec_count_",
        "emergency_unlock_count_",
        "nfc_scan_count_",
        "qr_scan_count_",
        "barcode_scan_count_",
        "temp_enable_count_",
        "scan_code_last_used_",
        "scan_code_count_",
    )

    private val internalStatsExactKeys = setOf(
        "blocked_inbox_events",
        "blocked_inbox_events_updated_at",
        "switchly_active_overall_ms",
    )

    private val defaultStatsPrefixes = listOf(
        "web_usage_day_",
        "web_usage_sessions_",
        "qr_temp_last_",
        "qr_temp_count_",
        "nfc_td_last_",
        "nfc_td_count_",
    )

    private val uiHintsStatsExactKeys = setOf(
        "primary_toggle_tap_count",
    )

    private val internalListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        onPreferenceChanged(INTERNAL_PREFS_SOURCE, prefs, key)
    }
    private val defaultListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        onPreferenceChanged(DEFAULT_PREFS_SOURCE, prefs, key)
    }
    private val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        onPreferenceChanged(ActivityHistoryLogStore.PREFS_NAME, prefs, key)
    }
    private val uiHintsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        onPreferenceChanged(UI_HINTS_PREFS_SOURCE, prefs, key)
    }

    data class AppSession(
        val packageName: String,
        val startMs: Long,
        val endMs: Long,
    )

    data class ArchivedSession(
        val subject: String,
        val startMs: Long,
        val endMs: Long,
    )

    data class Snapshot(
        val values: List<StatValueEntity>,
        val sessions: List<StatSessionEntity>,
        val events: List<StatEventEntity>,
        val metadata: List<StatMetadataEntity>,
    )

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        synchronized(initLock) {
            if (initialized) {
                return
            }
            appContext = context.applicationContext
            internalPrefs = appContext.getSharedPreferences(INTERNAL_PREFS_SOURCE, Context.MODE_PRIVATE)
            defaultPrefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            historyPrefs = appContext.getSharedPreferences(ActivityHistoryLogStore.PREFS_NAME, Context.MODE_PRIVATE)
            uiHintsPrefs = appContext.getSharedPreferences(UI_HINTS_PREFS_SOURCE, Context.MODE_PRIVATE)

            internalPrefs.registerOnSharedPreferenceChangeListener(internalListener)
            defaultPrefs.registerOnSharedPreferenceChangeListener(defaultListener)
            historyPrefs.registerOnSharedPreferenceChangeListener(historyListener)
            uiHintsPrefs.registerOnSharedPreferenceChangeListener(uiHintsListener)
            initialized = true

            executeIo {
                reconcileDatabaseAndCache()
            }
        }
    }

    fun prepareForFullDataDeletion(context: Context) {
        initialize(context)
        synchronized(initLock) {
            pauseDepth.incrementAndGet()
            try {
                runIoBlocking { }
                internalPrefs.unregisterOnSharedPreferenceChangeListener(internalListener)
                defaultPrefs.unregisterOnSharedPreferenceChangeListener(defaultListener)
                historyPrefs.unregisterOnSharedPreferenceChangeListener(historyListener)
                uiHintsPrefs.unregisterOnSharedPreferenceChangeListener(uiHintsListener)
                StatsDatabase.closeInstance()
                initialized = false
            } catch (error: Throwable) {
                pauseDepth.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                throw error
            }
        }
    }

    fun resumeAfterFullDataDeletion(context: Context) {
        pauseDepth.set(0)
        initialize(context)
    }

    fun beginRestore(context: Context) {
        initialize(context)
        pauseDepth.incrementAndGet()
        try {
            flushBlocking(context)
        } catch (error: Throwable) {
            pauseDepth.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            throw error
        }
    }

    fun finishRestore(
        context: Context,
        databasePayload: Map<*, *>?,
        replaceDatabase: Boolean,
    ) {
        initialize(context)
        try {
            if (databasePayload != null) {
                val snapshot = StatsBackupCodec.decode(databasePayload)
                runIoBlocking {
                    val dao = dao()
                    if (replaceDatabase) {
                        dao.replaceAll(snapshot.values, snapshot.sessions, snapshot.events, snapshot.metadata)
                    } else {
                        if (snapshot.values.isNotEmpty()) {
                            dao.upsertValues(snapshot.values)
                        }
                        if (snapshot.sessions.isNotEmpty()) {
                            dao.upsertSessions(snapshot.sessions)
                        }
                        if (snapshot.events.isNotEmpty()) {
                            dao.upsertEvents(snapshot.events)
                        }
                        snapshot.metadata.forEach(dao::putMetadata)
                    }
                    // The selected backup preferences may contain newer or more complete legacy counters.
                    // Use the database archive only to fill missing cache entries, then mirror the merged result.
                    restoreCacheValues(dao.getAllValues(), overwriteExisting = false)
                    mirrorAllPreferenceValues()
                }
            } else {
                runIoBlocking {
                    if (replaceDatabase) {
                        dao().replaceAll(emptyList(), emptyList(), emptyList(), emptyList())
                    }
                    mirrorAllPreferenceValues()
                }
            }
        } finally {
            pauseDepth.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            executeIo { mirrorAllPreferenceValues() }
        }
    }

    fun flushBlocking(context: Context) {
        initialize(context)
        runIoBlocking {
            mirrorAllPreferenceValues()
        }
    }

    fun snapshotForBackup(context: Context): Snapshot {
        initialize(context)
        return runIoBlocking {
            mirrorAllPreferenceValues()
            val dao = dao()
            Snapshot(
                values = dao.getAllValues(),
                sessions = dao.getAllSessions(),
                events = dao.getAllEvents(),
                metadata = listOf(
                    StatMetadataEntity("schema", "1"),
                    StatMetadataEntity("exported_at_ms", System.currentTimeMillis().toString()),
                ),
            )
        }
    }

    fun archiveAppSessions(context: Context, sessions: List<AppSession>) {
        if (sessions.isEmpty()) {
            return
        }
        initialize(context)
        val now = System.currentTimeMillis()
        val entities = sessions.mapNotNull { session ->
            if (session.packageName.isBlank() || session.startMs <= 0L || session.endMs < session.startMs) {
                return@mapNotNull null
            }
            StatSessionEntity(
                id = sessionId(SESSION_APP, session.packageName, session.startMs),
                category = SESSION_APP,
                day = ymd(session.startMs),
                subject = session.packageName,
                startMs = session.startMs,
                endMs = session.endMs,
                updatedAtMs = now,
            )
        }
        if (entities.isEmpty()) {
            return
        }
        executeIo {
            dao().upsertSessions(entities)
        }
    }

    fun appSessionsForRange(
        context: Context,
        packageName: String?,
        startMs: Long,
        endMs: Long,
    ): List<AppSession> {
        if (endMs <= startMs) {
            return emptyList()
        }
        initialize(context)
        return runIoBlocking {
            dao().getSessions(SESSION_APP, packageName, startMs, endMs).map { entity ->
                AppSession(
                    packageName = entity.subject,
                    startMs = maxOf(entity.startMs, startMs),
                    endMs = minOf(entity.endMs, endMs),
                )
            }
        }
    }

    fun screenUnlockSessionsForRange(
        context: Context,
        startMs: Long,
        endMs: Long,
    ): List<ArchivedSession> {
        return archivedSessionsForRange(context, SESSION_SCREEN_UNLOCK, "device", startMs, endMs)
    }

    fun websiteSessionsForRange(
        context: Context,
        domain: String?,
        startMs: Long,
        endMs: Long,
    ): List<ArchivedSession> {
        return archivedSessionsForRange(context, SESSION_WEBSITE, domain, startMs, endMs)
    }

    private fun archivedSessionsForRange(
        context: Context,
        category: String,
        subject: String?,
        startMs: Long,
        endMs: Long,
    ): List<ArchivedSession> {
        if (endMs <= startMs) {
            return emptyList()
        }
        initialize(context)
        return runIoBlocking {
            dao().getSessions(category, subject, startMs, endMs).map { entity ->
                ArchivedSession(
                    subject = entity.subject,
                    startMs = maxOf(entity.startMs, startMs),
                    endMs = minOf(entity.endMs, endMs),
                )
            }
        }
    }

    fun isArchivedInternalKey(key: String): Boolean {
        if (key in internalStatsExactKeys) {
            return true
        }
        return internalStatsPrefixes.any { prefix -> key.startsWith(prefix) }
    }

    fun isArchivedDefaultKey(key: String): Boolean {
        return defaultStatsPrefixes.any { prefix -> key.startsWith(prefix) }
    }

    fun isArchivedUiHintsKey(key: String): Boolean {
        return key in uiHintsStatsExactKeys
    }

    fun clearAppSessions(context: Context, packageName: String) {
        if (packageName.isBlank()) {
            return
        }
        initialize(context)
        executeIo {
            dao().deleteSessionsForSubject(SESSION_APP, packageName)
        }
    }

    fun clearWebsiteSessions(context: Context, domain: String) {
        if (domain.isBlank()) {
            return
        }
        initialize(context)
        executeIo {
            dao().deleteSessionsForSubject(SESSION_WEBSITE, domain)
        }
    }

    private fun onPreferenceChanged(
        prefsName: String,
        preferences: SharedPreferences,
        key: String?,
    ) {
        if (!initialized || pauseDepth.get() > 0) {
            return
        }
        if (key == null) {
            executeIo { mirrorAllPreferenceValues() }
            return
        }
        if (!isStatisticsKey(prefsName, key)) {
            return
        }
        val value = preferences.all[key]
        executeIo {
            mirrorOneValue(prefsName, key, value)
        }
    }

    private fun reconcileDatabaseAndCache() {
        val dao = dao()
        val existing = dao.getAllValues()
        if (existing.isNotEmpty()) {
            pauseDepth.incrementAndGet()
            try {
                restoreCacheValues(existing, overwriteExisting = false)
            } finally {
                pauseDepth.decrementAndGet()
            }
        }
        mirrorAllPreferenceValues()
        dao.putMetadata(StatMetadataEntity(MIGRATION_VERSION_KEY, MIGRATION_VERSION))
    }

    private fun restoreCacheValues(
        values: List<StatValueEntity>,
        overwriteExisting: Boolean,
    ) {
        values.groupBy(StatValueEntity::prefsName).forEach { (prefsName, rows) ->
            val preferences = preferencesForSource(prefsName) ?: return@forEach
            preferences.edit(commit = true) {
                rows.forEach { row ->
                    if (overwriteExisting || !preferences.contains(row.prefKey)) {
                        putEntityValue(this, row)
                    }
                }
            }
        }
    }

    private fun mirrorAllPreferenceValues() {
        val values = ArrayList<StatValueEntity>()
        collectPreferenceValues(INTERNAL_PREFS_SOURCE, internalPrefs, values)
        collectPreferenceValues(DEFAULT_PREFS_SOURCE, defaultPrefs, values)
        collectPreferenceValues(ActivityHistoryLogStore.PREFS_NAME, historyPrefs, values)
        collectPreferenceValues(UI_HINTS_PREFS_SOURCE, uiHintsPrefs, values)

        val dao = dao()
        val currentIds = values.mapTo(hashSetOf()) { value -> value.id }
        val archivedSources = setOf(
            INTERNAL_PREFS_SOURCE,
            DEFAULT_PREFS_SOURCE,
            ActivityHistoryLogStore.PREFS_NAME,
            UI_HINTS_PREFS_SOURCE,
        )
        val removedRows = dao.getAllValues().filter { row ->
            row.prefsName in archivedSources && row.id !in currentIds
        }
        if (removedRows.isNotEmpty()) {
            dao.deleteValues(removedRows.map(StatValueEntity::id))
            removedRows.forEach { row ->
                removeStructuredRowsForKey(dao, row.prefsName, row.prefKey)
            }
        }
        if (values.isNotEmpty()) {
            dao.upsertValues(values)
        }
        rebuildStructuredRows(values)
    }

    private fun collectPreferenceValues(
        prefsName: String,
        preferences: SharedPreferences,
        output: MutableList<StatValueEntity>,
    ) {
        val now = System.currentTimeMillis()
        preferences.all.forEach { (key, value) ->
            if (!isStatisticsKey(prefsName, key)) {
                return@forEach
            }
            encodePreferenceValue(prefsName, key, value, now)?.let(output::add)
        }
    }

    private fun mirrorOneValue(prefsName: String, key: String, value: Any?) {
        val dao = dao()
        val id = valueId(prefsName, key)
        if (value == null) {
            dao.deleteValue(id)
            removeStructuredRowsForKey(dao, prefsName, key)
            return
        }
        val entity = encodePreferenceValue(prefsName, key, value, System.currentTimeMillis()) ?: return
        dao.upsertValues(listOf(entity))
        rebuildStructuredRow(entity)
    }

    private fun rebuildStructuredRows(values: List<StatValueEntity>) {
        values.forEach(::rebuildStructuredRow)
    }

    private fun rebuildStructuredRow(value: StatValueEntity) {
        val dao = dao()
        val raw = decodeTextValue(value)
        when {
            value.prefsName == INTERNAL_PREFS_SOURCE && value.prefKey.startsWith("screen_unlock_sessions_") -> {
                val day = value.prefKey.removePrefix("screen_unlock_sessions_").take(8).toIntOrNull() ?: return
                val sessions = parseScreenUnlockSessions(raw).map { (start, end) ->
                    StatSessionEntity(
                        id = sessionId(SESSION_SCREEN_UNLOCK, "device", start),
                        category = SESSION_SCREEN_UNLOCK,
                        day = day,
                        subject = "device",
                        startMs = start,
                        endMs = end,
                        updatedAtMs = value.updatedAtMs,
                    )
                }
                if (sessions.isNotEmpty()) {
                    dao.upsertSessions(sessions)
                }
            }

            value.prefsName == DEFAULT_PREFS_SOURCE && value.prefKey.startsWith("web_usage_sessions_") -> {
                val day = value.prefKey.removePrefix("web_usage_sessions_").take(8).toIntOrNull() ?: return
                val sessions = parseWebsiteSessions(raw).map { session ->
                    StatSessionEntity(
                        id = sessionId(SESSION_WEBSITE, session.first, session.second),
                        category = SESSION_WEBSITE,
                        day = day,
                        subject = session.first,
                        startMs = session.second,
                        endMs = session.third,
                        updatedAtMs = value.updatedAtMs,
                    )
                }
                if (sessions.isNotEmpty()) {
                    dao.upsertSessions(sessions)
                }
            }

            value.prefsName == ActivityHistoryLogStore.PREFS_NAME && value.prefKey.startsWith("day_") -> {
                val day = value.prefKey.removePrefix("day_").take(8).toIntOrNull() ?: return
                val events = parseActivityEvents(raw, day)
                if (events.isNotEmpty()) {
                    dao.upsertEvents(events)
                }
            }
        }
    }

    private fun removeStructuredRowsForKey(dao: StatsDao, prefsName: String, key: String) {
        when {
            prefsName == INTERNAL_PREFS_SOURCE && key.startsWith("screen_unlock_sessions_") -> {
                key.removePrefix("screen_unlock_sessions_").take(8).toIntOrNull()?.let { day ->
                    dao.deleteSessionsForDay(SESSION_SCREEN_UNLOCK, day)
                }
            }
            prefsName == DEFAULT_PREFS_SOURCE && key.startsWith("web_usage_sessions_") -> {
                key.removePrefix("web_usage_sessions_").take(8).toIntOrNull()?.let { day ->
                    dao.deleteSessionsForDay(SESSION_WEBSITE, day)
                }
            }
            prefsName == ActivityHistoryLogStore.PREFS_NAME && key.startsWith("day_") -> {
                key.removePrefix("day_").take(8).toIntOrNull()?.let(dao::deleteEventsForDay)
            }
        }
    }

    private fun isStatisticsKey(prefsName: String, key: String): Boolean {
        return when (prefsName) {
            INTERNAL_PREFS_SOURCE -> isArchivedInternalKey(key)
            DEFAULT_PREFS_SOURCE -> isArchivedDefaultKey(key)
            ActivityHistoryLogStore.PREFS_NAME -> key.startsWith("day_") || key == ActivityHistoryLogStore.KEY_LINES
            UI_HINTS_PREFS_SOURCE -> isArchivedUiHintsKey(key)
            else -> false
        }
    }

    private fun preferencesForSource(prefsName: String): SharedPreferences? {
        return when (prefsName) {
            INTERNAL_PREFS_SOURCE -> internalPrefs
            DEFAULT_PREFS_SOURCE -> defaultPrefs
            ActivityHistoryLogStore.PREFS_NAME -> historyPrefs
            UI_HINTS_PREFS_SOURCE -> uiHintsPrefs
            else -> appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }
    }

    private fun encodePreferenceValue(
        prefsName: String,
        key: String,
        value: Any?,
        updatedAt: Long,
    ): StatValueEntity? {
        val base = StatValueEntity(
            id = valueId(prefsName, key),
            prefsName = prefsName,
            prefKey = key,
            valueType = "",
            updatedAtMs = updatedAt,
        )
        return when (value) {
            is Int -> base.copy(valueType = "int", longValue = value.toLong())
            is Long -> base.copy(valueType = "long", longValue = value)
            is Boolean -> {
                val encoded = if (value) {
                    1L
                } else {
                    0L
                }
                base.copy(valueType = "boolean", longValue = encoded)
            }
            is Float -> base.copy(valueType = "float", realValue = value.toDouble())
            is String -> base.copy(valueType = "string", textValue = value)
            is Set<*> -> {
                val strings = value.filterIsInstance<String>().sorted()
                base.copy(valueType = "string_set", textValue = JSONArray(strings).toString())
            }
            else -> null
        }
    }

    private fun putEntityValue(editor: SharedPreferences.Editor, value: StatValueEntity) {
        when (value.valueType) {
            "int" -> editor.putInt(value.prefKey, value.longValue.orZero().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            "long" -> editor.putLong(value.prefKey, value.longValue.orZero())
            "boolean" -> editor.putBoolean(value.prefKey, value.longValue == 1L)
            "float" -> editor.putFloat(value.prefKey, (value.realValue ?: 0.0).toFloat())
            "string" -> editor.putString(value.prefKey, value.textValue.orEmpty())
            "string_set" -> editor.putStringSet(value.prefKey, parseStringSet(value.textValue))
        }
    }

    private fun decodeTextValue(value: StatValueEntity): String? {
        if (value.valueType != "string") {
            return null
        }
        return value.textValue
    }

    private fun parseStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) {
            return emptySet()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun parseScreenUnlockSessions(raw: String?): List<Pair<Long, Long>> {
        return raw.orEmpty().split(';').mapNotNull { entry ->
            val pieces = entry.split(':', limit = 2)
            val start = pieces.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val end = pieces.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (start <= 0L || end < start) {
                null
            } else {
                start to end
            }
        }
    }

    private fun parseWebsiteSessions(raw: String?): List<Triple<String, Long, Long>> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val domain = item.optString("d").trim()
                    val start = item.optLong("s", 0L)
                    val end = item.optLong("e", 0L)
                    if (domain.isNotBlank() && start > 0L && end >= start) {
                        add(Triple(domain, start, end))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseActivityEvents(raw: String?, fallbackDay: Int): List<StatEventEntity> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split('\u001E').mapNotNull { line ->
            val clean = line.trim()
            if (clean.isBlank()) {
                return@mapNotNull null
            }
            val tagStart = clean.indexOf('[')
            val tagEnd = clean.indexOf(']', tagStart + 1)
            if (tagStart < 0 || tagEnd <= tagStart) {
                return@mapNotNull null
            }
            val tag = clean.substring(tagStart + 1, tagEnd).trim()
            val message = clean.substring(tagEnd + 1).trim()
            val timestamp = parseActivityTimestamp(clean) ?: dayStartMs(fallbackDay)
            StatEventEntity(
                id = "event:" + sha256(clean),
                day = ymd(timestamp),
                timestampMs = timestamp,
                category = tag.lowercase(Locale.US),
                tag = tag,
                message = message,
                rawLine = clean,
            )
        }
    }

    private fun parseActivityTimestamp(line: String): Long? {
        if (line.length < 19) {
            return null
        }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }
                .parse(line.take(19))
                ?.time
        }.getOrNull()
    }

    private fun dayStartMs(day: Int): Long {
        val year = day / 10000
        val month = (day / 100) % 100
        val dayOfMonth = day % 100
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, dayOfMonth, 0, 0, 0)
        }.timeInMillis
    }

    private fun valueId(prefsName: String, key: String): String = "$prefsName:$key"

    private fun sessionId(category: String, subject: String, startMs: Long): String =
        "session:$category:${sha256(subject).take(16)}:$startMs"

    private fun ymd(timeMs: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMs }
        return calendar.get(Calendar.YEAR) * 10000 +
            (calendar.get(Calendar.MONTH) + 1) * 100 +
            calendar.get(Calendar.DAY_OF_MONTH)
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun dao(): StatsDao = StatsDatabase.get(appContext).statsDao()

    private fun executeIo(block: () -> Unit) {
        ioExecutor.execute {
            ioThread = Thread.currentThread()
            runCatching(block)
                .onFailure { error -> Log.e(TAG, "Statistics archive operation failed", error) }
        }
    }

    private fun <T> runIoBlocking(block: () -> T): T {
        if (Thread.currentThread() === ioThread) {
            return block()
        }
        val future = ioExecutor.submit(Callable {
            ioThread = Thread.currentThread()
            block()
        })
        return future.get(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
