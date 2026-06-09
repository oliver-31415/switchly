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

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.blocker.BlockerActivity

internal class BlockLaunchController(
    private val service: AccessibilityService,
    private val handler: Handler
) {

    private val lastVisibilityRetryAtByPkg = HashMap<String, Long>()

    private val BLOCKER_VERIFY_DELAY_MS = 750L
    private val BLOCKER_RETRY_DELAY_MS = 140L
    private val BLOCKER_RETRY_COOLDOWN_MS = 2_500L
    private val BLOCKER_VERIFY_TTL_MS = 2_500L

    fun performBackSequence(
        backCount: Int,
        initialDelayMs: Long = 0L,
        stepMs: Long = 120L
    ) {
        if (backCount <= 0) return
        for (i in 0 until backCount) {
            val delay = initialDelayMs + i.toLong() * stepMs
            if (delay <= 0L) {
                runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            } else {
                handler.postDelayed({
                    runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
                }, delay)
            }
        }
    }

    fun pauseActiveMediaPlayback() {
        val audio = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
        runCatching { audio.dispatchMediaKeyEvent(down) }
        runCatching { audio.dispatchMediaKeyEvent(up) }
    }

    fun postHome(delayMs: Long = 0L) {
        if (delayMs <= 0L) {
            runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
            return
        }
        handler.postDelayed({
            runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        }, delayMs)
    }

    fun postKillBackgroundPackage(pkg: String, delayMs: Long = 0L) {
        val killAction = {
            if (SwitchModeStore.isEnabled(service)) {
                runCatching {
                val activityManager = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.killBackgroundProcesses(pkg)
                }
            }
        }
        if (delayMs <= 0L) {
            killAction()
        } else {
            handler.postDelayed({ killAction() }, delayMs)
        }
    }

    fun showAppBlocker(pkg: String, label: String, delayMs: Long) {
        handler.postDelayed({
            if (!SwitchModeStore.isEnabled(service)) {
                BlockerActivity.clearVisibilityState("show_skipped_disabled")
                BlockingRuntime.markBlockerVerify(service, pkg, "skipped reason=switchly_disabled")
                AppLogStore.append(service, "Blocking", "[blocker_launch] pkg=$pkg result=skipped reason=switchly_disabled")
                return@postDelayed
            }
            launchAppBlocker(pkg, label, reason = "initial")
            handler.postDelayed({
                verifyAppBlockerVisible(pkg, label)
            }, BLOCKER_VERIFY_DELAY_MS)
        }, delayMs)
    }

    private fun launchAppBlocker(pkg: String, label: String, reason: String) {
        if (!SwitchModeStore.isEnabled(service)) {
            BlockerActivity.clearVisibilityState("launch_skipped_disabled")
            BlockingRuntime.markBlockerVerify(service, pkg, "launch_skipped reason=$reason switchly_disabled")
            AppLogStore.append(service, "Blocking", "[blocker_launch] pkg=$pkg result=skipped reason=switchly_disabled launchReason=$reason")
            return
        }
        BlockingRuntime.markBlockerLaunchRequested(service, pkg, "reason=$reason label=$label")
        val result = runCatching { BlockerActivity.show(service, pkg, label) }
        if (result.isFailure) {
            val error = result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
            BlockingRuntime.markBlockerVerify(service, pkg, "launch_failed reason=$reason error=$error")
            AppLogStore.append(service, "Blocking", "[blocker_launch] pkg=$pkg result=failed reason=$reason error=$error")
        }
        postKillBackgroundPackage(pkg, delayMs = 220L)
    }

    private fun verifyAppBlockerVisible(pkg: String, label: String) {
        if (!SwitchModeStore.isEnabled(service)) {
            BlockerActivity.clearVisibilityState("verify_skipped_disabled")
            BlockingRuntime.markBlockerVerify(service, pkg, "verify_skipped switchly_disabled")
            AppLogStore.append(service, "Blocking", "[blocker_verify] pkg=$pkg result=skipped reason=switchly_disabled")
            return
        }
        if (BlockerActivity.isRecentlyFocusedFor(pkg, BLOCKER_VERIFY_TTL_MS)) {
            BlockingRuntime.markBlockerVerify(service, pkg, "visible ${BlockerActivity.debugVisibilityState(pkg)}")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val lastRetry = lastVisibilityRetryAtByPkg[pkg] ?: 0L
        val canRetry = now - lastRetry >= BLOCKER_RETRY_COOLDOWN_MS
        val state = BlockerActivity.debugVisibilityState(pkg)

        if (!canRetry) {
            BlockingRuntime.markBlockerVerify(service, pkg, "not_visible retry=throttled $state")
            AppLogStore.append(service, "Blocking", "[blocker_verify] pkg=$pkg result=not_visible retry=throttled $state")
            return
        }

        lastVisibilityRetryAtByPkg[pkg] = now
        BlockingRuntime.markBlockerVerify(service, pkg, "not_visible retry=home_bounce manufacturer=${Build.MANUFACTURER} $state")
        AppLogStore.append(service, "Blocking", "[blocker_verify] pkg=$pkg result=not_visible retry=home_bounce manufacturer=${Build.MANUFACTURER} $state")

        // Some OEMs keep the previous app or an app-filter surface in front even though the Activity launch succeeded.
        // Bouncing to Home first makes the retry much more likely to get a real focused window.
        postHome()
        handler.postDelayed({
            if (!SwitchModeStore.isEnabled(service)) {
                BlockerActivity.clearVisibilityState("retry_skipped_disabled")
                BlockingRuntime.markBlockerVerify(service, pkg, "retry_skipped switchly_disabled")
                AppLogStore.append(service, "Blocking", "[blocker_verify] pkg=$pkg result=retry_skipped reason=switchly_disabled")
                return@postDelayed
            }
            launchAppBlocker(pkg, label, reason = "visibility_retry")
            handler.postDelayed({
                val retryState = BlockerActivity.debugVisibilityState(pkg)
                val visibleAfterRetry = BlockerActivity.isRecentlyFocusedFor(pkg, BLOCKER_VERIFY_TTL_MS)
                BlockingRuntime.markBlockerVerify(
                    service,
                    pkg,
                    if (visibleAfterRetry) "retry_visible $retryState" else "retry_failed $retryState"
                )
                if (!visibleAfterRetry) {
                    AppLogStore.append(service, "Blocking", "[blocker_verify] pkg=$pkg result=retry_failed $retryState")
                }
            }, BLOCKER_VERIFY_DELAY_MS)
        }, BLOCKER_RETRY_DELAY_MS)
    }

    fun bounceHomeAndKill(pkg: String) {
        postHome()
        postKillBackgroundPackage(pkg)
    }
}
