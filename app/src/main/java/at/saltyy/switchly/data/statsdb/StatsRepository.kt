package at.saltyy.switchly.data.statsdb

import android.content.Context
import java.util.Calendar

/**
 * Repository for Switchly stats.
 * Design goals:
 * - cheap writes (service can log frequently)
 * - reliable blocked-duration tracking (via windows)
 * - fast UI queries (via daily aggregates)
 */
class StatsRepository private constructor(private val db: StatsDatabase) {

    enum class Type(val id: Int) {
        BLOCK_SHOWN(1),
        BLOCK_ATTEMPT(2),
        EMERGENCY_UNLOCK_USED(3),
        SERVICE_STARTED(4),
        SERVICE_STOPPED(5)
    }

    private val dao by lazy { db.dao() }

    suspend fun logAttempt(pkg: String, profileId: String?, reason: String?) {
        if (pkg.isBlank()) return
        val now = System.currentTimeMillis()
        dao.insertEvent(
            StatEvent(
                ts = now,
                type = Type.BLOCK_ATTEMPT.id,
                packageName = pkg,
                profileId = profileId,
                reason = reason,
                metaJson = null
            )
        )
        bumpDaily(pkg, now) { cur ->
            cur.copy(attemptCount = cur.attemptCount + 1)
        }
    }

    suspend fun logBlockShown(pkg: String, profileId: String?, reason: String?) {
        if (pkg.isBlank()) return
        val now = System.currentTimeMillis()
        dao.insertEvent(
            StatEvent(
                ts = now,
                type = Type.BLOCK_SHOWN.id,
                packageName = pkg,
                profileId = profileId,
                reason = reason,
                metaJson = null
            )
        )
        bumpDaily(pkg, now) { cur ->
            cur.copy(blockCount = cur.blockCount + 1)
        }
    }

    suspend fun logEmergencyUnlockUsed() {
        val now = System.currentTimeMillis()
        dao.insertEvent(
            StatEvent(
                ts = now,
                type = Type.EMERGENCY_UNLOCK_USED.id,
                packageName = null,
                profileId = null,
                reason = null,
                metaJson = null
            )
        )
    }

    suspend fun logServiceStarted() {
        val now = System.currentTimeMillis()
        dao.insertEvent(
            StatEvent(
                ts = now,
                type = Type.SERVICE_STARTED.id,
                packageName = null,
                profileId = null,
                reason = null,
                metaJson = null
            )
        )
    }

    suspend fun logServiceStopped() {
        val now = System.currentTimeMillis()
        dao.insertEvent(
            StatEvent(
                ts = now,
                type = Type.SERVICE_STOPPED.id,
                packageName = null,
                profileId = null,
                reason = null,
                metaJson = null
            )
        )
    }

    // Start a blocked window for a given package if one isn't already open.
    suspend fun ensureWindowOpen(pkg: String, profileId: String?, reason: String?) {
        if (pkg.isBlank()) return
        val open = dao.getOpenWindowForPkg(pkg)
        if (open != null) return
        val now = System.currentTimeMillis()
        dao.insertWindow(
            BlockedWindow(
                packageName = pkg,
                profileId = profileId,
                reason = reason,
                startTs = now,
                endTs = null
            )
        )
    }

    // Close open windows for a package and attribute the blocked duration into daily aggregates.
    suspend fun closeWindowsForPkg(pkg: String, reason: String? = null) {
        if (pkg.isBlank()) return
        val open = dao.getOpenWindowForPkg(pkg) ?: return
        val now = System.currentTimeMillis()
        // Close all open windows for that pkg (defensive)
        dao.closeAllOpenWindowsForPkg(pkg, now)
        val start = open.startTs
        if (now > start) {
            addBlockedDurationToDaily(pkg, start, now)
        }
    }

    suspend fun closeWindowsForProfile(profileId: String) {
        val now = System.currentTimeMillis()
        val opens = dao.getOpenWindowsForProfile(profileId)
        dao.closeAllOpenWindowsForProfile(profileId, now)
        for (w in opens) {
            if (now > w.startTs) addBlockedDurationToDaily(w.packageName, w.startTs, now)
        }
    }

    suspend fun listDailyBetween(fromYmd: Int, toYmd: Int): List<DailyAppStat> {
        return dao.listDailyBetween(fromYmd, toYmd)
    }

    suspend fun replaceAllDaily(stats: List<DailyAppStat>) {
        dao.deleteAllDaily()
        for (s in stats) dao.upsertDaily(s)
    }

    /**
     * Estimate service runtime between [fromTs] and [toTs] using SERVICE_STARTED/SERVICE_STOPPED events.
     * If there is a start without stop, we assume it ran until [toTs].
     */
    suspend fun estimateServiceRuntimeMs(fromTs: Long, toTs: Long): Long {
        if (toTs <= fromTs) return 0L
        val events = dao.listEventsBetween(fromTs, toTs)
            .filter { it.type == Type.SERVICE_STARTED.id || it.type == Type.SERVICE_STOPPED.id }
            .sortedBy { it.ts }

        var runningSince: Long? = null
        var total = 0L

        for (e in events) {
            when (e.type) {
                Type.SERVICE_STARTED.id -> {
                    if (runningSince == null) runningSince = e.ts
                }
                Type.SERVICE_STOPPED.id -> {
                    val s = runningSince
                    if (s != null) {
                        total += (e.ts - s).coerceAtLeast(0L)
                        runningSince = null
                    }
                }
            }
        }

        val s = runningSince
        if (s != null) total += (toTs - s).coerceAtLeast(0L)

        return total
    }

    private suspend fun bumpDaily(pkg: String, ts: Long, mutate: (DailyAppStat) -> DailyAppStat) {
        val ymd = ymdInt(ts)
        val curList = dao.listDailyForPkgBetween(pkg, ymd, ymd)
        val cur = curList.firstOrNull() ?: DailyAppStat(
            ymd = ymd,
            packageName = pkg,
            blockedMs = 0L,
            blockCount = 0,
            attemptCount = 0
        )
        dao.upsertDaily(mutate(cur))
    }

    private suspend fun addBlockedDurationToDaily(pkg: String, startTs: Long, endTs: Long) {
        var start = startTs
        val end = endTs
        if (end <= start) return

        // Split by local day boundary to keep day aggregates correct.
        while (true) {
            val startDayEnd = endOfDayMs(start)
            val sliceEnd = minOf(end, startDayEnd)
            val delta = (sliceEnd - start).coerceAtLeast(0L)
            if (delta > 0L) {
                val ymd = ymdInt(start)
                val curList = dao.listDailyForPkgBetween(pkg, ymd, ymd)
                val cur = curList.firstOrNull() ?: DailyAppStat(
                    ymd = ymd,
                    packageName = pkg,
                    blockedMs = 0L,
                    blockCount = 0,
                    attemptCount = 0
                )
                dao.upsertDaily(cur.copy(blockedMs = cur.blockedMs + delta))
            }
            if (sliceEnd >= end) break
            start = sliceEnd
        }
    }

    private fun ymdInt(ts: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }

    private fun endOfDayMs(ts: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    companion object {
        @Volatile private var INSTANCE: StatsRepository? = null

        fun get(ctx: Context): StatsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StatsRepository(StatsDatabase.get(ctx.applicationContext)).also { INSTANCE = it }
            }
        }
    }
}
