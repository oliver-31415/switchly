package at.saltyy.switchly.blocking

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import at.saltyy.switchly.util.PermissionUtils

/**
 * Blocking runtime selector.
 * Switchly is now Accessibility-only: we rely entirely on [SwitchlyAccessibilityService].
 * Accessibility is managed by the system, therefore there is no app-blocking foreground service that we need to start/stop.
 */
object BlockingRuntime {

    private const val PREFS_RUNTIME = "switchly_runtime"
    private const val KEY_A11Y_CONNECTED = "a11y_connected"
    private const val KEY_A11Y_LAST_HEARTBEAT_MS = "a11y_last_heartbeat_ms"
    private const val A11Y_HEARTBEAT_STALE_MS = 8_000L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE)

    // Called by [SwitchlyAccessibilityService] while it is connected and alive.
    fun markAccessibilityHeartbeat(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        prefs(ctx).edit {
            putBoolean(KEY_A11Y_CONNECTED, true)
            putLong(KEY_A11Y_LAST_HEARTBEAT_MS, now)
        }
    }

    // Called when the Accessibility service disconnects or is destroyed.
    fun markAccessibilityDisconnected(ctx: Context) {
        prefs(ctx).edit { putBoolean(KEY_A11Y_CONNECTED, false) }
    }

    // Whether Accessibility is enabled in Android settings.
    fun isAccessibilityEnabledInSettings(ctx: Context): Boolean {
        return PermissionUtils.isAccessibilityServiceEnabled(
            ctx,
            SwitchlyAccessibilityService::class.java
        )
    }

    fun isAccessibilityActive(ctx: Context): Boolean {
        // Accessibility must be enabled in Settings AND service heartbeat must be fresh.
        // This catches force-stop/OEM kill cases where the toggle may still look enabled, but blocking is currently not enforced.
        if (!isAccessibilityEnabledInSettings(ctx)) return false

        val p = prefs(ctx)
        val connected = p.getBoolean(KEY_A11Y_CONNECTED, false)
        val last = p.getLong(KEY_A11Y_LAST_HEARTBEAT_MS, 0L)
        if (!connected || last <= 0L) return false

        val age = SystemClock.elapsedRealtime() - last
        return age in 0..A11Y_HEARTBEAT_STALE_MS
    }

    // No-op in the Accessibility-only runtime.
    fun ensureRunning(ctx: Context) {
        // Accessibility-only runtime: nothing to start here.
        // But we do refresh the "protection inactive" notification state.
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(ctx) }
    }

    /**
     * No-op in the Accessibility-only runtime.
     * Callers may still call this when the user disables Switchly.
     */
    fun stop(ctx: Context) {
        // When Switchly is turned off, ensure we clear any warning notification.
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(ctx) }
    }
}
