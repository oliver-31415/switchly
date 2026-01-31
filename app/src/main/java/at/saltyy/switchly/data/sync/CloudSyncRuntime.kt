package at.saltyy.switchly.data.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import at.saltyy.switchly.auth.Auth
import at.saltyy.switchly.R
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import kotlin.jvm.JvmStatic

/**
 * Runtime implementation for CloudSync.
 *
 * This component synchronizes SharedPreferences with Firestore using the
 * current Firebase Auth UID as the user identifier.
 *
 * Stored data:
 * - Default SharedPreferences -> field "prefs"
 * - Internal "switchly_prefs" (profiles, selected apps, switch mode) -> field "switchly_prefs"
 *
 * Additionally backed up:
 * - "switchly_prefs_schedules" (ScheduleStore) -> field "schedules_prefs"
 *
 * In addition to the main user document, each user also maintains a
 * "backups" subcollection containing timestamped backup versions.
 *
 * SAFETY:
 * - On restore we force-disable all schedules (enabled=false) so triggers don't
 *   suddenly activate after restore.
 */
object CloudSyncRuntime {

    private const val TAG = "CloudSyncRuntime"
    private const val COLLECTION = "switchly_users"

    private const val FIELD_PREFS = "prefs"
    private const val FIELD_SWITCHLY_PREFS = "switchly_prefs"
    private const val FIELD_SCHEDULES_PREFS = "schedules_prefs"
    private const val FIELD_CREATED_AT = "created_at"

    // Compact stats payload (usage_day_*, blocked_*, runtime_*, etc.)
    private const val FIELD_STATS = "stats"
    private const val SUB_BACKUPS = "backups"

    // Synthetic id used to represent the legacy root user document as a "backup"
    private const val ROOT_LATEST_ID = "__root_latest__"

    private const val FIELD_LATEST_BACKUP_ID = "latest_backup_id"

    /**
     * True if a snapshot contains an actual backup payload (not just metadata).
     *
     * We treat the root user document as a valid backup as long as it contains
     * at least one of the payload fields.
     */
    private fun snapshotHasBackupPayload(snapshot: DocumentSnapshot): Boolean {
        return (snapshot.get(FIELD_PREFS) as? Map<*, *>)?.isNotEmpty() == true ||
            (snapshot.get(FIELD_SWITCHLY_PREFS) as? Map<*, *>)?.isNotEmpty() == true ||
            (snapshot.get(FIELD_SCHEDULES_PREFS) as? Map<*, *>)?.isNotEmpty() == true ||
            (snapshot.get(FIELD_STATS) as? Map<*, *>)?.isNotEmpty() == true
    }

    // ScheduleStore prefs name in your project
    private const val SCHEDULES_PREFS_NAME = "switchly_prefs_schedules"
    private const val SCHEDULES_KEY_ITEMS = "items" // JSON list stored by ScheduleStore

    data class CloudBackupMeta(
        val id: String,
        val createdAt: Long
    )

