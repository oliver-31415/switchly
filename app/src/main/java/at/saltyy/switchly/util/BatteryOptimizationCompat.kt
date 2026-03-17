package at.saltyy.switchly.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

object BatteryOptimizationCompat {
    private const val PREFS_SCHEDULE_HEALTH = "switchly_schedule_health"
    private const val KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE = "battery_optimization_confirmed_max_available"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching { am.isBackgroundRestricted }.getOrDefault(false)
    }

    fun isUserConfirmedMaxAvailable(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_SCHEDULE_HEALTH, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE, false)
    }

    fun isEffectivelyOk(context: Context): Boolean {
        return isIgnoringBatteryOptimizations(context) ||
            !isBackgroundRestricted(context) ||
            isUserConfirmedMaxAvailable(context)
    }

    fun isLikelyStillRestricted(context: Context): Boolean = !isEffectivelyOk(context)
}
