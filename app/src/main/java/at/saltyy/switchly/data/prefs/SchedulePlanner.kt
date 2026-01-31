package at.saltyy.switchly.data.prefs

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import java.util.Calendar
import kotlin.math.max

/**
 * Calculates upcoming schedule boundaries and manages the alarm
 * that triggers schedule re-evaluation.
 *
 * NOTE:
 * Inexact alarms can be deferred heavily in Doze, so time schedules may only apply when the device wakes / user opens the app.
 * We therefore prefer exact alarms *when allowed*, otherwise fall back to inexact.
 */
object SchedulePlanner {

    private const val PREFS = "switchly_prefs"
    private const val KEY_NEXT_BOUNDARY_MS = "schedule_next_boundary_ms"

    // Broadcast action used to notify the UI about changes to the next schedule boundary
    const val ACTION_NEXT_CHANGED = "at.saltyy.switchly.schedule.NEXT_CHANGED"

    // Fire at the exact boundary. Using an "early" window can cause off-by-one-minute
    // behavior when schedules are defined in whole minutes.
    private const val EARLY_WINDOW_MS = 0L

    // Keep a stable requestCode for the regular one-off tick PendingIntent.
    // Some refactors referenced this as PI_TICK but forgot to define it.
    private const val PI_TICK = 42

    // Fallback: if exact alarms are not allowed (Android 12+ without permission), keep a
    // lightweight inexact repeating tick. This prevents schedules from \"never\" applying
    // on devices that heavily defer one-off inexact alarms in Doze.
    private const val FALLBACK_TICK_RC = 43
    private const val FALLBACK_INTERVAL_MS = 15 * 60 * 1000L

    fun updateNextAlarm(context: Context) {
        val ctx = context.applicationContext

        ScheduleStore.pruneExpired(ctx)

        val all = ScheduleStore.getAll(ctx).filter { it.enabled }

        // WiFi / Bluetooth schedules are connection-triggered; ignore for time-based boundary calc if they are "always active".
        val timeBased = all.filterNot { s ->
            val isConn = !s.wifiSsid.isNullOrBlank() || !s.btDeviceName.isNullOrBlank()
            isConn && s.startMinutes == 0 && s.endMinutes >= 1439
        }

        if (timeBased.isEmpty()) {
            saveNextBoundary(ctx, -1)
            cancelAlarm(ctx)
            ensureFallbackAlarm(ctx, enabled = false)
            return
        }

        val now = Calendar.getInstance()
        val nowMs = now.timeInMillis
        var best: Long? = null

        fun consider(t: Long) {
            if (t > nowMs && (best == null || t < best!!)) best = t
        }

        fun ymdOf(cal: Calendar): Int {
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return y * 10000 + m * 100 + d
        }

        fun atMinutes(baseDay: Calendar, minutes: Int): Long {
            return (baseDay.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        fun isRangeAction(action: ScheduleStore.Action): Boolean {
            return when (action) {
                ScheduleStore.Action.ENABLE_AND_DISABLE,
                ScheduleStore.Action.DISABLE_AND_ENABLE -> true
                else -> false
            }
        }

        for (s in timeBased) {
            when (s.type) {
                ScheduleStore.Type.WEEKLY -> {
                    repeat(14) { offset ->
                        val day = Calendar.getInstance().apply {
                            timeInMillis = nowMs
                            add(Calendar.DAY_OF_YEAR, offset)
                        }

                        val bit = ScheduleStore.Days.fromCalendarDay(day.get(Calendar.DAY_OF_WEEK))
                        if (s.daysMask and bit == 0) return@repeat

                        // Always consider start
                        consider(atMinutes(day, s.startMinutes))

                        // Only consider end for range schedules
                        if (isRangeAction(s.action)) {
                            consider(atMinutes(day, s.endMinutes))
                        }
                    }
                }

                ScheduleStore.Type.ONE_TIME -> {
                    if (s.startDate <= 0 || s.endDate <= 0) continue

                    repeat(30) { offset ->
                        val day = Calendar.getInstance().apply {
                            timeInMillis = nowMs
                            add(Calendar.DAY_OF_YEAR, offset)
                        }

                        val ymd = ymdOf(day)
                        if (ymd !in s.startDate..s.endDate) return@repeat

                        consider(atMinutes(day, s.startMinutes))

                        if (isRangeAction(s.action)) {
                            consider(atMinutes(day, s.endMinutes))
                        }
                    }
                }
            }
        }

        if (best != null) {
            // NOTE:
            // We store and forward the *real boundary timestamp* to the receiver.
            // This prevents off-by-one-minute behavior when schedules are defined in whole minutes.
            val boundaryMs = best!!

            saveNextBoundary(ctx, boundaryMs)
            val fireAt = max(nowMs + 1_000L, boundaryMs - EARLY_WINDOW_MS)
            scheduleAlarm(ctx, fireAt, boundaryMs)
            ensureFallbackAlarm(ctx, enabled = true)
        } else {
            saveNextBoundary(ctx, -1)
            cancelAlarm(ctx)
            ensureFallbackAlarm(ctx, enabled = false)
        }
    }

    fun getNextBoundaryMillis(context: Context): Long {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLong(KEY_NEXT_BOUNDARY_MS, -1)
    }

    private fun saveNextBoundary(ctx: Context, t: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_NEXT_BOUNDARY_MS, t)
        }
    }

    /**
     * Prefer exact alarms when allowed; otherwise fall back to inexact.
     *
     * API notes:
     * - AlarmManager.canScheduleExactAlarms() exists on API 31+
     * - On <31 there isn't the same user-toggle mechanism; setExactAndAllowWhileIdle is usable without that check.
     */
    private fun scheduleAlarm(ctx: Context, t: Long, boundaryMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingTickIntent(ctx, boundaryMs)

        // Cancel first to avoid duplicates drifting around
        am.cancel(pi)

        val canExact = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            // On <31 there's no user-toggle for exact alarms like on S+
            true
        }

        if (canExact) {
            // Most reliable for time schedules in Doze
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            // Best-effort fallback: may be deferred in Doze
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    private fun ensureFallbackAlarm(ctx: Context, enabled: Boolean) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (!enabled) {
            am.cancel(pendingFallbackIntent(ctx))
            return
        }

        // Only keep the fallback when exact alarms are NOT allowed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            val first = System.currentTimeMillis() + 60_000L
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                first,
                FALLBACK_INTERVAL_MS,
                pendingFallbackIntent(ctx)
            )
        } else {
            am.cancel(pendingFallbackIntent(ctx))
        }
    }

    private fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Extras are ignored for PendingIntent identity, so boundaryMs doesn't matter here.
        am.cancel(pendingTickIntent(ctx, 0L))
        am.cancel(pendingFallbackIntent(ctx))
    }

    private fun pendingTickIntent(ctx: Context, boundaryMs: Long) =
        PendingIntent.getBroadcast(
            ctx,
            PI_TICK,
            Intent(ctx, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_TICK
                putExtra("alarm_reason", "time_boundary")
                // IMPORTANT: evaluation uses this timestamp, not the receiver's wall-clock time.
                putExtra("boundary_ms", boundaryMs)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun pendingFallbackIntent(ctx: Context) =
        PendingIntent.getBroadcast(
            ctx,
            FALLBACK_TICK_RC,
            Intent(ctx, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_TICK
                putExtra("alarm_reason", "fallback_repeat")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun notifyNextChanged(ctx: Context) {
        val intent = Intent(ACTION_NEXT_CHANGED).apply {
            setPackage(ctx.packageName)
        }
        ctx.sendBroadcast(intent)
    }
}
