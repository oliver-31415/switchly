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

import at.saltyy.switchly.BuildConfig
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.auth.Auth
import at.saltyy.switchly.data.prefs.ActivityHistoryLogStore
import at.saltyy.switchly.data.prefs.ActiveDurationStore
import at.saltyy.switchly.data.prefs.ProfileUsageStore
import at.saltyy.switchly.data.prefs.SurfaceUsageStore
import at.saltyy.switchly.data.prefs.SwitchlyRuntimeStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.prefs.WebUsageStore
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.statistics.StatsBackupCodec
import at.saltyy.switchly.data.statistics.StatsPersistence
import at.saltyy.switchly.feature.usage.StatsArchiveSync
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import java.util.concurrent.Executors
import kotlin.jvm.JvmStatic

/**
 * Runtime implementation for CloudSync.
 * This version keeps ONLY the current (versioned) backup model: Backups are stored in: switchly_users/{uid}/backups/{backupId}
 */
object CloudSyncRuntime {

    private const val TAG = "CloudSyncRuntime"
    private val backupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SwitchlyBackup").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val COLLECTION = "switchly_users"
    private const val SUB_BACKUPS = "backups"
    private const val SUB_STATS_CHUNKS = "stats_chunks"
    private const val ROOT_DOCUMENT_BACKUP_ID = "__root_document__"
    private const val MAX_CLOUD_BACKUPS = 10
    private const val DELETE_BATCH_SIZE = 450

    private const val FIELD_PREFS = "prefs"
    private const val FIELD_SWITCHLY_PREFS = "switchly_prefs"
    private const val FIELD_SCHEDULES_PREFS = "schedules_prefs"
    private const val FIELD_UI_HINTS_PREFS = "ui_hints_prefs"
    private const val FIELD_CREATED_AT = "created_at"
    private const val FIELD_STATS = "stats"
    private const val FIELD_STATS_DATABASE = "stats_database"
    private const val FIELD_STATS_CHUNK_COUNT = "chunk_count"
    private const val FIELD_STATS_CHUNK_INDEX = "index"
    private const val FIELD_STATS_CHUNK_DATA = "data"
    private const val FIELD_BACKUP_SCHEMA_VERSION = "backup_schema_version"
    private const val FIELD_CREATED_WITH_VERSION = "created_with_version"
    private const val FIELD_CREATED_WITH_VERSION_CODE = "created_with_version_code"
    private const val BACKUP_SCHEMA_VERSION = 222

    // ScheduleStore prefs name in the project
    private const val SCHEDULES_PREFS_NAME = "switchly_prefs_schedules"
    private const val SCHEDULES_KEY_ITEMS = "items" // JSON list stored by ScheduleStore
    private const val UI_HINTS_PREFS_NAME = "switchly_ui_hints"

    private val backupExcludedExactKeys = setOf(
        "switch_mode_active_since_ms",
        "switchly_runtime_running_since",
        "stats_archive_last_sync_ms",
    )

    private val backupExcludedKeyMarkers = listOf(
        "access_token",
        "app_lock",
        "auth_token",
        "billing",
        "emergency_pin",
        "entitlement",
        "firebase",
        "id_token",
        "password",
        "pin_hash",
        "pin_salt",
        "premium",
        "purchase",
        "refresh_token",
        "subscription",
        "unlock_pin"
    )

    private fun isBackupExcludedKey(key: String): Boolean {
        val normalized = key.trim().lowercase()
        if (normalized.isBlank()) {
            return true
        }
        if (normalized in backupExcludedExactKeys) {
            return true
        }
        return backupExcludedKeyMarkers.any { marker -> normalized.contains(marker) }
    }

    data class CloudBackupMeta(
        val id: String,
        val createdAt: Long
    )

    data class BackupCompatibility(
        val shouldWarn: Boolean,
        val createdWithVersion: String?,
        val legacyStatistics: Boolean,
    )

