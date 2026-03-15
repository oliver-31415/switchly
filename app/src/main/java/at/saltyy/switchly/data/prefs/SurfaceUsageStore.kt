package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily usage tracking for in-app "surfaces" like:
 *  - yt:shorts
 *  - ig:reels
 *  - ig:explore
 */
object SurfaceUsageStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX_DAY = "surf_usage_day_" // + yyyymmdd + "_" + key

    // Buffer frequent increments to avoid high-frequency SharedPreferences writes.
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_PENDING_KEYS = 32

    private val lock = Any()
    private val pending = ConcurrentHashMap<String, Long>()
    @Volatile private var lastFlushAt: Long = 0L

    private fun dayKey(): String {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return "%04d%02d%02d".format(y, m, d)
    }

    private fun prefKey(surfaceKey: String): String = PREFIX_DAY + dayKey() + "_" + surfaceKey

    fun addUsageMsToday(ctx: Context, surfaceKey: String, deltaMs: Long) {
        if (surfaceKey.isBlank() || deltaMs <= 0L) return
        val k = prefKey(surfaceKey)
        pending.merge(k, deltaMs) { a, b -> a + b }
        maybeFlush(ctx, force = false)
    }

    fun getUsageMsToday(ctx: Context, surfaceKey: String): Long {
        if (surfaceKey.isBlank()) return 0L
        val k = prefKey(surfaceKey)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = prefs.getLong(k, 0L)
        val extra = pending[k] ?: 0L
        return (base + extra).coerceAtLeast(0L)
    }

    fun flush(ctx: Context) = maybeFlush(ctx, force = true)

    private fun maybeFlush(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        val should = force || (now - lastFlushAt) >= FLUSH_INTERVAL_MS || pending.size >= MAX_PENDING_KEYS
        if (!should) return
        synchronized(lock) {
            if (!force && (now - lastFlushAt) < FLUSH_INTERVAL_MS && pending.size < MAX_PENDING_KEYS) return
            lastFlushAt = now
            val snapshot = HashMap(pending)
            pending.clear()
            if (snapshot.isEmpty()) return
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit {
                for ((k, add) in snapshot) {
                    val cur = prefs.getLong(k, 0L)
                    putLong(k, cur + add)
                }
            }
        }
    }
}
