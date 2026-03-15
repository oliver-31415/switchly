package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

object UsageStore {
    private const val PREFS = "switchly_prefs"

    // usage_day_yyyymmdd_pkg
    private const val PREFIX_DAY = "usage_day_" // + yyyymmdd + "_" + pkg

    // Buffer frequent increments to avoid high-frequency SharedPreferences writes.
    // We flush at most every FLUSH_INTERVAL_MS or if too many keys are pending.
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_PENDING_KEYS = 32

    private val lock = Any()
    private val pending = HashMap<String, Long>()
    @Volatile private var lastFlushAtMs: Long = 0L

    fun addUsageMsToday(ctx: Context, pkg: String, deltaMs: Long) {
        if (pkg.isBlank()) return
        if (deltaMs <= 0L) return

        val ymd = todayYmdInt()
        val key = dayKey(ymd, pkg)

        val now = System.currentTimeMillis()
        var shouldFlush = false

        synchronized(lock) {
            pending[key] = (pending[key] ?: 0L) + deltaMs
            shouldFlush = (now - lastFlushAtMs) >= FLUSH_INTERVAL_MS || pending.size >= MAX_PENDING_KEYS
        }

        if (shouldFlush) flush(ctx)
    }

    fun getUsageMsToday(ctx: Context, pkg: String): Long {
        if (pkg.isBlank()) return 0L
        val ymd = todayYmdInt()
        val key = dayKey(ymd, pkg)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val persisted = sp.getLong(key, 0L)
        val buffered = synchronized(lock) { pending[key] ?: 0L }
        return persisted + buffered
    }

    // Explicit setter used for "clear today usage" when removing limit.
    fun setUsageMsToday(ctx: Context, pkg: String, ms: Long) {
        if (pkg.isBlank()) return
        // Ensure buffered deltas are persisted before overwriting.
        flush(ctx)
        val ymd = todayYmdInt()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        sp.edit {
            putLong(dayKey(ymd, pkg), ms.coerceAtLeast(0L))
        }
    }

    fun getUsageMsForLastNDays(ctx: Context, pkg: String, days: Int): Long {
        if (pkg.isBlank()) return 0L
        if (days <= 0) return 0L

        // Stats screen: flush once so the most recent buffered data is included.
        flush(ctx)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()

        var sum = 0L
        for (i in 0 until days) {
            val ymd = ymdInt(cal)
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    /**
     * Sum usage for a range of days in the past.
     * @param startOffsetDays 0 = include today, 1 = start yesterday, 7 = start 7 days ago...
     * @param days number of days to sum (must be > 0)
     */
    fun getUsageMsForPastRange(ctx: Context, pkg: String, startOffsetDays: Int, days: Int): Long {
        if (pkg.isBlank()) return 0L
        if (days <= 0) return 0L

        flush(ctx)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -startOffsetDays)

        var sum = 0L
        for (i in 0 until days) {
            val ymd = ymdInt(cal)
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getUsageMsForMonth(ctx: Context, pkg: String, year: Int, month1Based: Int): Long {
        if (pkg.isBlank()) return 0L

        flush(ctx)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12) // avoid DST weirdness
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val targetMonth = cal.get(Calendar.MONTH)
        var sum = 0L
        while (cal.get(Calendar.MONTH) == targetMonth) {
            val ymd = ymdInt(cal)
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return sum
    }

    fun getUsageMsForYear(ctx: Context, pkg: String, year: Int): Long {
        if (pkg.isBlank()) return 0L

        flush(ctx)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var sum = 0L
        while (cal.get(Calendar.YEAR) == year) {
            val ymd = ymdInt(cal)
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum
    }

    fun getUsageMsOverall(ctx: Context, pkg: String): Long {
        if (pkg.isBlank()) return 0L
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0L
        val suffix = "_" + pkg
        for ((k, v) in sp.all) {
            if (k.startsWith(PREFIX_DAY) && k.endsWith(suffix)) {
                sum += (v as? Number)?.toLong() ?: 0L
            }
        }
        return sum
    }

    /**
     * Forces a flush of buffered deltas to SharedPreferences.
     * Safe to call frequently; does nothing if no pending values exist.
     */
    fun flush(ctx: Context) {
        val toWrite: Map<String, Long>
        synchronized(lock) {
            if (pending.isEmpty()) return
            toWrite = HashMap(pending)
            pending.clear()
            lastFlushAtMs = System.currentTimeMillis()
        }

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val merged = HashMap<String, Long>(toWrite.size)
        for ((k, delta) in toWrite) {
            val cur = sp.getLong(k, 0L)
            merged[k] = cur + delta
        }

        sp.edit {
            for ((k, v) in merged) {
                putLong(k, v)
            }
        }
    }

    private fun dayKey(ymd: Int, pkg: String): String {
        return PREFIX_DAY + ymd.toString() + "_" + pkg
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
