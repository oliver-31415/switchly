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
                val num = v as? Number ?: continue
                items += buildItem(m) + mapOf("v" to num.toLong())
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

    private fun applyStatsToInternalPrefs(ctx: Context, stats: Any?) {
        val map = stats as? Map<*, *> ?: return
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

        val sp = ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)

        fun buildKeyWithPkg(prefix: String, item: Map<*, *>): String? {
            val d = item["d"] as? String ?: return null
            val p = item["p"] as? String ?: return null
            return "${prefix}${d}_${p}"
        }

        fun buildKeyNoPkg(prefix: String, item: Map<*, *>): String? {
            val d = item["d"] as? String ?: return null
            return prefix + d
        }

        sp.edit {
            fun applyListLong(listKey: String, keyBuilder: (Map<*, *>) -> String?) {
                val items = map[listKey] as? List<*> ?: return
                for (it in items) {
                    val item = it as? Map<*, *> ?: continue
                    val v = (item["v"] as? Number)?.toLong() ?: continue
                    val key = keyBuilder(item) ?: continue
                    putLong(key, v)
                }
            }

            fun applyListInt(listKey: String, keyBuilder: (Map<*, *>) -> String?) {
                val items = map[listKey] as? List<*> ?: return
                for (it in items) {
                    val item = it as? Map<*, *> ?: continue
                    val v = (item["v"] as? Number)?.toInt() ?: continue
                    val key = keyBuilder(item) ?: continue
                    putInt(key, v)
                }
            }

            fun applyListBoolTrue(listKey: String, keyBuilder: (Map<*, *>) -> String?) {
                val items = map[listKey] as? List<*> ?: return
                for (it in items) {
                    val item = it as? Map<*, *> ?: continue
                    val v = item["v"] as? Boolean ?: continue
                    if (!v) continue
                    val key = keyBuilder(item) ?: continue
                    putBoolean(key, true)
                }
            }

            applyListLong("usage_day") { i -> buildKeyWithPkg("usage_day_", i) }
            applyListLong("blocked_ms") { i -> buildKeyWithPkg("blocked_ms_", i) }
            applyListLong("blocked_count") { i -> buildKeyWithPkg("blocked_count_", i) }
            applyListLong("blocked_attempt") { i -> buildKeyWithPkg("blocked_attempt_", i) }

            applyListLong("runtime_ms") { i -> buildKeyNoPkg("switchly_runtime_ms_", i) }
            applyListLong("emergency_unlock_count") { i -> buildKeyNoPkg("emergency_unlock_count_", i) }
            applyListLong("nfc_scan_count") { i -> buildKeyNoPkg("nfc_scan_count_", i) }
            applyListLong("schedule_exec_count") { i -> buildKeyNoPkg("schedule_exec_count_", i) }

            // Usage limits are Int + Bool
            applyListBoolTrue("usage_limit_ever") { i ->
                val p = i["p"] as? String ?: return@applyListBoolTrue null
                "usage_limit_ever__${p}"
            }
            applyListInt("usage_limit_min") { i ->
                val pr = i["pr"] as? String ?: return@applyListInt null
                val p = i["p"] as? String ?: return@applyListInt null
                "usage_limit_min__${pr}__${p}"
            }
        }
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
                        uploadStatsChunks(created, prepared.statsChunks) { chunkOk, chunkError ->
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
                            deliverToMain { onDone(true, null) }
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
            .addOnFailureListener { error -> onDone(false, error.localizedMessage) }
    }

    // Retrieves the last N backups from the "backups" subcollection.
    fun listBackups(
        ctx: Context,
        limit: Long = 10,
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

        StatsPersistence.beginRestore(ctx)
        try {
            applyPrefsMapToLocal(ctx, prefsMap, isInternal = false, isSchedules = false, clearBeforeApply = !partialBackup)
            applyPrefsMapToLocal(ctx, internalMap, isInternal = true, isSchedules = false, clearBeforeApply = !partialBackup)
            applyPrefsMapToLocal(ctx, schedulesMap, isInternal = false, isSchedules = true, clearBeforeApply = !partialBackup)
            applyPrefsMapToLocal(ctx, uiHintsMap, prefsName = UI_HINTS_PREFS_NAME, clearBeforeApply = !partialBackup)

            // Expand compact stats payload back into internal prefs keys
            applyStatsToInternalPrefs(ctx, stats)

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

        // Firestore returns integral numbers as Long. Some of our prefs are truly Int-based.
        // If we store them as Long, SharedPreferences.getInt(...) will crash with ClassCastException.
        fun shouldStoreAsInt(key: String): Boolean {
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

        prefs.edit(commit = true) {
            if (clearBeforeApply) clear()

            fun putSupportedValue(key: String, value: Any?) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> {
                        if (shouldStoreAsInt(key) && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                            putInt(key, value.toInt())
                        } else {
                            putLong(key, value)
                        }
                    }
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is List<*> -> {
                        if (value.all { it is String }) {
                            putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    else -> Unit
                }
            }

            for ((rawKey, value) in map) {
                val key = rawKey as? String ?: continue
                if (isBackupExcludedKey(key)) continue
                putSupportedValue(key, value)
            }

            for ((key, value) in preservedEntitlementPrefs) {
                putSupportedValue(key, value)
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

        val firestore = FirebaseFirestore.getInstance()
        val backupRef = firestore
            .collection(COLLECTION)
            .document(uid)
            .collection(SUB_BACKUPS)
            .document(backupId)

        backupRef.collection(SUB_STATS_CHUNKS).get()
            .addOnSuccessListener { chunks ->
                val batch = firestore.batch()
                chunks.documents.forEach { document -> batch.delete(document.reference) }
                batch.delete(backupRef)
                batch.commit()
                    .addOnSuccessListener { cb(true, null) }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "deleteBackup failed", error)
                        AppLogStore.append(ctx, TAG, "Deleting cloud backup failed", error)
                        cb(false, error.message)
                    }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "deleteBackup chunks lookup failed", error)
                cb(false, error.message)
            }
    }
}