    fun inspectBackupCompatibility(payload: Map<*, *>): BackupCompatibility {
        val createdWithVersion = payload[FIELD_CREATED_WITH_VERSION]
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val createdWithVersionCode = when (val value = payload[FIELD_CREATED_WITH_VERSION_CODE]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        val schemaVersion = when (val value = payload[FIELD_BACKUP_SCHEMA_VERSION]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        val includedCategories = BackupCategoryFilter.includedCategoryIdsFromPayload(payload)
        val includesStatistics = includedCategories == null || BackupCategory.STATISTICS.id in includedCategories
        val legacyStatistics = includesStatistics && payload[FIELD_STATS_DATABASE] !is Map<*, *>
        val versionDiffers = when {
            createdWithVersionCode != null -> createdWithVersionCode != BuildConfig.VERSION_CODE.toLong()
            createdWithVersion != null -> createdWithVersion != BuildConfig.VERSION_NAME
            else -> true
        }
        val schemaDiffers = schemaVersion != BACKUP_SCHEMA_VERSION

        return BackupCompatibility(
            shouldWarn = versionDiffers || schemaDiffers || legacyStatistics,
            createdWithVersion = createdWithVersion,
            legacyStatistics = legacyStatistics,
        )
    }

    private fun hasBackupPayload(snapshot: DocumentSnapshot): Boolean {
        return snapshot.exists() && (
            snapshot.contains(FIELD_PREFS) ||
                snapshot.contains(FIELD_SWITCHLY_PREFS) ||
                snapshot.contains(FIELD_SCHEDULES_PREFS) ||
                snapshot.contains(FIELD_UI_HINTS_PREFS) ||
                snapshot.contains(FIELD_STATS) ||
                snapshot.contains(FIELD_STATS_DATABASE)
            )
    }

    // Converts SharedPreferences maps into Firestore-compatible maps: Collections/Sets -> List
    private fun normalizePrefsMap(src: Map<String, *>): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        for ((rawKey, value) in src) {
            if (isBackupExcludedKey(rawKey)) continue

            val v: Any? = when (value) {
                is Set<*> -> value.filterNotNull().toList()
                is Collection<*> -> value.filterNotNull().toList()
                else -> value
            }
            out[rawKey] = v
        }
        return out
    }

    /**
     * Stats keys (usage_day_*, blocked_*, runtime_*, etc.) are stored as many single entries in "switchly_prefs".
     * For cloud backup we compress them into structured lists to keep the remote document smaller and cleaner.
     * Restore expands them back into the original SharedPreferences keys.
     */
    private fun extractStatsFromInternalPrefs(
        src: Map<String, Any?>
    ): Pair<Map<String, Any?>, Map<String, Any?>> {
        val prefsOut = src.toMutableMap()
        val statsOut = mutableMapOf<String, Any?>()

        fun takeRegex(
            listKey: String,
            regex: Regex,
            buildItem: (MatchResult) -> Map<String, Any?>
        ) {
            val items = mutableListOf<Map<String, Any?>>()
            val toRemove = mutableListOf<String>()

            for ((k, v) in prefsOut) {
                val m = regex.matchEntire(k) ?: continue
                val numericValue = when (v) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull()
                    else -> null
                } ?: continue
                items += buildItem(m) + mapOf("v" to numericValue)
                toRemove += k
            }

            if (items.isNotEmpty()) {
                statsOut[listKey] = items
                toRemove.forEach { prefsOut.remove(it) }
            }
        }

        // Per-app per-day
        takeRegex(
            listKey = "usage_day",
            regex = Regex("usage_day_(\\d{8})_(.+)")
        ) { m -> mapOf("d" to m.groupValues[1], "p" to m.groupValues[2]) }

        takeRegex(
            listKey = "blocked_ms",
            regex = Regex("blocked_ms_(\\d{8})_(.+)")
        ) { m -> mapOf("d" to m.groupValues[1], "p" to m.groupValues[2]) }

        takeRegex(
            listKey = "blocked_count",
            regex = Regex("blocked_count_(\\d{8})_(.+)")
        ) { m -> mapOf("d" to m.groupValues[1], "p" to m.groupValues[2]) }

        takeRegex(
            listKey = "blocked_attempt",
            regex = Regex("blocked_attempt_(\\d{8})_(.+)")
        ) { m -> mapOf("d" to m.groupValues[1], "p" to m.groupValues[2]) }

        // Per-day (no pkg)
        takeRegex(
            listKey = "runtime_ms",
            regex = Regex("switchly_runtime_ms_(\\d{8})")
        ) { m -> mapOf("d" to m.groupValues[1]) }

        takeRegex(
            listKey = "emergency_unlock_count",
            regex = Regex("emergency_unlock_count_(\\d{8})")
        ) { m -> mapOf("d" to m.groupValues[1]) }

        takeRegex(
            listKey = "nfc_scan_count",
            regex = Regex("nfc_scan_count_(\\d{8})")
        ) { m -> mapOf("d" to m.groupValues[1]) }

        takeRegex(
            listKey = "schedule_exec_count",
            regex = Regex("schedule_exec_count_(\\d{8})")
        ) { m -> mapOf("d" to m.groupValues[1]) }

        return prefsOut to statsOut
    }

    private fun applyStatsToInternalPrefs(ctx: Context, stats: Any?): Int {
        val map = stats as? Map<*, *> ?: return 0
        var restoredValues = 0

        val restoredActivityDays = (map["activity_history_days"] as? Map<*, *>)
            ?.mapNotNull { (day, encoded) ->
                val dayKey = day as? String ?: return@mapNotNull null
                val value = encoded as? String ?: return@mapNotNull null
                dayKey to value
            }
            ?.toMap()
        when {
            restoredActivityDays != null -> ActivityHistoryLogStore.replaceDays(ctx, restoredActivityDays)
            map.containsKey("activity_history_logs") -> {
                val restoredActivityLogs = (map["activity_history_logs"] as? List<*>)
                    ?.filterIsInstance<String>()
                    .orEmpty()
                ActivityHistoryLogStore.replaceLines(ctx, restoredActivityLogs)
            }
        }

        fun stringValue(value: Any?): String? = when (value) {
            is String -> value.trim().takeIf(String::isNotEmpty)
            is Number -> value.toLong().toString()
            else -> null
        }

        fun dayValue(value: Any?): String? {
            val day = stringValue(value) ?: return null
            return day.takeIf { candidate -> candidate.length == 8 && candidate.all(Char::isDigit) }
        }

        fun longValue(value: Any?): Long? = when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }

