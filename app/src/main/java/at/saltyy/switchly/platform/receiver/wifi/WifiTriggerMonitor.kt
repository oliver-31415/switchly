package at.saltyy.switchly.platform.receiver.wifi

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.ui.MainActivity

object WifiTriggerMonitor {

    private const val PREFS = "switchly_prefs_schedules"
    private const val KEY_SCHEDULES = "items"

    @Volatile private var listening = false
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var cachedServiceIntent: Intent? = null

    private fun serviceIntent(context: Context): Intent {
        return cachedServiceIntent ?: synchronized(this) {
            cachedServiceIntent ?: Intent(context.applicationContext, WifiTriggerService::class.java).also {
                cachedServiceIntent = it
            }
        }
    }

    fun ensureStarted(context: Context) {
        val ctx = context.applicationContext

        if (!listening) {
            listening = true
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_SCHEDULES) sync(ctx)
            }
            listener = l
            sp.registerOnSharedPreferenceChangeListener(l)
        }

        sync(ctx)
    }

    private fun sync(context: Context) {
        val ctx = context.applicationContext
        val schedules = ScheduleStore.getAll(ctx)
        val active = schedules.any { it.enabled && !it.wifiSsid.isNullOrBlank() }

        if (active) startService(ctx) else stopService(ctx)
    }

    private fun startService(context: Context) {
        val ctx = context.applicationContext
        val intent = serviceIntent(ctx)

        val started = runCatching {
            ContextCompat.startForegroundService(ctx, intent)
        }.isSuccess

        if (!started) {
            postEnableNotification(ctx)
        }
    }

    private fun stopService(context: Context) {
        context.applicationContext.stopService(serviceIntent(context))

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(FALLBACK_NOTIF_ID)
        }
    }

    private fun postEnableNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    FALLBACK_CHANNEL_ID,
                    context.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, 0, openIntent, piFlags)

        val notif = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.app_blocking_white_24)
            .setContentTitle(context.getString(R.string.notif_enable_wifi_triggers_title))
            .setContentText(
                context.getString(
                    R.string.notif_enable_wifi_triggers_text_fmt,
                    context.getString(R.string.app_name)
                )
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching { nm.notify(FALLBACK_NOTIF_ID, notif) }
    }

    private const val FALLBACK_CHANNEL_ID = "switchly_fallback_wifi"
    private const val FALLBACK_NOTIF_ID = 23004
}
