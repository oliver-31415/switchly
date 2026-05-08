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
import android.os.Handler
import android.view.KeyEvent
import at.saltyy.switchly.feature.blocker.BlockerActivity

internal class BlockLaunchController(
    private val service: AccessibilityService,
    private val handler: Handler
) {

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
            runCatching {
                val activityManager = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.killBackgroundProcesses(pkg)
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
            runCatching { BlockerActivity.show(service, pkg, label) }
            postKillBackgroundPackage(pkg, delayMs = 220L)
        }, delayMs)
    }

    fun bounceHomeAndKill(pkg: String) {
        postHome()
        postKillBackgroundPackage(pkg)
    }
}