        fun booleanValue(value: Any?): Boolean? = when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }

        fun buildKeyWithPkg(prefix: String, item: Map<*, *>): String? {
            val day = dayValue(item["d"]) ?: return null
            val packageName = stringValue(item["p"]) ?: return null
            return "$prefix${day}_$packageName"
        }

        fun buildKeyNoPkg(prefix: String, item: Map<*, *>): String? {
            val day = dayValue(item["d"]) ?: return null
            return prefix + day
        }

        val internalPrefs = ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)
        internalPrefs.edit(commit = true) {
            fun applyListLong(listKey: String, keyBuilder: (Map<*, *>) -> String?) {
                val items = map[listKey] as? Collection<*> ?: return
                for (rawItem in items) {
                    val item = rawItem as? Map<*, *> ?: continue
                    val value = longValue(item["v"]) ?: continue
                    val key = keyBuilder(item) ?: continue
                    putLong(key, value)
                    restoredValues++
                }
            }

            fun applyListInt(listKey: String, keyBuilder: (Map<*, *>) -> String?) {
                val items = map[listKey] as? Collection<*> ?: return
                for (rawItem in items) {
                    val item = rawItem as? Map<*, *> ?: continue
                    val value = longValue(item["v"])
                        ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        ?.toInt()
                        ?: continue
                    val key = keyBuilder(item) ?: continue
                    putInt(key, value)
                    restoredValues++
                }
            }

            fun applyListBoolean(listKey: String, keyBuilder: (Map<*, *>) -> String?) {
                val items = map[listKey] as? Collection<*> ?: return
                for (rawItem in items) {
                    val item = rawItem as? Map<*, *> ?: continue
                    val value = booleanValue(item["v"]) ?: continue
                    val key = keyBuilder(item) ?: continue
                    putBoolean(key, value)
                    restoredValues++
                }
            }

            applyListLong("usage_day") { item -> buildKeyWithPkg("usage_day_", item) }
            applyListLong("blocked_ms") { item -> buildKeyWithPkg("blocked_ms_", item) }
            applyListLong("blocked_count") { item -> buildKeyWithPkg("blocked_count_", item) }
            applyListLong("blocked_attempt") { item -> buildKeyWithPkg("blocked_attempt_", item) }
            applyListLong("app_launch_count") { item -> buildKeyWithPkg("app_launch_count_", item) }

            applyListInt("open_count") { item ->
                val day = dayValue(item["d"]) ?: return@applyListInt null
                val packageName = stringValue(item["p"]) ?: return@applyListInt null
                val profile = stringValue(item["pr"])
                if (profile == null) {
                    "open_count_${day}_$packageName"
                } else {
                    "open_count_${day}__${profile}__$packageName"
                }
            }

            applyListLong("runtime_ms") { item -> buildKeyNoPkg("switchly_runtime_ms_", item) }
            applyListLong("emergency_unlock_count") { item ->
                buildKeyNoPkg("emergency_unlock_count_", item)
            }
            applyListLong("nfc_scan_count") { item -> buildKeyNoPkg("nfc_scan_count_", item) }
            applyListLong("qr_scan_count") { item -> buildKeyNoPkg("qr_scan_count_", item) }
            applyListLong("barcode_scan_count") { item ->
                buildKeyNoPkg("barcode_scan_count_", item)
            }
            applyListLong("temp_enable_count") { item -> buildKeyNoPkg("temp_enable_count_", item) }
            applyListLong("schedule_exec_count") { item ->
                buildKeyNoPkg("schedule_exec_count_", item)
            }

            applyListLong("switch_action_count") { item ->
                val action = stringValue(item["a"] ?: item["action"]) ?: return@applyListLong null
                val day = dayValue(item["d"]) ?: return@applyListLong null
                "switch_action_count_${action}_$day"
            }

            applyListBoolean("usage_limit_ever") { item ->
                val packageName = stringValue(item["p"]) ?: return@applyListBoolean null
                "usage_limit_ever__$packageName"
            }
            applyListInt("usage_limit_min") { item ->
                val profile = stringValue(item["pr"]) ?: return@applyListInt null
                val packageName = stringValue(item["p"]) ?: return@applyListInt null
                "usage_limit_min__${profile}__$packageName"
            }
        }

        fun restoreRawStatistics(raw: Map<*, *>) {
            val internalWrites = linkedMapOf<String, Any?>()
            val defaultWrites = linkedMapOf<String, Any?>()
            val uiHintsWrites = linkedMapOf<String, Any?>()

            raw.forEach { (rawKey, value) ->
                val key = rawKey as? String ?: return@forEach
                when {
                    StatsPersistence.isArchivedInternalKey(key) -> internalWrites[key] = value
                    StatsPersistence.isArchivedDefaultKey(key) -> defaultWrites[key] = value
                    StatsPersistence.isArchivedUiHintsKey(key) -> uiHintsWrites[key] = value
                }
            }

            fun writeValues(preferences: SharedPreferences, values: Map<String, Any?>) {
                if (values.isEmpty()) {
                    return
                }
                preferences.edit(commit = true) {
                    values.forEach { (key, value) ->
                        putSupportedPreferenceValue(this, key, value)
                        restoredValues++
                    }
                }
            }

            writeValues(internalPrefs, internalWrites)
            writeValues(PreferenceManager.getDefaultSharedPreferences(ctx), defaultWrites)
            writeValues(ctx.getSharedPreferences(UI_HINTS_PREFS_NAME, Context.MODE_PRIVATE), uiHintsWrites)
        }

        // Some pre-database backups kept raw statistic keys instead of compact lists.
        restoreRawStatistics(map)
        (map["values"] as? Map<*, *>)?.let(::restoreRawStatistics)

        return restoredValues
    }

    fun createLocalBackupPayload(ctx: Context): Map<String, Any?> =
        createLocalBackupPayload(ctx, BackupSelection.full())

    fun createLocalBackupPayload(ctx: Context, selection: BackupSelection): Map<String, Any?> {
        val now = System.currentTimeMillis()

        if (selection.includes(BackupCategory.STATISTICS)) {
            runCatching { StatsArchiveSync.sync(ctx, force = true) }
        }

        // Persist all buffered/live statistic deltas before reading SharedPreferences.
        UsageStore.flush(ctx)
        ProfileUsageStore.flush(ctx)
        SurfaceUsageStore.flush(ctx)
        WebUsageStore.flush(ctx)
        ActiveDurationStore.checkpointForBackup(ctx)
        SwitchlyRuntimeStore.checkpointForBackup(ctx)
        val statsDatabase = if (selection.includes(BackupCategory.STATISTICS)) {
            StatsBackupCodec.encode(StatsPersistence.snapshotForBackup(ctx))
        } else {
            null
        }

        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(ctx).all
        val internalPrefs = ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE).all
        val schedulesPrefs = ctx.getSharedPreferences(SCHEDULES_PREFS_NAME, Context.MODE_PRIVATE).all
        val uiHintsPrefs = ctx.getSharedPreferences(UI_HINTS_PREFS_NAME, Context.MODE_PRIVATE).all

        val all = BackupCategoryFilter.filterDefaultPrefs(normalizePrefsMap(defaultPrefs), selection)
        val internalAllRaw = BackupCategoryFilter.filterInternalPrefs(normalizePrefsMap(internalPrefs), selection)
        val schedulesAll = BackupCategoryFilter.filterSchedulesPrefs(normalizePrefsMap(schedulesPrefs), selection)
        val uiHintsAll = BackupCategoryFilter.filterUiHintsPrefs(normalizePrefsMap(uiHintsPrefs), selection)

        val (internalAll, statsMapRaw) = extractStatsFromInternalPrefs(internalAllRaw)
        val statsMapWithLogs = statsMapRaw.toMutableMap()
        ActivityHistoryLogStore.ensureMigrated(ctx, AppLogStore.latestLines(ctx, 1000))
        statsMapWithLogs["activity_history_days"] = ActivityHistoryLogStore.exportDays(ctx)
        val statsMap = BackupCategoryFilter.filterStats(statsMapWithLogs, selection)

        return mapOf(
            FIELD_BACKUP_SCHEMA_VERSION to BACKUP_SCHEMA_VERSION,
            FIELD_CREATED_WITH_VERSION to BuildConfig.VERSION_NAME,
            FIELD_CREATED_WITH_VERSION_CODE to BuildConfig.VERSION_CODE,
            FIELD_PREFS to all,
            FIELD_SWITCHLY_PREFS to internalAll,
            FIELD_STATS to statsMap,
            FIELD_STATS_DATABASE to statsDatabase,
            FIELD_SCHEDULES_PREFS to schedulesAll,
            FIELD_UI_HINTS_PREFS to uiHintsAll,
            BackupCategoryFilter.FIELD_INCLUDED_CATEGORIES to selection.categoryIds.toList().sorted(),
            BackupCategoryFilter.FIELD_IS_PARTIAL_BACKUP to !selection.isFull,
            FIELD_CREATED_AT to now
        )
    }

    @JvmStatic
    fun pushLocalState(ctx: Context, onDone: (Boolean, String?) -> Unit) {
        pushLocalState(ctx, BackupSelection.full(), onDone)
    }

    fun pushLocalState(ctx: Context, selection: BackupSelection, onDone: (Boolean, String?) -> Unit) {
        val uid = Auth.uid()
        if (uid == null) {
            onDone(false, ctx.getString(R.string.cloud_error_not_logged_in))
            return
        }

        backupExecutor.execute {
            try {
                val data = createLocalBackupPayload(ctx, selection)
                val prepared = prepareCloudPayload(data)
                val db = FirebaseFirestore.getInstance()
                val userRef = db.collection(COLLECTION).document(uid)

                userRef.collection(SUB_BACKUPS)
                    .add(prepared.rootPayload)
                    .addOnSuccessListener { created ->
                        uploadStatsChunks(ctx, created, prepared.statsChunks) { chunkOk, chunkError ->
                            if (!chunkOk) {
                                created.delete()
                                val error = chunkError ?: "Statistics backup chunks could not be uploaded"
                                AppLogStore.append(ctx, TAG, error)
                                deliverToMain { onDone(false, error) }
                                return@uploadStatsChunks
                            }
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "pushLocalState: backup version created: ${created.id}")
                            }
                            AppLogStore.append(ctx, TAG, "Cloud backup created: ${created.id}")
                            pruneOldBackups(ctx, userRef) { cleanupError ->
                                if (cleanupError != null) {
                                    Log.w(TAG, "Cloud backup retention cleanup failed", cleanupError)
                                    AppLogStore.append(
                                        ctx,
                                        TAG,
                                        "Cloud backup created, but retention cleanup failed",
                                        cleanupError,
                                    )
                                }
                                deliverToMain { onDone(true, cleanupError?.localizedMessage) }
                            }
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "pushLocalState: backup version failed", error)
                        AppLogStore.append(ctx, TAG, "Cloud backup failed", error)
                        deliverToMain { onDone(false, error.localizedMessage) }
                    }
            } catch (error: Exception) {
                Log.e(TAG, "pushLocalState crashed", error)
                AppLogStore.append(ctx, TAG, "Cloud backup crashed", error)
                deliverToMain { onDone(false, error.localizedMessage) }
            }
        }
    }

    fun applyBackupPayloadAsync(
        ctx: Context,
        payload: Map<*, *>,
        onDone: (Result<Unit>) -> Unit,
    ) {
        backupExecutor.execute {
            val result = runCatching { applyBackupPayload(ctx, payload) }
            deliverToMain { onDone(result) }
        }
    }

    private fun deliverToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private data class PreparedCloudPayload(
        val rootPayload: Map<String, Any?>,
        val statsChunks: List<String>,
    )

    private fun prepareCloudPayload(payload: Map<String, Any?>): PreparedCloudPayload {
        val root = payload.toMutableMap()
        val statsDatabase = payload[FIELD_STATS_DATABASE] as? Map<*, *>
            ?: return PreparedCloudPayload(root, emptyList())
        val chunks = (statsDatabase[StatsBackupCodec.FIELD_CHUNKS] as? List<*>)
            ?.filterIsInstance<String>()
            .orEmpty()
        if (chunks.isEmpty()) {
            return PreparedCloudPayload(root, emptyList())
        }
        val manifest = statsDatabase.entries.associate { entry -> entry.key.toString() to entry.value }.toMutableMap()
        manifest.remove(StatsBackupCodec.FIELD_CHUNKS)
        manifest[FIELD_STATS_CHUNK_COUNT] = chunks.size
        root[FIELD_STATS_DATABASE] = manifest

        // Room chunks hold the complete statistics archive. Keep the root Firestore document small
        // instead of duplicating years of counters and sessions in the legacy preference payload.
        root.remove(FIELD_STATS)
        root[FIELD_SWITCHLY_PREFS] = (root[FIELD_SWITCHLY_PREFS] as? Map<*, *>)
            ?.entries
            ?.filterNot { entry -> StatsPersistence.isArchivedInternalKey(entry.key.toString()) }
            ?.associate { entry -> entry.key.toString() to entry.value }
            .orEmpty()
        root[FIELD_PREFS] = (root[FIELD_PREFS] as? Map<*, *>)
            ?.entries
            ?.filterNot { entry -> StatsPersistence.isArchivedDefaultKey(entry.key.toString()) }
            ?.associate { entry -> entry.key.toString() to entry.value }
            .orEmpty()
        root[FIELD_UI_HINTS_PREFS] = (root[FIELD_UI_HINTS_PREFS] as? Map<*, *>)
            ?.entries
            ?.filterNot { entry -> StatsPersistence.isArchivedUiHintsKey(entry.key.toString()) }
            ?.associate { entry -> entry.key.toString() to entry.value }
            .orEmpty()
        return PreparedCloudPayload(root, chunks)
    }

    private fun uploadStatsChunks(
        ctx: Context,
        backupRef: DocumentReference,
        chunks: List<String>,
        onDone: (Boolean, String?) -> Unit,
    ) {
        if (chunks.isEmpty()) {
            onDone(true, null)
            return
        }
        val firestore = FirebaseFirestore.getInstance()
        val batch = firestore.batch()
        chunks.forEachIndexed { index, chunk ->
            val chunkRef = backupRef.collection(SUB_STATS_CHUNKS).document(index.toString().padStart(6, '0'))
            batch.set(chunkRef, mapOf(FIELD_STATS_CHUNK_INDEX to index, FIELD_STATS_CHUNK_DATA to chunk))
        }
        batch.commit()
            .addOnSuccessListener { onDone(true, null) }
            .addOnFailureListener { error ->
                val message = if (
                    error is FirebaseFirestoreException &&
                    error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                ) {
                    ctx.getString(R.string.cloud_error_stats_chunks_permission)
                } else {
                    error.localizedMessage
                }
                onDone(false, message)
            }
    }

    private fun pruneOldBackups(
        ctx: Context,
        userRef: DocumentReference,
        onDone: (Throwable?) -> Unit,
    ) {
        userRef.collection(SUB_BACKUPS)
            .get()
            .addOnSuccessListener { snapshot ->
                val staleBackups = snapshot.documents
                    .sortedByDescending { document -> document.getLong(FIELD_CREATED_AT) ?: 0L }
                    .drop(MAX_CLOUD_BACKUPS)
                    .map { document -> document.reference }

                deleteBackupReferencesSequentially(ctx, staleBackups, onDone)
            }
            .addOnFailureListener { error -> onDone(error) }
    }

    private fun deleteBackupReferencesSequentially(
        ctx: Context,
        references: List<DocumentReference>,
        onDone: (Throwable?) -> Unit,
    ) {
        val current = references.firstOrNull()
        if (current == null) {
            onDone(null)
            return
        }

        deleteBackupReference(ctx, current) { error ->
            if (error != null) {
                onDone(error)
            } else {
                deleteBackupReferencesSequentially(ctx, references.drop(1), onDone)
            }
        }
    }

    private fun deleteBackupReference(
        ctx: Context,
        backupRef: DocumentReference,
        onDone: (Throwable?) -> Unit,
    ) {
        backupRef.collection(SUB_STATS_CHUNKS)
            .get()
            .addOnSuccessListener { chunks ->
                val references = chunks.documents.map { document -> document.reference } + backupRef
                deleteReferencesInBatches(ctx, references, onDone)
            }
            .addOnFailureListener { error -> onDone(error) }
    }

    private fun deleteReferencesInBatches(
        ctx: Context,
        references: List<DocumentReference>,
        onDone: (Throwable?) -> Unit,
    ) {
        val currentBatch = references.take(DELETE_BATCH_SIZE)
        if (currentBatch.isEmpty()) {
            onDone(null)
            return
        }

        val firestore = FirebaseFirestore.getInstance()
        val batch = firestore.batch()
        currentBatch.forEach { reference -> batch.delete(reference) }
        batch.commit()
            .addOnSuccessListener {
                deleteReferencesInBatches(ctx, references.drop(currentBatch.size), onDone)
            }
            .addOnFailureListener { error ->
                AppLogStore.append(ctx, TAG, "Deleting cloud backup documents failed", error)
                onDone(error)
            }
    }

    // Retrieves the last N backups from the "backups" subcollection.
    fun listBackups(
        ctx: Context,
        limit: Long = MAX_CLOUD_BACKUPS.toLong(),
        onDone: (Boolean, String?, List<CloudBackupMeta>?) -> Unit
    ) {
        val uid = Auth.uid()
        if (uid == null) {
            onDone(false, ctx.getString(R.string.cloud_error_not_logged_in), null)
            return
        }

        val db = FirebaseFirestore.getInstance()
        val userRef = db.collection(COLLECTION).document(uid)

        userRef.collection(SUB_BACKUPS)
            .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snapshot ->
                val versioned = snapshot.documents.map { doc ->
                    val ts = doc.getLong(FIELD_CREATED_AT) ?: 0L
                    CloudBackupMeta(doc.id, ts)
                }

                userRef
                    .get()
                    .addOnSuccessListener { userSnapshot ->
                        val combined = versioned.toMutableList()
                        if (hasBackupPayload(userSnapshot)) {
                            val ts = userSnapshot.getLong(FIELD_CREATED_AT) ?: 0L
                            combined += CloudBackupMeta(ROOT_DOCUMENT_BACKUP_ID, ts)
                        }
                        onDone(true, null, combined.sortedByDescending { it.createdAt }.take(limit.toInt()))
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "listBackups: root document backup check failed", e)
                        AppLogStore.append(ctx, TAG, "Cloud backup root document check failed", e)
                        onDone(true, null, versioned)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "listBackups failed", e)
                AppLogStore.append(ctx, TAG, "Listing cloud backups failed", e)
                onDone(false, e.localizedMessage, null)
            }
    }

    // Restores the most recent versioned backup.
    @JvmStatic
    fun pullRemoteState(ctx: Context, onDone: (Boolean, String?) -> Unit) {
        listBackups(ctx, limit = 1) { ok, msg, list ->
            if (!ok) {
                onDone(false, msg)
                return@listBackups
            }
            val id = list?.firstOrNull()?.id
            if (id.isNullOrBlank()) {
                onDone(false, ctx.getString(R.string.cloud_error_no_backup_found))
                return@listBackups
            }
            pullBackup(ctx, id, onDone)
        }
    }

    fun pullBackup(ctx: Context, backupId: String, onDone: (Boolean, String?) -> Unit) {
        val uid = Auth.uid()
        if (uid == null) {
            onDone(false, ctx.getString(R.string.cloud_error_not_logged_in))
            return
        }

        val userRef = FirebaseFirestore.getInstance().collection(COLLECTION).document(uid)
        val snapshotTask = if (backupId == ROOT_DOCUMENT_BACKUP_ID) {
            userRef.get()
        } else {
            userRef.collection(SUB_BACKUPS)
                .document(backupId)
                .get()
        }

        snapshotTask
            .addOnSuccessListener { snapshot ->
                if (!hasBackupPayload(snapshot)) {
                    onDone(false, ctx.getString(R.string.cloud_error_backup_not_found))
                    return@addOnSuccessListener
                }
                loadPayloadWithStatsChunks(snapshot.reference, payloadFromSnapshot(snapshot)) { payloadResult ->
                    payloadResult
                        .onSuccess { payload ->
                            applyBackupPayloadAsync(ctx, payload) { result ->
                                result
                                    .onSuccess { onDone(true, null) }
                                    .onFailure { error ->
                                        Log.e(TAG, "pullBackup failed", error)
                                        AppLogStore.appendRateLimited(ctx, TAG, "Cloud restore failed", error)
                                        onDone(false, error.localizedMessage)
                                    }
                            }
                        }
                        .onFailure { error ->
                            AppLogStore.appendRateLimited(ctx, TAG, "Loading statistics backup chunks failed", error)
                            onDone(false, error.localizedMessage)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "pullBackup failed", e)
                AppLogStore.appendRateLimited(ctx, TAG, "Cloud restore failed", e)
                onDone(false, e.localizedMessage)
            }
    }

    fun applyBackupPayload(ctx: Context, payload: Map<*, *>) {
        if (!hasBackupPayload(payload)) {
            throw IllegalArgumentException(ctx.getString(R.string.cloud_error_backup_not_found))
        }

        val prefsMap = payload[FIELD_PREFS] as? Map<*, *> ?: emptyMap<Any, Any>()
        val internalMap = payload[FIELD_SWITCHLY_PREFS] as? Map<*, *> ?: emptyMap<Any, Any>()
        val schedulesMap = payload[FIELD_SCHEDULES_PREFS] as? Map<*, *> ?: emptyMap<Any, Any>()
        val uiHintsMap = payload[FIELD_UI_HINTS_PREFS] as? Map<*, *> ?: emptyMap<Any, Any>()
        val stats = payload[FIELD_STATS]
        val statsDatabase = payload[FIELD_STATS_DATABASE] as? Map<*, *>
        val partialBackup = BackupCategoryFilter.isPartialBackup(payload)

        val legacyStatisticsBackup = statsDatabase == null && stats is Map<*, *>
        var restoredCompactValues = 0

        StatsPersistence.beginRestore(ctx)
        try {
            applyPrefsMapToLocal(ctx, prefsMap, isInternal = false, isSchedules = false, clearBeforeApply = !partialBackup)
            applyPrefsMapToLocal(ctx, internalMap, isInternal = true, isSchedules = false, clearBeforeApply = !partialBackup)
            applyPrefsMapToLocal(ctx, schedulesMap, isInternal = false, isSchedules = true, clearBeforeApply = !partialBackup)
            applyPrefsMapToLocal(ctx, uiHintsMap, prefsName = UI_HINTS_PREFS_NAME, clearBeforeApply = !partialBackup)

            // Expand compact statistics from 2.1.x/2.2.x backups before Room is restored.
            restoredCompactValues = applyStatsToInternalPrefs(ctx, stats)
            normalizeLegacyStatisticsPreferences(ctx)

            // Safety: restored schedules should not immediately fire
            if (schedulesMap.isNotEmpty()) {
                forceDisableAllSchedules(ctx)
            }

            // Safety: after restore, keep Switchly base state OFF so users don't get locked out
            forceDisableSwitchlyAfterRestore(ctx)
        } finally {
            StatsPersistence.finishRestore(
                context = ctx,
                databasePayload = statsDatabase,
                replaceDatabase = !partialBackup,
            )

            // Re-apply compact values after the database phase so an older or incomplete database archive cannot overwrite counters restored from legacy backups.
            if (restoredCompactValues > 0) {
                restoredCompactValues = applyStatsToInternalPrefs(ctx, stats)
            }
            normalizeLegacyStatisticsPreferences(ctx)
            StatsPersistence.flushBlocking(ctx)

            if (legacyStatisticsBackup && restoredCompactValues > 0) {
                AppLogStore.append(
                    ctx,
                    TAG,
                    "Legacy statistics migration restored $restoredCompactValues preference values",
                )
            }
        }
    }

    fun loadBackupPayload(
        ctx: Context,
        backupId: String,
        onDone: (Boolean, String?, Map<*, *>?) -> Unit
    ) {
        val uid = Auth.uid()
        if (uid == null) {
            onDone(false, ctx.getString(R.string.cloud_error_not_logged_in), null)
            return
        }

        val userRef = FirebaseFirestore.getInstance().collection(COLLECTION).document(uid)
        val snapshotTask = if (backupId == ROOT_DOCUMENT_BACKUP_ID) {
            userRef.get()
        } else {
            userRef.collection(SUB_BACKUPS)
                .document(backupId)
                .get()
        }

        snapshotTask
            .addOnSuccessListener { snapshot ->
                if (!hasBackupPayload(snapshot)) {
                    onDone(false, ctx.getString(R.string.cloud_error_backup_not_found), null)
                    return@addOnSuccessListener
                }

                loadPayloadWithStatsChunks(snapshot.reference, payloadFromSnapshot(snapshot)) { payloadResult ->
                    payloadResult
                        .onSuccess { payload -> onDone(true, null, payload) }
                        .onFailure { error ->
                            AppLogStore.appendRateLimited(ctx, TAG, "Loading cloud backup preview failed", error)
                            onDone(false, error.localizedMessage, null)
                        }
                }
            }
            .addOnFailureListener { e ->
                AppLogStore.appendRateLimited(ctx, TAG, "Loading cloud backup preview failed", e)
                onDone(false, e.localizedMessage, null)
            }
    }

    private fun hasBackupPayload(payload: Map<*, *>): Boolean {
        return payload.containsKey(FIELD_PREFS) ||
            payload.containsKey(FIELD_SWITCHLY_PREFS) ||
            payload.containsKey(FIELD_SCHEDULES_PREFS) ||
            payload.containsKey(FIELD_UI_HINTS_PREFS) ||
            payload.containsKey(FIELD_STATS) ||
            payload.containsKey(FIELD_STATS_DATABASE)
    }

    private fun payloadFromSnapshot(snapshot: DocumentSnapshot): Map<String, Any?> {
        return mapOf(
            FIELD_BACKUP_SCHEMA_VERSION to snapshot.get(FIELD_BACKUP_SCHEMA_VERSION),
            FIELD_CREATED_WITH_VERSION to snapshot.get(FIELD_CREATED_WITH_VERSION),
            FIELD_CREATED_WITH_VERSION_CODE to snapshot.get(FIELD_CREATED_WITH_VERSION_CODE),
            FIELD_PREFS to snapshot.get(FIELD_PREFS),
            FIELD_SWITCHLY_PREFS to snapshot.get(FIELD_SWITCHLY_PREFS),
            FIELD_SCHEDULES_PREFS to snapshot.get(FIELD_SCHEDULES_PREFS),
            FIELD_UI_HINTS_PREFS to snapshot.get(FIELD_UI_HINTS_PREFS),
            FIELD_STATS to snapshot.get(FIELD_STATS),
            FIELD_STATS_DATABASE to snapshot.get(FIELD_STATS_DATABASE),
            BackupCategoryFilter.FIELD_INCLUDED_CATEGORIES to snapshot.get(BackupCategoryFilter.FIELD_INCLUDED_CATEGORIES),
            BackupCategoryFilter.FIELD_IS_PARTIAL_BACKUP to snapshot.get(BackupCategoryFilter.FIELD_IS_PARTIAL_BACKUP)
        )
    }

    private fun loadPayloadWithStatsChunks(
        backupRef: DocumentReference,
        payload: Map<String, Any?>,
        onDone: (Result<Map<String, Any?>>) -> Unit,
    ) {
        val statsDatabase = payload[FIELD_STATS_DATABASE] as? Map<*, *>
        if (statsDatabase == null || statsDatabase.containsKey(StatsBackupCodec.FIELD_CHUNKS)) {
            onDone(Result.success(payload))
            return
        }
        val chunkCount = (statsDatabase[FIELD_STATS_CHUNK_COUNT] as? Number)?.toInt() ?: 0
        if (chunkCount <= 0) {
            onDone(Result.success(payload))
            return
        }
        backupRef.collection(SUB_STATS_CHUNKS)
            .orderBy(FIELD_STATS_CHUNK_INDEX, Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                val chunks = snapshot.documents.mapNotNull { document ->
                    document.getString(FIELD_STATS_CHUNK_DATA)
                }
                if (chunks.size != chunkCount) {
                    onDone(Result.failure(IllegalStateException("Statistics backup is incomplete")))
                    return@addOnSuccessListener
                }
                val restoredStats = statsDatabase.entries
                    .associate { entry -> entry.key.toString() to entry.value }
                    .toMutableMap()
                restoredStats.remove(FIELD_STATS_CHUNK_COUNT)
                restoredStats[StatsBackupCodec.FIELD_CHUNKS] = chunks
                val restoredPayload = payload.toMutableMap()
                restoredPayload[FIELD_STATS_DATABASE] = restoredStats
                onDone(Result.success(restoredPayload))
            }
            .addOnFailureListener { error -> onDone(Result.failure(error)) }
    }

    private fun shouldStoreAsInt(key: String): Boolean {
        return key == "onboarding_version" ||
            key == "primary_toggle_tap_count" ||
            key.startsWith("usage_limit_min__") ||
            key.startsWith("session_limit_min__") ||
            key.startsWith("attempt_limit__") ||
            key.startsWith("inapp_limit_min__") ||
            key.startsWith("surf_rule__") ||
            key.startsWith("domain_limit_min_") ||
            key.startsWith("scan_code_daily_limit_") ||
            key.startsWith("scan_code_cooldown_") ||
            key.startsWith("scan_code_count_") ||
            key.startsWith("qr_temp_count_") ||
            key.startsWith("nfc_td_count_") ||
            key.startsWith("nfc_td_cfg_daily_") ||
            key.startsWith("nfc_td_cfg_cooldown_") ||
            key.startsWith("nfc_tag_read_only_duration_") ||
            key.startsWith("open_count_")
    }

    private fun isArchivedStatisticsKey(key: String): Boolean {
        return StatsPersistence.isArchivedInternalKey(key) ||
            StatsPersistence.isArchivedDefaultKey(key) ||
            StatsPersistence.isArchivedUiHintsKey(key)
    }

    private fun putSupportedPreferenceValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?,
    ) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Number -> {
                val numericValue = value.toLong()
                when {
                    shouldStoreAsInt(key) && numericValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> {
                        editor.putInt(key, numericValue.toInt())
                    }
                    isArchivedStatisticsKey(key) -> editor.putLong(key, numericValue)
                    value is Float -> editor.putFloat(key, value)
                    value is Double && value % 1.0 != 0.0 -> editor.putFloat(key, value.toFloat())
                    value is Int -> editor.putInt(key, value)
                    else -> editor.putLong(key, numericValue)
                }
            }
            is String -> {
                val numericValue = value.toLongOrNull()
                when {
                    numericValue != null && shouldStoreAsInt(key) &&
                        numericValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> {
                        editor.putInt(key, numericValue.toInt())
                    }
                    numericValue != null && isArchivedStatisticsKey(key) -> {
                        editor.putLong(key, numericValue)
                    }
                    else -> editor.putString(key, value)
                }
            }
            is Collection<*> -> {
                if (value.all { item -> item is String }) {
                    editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }

    // Older backups could store statistic keys in the wrong SharedPreferences file or with JSON Int values.
    // Put every archived key back into its canonical file and normalize numeric types before Room mirrors it.
    private fun normalizeLegacyStatisticsPreferences(ctx: Context) {
        val internalPrefs = ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val uiHintsPrefs = ctx.getSharedPreferences(UI_HINTS_PREFS_NAME, Context.MODE_PRIVATE)
        val sources = listOf(internalPrefs, defaultPrefs, uiHintsPrefs)
        val snapshots = sources.associateWith { prefs -> prefs.all }
        val writes = sources.associateWith { linkedMapOf<String, Any?>() }.toMutableMap()
        val removals = sources.associateWith { linkedSetOf<String>() }.toMutableMap()

        val keys = snapshots.values.flatMap { values -> values.keys }.toSet()
        for (key in keys) {
            val target = when {
                StatsPersistence.isArchivedInternalKey(key) -> internalPrefs
                StatsPersistence.isArchivedDefaultKey(key) -> defaultPrefs
                StatsPersistence.isArchivedUiHintsKey(key) -> uiHintsPrefs
                else -> null
            } ?: continue

            val value = snapshots[target]?.get(key)
                ?: sources.firstNotNullOfOrNull { source -> snapshots[source]?.get(key) }
                ?: continue
            writes.getValue(target)[key] = value
            sources.filter { source -> source !== target }.forEach { source ->
                if (snapshots[source]?.containsKey(key) == true) {
                    removals.getValue(source).add(key)
                }
            }
        }

        sources.forEach { prefs ->
            val values = writes.getValue(prefs)
            val keysToRemove = removals.getValue(prefs)
            if (values.isEmpty() && keysToRemove.isEmpty()) {
                return@forEach
            }
            prefs.edit(commit = true) {
                keysToRemove.forEach(::remove)
                values.forEach { (key, value) ->
                    putSupportedPreferenceValue(this, key, value)
                }
            }
        }
    }

    // Applies a Firestore-loaded map to local SharedPreferences.
    private fun applyPrefsMapToLocal(
        ctx: Context,
        map: Map<*, *>,
        isInternal: Boolean = false,
        isSchedules: Boolean = false,
        prefsName: String? = null,
        clearBeforeApply: Boolean = true
    ) {
        val prefs = when {
            prefsName != null -> ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            isSchedules -> ctx.getSharedPreferences(SCHEDULES_PREFS_NAME, Context.MODE_PRIVATE)
            isInternal -> ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)
            else -> PreferenceManager.getDefaultSharedPreferences(ctx)
        }
        val preservedEntitlementPrefs = prefs.all.filterKeys { key -> isBackupExcludedKey(key) }

        prefs.edit(commit = true) {
            if (clearBeforeApply) clear()

            for ((rawKey, value) in map) {
                val key = rawKey as? String ?: continue
                if (isBackupExcludedKey(key)) continue
                putSupportedPreferenceValue(this, key, value)
            }

            for ((key, value) in preservedEntitlementPrefs) {
                putSupportedPreferenceValue(this, key, value)
            }
        }
    }

    // After restoring schedules, force-disable them for safety.
    private fun forceDisableAllSchedules(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(SCHEDULES_PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(SCHEDULES_KEY_ITEMS, null) ?: return

            // Best-effort JSON patch: enabled:true -> enabled:false
            val patched = raw
                .replace("\"enabled\":true", "\"enabled\":false")
                .replace("\"enabled\" : true", "\"enabled\":false")
                .replace("\"enabled\"  :  true", "\"enabled\":false")
                .replace("\"enabled\": true", "\"enabled\":false")
                .replace("\"enabled\" :true", "\"enabled\":false")

            if (patched != raw) {
                sp.edit { putString(SCHEDULES_KEY_ITEMS, patched) }
                if (BuildConfig.DEBUG) Log.d(TAG, "forceDisableAllSchedules: patched schedules enabled->false")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "forceDisableAllSchedules failed: ${t.message}")
        }
    }

    private fun forceDisableSwitchlyAfterRestore(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)
            sp.edit(commit = true) {
                putBoolean("switch_mode_enabled", false)
                putLong("switch_mode_temp_disable_until", 0L)
                putLong("switch_mode_temp_enable_until", 0L)
                remove("switch_mode_base_before_temp_enable")
            }

            // Keep runtime flow/state in sync with prefs and bypass NFC lock for this forced safety off.
            runCatching { SwitchModeStore.setEnabled(ctx, false, allowNfcBypass = true) }
                .onFailure { Log.w(TAG, "forceDisableSwitchlyAfterRestore runtime sync failed", it) }
        } catch (t: Throwable) {
            Log.w(TAG, "forceDisableSwitchlyAfterRestore failed: ${t.message}")
        }
    }

    fun deleteBackup(ctx: Context, backupId: String, cb: (ok: Boolean, err: String?) -> Unit) {
        val uid = Auth.uid()
        if (uid.isNullOrBlank()) {
            cb(false, ctx.getString(R.string.cloud_not_logged_in))
            return
        }

        val backupRef = FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(uid)
            .collection(SUB_BACKUPS)
            .document(backupId)

        deleteBackupReference(ctx, backupRef) { error ->
            if (error == null) {
                cb(true, null)
            } else {
                Log.w(TAG, "deleteBackup failed", error)
                AppLogStore.append(ctx, TAG, "Deleting cloud backup failed", error)
                cb(false, error.message)
            }
        }
    }
}
