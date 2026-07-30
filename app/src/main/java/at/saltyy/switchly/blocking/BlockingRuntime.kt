/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.blocking

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.feature.blocker.BlockerActivity
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
    private const val KEY_A11Y_LAST_EVENT_WALL_MS = "a11y_last_event_wall_ms"
    private const val KEY_A11Y_LAST_EVENT_PKG = "a11y_last_event_pkg"
    private const val KEY_A11Y_LAST_EVENT_TYPE = "a11y_last_event_type"
    private const val KEY_FOREGROUND_LAST_WALL_MS = "foreground_last_wall_ms"
    private const val KEY_FOREGROUND_LAST_PKG = "foreground_last_pkg"
    private const val KEY_FOREGROUND_LAST_SOURCE = "foreground_last_source"
    private const val KEY_USAGE_RESOLVE_LAST_WALL_MS = "usage_resolve_last_wall_ms"
    private const val KEY_USAGE_RESOLVE_LAST_PKG = "usage_resolve_last_pkg"
    private const val KEY_USAGE_RESOLVE_LAST_SOURCE = "usage_resolve_last_source"
    private const val KEY_BLOCK_CHECK_LAST_WALL_MS = "block_check_last_wall_ms"
    private const val KEY_BLOCK_CHECK_LAST_PKG = "block_check_last_pkg"
    private const val KEY_BLOCK_CHECK_LAST_REASON = "block_check_last_reason"
    private const val KEY_BLOCK_CHECK_LAST_DETAILS = "block_check_last_details"
    private const val KEY_BLOCK_SHOWN_LAST_WALL_MS = "block_shown_last_wall_ms"
    private const val KEY_BLOCK_SHOWN_LAST_PKG = "block_shown_last_pkg"
    private const val KEY_BLOCK_SHOWN_LAST_DETAILS = "block_shown_last_details"
    private const val KEY_BLOCKER_LAUNCH_LAST_WALL_MS = "blocker_launch_last_wall_ms"
    private const val KEY_BLOCKER_LAUNCH_LAST_PKG = "blocker_launch_last_pkg"
    private const val KEY_BLOCKER_LAUNCH_LAST_DETAILS = "blocker_launch_last_details"
    private const val KEY_BLOCKER_VERIFY_LAST_WALL_MS = "blocker_verify_last_wall_ms"
    private const val KEY_BLOCKER_VERIFY_LAST_PKG = "blocker_verify_last_pkg"
    private const val KEY_BLOCKER_VERIFY_LAST_DETAILS = "blocker_verify_last_details"
    private const val KEY_BLOCKER_ACTIVITY_LAST_WALL_MS = "blocker_activity_last_wall_ms"
    private const val KEY_BLOCKER_ACTIVITY_LAST_PKG = "blocker_activity_last_pkg"
    private const val KEY_BLOCKER_ACTIVITY_LAST_DETAILS = "blocker_activity_last_details"
    private const val KEY_MULTIWINDOW_BLOCK_LAST_WALL_MS = "multiwindow_block_last_wall_ms"
    private const val KEY_MULTIWINDOW_BLOCK_LAST_PKG = "multiwindow_block_last_pkg"
    private const val KEY_MULTIWINDOW_BLOCK_LAST_DETAILS = "multiwindow_block_last_details"
    private const val A11Y_HEARTBEAT_STALE_MS = 8_000L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE)

    data class RuntimeDiagnostics(
        val accessibilityEnabledInSettings: Boolean,
        val accessibilityActive: Boolean,
        val heartbeatAgeMs: Long,
        val lastEventWallMs: Long,
        val lastEventPackage: String,
        val lastEventType: Int,
        val lastForegroundWallMs: Long,
        val lastForegroundPackage: String,
        val lastForegroundSource: String,
        val lastUsageResolveWallMs: Long,
        val lastUsageResolvePackage: String,
        val lastUsageResolveSource: String,
        val lastBlockCheckWallMs: Long,
        val lastBlockCheckPackage: String,
        val lastBlockCheckReason: String,
        val lastBlockCheckDetails: String,
        val lastBlockShownWallMs: Long,
        val lastBlockShownPackage: String,
        val lastBlockShownDetails: String,
        val lastBlockerLaunchWallMs: Long,
        val lastBlockerLaunchPackage: String,
        val lastBlockerLaunchDetails: String,
        val lastBlockerVerifyWallMs: Long,
        val lastBlockerVerifyPackage: String,
        val lastBlockerVerifyDetails: String,
        val lastBlockerActivityWallMs: Long,
        val lastBlockerActivityPackage: String,
        val lastBlockerActivityDetails: String,
        val lastMultiWindowBlockWallMs: Long,
        val lastMultiWindowBlockPackage: String,
        val lastMultiWindowBlockDetails: String
    )

    // Called by [SwitchlyAccessibilityService] while it is connected and alive.
    fun markAccessibilityHeartbeat(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        prefs(ctx).edit {
            putBoolean(KEY_A11Y_CONNECTED, true)
            putLong(KEY_A11Y_LAST_HEARTBEAT_MS, now)
        }
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.onAccessibilityHeartbeat(ctx) }
    }

    fun markAccessibilityEvent(ctx: Context, pkg: String, eventType: Int) {
        if (pkg.isBlank()) {
            return
        }
        prefs(ctx).edit {
            putLong(KEY_A11Y_LAST_EVENT_WALL_MS, System.currentTimeMillis())
            putString(KEY_A11Y_LAST_EVENT_PKG, pkg)
            putInt(KEY_A11Y_LAST_EVENT_TYPE, eventType)
        }
    }

    fun markForegroundPackage(ctx: Context, pkg: String, source: String) {
        if (pkg.isBlank()) {
            return
        }
        prefs(ctx).edit {
            putLong(KEY_FOREGROUND_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_FOREGROUND_LAST_PKG, pkg)
            putString(KEY_FOREGROUND_LAST_SOURCE, source)
        }
    }

    fun markUsageTopResolution(ctx: Context, pkg: String?, source: String) {
        prefs(ctx).edit {
            putLong(KEY_USAGE_RESOLVE_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_USAGE_RESOLVE_LAST_PKG, pkg?.takeIf { it.isNotBlank() } ?: "-")
            putString(KEY_USAGE_RESOLVE_LAST_SOURCE, source)
        }
    }

    fun markBlockingCheck(ctx: Context, pkg: String, reason: String, details: String = "") {
        if (pkg.isBlank()) {
            return
        }
        prefs(ctx).edit {
            putLong(KEY_BLOCK_CHECK_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_BLOCK_CHECK_LAST_PKG, pkg)
            putString(KEY_BLOCK_CHECK_LAST_REASON, reason)
            putString(KEY_BLOCK_CHECK_LAST_DETAILS, details.take(500))
        }
    }

    fun markBlockShown(ctx: Context, pkg: String, details: String = "") {
        if (pkg.isBlank()) {
            return
        }
        prefs(ctx).edit {
            putLong(KEY_BLOCK_SHOWN_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_BLOCK_SHOWN_LAST_PKG, pkg)
            putString(KEY_BLOCK_SHOWN_LAST_DETAILS, details.take(500))
        }
    }

    fun markBlockerLaunchRequested(ctx: Context, pkg: String, details: String = "") {
        if (pkg.isBlank()) {
            return
        }
        prefs(ctx).edit {
            putLong(KEY_BLOCKER_LAUNCH_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_BLOCKER_LAUNCH_LAST_PKG, pkg)
            putString(KEY_BLOCKER_LAUNCH_LAST_DETAILS, details.take(500))
        }
    }

    fun markBlockerVerify(ctx: Context, pkg: String, details: String = "") {
        if (pkg.isBlank()) {
            return
        }
        prefs(ctx).edit {
            putLong(KEY_BLOCKER_VERIFY_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_BLOCKER_VERIFY_LAST_PKG, pkg)
            putString(KEY_BLOCKER_VERIFY_LAST_DETAILS, details.take(500))
        }
    }

    fun markBlockerActivityState(ctx: Context, pkg: String, state: String, details: String = "") {
        prefs(ctx).edit {
            putLong(KEY_BLOCKER_ACTIVITY_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_BLOCKER_ACTIVITY_LAST_PKG, pkg.takeIf { it.isNotBlank() } ?: "-")
            putString(KEY_BLOCKER_ACTIVITY_LAST_DETAILS, listOf(state, details).filter { it.isNotBlank() }.joinToString(" | ").take(500))
        }
    }

    fun markMultiWindowBlock(ctx: Context, pkg: String, details: String = "") {
        if (pkg.isBlank()) {
            return
        }
        prefs(ctx).edit {
            putLong(KEY_MULTIWINDOW_BLOCK_LAST_WALL_MS, System.currentTimeMillis())
            putString(KEY_MULTIWINDOW_BLOCK_LAST_PKG, pkg)
            putString(KEY_MULTIWINDOW_BLOCK_LAST_DETAILS, details.take(500))
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
        if (!isAccessibilityEnabledInSettings(ctx)) {
            return false
        }

        val p = prefs(ctx)
        val connected = p.getBoolean(KEY_A11Y_CONNECTED, false)
        val last = p.getLong(KEY_A11Y_LAST_HEARTBEAT_MS, 0L)
        if (!connected || last <= 0L) {
            return false
        }

        val age = SystemClock.elapsedRealtime() - last
        return age in 0..A11Y_HEARTBEAT_STALE_MS
    }

    fun getRuntimeDiagnostics(ctx: Context): RuntimeDiagnostics {
        val p = prefs(ctx)
        val enabled = isAccessibilityEnabledInSettings(ctx)
        val connected = p.getBoolean(KEY_A11Y_CONNECTED, false)
        val lastHeartbeat = p.getLong(KEY_A11Y_LAST_HEARTBEAT_MS, 0L)
        val age = if (lastHeartbeat > 0L) {
            (SystemClock.elapsedRealtime() - lastHeartbeat).coerceAtLeast(0L)
        } else {
            -1L
        }
        val active = enabled && connected && age in 0..A11Y_HEARTBEAT_STALE_MS
        return RuntimeDiagnostics(
            accessibilityEnabledInSettings = enabled,
            accessibilityActive = active,
            heartbeatAgeMs = age,
            lastEventWallMs = p.getLong(KEY_A11Y_LAST_EVENT_WALL_MS, 0L),
            lastEventPackage = p.getString(KEY_A11Y_LAST_EVENT_PKG, null).orEmpty(),
            lastEventType = p.getInt(KEY_A11Y_LAST_EVENT_TYPE, 0),
            lastForegroundWallMs = p.getLong(KEY_FOREGROUND_LAST_WALL_MS, 0L),
            lastForegroundPackage = p.getString(KEY_FOREGROUND_LAST_PKG, null).orEmpty(),
            lastForegroundSource = p.getString(KEY_FOREGROUND_LAST_SOURCE, null).orEmpty(),
            lastUsageResolveWallMs = p.getLong(KEY_USAGE_RESOLVE_LAST_WALL_MS, 0L),
            lastUsageResolvePackage = p.getString(KEY_USAGE_RESOLVE_LAST_PKG, null).orEmpty(),
            lastUsageResolveSource = p.getString(KEY_USAGE_RESOLVE_LAST_SOURCE, null).orEmpty(),
            lastBlockCheckWallMs = p.getLong(KEY_BLOCK_CHECK_LAST_WALL_MS, 0L),
            lastBlockCheckPackage = p.getString(KEY_BLOCK_CHECK_LAST_PKG, null).orEmpty(),
            lastBlockCheckReason = p.getString(KEY_BLOCK_CHECK_LAST_REASON, null).orEmpty(),
            lastBlockCheckDetails = p.getString(KEY_BLOCK_CHECK_LAST_DETAILS, null).orEmpty(),
            lastBlockShownWallMs = p.getLong(KEY_BLOCK_SHOWN_LAST_WALL_MS, 0L),
            lastBlockShownPackage = p.getString(KEY_BLOCK_SHOWN_LAST_PKG, null).orEmpty(),
            lastBlockShownDetails = p.getString(KEY_BLOCK_SHOWN_LAST_DETAILS, null).orEmpty(),
            lastBlockerLaunchWallMs = p.getLong(KEY_BLOCKER_LAUNCH_LAST_WALL_MS, 0L),
            lastBlockerLaunchPackage = p.getString(KEY_BLOCKER_LAUNCH_LAST_PKG, null).orEmpty(),
            lastBlockerLaunchDetails = p.getString(KEY_BLOCKER_LAUNCH_LAST_DETAILS, null).orEmpty(),
            lastBlockerVerifyWallMs = p.getLong(KEY_BLOCKER_VERIFY_LAST_WALL_MS, 0L),
            lastBlockerVerifyPackage = p.getString(KEY_BLOCKER_VERIFY_LAST_PKG, null).orEmpty(),
            lastBlockerVerifyDetails = p.getString(KEY_BLOCKER_VERIFY_LAST_DETAILS, null).orEmpty(),
            lastBlockerActivityWallMs = p.getLong(KEY_BLOCKER_ACTIVITY_LAST_WALL_MS, 0L),
            lastBlockerActivityPackage = p.getString(KEY_BLOCKER_ACTIVITY_LAST_PKG, null).orEmpty(),
            lastBlockerActivityDetails = p.getString(KEY_BLOCKER_ACTIVITY_LAST_DETAILS, null).orEmpty(),
            lastMultiWindowBlockWallMs = p.getLong(KEY_MULTIWINDOW_BLOCK_LAST_WALL_MS, 0L),
            lastMultiWindowBlockPackage = p.getString(KEY_MULTIWINDOW_BLOCK_LAST_PKG, null).orEmpty(),
            lastMultiWindowBlockDetails = p.getString(KEY_MULTIWINDOW_BLOCK_LAST_DETAILS, null).orEmpty()
        )
    }

    // No-op in the Accessibility-only runtime.
    fun ensureRunning(ctx: Context) {
        // Accessibility-only runtime: nothing to start here.
        // But we do refresh the "protection inactive" notification state and reconcile auto-blocked new apps.
        runCatching {
            val changed = ProfileStore.reconcileAutoBlockNewApps(ctx)
            if (changed > 0) {
                AppLogStore.append(ctx, "Blocking", "Auto-block reconciled newly installed apps count=$changed")
            }
        }
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(ctx) }
    }

    /**
     * No-op in the Accessibility-only runtime.
     * Callers may still call this when the user disables Switchly.
     */
    fun stop(ctx: Context) {
        // When Switchly is turned off or temporarily disabled, clear stale blocker UI/state.
        runCatching { BlockerActivity.clearVisibilityState("runtime_stop") }
        runCatching { AppLogStore.append(ctx, "Blocking", "Runtime stopped and blocker state cleared") }
        runCatching { at.saltyy.switchly.util.ProtectionStatusNotifier.refresh(ctx) }
    }
}