    /**
     * Converts SharedPreferences maps into Firestore-compatible maps:
     * - Collections / Sets -> List
     */
    private fun normalizePrefsMap(src: Map<String, *>): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        for ((rawKey, value) in src) {
            val v: Any? = when (value) {
                is Set<*> -> value.filterNotNull().map { it }
                is Collection<*> -> value.filterNotNull().map { it }
                else -> value
            }
            out[rawKey] = v
        }
        return out
    }

    /**
     * Stats keys (usage_*, blocked_*, runtime_*, etc.) are stored as many single entries in "switchly_prefs".
     * For cloud backup we compress them into structured lists to keep the remote document smaller and cleaner.
     *
     * Restore expands them back into the original SharedPreferences keys, so the rest of the app stays unchanged.
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

        fun takeBoolRegex(
            listKey: String,
            regex: Regex,
            buildItem: (MatchResult) -> Map<String, Any?>
        ) {
            val items = mutableListOf<Map<String, Any?>>()
            val toRemove = mutableListOf<String>()

            for ((k, v) in prefsOut) {
                val m = regex.matchEntire(k) ?: continue
                val b = v as? Boolean ?: continue
                items += buildItem(m) + mapOf("v" to b)
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

        // Usage limit bookkeeping
        takeBoolRegex(
            listKey = "usage_limit_ever",
            regex = Regex("usage_limit_ever__(.+)")
        ) { m -> mapOf("p" to m.groupValues[1]) }

        takeRegex(
            listKey = "usage_limit_min",
            regex = Regex("usage_limit_min__(.+?)__(.+)")
        ) { m -> mapOf("pr" to m.groupValues[1], "p" to m.groupValues[2]) }

        return prefsOut to statsOut
    }

    private fun applyStatsToInternalPrefs(ctx: Context, stats: Any?) {
        val map = stats as? Map<*, *> ?: return
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

            // Usage limit bookkeeping
            applyListBoolTrue("usage_limit_ever") { i ->
                val p = i["p"] as? String ?: return@applyListBoolTrue null
                "usage_limit_ever__" + p
            }

            applyListInt("usage_limit_min") { i ->
                val pr = i["pr"] as? String ?: return@applyListInt null
                val p = i["p"] as? String ?: return@applyListInt null
                "usage_limit_min__${pr}__${p}"
            }
        }
    }
    @JvmStatic
    fun pushLocalState(ctx: Context, onDone: (Boolean, String?) -> Unit) {
        val uid = Auth.uid()
        if (uid == null) {
            onDone(false, ctx.getString(R.string.cloud_error_not_logged_in))
            return
        }

        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection(COLLECTION).document(uid)

        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val all = normalizePrefsMap(prefs.all)

            val internalPrefs = ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)
            val internalAllRaw = normalizePrefsMap(internalPrefs.all)

            val (internalAll, statsMap) = extractStatsFromInternalPrefs(internalAllRaw)

            val schedulesPrefs = ctx.getSharedPreferences(SCHEDULES_PREFS_NAME, Context.MODE_PRIVATE)
            val schedulesAll = normalizePrefsMap(schedulesPrefs.all)

            val now = System.currentTimeMillis()

            val data = mapOf(
                FIELD_PREFS to all,
                FIELD_SWITCHLY_PREFS to internalAll,
                FIELD_STATS to statsMap,
                FIELD_SCHEDULES_PREFS to schedulesAll,
                FIELD_CREATED_AT to now
            )

            // Create a new backup version in the subcollection (single source of truth)
            docRef.collection(SUB_BACKUPS)
                .add(data)
                .addOnSuccessListener { created ->
                    Log.d(TAG, "pushLocalState: backup version created: ${created.id}")

                    // Also store the "latest" payload on the root document.
                    // This keeps restore working even if Firestore rules (or OEM quirks)
                    // block reading the subcollection on some setups.
                    //
                    // Root = latest snapshot, Subcollection = history.
                    docRef.set(
                        data + mapOf(FIELD_LATEST_BACKUP_ID to created.id),
                        SetOptions.merge()
                    ).addOnFailureListener { e ->
                        Log.w(TAG, "pushLocalState: root latest write failed", e)
                    }

                    onDone(true, null)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "pushLocalState: backup version failed", e)
                    onDone(false, e.localizedMessage)
                }

        } catch (e: Exception) {
            Log.e(TAG, "pushLocalState crashed", e)
            onDone(false, e.localizedMessage)
        }
    }

    /**
     * Retrieves the last N backups from the "backups" subcollection.
     */
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
        val colRef = db.collection(COLLECTION)
            .document(uid)
            .collection(SUB_BACKUPS)

        fun fallbackToRoot(onFail: (String?) -> Unit) {
            db.collection(COLLECTION).document(uid).get()
                .addOnSuccessListener { root ->
                    if (!root.exists()) {
                        onFail(null)
                        return@addOnSuccessListener
                    }

                    // 1) Root contains a full payload -> expose it as a synthetic backup
                    if (snapshotHasBackupPayload(root)) {
                        val ts = root.getLong(FIELD_CREATED_AT) ?: 0L
                        onDone(true, null, listOf(CloudBackupMeta(ROOT_LATEST_ID, ts)))
                        return@addOnSuccessListener
                    }

                    // 2) Root is metadata-only but points to a versioned backup
                    val latestId = root.getString(FIELD_LATEST_BACKUP_ID)
                    if (!latestId.isNullOrBlank()) {
                        val ts = root.getLong(FIELD_CREATED_AT) ?: 0L
                        onDone(true, null, listOf(CloudBackupMeta(latestId, ts)))
                        return@addOnSuccessListener
                    }

                    onFail(null)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "listBackups root fallback failed", e)
                    onFail(e.localizedMessage)
                }
        }

        colRef.orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot.documents.map { doc ->
                    val ts = doc.getLong(FIELD_CREATED_AT) ?: 0L
                    CloudBackupMeta(doc.id, ts)
                }

                if (list.isNotEmpty()) {
                    onDone(true, null, list)
                } else {
                    fallbackToRoot {
                        onDone(true, null, emptyList())
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "listBackups failed, trying root fallback", e)
                fallbackToRoot { msg ->
                    onDone(false, msg ?: e.localizedMessage, null)
                }
            }
    }

    /**
     * Loads a *specific* backup version from the "backups" subcollection.
     *
     * Compatibility: if there are no versioned backups (or rules block reading them),
     * listBackups() may expose a synthetic entry with id ROOT_LATEST_ID which loads the
     * legacy root document. When that path is used we also try (best-effort) to migrate
     * the root document into the versioned subcollection and wipe the large root fields.
     */
    fun pullBackup(ctx: Context, backupId: String, onDone: (Boolean, String?) -> Unit) {
        val uid = Auth.uid()
        if (uid == null) {
            onDone(false, ctx.getString(R.string.cloud_error_not_logged_in))
            return
        }

        val db = FirebaseFirestore.getInstance()
        val userRef = db.collection(COLLECTION).document(uid)

        if (backupId == ROOT_LATEST_ID) {
            userRef.get()
                .addOnSuccessListener { snapshot ->
                    if (!snapshot.exists()) {
                        onDone(false, ctx.getString(R.string.cloud_error_no_backup_found))
                        return@addOnSuccessListener
                    }

                    // If the root doc is metadata-only (because an older build "cleaned" it),
                    // follow the pointer to the latest versioned backup.
                    if (!snapshotHasBackupPayload(snapshot)) {
                        val latestId = snapshot.getString(FIELD_LATEST_BACKUP_ID)
                        if (!latestId.isNullOrBlank()) {
                            // Delegate to normal versioned restore.
                            pullBackup(ctx, latestId, onDone)
                            return@addOnSuccessListener
                        }
                    }

                    try {
                        applyBackupSnapshot(ctx, snapshot)
                        onDone(true, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "pullBackup(latest) failed", e)
                        onDone(false, e.localizedMessage)
                        return@addOnSuccessListener
                    }

                    // Optional best-effort: create a versioned backup entry from the root snapshot.
                    // We do NOT delete the root payload (root remains the "latest" snapshot).
                    runCatching {
                        val data = buildBackupDataFromSnapshot(snapshot)
                        userRef.collection(SUB_BACKUPS)
                            .add(data)
                            .addOnSuccessListener { created ->
                                userRef.set(
                                    mapOf(
                                        FIELD_LATEST_BACKUP_ID to created.id,
                                        FIELD_CREATED_AT to (data[FIELD_CREATED_AT] as? Long ?: System.currentTimeMillis())
                                    ),
                                    SetOptions.merge()
                                ).addOnFailureListener { t ->
                                    Log.w(TAG, "pullBackup(latest): meta write failed", t)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.w(TAG, "pullBackup(latest): create versioned entry failed", e)
                            }
                    }.onFailure {
                        Log.w(TAG, "pullBackup(latest): create versioned entry crashed: ${it.message}")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "pullBackup(latest) failed", e)
                    onDone(false, e.localizedMessage)
                }
            return
        }

        val backupRef = userRef.collection(SUB_BACKUPS).document(backupId)
        backupRef.get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    onDone(false, ctx.getString(R.string.cloud_error_backup_not_found))
                    return@addOnSuccessListener
                }

                try {
                    applyBackupSnapshot(ctx, snapshot)
                    onDone(true, null)
                } catch (e: Exception) {
                    Log.e(TAG, "pullBackup failed", e)
                    onDone(false, e.localizedMessage)
                    return@addOnSuccessListener
                }

                // Best-effort: write the restored payload as the "latest" root snapshot.
                // This makes restore resilient even if some Firestore rules allow root reads
                // but restrict reading the versioned subcollection.
                runCatching {
                    val data = buildBackupDataFromSnapshot(snapshot)
                    userRef.set(
                        data + mapOf(FIELD_LATEST_BACKUP_ID to backupId),
                        SetOptions.merge()
                    ).addOnFailureListener { t ->
                        Log.w(TAG, "pullBackup: root latest write failed", t)
                    }
                }.onFailure {
                    Log.w(TAG, "pullBackup: root latest write crashed: ${it.message}")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "pullBackup failed", e)
                onDone(false, e.localizedMessage)
            }
    }

    /**
     * Applies a backup document (either a versioned backup or the legacy root doc) to local storage.
     */
    private fun applyBackupSnapshot(ctx: Context, snapshot: DocumentSnapshot) {
        val prefsMap = snapshot.get(FIELD_PREFS) as? Map<*, *> ?: emptyMap<Any, Any>()
        val internalMap = snapshot.get(FIELD_SWITCHLY_PREFS) as? Map<*, *> ?: emptyMap<Any, Any>()
        val schedulesMap = snapshot.get(FIELD_SCHEDULES_PREFS) as? Map<*, *> ?: emptyMap<Any, Any>()
        val stats = snapshot.get(FIELD_STATS)

        applyPrefsMapToLocal(ctx, prefsMap, isInternal = false, isSchedules = false)
        applyPrefsMapToLocal(ctx, internalMap, isInternal = true, isSchedules = false)
        applyPrefsMapToLocal(ctx, schedulesMap, isInternal = false, isSchedules = true)

        // Expand compact stats payload back into internal prefs keys
        applyStatsToInternalPrefs(ctx, stats)

        // Safety: restored schedules should not immediately fire
        forceDisableAllSchedules(ctx)
    }

    /**
     * Builds a versioned-backup payload from a legacy root document.
     */
    private fun buildBackupDataFromSnapshot(snapshot: DocumentSnapshot): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val createdAt = snapshot.getLong(FIELD_CREATED_AT) ?: now

        val prefsMap = snapshot.get(FIELD_PREFS) as? Map<*, *> ?: emptyMap<Any, Any>()
        val internalMap = snapshot.get(FIELD_SWITCHLY_PREFS) as? Map<*, *> ?: emptyMap<Any, Any>()
        val schedulesMap = snapshot.get(FIELD_SCHEDULES_PREFS) as? Map<*, *> ?: emptyMap<Any, Any>()
        val stats = snapshot.get(FIELD_STATS) as? Map<*, *> ?: emptyMap<Any, Any>()

        return mapOf(
            FIELD_PREFS to prefsMap,
            FIELD_SWITCHLY_PREFS to internalMap,
            FIELD_SCHEDULES_PREFS to schedulesMap,
            FIELD_STATS to stats,
            FIELD_CREATED_AT to createdAt
        )
    }

    /**
     * Applies a Firestore-loaded map to local SharedPreferences.
     */
    private fun applyPrefsMapToLocal(
        ctx: Context,
        map: Map<*, *>,
        isInternal: Boolean = false,
        isSchedules: Boolean = false
    ) {
        val prefs = when {
            isSchedules -> ctx.getSharedPreferences(SCHEDULES_PREFS_NAME, Context.MODE_PRIVATE)
            isInternal -> ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)
            else -> PreferenceManager.getDefaultSharedPreferences(ctx)
        }

        // Firestore returns integral numbers as Long. Some of our prefs are truly Int-based
        // (e.g. onboarding version, usage limit minutes). If we store them as Long,
        // SharedPreferences.getInt(...) will crash with ClassCastException.
        fun shouldStoreAsInt(key: String): Boolean {
            return key == "onboarding_version" ||
                key.startsWith("usage_limit_min__")
        }

        prefs.edit(commit = true) {
            clear()

            for ((rawKey, value) in map) {
                val key = rawKey as? String ?: continue
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
                        val canCast = value.all { it is String }
                        if (canCast) {
                            putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    else -> {
                        // ignore unsupported types
                    }
                }
            }
        }
    }

    /**
     * After restoring schedules, force-disable them for safety.
     *
     * We do this "best-effort" without depending on ScheduleStore internals.
     * If the schedules JSON format changes, nothing crashes; worst case: no change.
     */
    private fun forceDisableAllSchedules(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(SCHEDULES_PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(SCHEDULES_KEY_ITEMS, null) ?: return

            // ScheduleStore likely stores JSON objects that contain `"enabled":true/false`.
            // Replace any enabled:true with enabled:false (best-effort, safe fallback).
            val patched = raw
                .replace("\"enabled\":true", "\"enabled\":false")
                .replace("\"enabled\" : true", "\"enabled\":false")
                .replace("\"enabled\"  :  true", "\"enabled\":false")
                .replace("\"enabled\": true", "\"enabled\":false")
                .replace("\"enabled\" :true", "\"enabled\":false")

            if (patched != raw) {
                sp.edit { putString(SCHEDULES_KEY_ITEMS, patched) }
                Log.d(TAG, "forceDisableAllSchedules: patched schedules enabled->false")
            } else {
                Log.d(TAG, "forceDisableAllSchedules: no enabled=true found to patch")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "forceDisableAllSchedules failed: ${t.message}")
        }
    }
}
