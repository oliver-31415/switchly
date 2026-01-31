package at.saltyy.switchly.blocking

import android.content.Context
import at.saltyy.switchly.util.PermissionUtils

/**
 * Blocking runtime selector.
 *
 * Switchly is now Accessibility-only: we rely entirely on [SwitchlyAccessibilityService].
 * Accessibility is managed by the system, therefore there is no app-blocking foreground service
 * that we need to start/stop.
 */
object BlockingRuntime {

    fun isAccessibilityActive(ctx: Context): Boolean {
        return PermissionUtils.isAccessibilityServiceEnabled(
            ctx,
            SwitchlyAccessibilityService::class.java
        )
    }

    /**
     * No-op in the Accessibility-only runtime.
     */
    fun ensureRunning(ctx: Context) {
        // Accessibility-only runtime: nothing to start here.
        // But we do refresh the "protection inactive" notification state.
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(ctx) }
    }

    /**
     * No-op in the Accessibility-only runtime.
     *
     * Callers may still call this when the user disables Switchly.
     */
    fun stop(ctx: Context) {
        // When Switchly is turned off, ensure we clear any warning notification.
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(ctx) }
    }
}
