package at.saltyy.switchly.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager

/**
 * Direct Android battery-optimization exemption request.
 * The action value is the platform value of
 * Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (API 23+).
 * Keeping it centralized here avoids scattering the policy-sensitive request throughout the UI while still using the supported platform flow.
 */
object BatteryOptimizationRequest {
    private const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS =
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"

    fun isAlreadyAllowed(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun intent(context: Context): Intent =
        Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
}
